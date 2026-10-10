package com.consel.claudecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tools Claude can call in auto mode. They run on MCP server threads and hop to the client thread
 * for anything that touches the game.
 */
public final class AgentTools {
	private static final int MAX_COMMANDS_PER_REQUEST = 300;
	private static final int MAX_COMMANDS_PER_CALL = 64;
	private static final int MAX_SCAN_VOLUME = 32768;
	private static final int MAX_LISTED_BLOCKS = 800;
	private static final int APPROVAL_TIMEOUT_SECONDS = 120;
	private static final TextColor RED = TextColor.fromLegacyFormat(ChatFormatting.RED);

	/** Commands that change server-wide state or affect other players' access. */
	private static final Set<String> RISKY_ROOTS = Set.of(
			"stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist", "save-off", "reload",
			"publish", "transfer", "setidletimeout", "jfr", "perf", "debug", "difficulty", "defaultgamemode", "gamerule",
			"worldborder", "setworldspawn", "forceload", "datapack");
	/** Commands that post to other players' chat. */
	private static final Set<String> PUBLIC_CHAT_ROOTS = Set.of("say", "me", "msg", "tell", "w", "teammsg", "tm");
	private static final Pattern ALL_PLAYERS = Pattern.compile("@a\\b");
	private static final Pattern RANDOM_PLAYER = Pattern.compile("@r\\b");
	private static final Pattern UNTYPED_ENTITIES = Pattern.compile("@e(?!\\[[^\\]]*type=)");
	private static final Pattern RUN = Pattern.compile("\\brun\\s+");
	/** The command stored in a command block or minecart, e.g. {Command:"say hi"}. */
	private static final Pattern STORED_COMMAND = Pattern.compile("command\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)')");

	private static McpServer server;

	// Per-request state, touched from MCP threads.
	private static volatile boolean allowAll;
	private static volatile boolean cancelled;
	private static final AtomicInteger commandsUsed = new AtomicInteger();
	private static final AtomicInteger nextApproval = new AtomicInteger(1);
	private static final Map<Integer, CompletableFuture<Boolean>> approvals = new ConcurrentHashMap<>();

	// Command execution, client thread only.
	private static final class Job {
		final String command;
		final CompletableFuture<String> done = new CompletableFuture<>();
		final List<String> lines = new ArrayList<>();
		boolean sent;
		int age;
		int quiet;

		Job(final String command) {
			this.command = command;
		}
	}

	private static final Deque<Job> jobs = new ArrayDeque<>();
	private static Job current;

	private AgentTools() {
	}

	public static synchronized McpServer server(final Minecraft mc) throws IOException {
		if (server == null) {
			server = new McpServer(List.of(
					new McpServer.Tool("run_commands",
							"Run Minecraft commands as the player, in order, and return each command's chat feedback "
									+ "(lines prefixed ERROR: failed). Commands without a leading slash. At most "
									+ MAX_COMMANDS_PER_CALL + " per call. Risky commands wait for the player's approval; "
									+ "if one is denied the remaining commands in the call are not run.",
							schema("{\"type\":\"object\",\"properties\":{\"commands\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"commands\"]}"),
							args -> runCommands(mc, args)),
					new McpServer.Tool("get_context",
							"Fresh game context: player position, facing, looked-at block (including command block contents), "
									+ "selection, held item and available commands. Call it again after the player may have moved.",
							schema("{\"type\":\"object\",\"properties\":{}}"),
							args -> getContext(mc)),
					new McpServer.Tool("get_blocks",
							"Read blocks in a box (inclusive corners) from the player's loaded chunks, to inspect a build or check "
									+ "your work. Returns counts per block state and, for small results, each non-air block. Max "
									+ MAX_SCAN_VOLUME + " blocks.",
							schema("{\"type\":\"object\",\"properties\":{"
									+ "\"x1\":{\"type\":\"integer\"},\"y1\":{\"type\":\"integer\"},\"z1\":{\"type\":\"integer\"},"
									+ "\"x2\":{\"type\":\"integer\"},\"y2\":{\"type\":\"integer\"},\"z2\":{\"type\":\"integer\"}},"
									+ "\"required\":[\"x1\",\"y1\",\"z1\",\"x2\",\"y2\",\"z2\"]}"),
							args -> getBlocks(mc, args))));
		}
		return server;
	}

