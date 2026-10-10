package com.consel.claudecraft;

import com.mojang.brigadier.tree.CommandNode;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Describes the player's surroundings so Claude can write commands with real coordinates. */
public final class GameContext {
	public static BlockPos pos1;
	public static BlockPos pos2;

	private GameContext() {
	}

	/** The block the crosshair is on, or the block at the player's feet. */
	public static BlockPos targetOrFeet(final Minecraft mc) {
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			return hit.getBlockPos();
		}
		return mc.player.blockPosition();
	}

	/** True when the crosshair is on a command block whose contents must be fetched from the server. */
	public static BlockPos remoteCommandBlock(final Minecraft mc) {
		if (mc.getSingleplayerServer() == null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
				&& mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof CommandBlock) {
			return hit.getBlockPos();
		}
		return null;
	}

	/** Identifies the world or server so each one keeps its own conversation. */
	public static String worldKey(final Minecraft mc) {
		final IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			return "sp:" + server.getWorldData().getLevelName();
		}
		return mc.getCurrentServer() != null ? "mp:" + mc.getCurrentServer().ip : "unknown";
	}

	/** Root commands the server lets this player use (the server only sends permitted ones). */
	public static String availableCommands(final Minecraft mc) {
		return mc.player.connection.getCommands().getRoot().getChildren().stream()
				.map(CommandNode::getName)
				.filter(n -> !n.startsWith("claude"))
				.sorted()
				.collect(Collectors.joining(" "));
	}

	/**
	 * @param remoteCommandBlock command block NBT fetched from a multiplayer server, or null
	 * @param commands available root commands, or null when unchanged since the last message
	 */
	public static String describe(final Minecraft mc, final CompoundTag remoteCommandBlock, final String commands) {
		final LocalPlayer player = mc.player;
		final StringBuilder sb = new StringBuilder();
		final IntegratedServer server = mc.getSingleplayerServer();

		sb.append("Game: Minecraft Java Edition 26.2 (Fabric)\n");
		if (server != null) {
			sb.append("World: singleplayer \"").append(server.getWorldData().getLevelName()).append("\"\n");
		} else if (mc.getCurrentServer() != null) {
			sb.append("World: multiplayer server ").append(mc.getCurrentServer().ip);
			final String brand = player.connection.serverBrand();
			if (brand != null) {
				sb.append(" (server software: ").append(brand).append(')');
			}
			sb.append(". The ClaudeCraft mod runs only on the player's client; commands are sent as the player.\n");
		}
		if (commands != null) {
			sb.append("Commands this player may use on this server: ").append(commands).append('\n');
		}
		sb.append("Player: ").append(player.getName().getString());
		if (mc.gameMode != null) {
			sb.append(", gamemode ").append(mc.gameMode.getPlayerMode().getName());
		}
		sb.append(", can use cheat commands: ").append(player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) ? "yes" : "NO").append('\n');
		sb.append("Dimension: ").append(mc.level.dimension().identifier()).append('\n');

		final BlockPos feet = player.blockPosition();
		sb.append(String.format(Locale.ROOT, "Player position: %.2f %.2f %.2f (block %s)%n",
				player.getX(), player.getY(), player.getZ(), xyz(feet)));
		sb.append(String.format(Locale.ROOT, "Facing: %s (yaw %.1f, pitch %.1f)%n",
				player.getDirection().getName(), player.getYRot(), player.getXRot()));

		final ItemStack held = player.getMainHandItem();
		if (!held.isEmpty()) {
			sb.append("Held item: ").append(BuiltInRegistries.ITEM.getKey(held.getItem())).append(" x").append(held.getCount()).append('\n');
		}

		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			final BlockPos pos = hit.getBlockPos();
			final BlockState state = mc.level.getBlockState(pos);
			sb.append("Looking at block: ").append(xyz(pos)).append(' ')
					.append(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
					.append(propertiesOf(state))
					.append(" (clicked face: ").append(hit.getDirection().getName()).append(")\n");
			if (remoteCommandBlock != null) {
				describeCommandBlock(sb, remoteCommandBlock);
			} else {
				describeCommandBlock(sb, server, mc, pos);
			}
		} else {
			sb.append("Looking at block: none\n");
		}

		if (pos1 != null || pos2 != null) {
			sb.append("Selection: pos1 ").append(pos1 == null ? "unset" : xyz(pos1))
					.append(", pos2 ").append(pos2 == null ? "unset" : xyz(pos2));
			if (pos1 != null && pos2 != null) {
				final long dx = Math.abs(pos1.getX() - pos2.getX()) + 1L;
				final long dy = Math.abs(pos1.getY() - pos2.getY()) + 1L;
				final long dz = Math.abs(pos1.getZ() - pos2.getZ()) + 1L;
				sb.append(" (size ").append(dx).append('x').append(dy).append('x').append(dz)
						.append(" = ").append(dx * dy * dz).append(" blocks)");
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	/** Command block contents only exist server-side, so this works in singleplayer only. */
	private static void describeCommandBlock(final StringBuilder sb, final IntegratedServer server, final Minecraft mc, final BlockPos pos) {
		if (server == null) {
			return;
		}
		final ServerLevel level = server.getLevel(mc.level.dimension());
		if (level == null) {
			return;
		}
		final BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof CommandBlockEntity cb) {
			sb.append("  Command block mode: ").append(cb.getMode().name().toLowerCase(Locale.ROOT))
					.append(", conditional: ").append(cb.isConditional())
					.append(", always active: ").append(cb.isAutomatic()).append('\n');
			sb.append("  Current command: ").append(cb.getCommandBlock().getCommand()).append('\n');
			sb.append("  Last output: ").append(cb.getCommandBlock().getLastOutput().getString()).append('\n');
		}
	}

	private static void describeCommandBlock(final StringBuilder sb, final CompoundTag tag) {
		sb.append("  Always active: ").append(tag.getBooleanOr("auto", false)).append('\n');
		sb.append("  Current command: ").append(tag.getStringOr("Command", "")).append('\n');
		final Tag out = tag.get("LastOutput");
		if (out != null) {
			sb.append("  Last output: ").append(out).append('\n');
		}
	}

	public static String stateString(final BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + propertiesOf(state);
	}

	private static String propertiesOf(final BlockState state) {
		final String s = state.toString();
		final int i = s.indexOf('[');
		return i < 0 ? "" : s.substring(i);
	}

	public static String xyz(final BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}
}
