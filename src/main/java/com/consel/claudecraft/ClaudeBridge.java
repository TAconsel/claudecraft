package com.consel.claudecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.permissions.Permissions;

/** Runs the local Claude Code CLI in headless mode, reusing whatever login the CLI already has. */
public final class ClaudeBridge {
	private static final int QUERY_TIMEOUT_TICKS = 20;
	private static final Path WORK_DIR = FabricLoader.getInstance().getConfigDir().resolve("claudecraft");

	private static final String SYSTEM_PROMPT = """
			You are ClaudeCraft, an assistant living inside the player's Minecraft Java Edition 26.2 game.
			You help with commands, command blocks, redstone/command contraptions and simple world editing.
			Every message starts with a <game_context> block describing the player's position, facing, the block
			they are looking at, and their selection (pos1/pos2). It may also contain <previous_command_output>
			with the chat feedback from the commands you produced last time; use it to fix errors.

			How to answer:
			- Your reply is shown in the Minecraft chat, which is narrow. Keep prose to a few short lines.
			  No markdown headings, tables or bullet nesting.
			- Use only syntax valid for Java Edition 26.2: item components (e.g. diamond_sword[enchantments={sharpness:5}]),
			  SNBT text components, current block/item/entity ids, `execute` subcommands, etc.
			- Prefer absolute coordinates taken from <game_context> over ~ ~ ~, because the player may move.
			- /fill and /clone are limited to 32768 blocks per command: split big regions into several commands.
			- Command blocks: place or edit them with setblock / data merge block, e.g.
			  setblock X Y Z minecraft:repeating_command_block[facing=up]{Command:"say hi",auto:1b}
			  Remember quotes and backslashes inside the Command string must be escaped.
			- Never run destructive commands (kill @e, large fills of air, /stop, /deop, /ban, gamerule changes)
			  unless the player clearly asked for that effect.
			- If "can use cheat commands" is NO, say most commands will fail and explain how to enable cheats (or get op).
			- On a multiplayer server, only use commands from the "Commands this player may use" list (it may include
			  plugin commands such as WorldEdit's //set; use them when they fit). Other players may be nearby:
			  avoid selectors like @a or @e that would affect them unless the player asked for it.
			""";

	private static final String SUGGEST_PROMPT = """

			Mode: SUGGEST. You cannot run anything yourself.
			- When something should be executed, put the commands in ONE fenced block tagged `commands`,
			  one command per line, without a leading slash. The mod shows them to the player with a [Run]
			  button (or runs them directly if auto-run is on), in order, one per tick, as the player.
			  Example:
			  ```commands
			  fill 10 64 10 20 70 20 minecraft:stone
			  ```
			- If the question only needs an explanation (or the player just wants a command to copy), answer
			  without a `commands` block, or show non-executable examples in a plain ``` block instead.
			""";

	private static final String AUTO_PROMPT = """

			Mode: AUTO. You act on your own using the minecraft tools, like an agent:
			- run_commands executes commands as the player and returns their chat feedback. get_context gives fresh
			  position/look/selection info. get_blocks reads blocks in a box so you can inspect builds and verify your work.
			- Do the task yourself instead of printing commands: run them, read the feedback, fix errors, and continue
			  until the task is done. For builds, check the result with get_blocks when it matters.
			- Do not output `commands` blocks in this mode.
			- Some risky commands pause for the player's approval. If the player denies one, do not retry it or work
			  around it; explain what you could not do.
			- If the player only asks a question, just answer it; use tools only when they help.
			- Finish with a short summary (1-3 lines) of what you did.
			""";

	private static volatile Process process;
	private static volatile boolean busy;
	private static volatile long startedAt;
	/** Conversation per world/server, keyed by {@link GameContext#worldKey}. */
	private static final Map<String, String> sessions = new HashMap<>();
	/** Command list last sent in each conversation, so it is only repeated when it changes. */
	private static final Map<String, String> sentCommands = new HashMap<>();
	private static String pendingPrompt;
	private static int pendingTicks;

	private ClaudeBridge() {
	}

	public static boolean isBusy() {
		return busy || pendingPrompt != null;
	}