	private static JsonObject schema(final String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	public static void beginRequest() {
		allowAll = false;
		cancelled = false;
		commandsUsed.set(0);
	}

	/** Denies pending approvals and makes further tool calls fail until the next request. */
	public static void cancel() {
		cancelled = true;
		approvals.values().forEach(f -> f.complete(false));
	}

	public static void endRequest() {
		approvals.values().forEach(f -> f.complete(false));
		approvals.clear();
	}

	public static boolean awaitingApproval() {
		return !approvals.isEmpty();
	}

	public static void answer(final int id, final boolean allow, final boolean all) {
		final CompletableFuture<Boolean> f = approvals.get(id);
		if (f == null || f.isDone()) {
			Chat.error("That approval request has expired.");
			return;
		}
		if (allow && all) {
			allowAll = true;
		}
		f.complete(allow);
	}

	// ---- run_commands ----

	private static McpServer.Result runCommands(final Minecraft mc, final JsonObject args) {
		if (cancelled) {
			return McpServer.Result.error("The player cancelled this request. Stop now.");
		}
		if (!args.has("commands") || !args.get("commands").isJsonArray()) {
			return McpServer.Result.error("commands must be an array of strings");
		}
		final List<String> commands = new ArrayList<>();
		for (final JsonElement e : args.getAsJsonArray("commands")) {
			String c = e.getAsString().strip();
			if (c.startsWith("/")) {
				c = c.substring(1);
			}
			if (!c.isEmpty()) {
				commands.add(c);
			}
		}
		if (commands.size() > MAX_COMMANDS_PER_CALL) {
			return McpServer.Result.error("At most " + MAX_COMMANDS_PER_CALL + " commands per call.");
		}
		final boolean multiplayer = mc.getSingleplayerServer() == null;
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < commands.size(); i++) {
			final String cmd = commands.get(i);
			if (cancelled) {
				out.append("Cancelled by the player; stop now.\n");
				break;
			}
			if (commandsUsed.incrementAndGet() > MAX_COMMANDS_PER_REQUEST) {
				out.append("Command limit for this request (").append(MAX_COMMANDS_PER_REQUEST)
						.append(") reached. Stop and tell the player what is left to do.\n");
				break;
			}
			if (cmd.toLowerCase(Locale.ROOT).startsWith("claude")) {
				out.append('/').append(cmd).append(" -> skipped (ClaudeCraft's own commands are not available to you)\n");
				continue;
			}
			final String risk = risk(cmd, multiplayer);
			if (risk != null && !allowAll && !askApproval(mc, cmd, risk)) {
				out.append('/').append(cmd).append(" -> DENIED by the player (").append(risk).append("). Do not retry it;")
						.append(" the remaining ").append(commands.size() - i - 1).append(" command(s) were not run.\n");
				break;
			}
			mc.execute(() -> Chat.send(Component.literal("» /" + cmd).withStyle(ChatFormatting.DARK_GRAY)));
			final String feedback = runOne(mc, cmd);
			out.append('/').append(cmd).append(" -> ").append(feedback.isEmpty() ? "(no chat feedback)" : feedback).append('\n');
		}
		return McpServer.Result.ok(out.toString());
	}

	/** Returns why a command needs the player's approval, or null if it can run freely. */
	static String risk(final String command, final boolean multiplayer) {
		final String lower = command.toLowerCase(Locale.ROOT);
		// Check the command itself and every command nested via "execute ... run".
		for (final String segment : RUN.split(lower)) {
			String root = segment.strip().split("\\s+", 2)[0];
			if (root.startsWith("minecraft:")) {
				root = root.substring("minecraft:".length());
			}
			if (RISKY_ROOTS.contains(root)) {
				return "/" + root + " changes server or world settings";
			}
			if (multiplayer && PUBLIC_CHAT_ROOTS.contains(root)) {
				return "sends a chat message other players see";
			}
		}
		// A command block will run its stored command later, with full permissions.
		final var stored = STORED_COMMAND.matcher(lower);
		while (stored.find()) {
			final String inner = (stored.group(1) != null ? stored.group(1) : stored.group(2)).replace("\\\"", "\"").replace("\\'", "'");
			final String innerRisk = risk(inner.startsWith("/") ? inner.substring(1) : inner, multiplayer);
			if (innerRisk != null) {
				return "stores a command block command that " + innerRisk.replaceFirst("^/\\S+ ", "");
			}
		}
		if (UNTYPED_ENTITIES.matcher(lower).find()) {
			return "targets every entity (@e without type=)";
		}
		if (multiplayer && ALL_PLAYERS.matcher(lower).find()) {
			return "affects all players (@a)";
		}
		if (multiplayer && RANDOM_PLAYER.matcher(lower).find()) {
			return "affects a random player (@r)";
		}
		return null;
	}

