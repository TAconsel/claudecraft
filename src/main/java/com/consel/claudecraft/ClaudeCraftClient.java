package com.consel.claudecraft;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.File;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClaudeCraftClient implements ClientModInitializer {
	public static final String MOD_ID = "claudecraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
	private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

	private static KeyMapping askKey;
	private static KeyMapping pos1Key;
	private static KeyMapping pos2Key;
	private static KeyMapping modeKey;
	private static int ticks;
	private static boolean wasBusy;

	@Override
	public void onInitializeClient() {
		Config.load();
		fixSpawnHelper();
		askKey = register("ask", GLFW.GLFW_KEY_K);
		pos1Key = register("pos1", InputConstants.UNKNOWN.getValue());
		pos2Key = register("pos2", InputConstants.UNKNOWN.getValue());
		modeKey = register("mode", InputConstants.UNKNOWN.getValue());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> registerCommands(dispatcher));
		ClientTickEvents.END_CLIENT_TICK.register(ClaudeCraftClient::tick);
		ClientReceiveMessageEvents.GAME.register(CommandRunner::onGameMessage);
		ClientReceiveMessageEvents.GAME.register(AgentTools::onGameMessage);
	}

	/**
	 * Some launchers unpack the Java runtime without the executable bit on lib/jspawnhelper,
	 * which makes every ProcessBuilder.start() fail with "posix_spawn failed, error: 13".
	 */
	private static void fixSpawnHelper() {
		final File helper = new File(System.getProperty("java.home"), "lib/jspawnhelper");
		if (helper.isFile() && !helper.canExecute()) {
			if (helper.setExecutable(true)) {
				LOGGER.info("Made {} executable so the claude CLI can be started", helper);
			} else {
				LOGGER.warn("{} is not executable and could not be fixed; starting claude will fail", helper);
			}
		}
	}

	private static KeyMapping register(final String name, final int key) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + "." + name, InputConstants.Type.KEYSYM, key, CATEGORY));
	}

	private static void registerCommands(final CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("claude")
				.executes(c -> help())
				.then(argument("prompt", StringArgumentType.greedyString()).executes(c -> {
					ClaudeBridge.ask(c.getSource().getClient(), StringArgumentType.getString(c, "prompt"));
					return 1;
				})));
		d.register(literal("claude-new").executes(c -> {
			ClaudeBridge.newSession(c.getSource().getClient());
			Chat.info("Started a new conversation for this world/server.");
			return 1;
		}));
		d.register(literal("claude-run")
				.executes(c -> {
					Chat.run(0);
					return 1;
				})
				.then(argument("batch", IntegerArgumentType.integer(1)).executes(c -> {
					Chat.run(IntegerArgumentType.getInteger(c, "batch"));
					return 1;
				})));
		d.register(literal("claude-stop").executes(c -> {
			final boolean any = ClaudeBridge.isBusy() || CommandRunner.isRunning();
			ClaudeBridge.cancel();
			CommandRunner.stop();
			Chat.info(any ? "Stopped." : "Nothing to stop.");
			return 1;
		}));
		d.register(literal("claude-mode")
				.executes(c -> {
					Chat.info("Mode: " + Config.mode.id() + ". Use /claude-mode suggest|autorun|auto.");
					return 1;
				})
				.then(literal("suggest").executes(c -> setMode(Config.Mode.SUGGEST)))
				.then(literal("autorun").executes(c -> setMode(Config.Mode.AUTORUN)))
				.then(literal("auto").executes(c -> setMode(Config.Mode.AUTO))));
		d.register(literal("claude-auto")
				.executes(c -> setMode(Config.mode == Config.Mode.AUTO ? Config.Mode.SUGGEST : Config.Mode.AUTO))
				.then(literal("on").executes(c -> setMode(Config.Mode.AUTO)))
				.then(literal("off").executes(c -> setMode(Config.Mode.SUGGEST))));
		d.register(literal("claude-allow")
				.then(argument("id", IntegerArgumentType.integer(1))
						.executes(c -> {
							AgentTools.answer(IntegerArgumentType.getInteger(c, "id"), true, false);
							return 1;
						})
						.then(literal("all").executes(c -> {
							AgentTools.answer(IntegerArgumentType.getInteger(c, "id"), true, true);
							return 1;
						}))));
		d.register(literal("claude-deny")
				.then(argument("id", IntegerArgumentType.integer(1)).executes(c -> {
					AgentTools.answer(IntegerArgumentType.getInteger(c, "id"), false, false);
					Chat.info("Denied.");
					return 1;
				})));
		d.register(literal("claude-model")
				.executes(c -> {
					Chat.info("Model: " + (Config.model.isEmpty() ? "CLI default" : Config.model) + ". Use /claude-model <name|default>.");
					return 1;
				})
				.then(argument("name", StringArgumentType.word()).executes(c -> {
					final String name = StringArgumentType.getString(c, "name");
					Config.model = name.equalsIgnoreCase("default") ? "" : name;
					Config.save();
					Chat.info("Model set to " + (Config.model.isEmpty() ? "CLI default" : Config.model) + ".");
					return 1;
				})));
		d.register(literal("claude-pos1").executes(c -> setPos(c, true)));
		d.register(literal("claude-pos2").executes(c -> setPos(c, false)));
		d.register(literal("claude-desel").executes(c -> {
			GameContext.pos1 = null;
			GameContext.pos2 = null;
			Chat.info("Selection cleared.");
			return 1;
		}));
	}

	private static int help() {
		Chat.info("Ask anything: /claude <question or task>   (key K opens it)");
		Chat.send(Component.literal("  /claude-run [n]   run the last (or n-th) suggested commands").withStyle(ChatFormatting.GRAY));
		Chat.send(Component.literal("  /claude-mode suggest|autorun|auto   current: " + Config.mode.id()).withStyle(ChatFormatting.GRAY));
		Chat.send(Component.literal("    suggest: click [Run]  autorun: run suggestions  auto: Claude works on its own").withStyle(ChatFormatting.DARK_GRAY));
		Chat.send(Component.literal("  /claude-pos1, /claude-pos2, /claude-desel   selection from the block you look at").withStyle(ChatFormatting.GRAY));
		Chat.send(Component.literal("  /claude-new   forget the conversation   /claude-stop   cancel").withStyle(ChatFormatting.GRAY));
		Chat.send(Component.literal("  /claude-model <name|default>   e.g. sonnet, opus, haiku").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int setMode(final Config.Mode mode) {
		Config.mode = mode;
		Config.save();
		Chat.info(switch (mode) {
			case SUGGEST -> "Mode: suggest. Claude suggests commands; click [Run] to execute.";
			case AUTORUN -> "Mode: autorun. Claude's suggested commands run immediately.";
			case AUTO -> "Mode: auto. Claude runs commands itself, checks the results and keeps going. Risky commands ask you first.";
		});
		return 1;
	}

	private static int setPos(final CommandContext<FabricClientCommandSource> c, final boolean first) {
		setPos(c.getSource().getClient(), first);
		return 1;
	}

	private static void setPos(final Minecraft mc, final boolean first) {
		final BlockPos pos = GameContext.targetOrFeet(mc);
		if (first) {
			GameContext.pos1 = pos;
		} else {
			GameContext.pos2 = pos;
		}
		Chat.info((first ? "pos1" : "pos2") + " set to " + GameContext.xyz(pos));
	}

	private static void tick(final Minecraft mc) {
		ticks++;
		CommandRunner.tick(mc);
		AgentTools.tick(mc);
		if (mc.player == null) {
			return;
		}
		ClaudeBridge.tick(mc);
		while (askKey.consumeClick()) {
			mc.gui.openChatAndAddText(ChatComponent.ChatMethod.MESSAGE, "/claude ");
		}
		while (pos1Key.consumeClick()) {
			setPos(mc, true);
		}
		while (pos2Key.consumeClick()) {
			setPos(mc, false);
		}
		while (modeKey.consumeClick()) {
			final Config.Mode[] modes = Config.Mode.values();
			setMode(modes[(Config.mode.ordinal() + 1) % modes.length]);
		}

		final boolean busy = ClaudeBridge.isBusy();
		if (busy && ticks % 2 == 0) {
			final String status = AgentTools.awaitingApproval() ? " Claude is waiting for your approval in chat"
					: Config.mode == Config.Mode.AUTO ? " Claude is working (auto)... " + ClaudeBridge.elapsedSeconds() + "s"
					: " Claude is thinking... " + ClaudeBridge.elapsedSeconds() + "s";
			mc.gui.chatListener().handleOverlay(Component.literal(SPINNER[(ticks / 2) % SPINNER.length] + status)
					.withStyle(s -> s.withColor(0xD97757)));
		} else if (!busy && wasBusy) {
			mc.gui.chatListener().handleOverlay(Component.empty());
		}
		wasBusy = busy;
	}
}
