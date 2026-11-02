# Hypixel 2020

A recreation of Hypixel **SkyWars**, **Bed Wars**, **Duels**, and **The Pit**, with bots, friends, parties, and Watchdog/NCP in one Spigot plugin.

This repository now includes the supplied complete sources merged with the rank, title, and world-filtered Tab changes. Plugin version: **1.1.1-rankfix**.

The rank display fix covers game join/result messages and keeps closing rank brackets when shortening lobby name tags. See [称号显示修复](docs/rank-display-fix.md).

See [完整源码合并与构建说明](docs/merged-source-build.md) for build dependencies, retained modules, tests, deployment steps, and platform limits.

## Fork Changes

This fork adds admin `/rank` and `/title` commands with custom colors and filters the Tab player list by the viewer's current world.

See [2026-11-01 更改说明与用法](docs/changes-2026-11-01.md) for command parameters, rank and color tables, examples, update steps, and acceptance checks.

See [Rank 括号颜色与 Title 前缀](docs/rank-title-guide.md) for the current bracket inheritance rules, separate title prefixes, formatting examples, and display limits.

- **Lightweight.** About 140 MB on disk after building, and about 1.6 GB of RAM and 1.2 CPU cores with around 80 bots online, you are able to host it locally.
- **20 maps from 2020** for SkyWars and Bed Wars are included. The real Hypixel lobby is not.
- **Made in about 1–2 hours with Claude.** The anticheat may not be fully accurate, so fix it yourself or somehow source the "accurate" one billionaire is making.

## Features

- **Bed Wars:** Solo, Doubles, 3v3v3v3 and 4v4v4v4. Includes the item shop, team upgrades and traps,
  generators, beds, bed bugs and dream defenders, fireballs/TNT/bridge eggs, `/shout`,
  using mid-2020 (v1.6) prices.
- **SkyWars:** Solo/Teams, Normal/Insane. Includes cages, kits, loot tables, refills, Doom/end phases and
  spectators.
- **Duels:** Classic, Boxing, NoDebuff, and Bridge, with per-mode bot matching preferences.
- **The Pit:** generated world, levels, rewards, persisted stats, and bots.
- **Social:** friends, parties, party chat, and party queues.
- **Bots:** each bot is a perfectly emulated serverside player, bots are also checked by the anticheat. Bots have human-like aim and CPS,
  bridging styles, looting, chat, and 2020-style names and skins. A configurable share of bots can cheat.
- **Watchdog:** a packet-level anticheat modelled on NoCheatPlus's check set (moving, fight, block place/break,
  inventory, net). It uses Hypixel-2020 presentation: delayed bans, `[WATCHDOG ANNOUNCEMENT]`, "A player has been
  removed from your game.", `/report`.

## What you need

- **Java 17 or newer**, to build the plugin: [download](https://adoptium.net/temurin/releases/?version=21)
- **Java 8**, to run the server: [download](https://adoptium.net/temurin/releases/?version=8)

## How to run it

The easiest way: paste this repo's link into Claude and tell it to set it up for you. Or do it yourself:

1. **Download** this repo (green **Code** button → **Download ZIP**, then unzip it) or `git clone` it.
2. **Start the server:**
   - **Windows:** double-click `start-server.bat`.
   - **Mac / Linux:** run `bash start-server.sh` in the project folder.
3. **Accept the Minecraft EULA** when it asks (type `y`). This only happens the first time.
4. **Wait** until the window says `Done`. The first start takes a minute or two.
5. **Join** `localhost` in Minecraft 1.8.9. You're made admin automatically.

To stop the server, type `stop` in its window.

## Commands

Use the compass or the NPCs in the lobby to join a game. Settings are in `run/plugins/Hypixel2020/config.yml`.

| Command | What it does |
|---|---|
| `/play <mode>` | Join a game: `solo_normal`, `solo_insane`, `teams_normal`, `teams_insane`, `bedwars_eight_one`, `bedwars_eight_two`, `bedwars_four_three`, `bedwars_four_four` |
| `/lobby` | Back to the lobby |
| `/pit` | Enter The Pit |
| `/play duels_classic_duel` | Classic Duel; also `duels_boxing_duel`, `duels_potion_duel`, and `duels_bridge_duel` |
| `/duels bots <mode> <on or off>` | Set bot matching preference for `classic`, `boxing`, `nodebuff`, or `bridge` |
| `/friend` / `/party` | Friends and party commands |
| `/h2020 create <mode> [map]` | Start a game on a specific map |
| `/h2020 games` / `/h2020 watch <id>` | List games / spectate one |
| `/h2020 fillspeed <x>` | How fast bots fill games (1 = real 2020 queue times) |
| `/h2020 cheaters <percent>` | Share of bots that cheat; Watchdog catches them |
| `/h2020 rank <player> <rank> [color]` | Set a rank, e.g. `/h2020 rank YourName MVP_PLUS RED` |
| `/rank <player> <rank or text> [color]` | Admin only; assign a built-in rank or custom rank, e.g. `/rank YourName Builder gold` or `/rank YourName &7[&bBuilder&7]`. Names follow the dominant rank color. Use `NONE` to clear only the rank. See the current rank/title guide for bracket colors and display limits. |
| `/title <player> <text> [color]` | Admin only; add text before the rank, e.g. `/title YourName Champion gold`. Supports spaces and inline formatting; `/title YourName clear` removes only this prefix. |
| `/h2020 date today` | Show today's date on scoreboards instead of a 2020 one |
| `/bot spawn [name] [cheat]` / `/bot list` | Spawn a bot / see what every bot is doing |
| `/wd alerts` | Anticheat alerts on or off for you (off by default; everything is logged to `watchdog.log`) |
| `/report <player>` | The 2020 report menu |


## Troubleshooting

- **"Java 8 not found":** install Java 8 from the link above, or set `JAVA8_HOME` to its folder.
- **Build failed:** make sure Java 17+ is installed and is the `java` on your PATH (`java -version`).
- **Can't connect:** use Minecraft **1.8.9** and join `localhost` while the server window is open.