	private static boolean askApproval(final Minecraft mc, final String cmd, final String reason) {
		final int id = nextApproval.getAndIncrement();
		final CompletableFuture<Boolean> f = new CompletableFuture<>();
		approvals.put(id, f);
		mc.execute(() -> Chat.askApproval(id, cmd, reason));
		try {
			return f.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (final TimeoutException e) {
			mc.execute(() -> Chat.info("No answer, so that command was denied."));
			return false;
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (final ExecutionException e) {
			return false;
		} finally {
			approvals.remove(id);
		}
	}

	private static String runOne(final Minecraft mc, final String cmd) {
		final Job job = new Job(cmd);
		mc.execute(() -> jobs.add(job));
		try {
			return job.done.get(30, TimeUnit.SECONDS);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return "ERROR: interrupted";
		} catch (final ExecutionException | TimeoutException e) {
			return "ERROR: the game did not run the command (" + e + ")";
		}
	}

	/** Sends queued commands and decides when their feedback has finished arriving. */
	public static void tick(final Minecraft mc) {
		if (current == null) {
			current = jobs.poll();
			if (current == null) {
				return;
			}
		}
		if (mc.player == null) {
			current.done.complete("ERROR: not in a world");
			current = null;
			return;
		}
		if (!current.sent) {
			mc.player.connection.sendCommand(current.command);
			current.sent = true;
			return;
		}
		current.age++;
		current.quiet++;
		// Singleplayer answers within a tick or two; a remote server needs a round trip.
		final int maxWait = mc.getSingleplayerServer() != null ? 10 : 40;
		if (current.age >= maxWait || (!current.lines.isEmpty() && current.quiet >= 3)) {
			current.done.complete(String.join(" | ", current.lines));
			current = null;
		}
	}

	public static void onGameMessage(final Component message, final boolean overlay) {
		if (overlay || CommandRunner.printing || current == null || !current.sent) {
			return;
		}
		final boolean error = message.visit((style, text) -> RED.equals(style.getColor()) && !text.isBlank()
				? java.util.Optional.of(Boolean.TRUE)
				: java.util.Optional.empty(), Style.EMPTY).orElse(Boolean.FALSE);
		current.lines.add((error ? "ERROR: " : "") + message.getString());
		current.quiet = 0;
	}

	// ---- get_context ----

	private static McpServer.Result getContext(final Minecraft mc) {
		try {
			final CompletableFuture<CompoundTag> commandBlock = new CompletableFuture<>();
			mc.execute(() -> {
				final BlockPos pos = mc.player == null ? null : GameContext.remoteCommandBlock(mc);
				if (pos == null) {
					commandBlock.complete(null);
				} else {
					mc.player.connection.getDebugQueryHandler().queryBlockEntityTag(pos, commandBlock::complete);
				}
			});
			CompoundTag tag;
			try {
				tag = commandBlock.get(2, TimeUnit.SECONDS);
			} catch (final TimeoutException e) {
				tag = null;
			}
			final CompoundTag cb = tag;
			return McpServer.Result.ok(mc.submit(() -> mc.player == null
					? "Not in a world."
					: GameContext.describe(mc, cb, GameContext.availableCommands(mc))).get(5, TimeUnit.SECONDS));
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpServer.Result.error("interrupted");
		} catch (final ExecutionException | TimeoutException e) {
			return McpServer.Result.error("Could not read the game: " + e);
		}
	}

	// ---- get_blocks ----

	private static McpServer.Result getBlocks(final Minecraft mc, final JsonObject a) {
		final int x1 = a.get("x1").getAsInt(), y1 = a.get("y1").getAsInt(), z1 = a.get("z1").getAsInt();
		final int x2 = a.get("x2").getAsInt(), y2 = a.get("y2").getAsInt(), z2 = a.get("z2").getAsInt();
		final long volume = (Math.abs((long) x1 - x2) + 1) * (Math.abs((long) y1 - y2) + 1) * (Math.abs((long) z1 - z2) + 1);
		if (volume > MAX_SCAN_VOLUME) {
			return McpServer.Result.error("Box has " + volume + " blocks; the maximum is " + MAX_SCAN_VOLUME + ". Scan smaller parts.");
		}
		mc.execute(() -> Chat.send(Component.literal("» inspecting " + x1 + " " + y1 + " " + z1 + " .. " + x2 + " " + y2 + " " + z2)
				.withStyle(ChatFormatting.DARK_GRAY)));
		try {
			return McpServer.Result.ok(mc.submit(() -> scan(mc, x1, y1, z1, x2, y2, z2)).get(10, TimeUnit.SECONDS));
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpServer.Result.error("interrupted");
		} catch (final ExecutionException | TimeoutException e) {
			return McpServer.Result.error("Could not read blocks: " + e);
		}
	}

	private static String scan(final Minecraft mc, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2) {
		if (mc.level == null) {
			return "Not in a world.";
		}
		final Map<String, Integer> counts = new LinkedHashMap<>();
		final List<String> listed = new ArrayList<>();
		int unloaded = 0;
		int nonAir = 0;
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
				for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
					pos.set(x, y, z);
					if (!mc.level.isLoaded(pos)) {
						unloaded++;
						continue;
					}
					final BlockState state = mc.level.getBlockState(pos);
					final String s = GameContext.stateString(state);
					counts.merge(s, 1, Integer::sum);
					if (!state.isAir()) {
						nonAir++;
						if (listed.size() < MAX_LISTED_BLOCKS) {
							listed.add(x + " " + y + " " + z + " " + s);
						}
					}
				}
			}
		}
		final StringBuilder sb = new StringBuilder("Counts:\n");
		counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> sb.append("  ").append(e.getValue()).append(" ").append(e.getKey()).append('\n'));
		if (unloaded > 0) {
			sb.append(unloaded).append(" positions are in unloaded chunks (unknown).\n");
		}
		if (nonAir <= MAX_LISTED_BLOCKS) {
			sb.append("Non-air blocks (x y z state):\n");
			listed.forEach(l -> sb.append(l).append('\n'));
		} else {
			sb.append(nonAir).append(" non-air blocks: too many to list; scan a smaller box for positions.\n");
		}
		return sb.toString();
	}
}