	public static long elapsedSeconds() {
		return (System.currentTimeMillis() - startedAt) / 1000;
	}

	public static void newSession(final Minecraft mc) {
		final String key = GameContext.worldKey(mc);
		sessions.remove(key);
		sentCommands.remove(key);
	}

	public static void cancel() {
		pendingPrompt = null;
		AgentTools.cancel();
		final Process p = process;
		if (p != null) {
			p.descendants().forEach(ProcessHandle::destroy);
			p.destroy();
		}
	}

	/** Must be called on the client thread. */
	public static void ask(final Minecraft mc, final String prompt) {
		if (isBusy()) {
			Chat.error("Claude is still working on the previous request. Use /claude-stop to cancel it.");
			return;
		}
		final String claude = findClaude();
		if (claude == null) {
			Chat.error("Could not find the claude CLI. Set claudePath in config/claudecraft.properties.");
			return;
		}

		// On a server the command block's contents have to be requested first (needs op, like F3+I).
		final BlockPos commandBlock = GameContext.remoteCommandBlock(mc);
		if (commandBlock != null && mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			pendingPrompt = prompt;
			pendingTicks = QUERY_TIMEOUT_TICKS;
			mc.player.connection.getDebugQueryHandler().queryBlockEntityTag(commandBlock, tag -> {
				if (pendingPrompt != null) {
					final String p = pendingPrompt;
					pendingPrompt = null;
					start(mc, claude, p, tag);
				}
			});
			return;
		}
		start(mc, claude, prompt, null);
	}

	/** Gives up waiting for command block data from the server. */
	public static void tick(final Minecraft mc) {
		if (pendingPrompt != null && --pendingTicks <= 0) {
			final String p = pendingPrompt;
			pendingPrompt = null;
			final String claude = findClaude();
			if (mc.player != null && claude != null) {
				start(mc, claude, p, null);
			}
		}
	}

