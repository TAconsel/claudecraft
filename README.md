# ClaudeCraft

An in-game Claude assistant for Minecraft Java Edition (Fabric). Ask about commands, set up command blocks or do simple world editing from chat. In **auto mode** Claude runs the commands itself, reads the results, fixes its own mistakes and keeps going until the job is done.

It uses your local [Claude Code](https://claude.com/claude-code) CLI and its existing login, so you don't need an API key.

> Unofficial fan project, not affiliated with or endorsed by Anthropic or Mojang.

## Features

- **`/claude <anything>`** (or press **K**): "make the command block I'm looking at give speed 2 to nearby players", "build a 10x10 glass dome around me", "how does /execute if block work?"
- **Knows where you are:** position, facing, dimension, the block you're looking at (including a command block's current command and last output), your held item, your selection and which commands you're allowed to use.
- **Three modes** (`/claude-mode`):
  - `auto` (default): Claude runs commands itself, reads the chat feedback, inspects blocks to check its work and keeps going until done.
  - `suggest`: Claude shows commands with **[▶ Run]** / **[Copy]** buttons.
  - `autorun`: suggested commands run immediately.
- **Approval for risky commands:** in auto mode, `/stop`, `/op`, `/ban`, `/gamerule` and similar, `@e` without `type=`, and (on servers) `@a`, `@r` and public chat ask you first with **[Allow] [Allow all] [Deny]**. This also covers commands nested in `execute … run` and commands stored in command blocks.
- **Simple selection** for world edits: `/claude-pos1`, `/claude-pos2`, `/claude-desel` (uses the block you're looking at).
- **Works on multiplayer servers without installing anything on the server.** Commands are sent as you, so you need op (or the relevant permissions). Plugin commands such as WorldEdit's are used when the server has them.
- **One conversation per world or server.** `/claude-new` starts over.

## Requirements

- Minecraft **26.2** with [Fabric Loader](https://fabricmc.net/) ≥ 0.19 and [Fabric API](https://modrinth.com/mod/fabric-api)
- [Claude Code](https://claude.com/claude-code) installed and logged in. Check that `claude -p "hi"` works in a terminal.

## Install

1. Download `claudecraft-x.y.z.jar` from [Releases](https://github.com/TAconsel/claudecraft/releases) (or build it, see below).
2. Put it in your `.minecraft/mods` folder next to Fabric API.
3. Start the game, open a world and type `/claude`.

The mod looks for `claude` on `PATH`, then `~/.local/bin`, `~/.claude/local`, `/usr/local/bin` and `/opt/homebrew/bin`. If yours is elsewhere, set `claudePath` in `config/claudecraft.properties`.

## Commands

| Command | |
|---|---|
| `/claude <prompt>` | Ask Claude (key **K** opens it) |
| `/claude-mode suggest\|autorun\|auto` | Switch mode (`/claude-auto` toggles auto/suggest) |
| `/claude-run [n]` | Run the last (or n-th) suggested batch |
| `/claude-stop` | Cancel the current request and any running commands |
| `/claude-new` | Forget the conversation for this world/server |
| `/claude-pos1`, `/claude-pos2`, `/claude-desel` | Selection for world edits |
| `/claude-model <name\|default>` | e.g. `sonnet`, `opus`, `haiku` |

Keybinds (Controls → ClaudeCraft): ask (K), pos1, pos2, switch mode.

## How it works

- Each request runs `claude -p --output-format stream-json` in `config/claudecraft/`, with a short description of your game state. Built-in tools, user settings, hooks and other MCP servers are disabled (`--tools "" --setting-sources "" --strict-mcp-config`), so Claude can't touch your files.
- In auto mode, the mod starts a small MCP server on `127.0.0.1` (random port, random bearer token) with three tools: `run_commands`, `get_context` and `get_blocks`. Only these tools are allowed.
- Command feedback is read from chat, and red messages count as errors. Commands run one per tick.

## Settings

`config/claudecraft.properties`: `mode`, `model`, `claudePath`, `ticksPerCommand`.

## Building

Needs a Java 25 JDK.

```sh
./gradlew build
# -> build/libs/claudecraft-<version>.jar
```

## Troubleshooting

- **`posix_spawn failed, error: 13`**: some launchers install Java without the executable bit on `lib/jspawnhelper`. The mod fixes this at startup; if it still fails, run `chmod +x <java runtime>/lib/jspawnhelper`.
- **"claude exited with code …"**: see `config/claudecraft/last-stderr.log`, and check that `claude -p "hi"` works in a terminal.

## License

MIT
