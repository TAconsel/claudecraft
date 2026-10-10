package com.consel.claudecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Settings stored in config/claudecraft.properties. */
public final class Config {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("claudecraft.properties");

	public enum Mode {
		/** Claude suggests commands; the player clicks [Run]. */
		SUGGEST,
		/** Suggested commands run immediately. */
		AUTORUN,
		/** Claude runs commands itself through tools, reads the results and keeps going until done. */
		AUTO;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public static Mode mode = Mode.AUTO;
	/** Model passed to --model; empty uses the CLI default. */
	public static String model = "";
	/** Path to the claude executable; empty searches PATH and the usual install locations. */
	public static String claudePath = "";
	/** Delay between queued commands, in ticks. */
	public static int ticksPerCommand = 1;

	private Config() {
	}

	public static void load() {
		final Properties p = new Properties();
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				p.load(r);
			} catch (final IOException e) {
				ClaudeCraftClient.LOGGER.warn("Could not read {}", FILE, e);
			}
		}
		final boolean legacyAutoRun = Boolean.parseBoolean(p.getProperty("autoRun", "false"));
		try {
			mode = Mode.valueOf(p.getProperty("mode", legacyAutoRun ? "AUTORUN" : "AUTO").trim().toUpperCase(Locale.ROOT));
		} catch (final IllegalArgumentException e) {
			mode = Mode.AUTO;
		}
		model = p.getProperty("model", "").trim();
		claudePath = p.getProperty("claudePath", "").trim();
		try {
			ticksPerCommand = Math.max(1, Integer.parseInt(p.getProperty("ticksPerCommand", "1").trim()));
		} catch (final NumberFormatException e) {
			ticksPerCommand = 1;
		}
		save();
	}

	public static void save() {
		final Properties p = new Properties();
		p.setProperty("mode", mode.id());
		p.setProperty("model", model);
		p.setProperty("claudePath", claudePath);
		p.setProperty("ticksPerCommand", Integer.toString(ticksPerCommand));
		try (Writer w = Files.newBufferedWriter(FILE)) {
			p.store(w, "ClaudeCraft settings");
		} catch (final IOException e) {
			ClaudeCraftClient.LOGGER.warn("Could not write {}", FILE, e);
		}
	}
}
