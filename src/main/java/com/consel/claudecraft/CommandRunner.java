package com.consel.claudecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Sends queued commands as the player, one per tick, and records the chat feedback they produce. */
public final class CommandRunner {
	/** How long after the last command to keep collecting feedback. */
	private static final int CAPTURE_TICKS = 40;
	private static final int MAX_CAPTURED = 60;
	private static final TextColor RED = TextColor.fromLegacyFormat(ChatFormatting.RED);

	private static final Deque<String> queue = new ArrayDeque<>();
	private static final List<String> captured = new ArrayList<>();
	private static int cooldown;
	private static int captureLeft;
	private static int ran;
	private static int errors;
	/** Set while the mod itself prints to chat so its own messages are not recorded. */
	static boolean printing;

	private CommandRunner() {
	}

	public static void enqueue(final List<String> commands) {
		for (final String c : commands) {
			final String cmd = c.startsWith("/") ? c.substring(1) : c;
			// Never let Claude trigger the mod's own commands.
			if (cmd.toLowerCase(Locale.ROOT).startsWith("claude")) {
				continue;
			}
			queue.add(cmd);
		}
		if (!queue.isEmpty() && captureLeft == 0) {
			ran = 0;
			errors = 0;
		}
	}

	public static boolean isRunning() {
		return !queue.isEmpty();
	}

	public static void stop() {
		queue.clear();
	}

	public static void tick(final Minecraft mc) {
		if (mc.player == null) {
			queue.clear();
			captureLeft = 0;
			return;
		}
		if (!queue.isEmpty()) {
			if (--cooldown <= 0) {
				final String cmd = queue.poll();
				captured.add("> /" + cmd);
				trimCaptured();
				mc.player.connection.sendCommand(cmd);
				ran++;
				cooldown = Config.ticksPerCommand;
				captureLeft = CAPTURE_TICKS;
			}
		} else if (captureLeft > 0 && --captureLeft == 0) {
			Chat.batchFinished(ran, errors);
		}
	}

	public static void onGameMessage(final Component message, final boolean overlay) {
		if (overlay || printing || captureLeft == 0) {
			return;
		}
		captured.add(message.getString());
		trimCaptured();
		if (isError(message)) {
			errors++;
		}
	}

	/** Returns and clears the feedback collected since the last request. */
	public static String drainOutput() {
		if (captured.isEmpty()) {
			return "";
		}
		final String s = String.join("\n", captured) + "\n";
		captured.clear();
		return s;
	}

	private static void trimCaptured() {
		while (captured.size() > MAX_CAPTURED) {
			captured.remove(0);
		}
	}

	/** Vanilla command errors are shown in red. */
	private static boolean isError(final Component message) {
		return message.visit((style, text) -> RED.equals(style.getColor()) && !text.isBlank()
				? Optional.of(Boolean.TRUE)
				: Optional.empty(), Style.EMPTY).orElse(Boolean.FALSE);
	}
}