	private static void start(final Minecraft mc, final String claude, final String prompt, final CompoundTag commandBlock) {
		final String key = GameContext.worldKey(mc);
		final String sessionId = sessions.get(key);
		String commands = GameContext.availableCommands(mc);
		if (sessionId != null && commands.equals(sentCommands.get(key))) {
			commands = null;
		} else {
			sentCommands.put(key, commands);
		}

		final StringBuilder message = new StringBuilder();
		message.append("<game_context>\n").append(GameContext.describe(mc, commandBlock, commands)).append("</game_context>\n");
		final String output = CommandRunner.drainOutput();
		if (!output.isEmpty()) {
			message.append("<previous_command_output>\n").append(output).append("</previous_command_output>\n");
		}
		message.append('\n').append(prompt);

		final boolean auto = Config.mode == Config.Mode.AUTO;
		final List<String> cmd = new ArrayList<>(List.of(
				claude, "-p",
				"--output-format", "stream-json", "--verbose",
				// No file or shell access, and no user hooks/plugins/MCP servers. Only the game's own tools in auto mode.
				"--tools", "",
				"--setting-sources", "",
				"--strict-mcp-config",
				"--append-system-prompt", SYSTEM_PROMPT + (auto ? AUTO_PROMPT : SUGGEST_PROMPT)));
		if (auto) {
			final McpServer mcp;
			try {
				mcp = AgentTools.server(mc);
			} catch (final IOException e) {
				ClaudeCraftClient.LOGGER.error("Could not start the MCP server", e);
				Chat.error("Could not start auto mode tools: " + e.getMessage());
				return;
			}
			cmd.add("--mcp-config");
			cmd.add(mcp.mcpConfigJson());
			cmd.add("--allowedTools");
			cmd.add(String.join(",", mcp.allowedToolNames()));
			AgentTools.beginRequest();
		}
		if (!Config.model.isEmpty()) {
			cmd.add("--model");
			cmd.add(Config.model);
		}
		if (sessionId != null) {
			cmd.add("--resume");
			cmd.add(sessionId);
		}

		busy = true;
		startedAt = System.currentTimeMillis();
		final Thread thread = new Thread(() -> run(mc, key, cmd, message.toString()), "ClaudeCraft request");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run(final Minecraft mc, final String key, final List<String> cmd, final String message) {
		boolean gotResult = false;
		try {
			Files.createDirectories(WORK_DIR);
			final File stderr = WORK_DIR.resolve("last-stderr.log").toFile();
			final ProcessBuilder pb = new ProcessBuilder(cmd)
					.directory(WORK_DIR.toFile())
					.redirectError(stderr);
			final Process p = pb.start();
			process = p;
			try (OutputStream in = p.getOutputStream()) {
				in.write(message.getBytes(StandardCharsets.UTF_8));
			}
			try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = out.readLine()) != null) {
					gotResult |= handleLine(mc, key, line);
				}
			}
			final int exit = p.waitFor();
			if (!gotResult) {
				final String err = tail(stderr.toPath());
				mc.execute(() -> Chat.error(exit == 143 || exit == 137
						? "Request cancelled."
						: "claude exited with code " + exit + (err.isEmpty() ? "" : ": " + err)));
			}
		} catch (final IOException e) {
			ClaudeCraftClient.LOGGER.error("Failed to run claude", e);
			mc.execute(() -> Chat.error("Failed to run claude: " + e.getMessage()));
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			process = null;
			AgentTools.endRequest();
			busy = false;
		}
	}

	/** Returns true when the final result message was seen. */
	private static boolean handleLine(final Minecraft mc, final String key, final String line) {
		final JsonObject obj;
		try {
			final JsonElement el = JsonParser.parseString(line);
			if (!el.isJsonObject()) {
				return false;
			}
			obj = el.getAsJsonObject();
		} catch (final RuntimeException e) {
			return false;
		}
		final String type = str(obj, "type");
		final String sid = str(obj, "session_id");
		if (!sid.isEmpty()) {
			mc.execute(() -> sessions.put(key, sid));
		}
		if ("assistant".equals(type) && obj.has("message")) {
			final StringBuilder text = new StringBuilder();
			for (final JsonElement block : obj.getAsJsonObject("message").getAsJsonArray("content")) {
				final JsonObject b = block.getAsJsonObject();
				if ("text".equals(str(b, "type"))) {
					text.append(str(b, "text"));
				}
			}
			if (!text.isEmpty()) {
				final String reply = text.toString();
				mc.execute(() -> Chat.showReply(reply));
			}
		} else if ("result".equals(type)) {
			if (obj.has("is_error") && obj.get("is_error").getAsBoolean()) {
				final String err = str(obj, "result");
				mc.execute(() -> Chat.error(err.isEmpty() ? "Claude returned an error (" + str(obj, "subtype") + ")" : err));
			}
			return true;
		}
		return false;
	}

	private static String str(final JsonObject obj, final String key) {
		final JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private static String tail(final Path file) {
		try {
			final List<String> lines = Files.readAllLines(file);
			return String.join(" ", lines.subList(Math.max(0, lines.size() - 3), lines.size())).trim();
		} catch (final IOException e) {
			return "";
		}
	}

	/** Minecraft launchers often start the game without ~/.local/bin on PATH, so check common install spots too. */
	private static String findClaude() {
		if (!Config.claudePath.isEmpty()) {
			return Config.claudePath;
		}
		final boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
		final List<Path> candidates = new ArrayList<>();
		final String path = System.getenv("PATH");
		if (path != null) {
			for (final String dir : path.split(File.pathSeparator)) {
				candidates.add(Path.of(dir, windows ? "claude.exe" : "claude"));
				if (windows) {
					candidates.add(Path.of(dir, "claude.cmd"));
				}
			}
		}
		final String home = System.getProperty("user.home");
		candidates.add(Path.of(home, ".local", "bin", windows ? "claude.exe" : "claude"));
		candidates.add(Path.of(home, ".claude", "local", "claude"));
		candidates.add(Path.of("/usr/local/bin/claude"));
		candidates.add(Path.of("/opt/homebrew/bin/claude"));
		for (final Path c : candidates) {
			if (Files.isRegularFile(c) && Files.isExecutable(c)) {
				return c.toString();
			}
		}
		return null;
	}
}
