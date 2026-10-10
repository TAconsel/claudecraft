package com.consel.claudecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Formats Claude's replies for the chat and keeps the command batches that [Run] buttons refer to. */
public final class Chat {
	private static final int CLAUDE_ORANGE = 0xD97757;
	/** The chat box only accepts 256 characters, so longer commands are copied instead of suggested. */
	private static final int CHAT_INPUT_LIMIT = 256;
	private static final int KEEP_BATCHES = 20;

	private static final Map<Integer, List<String>> batches = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<Integer, List<String>> eldest) {
			return size() > KEEP_BATCHES;
		}
	};
	private static int nextBatch = 1;
	private static int lastBatch;

	private Chat() {
	}

	public static void send(final Component message) {
		final Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		CommandRunner.printing = true;
		try {
			mc.player.sendSystemMessage(message);
		} finally {
			CommandRunner.printing = false;
		}
	}

	private static MutableComponent prefix() {
		return Component.literal("[Claude] ").withStyle(s -> s.withColor(CLAUDE_ORANGE));
	}

	public static void info(final String text) {
		send(prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
	}

	public static void error(final String text) {
		send(prefix().append(Component.literal(text).withStyle(ChatFormatting.RED)));
	}

	/** Splits a reply into prose and ```commands blocks and prints both. */
	public static void showReply(final String reply) {
		final List<String> commands = new ArrayList<>();
		final List<String> prose = new ArrayList<>();
		boolean inFence = false;
		boolean commandFence = false;
		for (final String raw : reply.split("\n")) {
			final String line = raw.stripTrailing();
			if (line.strip().startsWith("```")) {
				if (!inFence) {
					final String lang = line.strip().substring(3).strip().toLowerCase(Locale.ROOT);
					commandFence = lang.equals("commands") || lang.equals("mcfunction");
				}
				inFence = !inFence;
				continue;
			}
			if (inFence && commandFence) {
				final String cmd = line.strip();
				if (!cmd.isEmpty() && !cmd.startsWith("#")) {
					commands.add(cmd.startsWith("/") ? cmd.substring(1) : cmd);
				}
			} else if (inFence) {
				prose.add("\u0000" + line);
			} else {
				prose.add(cleanMarkdown(line));
			}
		}

		// Drop blank lines at the edges and collapse runs of them; chat space is precious.
		final List<String> lines = new ArrayList<>();
		for (final String l : prose) {
			if (l.isBlank() && (lines.isEmpty() || lines.getLast().isBlank())) {
				continue;
			}
			lines.add(l);
		}
		while (!lines.isEmpty() && lines.getLast().isBlank()) {
			lines.removeLast();
		}

		boolean first = true;
		for (final String l : lines) {
			final MutableComponent msg = first ? prefix() : Component.empty();
			first = false;
			if (l.startsWith("\u0000")) {
				// Example code: click to put it in the chat box.
				final String code = l.substring(1);
				msg.append(commandLine(code.startsWith("/") ? code.substring(1) : code, ChatFormatting.AQUA));
			} else {
				msg.append(Component.literal(l).withStyle(ChatFormatting.WHITE));
			}
			send(msg);
		}

		if (commands.isEmpty()) {
			return;
		}
		final int id = nextBatch++;
		batches.put(id, List.copyOf(commands));
		lastBatch = id;
		if (first) {
			send(prefix().append(Component.literal("Commands:").withStyle(ChatFormatting.GRAY)));
		}
		for (final String cmd : commands) {
			send(Component.literal("  ").append(commandLine(cmd, ChatFormatting.YELLOW)));
		}
		if (Config.mode != Config.Mode.SUGGEST) {
			run(id);
		} else {
			send(Component.literal("  ")
					.append(button("[▶ Run " + (commands.size() == 1 ? "" : "all " + commands.size()) + "]", ChatFormatting.GREEN,
							new ClickEvent.RunCommand("/claude-run " + id), "Run as you, in order"))
					.append(" ")
					.append(button("[Copy]", ChatFormatting.AQUA,
							new ClickEvent.CopyToClipboard(String.join("\n", commands.stream().map(c -> "/" + c).toList())), "Copy to clipboard")));
		}
	}

	/** Runs a stored batch; id 0 means the most recent one. */
	public static void run(final int id) {
		final List<String> cmds = batches.get(id == 0 ? lastBatch : id);
		if (cmds == null) {
			error("No such command batch (old batches are forgotten).");
			return;
		}
		info("Running " + cmds.size() + " command" + (cmds.size() == 1 ? "" : "s") + "...");
		CommandRunner.enqueue(cmds);
	}

	public static void batchFinished(final int ran, final int errors) {
		if (errors == 0) {
			info("Done (" + ran + " command" + (ran == 1 ? "" : "s") + ").");
			return;
		}
		send(prefix()
				.append(Component.literal(errors + " error" + (errors == 1 ? "" : "s") + " while running. ").withStyle(ChatFormatting.RED))
				.append(button("[Ask Claude to fix]", ChatFormatting.GOLD,
						new ClickEvent.RunCommand("/claude The commands produced errors (see previous_command_output). Fix them."),
						"Sends the error output back to Claude")));
	}

	/** Auto mode: Claude wants to run a risky command and is waiting for the player. */
	public static void askApproval(final int id, final String cmd, final String reason) {
		send(prefix().append(Component.literal("wants to run a command that " + reason + ":").withStyle(ChatFormatting.GOLD)));
		send(Component.literal("  ").append(commandLine(cmd, ChatFormatting.YELLOW)));
		send(Component.literal("  ")
				.append(button("[Allow]", ChatFormatting.GREEN, new ClickEvent.RunCommand("/claude-allow " + id), "Run this command"))
				.append(" ")
				.append(button("[Allow all]", ChatFormatting.DARK_GREEN, new ClickEvent.RunCommand("/claude-allow " + id + " all"),
						"Allow this and every other risky command until this request ends"))
				.append(" ")
				.append(button("[Deny]", ChatFormatting.RED, new ClickEvent.RunCommand("/claude-deny " + id), "Don't run it; Claude is told you said no")));
	}

	private static MutableComponent commandLine(final String cmd, final ChatFormatting color) {
		final ClickEvent click = cmd.length() + 1 <= CHAT_INPUT_LIMIT
				? new ClickEvent.SuggestCommand("/" + cmd)
				: new ClickEvent.CopyToClipboard("/" + cmd);
		final String hint = click instanceof ClickEvent.SuggestCommand ? "Click to edit in chat" : "Too long for the chat box: click to copy";
		return Component.literal("/" + cmd).withStyle(Style.EMPTY.withColor(color)
				.withClickEvent(click)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hint))));
	}

	private static MutableComponent button(final String label, final ChatFormatting color, final ClickEvent click, final String hover) {
		return Component.literal(label).withStyle(Style.EMPTY.withColor(color).withBold(true)
				.withClickEvent(click)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static String cleanMarkdown(final String line) {
		String s = line.replace("**", "").replace("__", "").replace("`", "");
		s = s.replaceFirst("^#+\\s*", "");
		s = s.replaceFirst("^(\\s*)[-*]\\s+", "$1• ");
		return s;
	}
}
