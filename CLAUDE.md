# Minecraft Server app (notes for Claude)

The owner (GitHub `schoolmcst`) asks for changes in chat; you make them, test them, and ship them as an update,
the same way as their DarkZone game.

## What it is
A Java 21+ Swing desktop app (`src/*.java`, no dependencies) that runs a Paper server with Geyser + Floodgate
(Bedrock) and the MCXboxBroadcast Geyser extension (console players via the Xbox Friends tab), plus playit.gg tunnels
for online play. `Start Server.bat` downloads a Java 25 runtime into `runtime/` on first run; the owner's PC currently
uses an installed Temurin JDK 25 and a desktop shortcut that runs `javaw -jar launcher.jar`.

Since 4.0 the app is installed in `%LOCALAPPDATA%\Programs\Minecraft Server` (HOME: launcher.jar, app/, online/,
servers.json, app-settings.properties) and manages several server folders. Each server folder keeps its own
`launcher-settings.properties` (server.type paper|fabric, server.crossplay, mc.target, paper.build, xbox.*...).
The owner's first server: `C:\Users\aiden\OneDrive\Desktop\minecraft stuff\Minecraft Server non mods`.
Fabric servers use fabric-server.jar from meta.fabricmc.net, mods from Modrinth (Mods.java), Geyser/Floodgate as
Fabric mods (config/Geyser-Fabric). Test overrides: -Dkit.home, -Dkit.dir, -Dkit.fabricmeta, -Dkit.modrinth.

## Ship an update (every installed app downloads it the next time it opens)
1. Change `src/`, compile (`javac --release 21 -d /tmp/c src/*.java`) and test (UI tests run under `xvfb-run`;
   set `-Dkit.dir=<folder>` and point downloads at local mocks with `-Dkit.fill`, `-Dkit.geyser`, `-Dkit.playit`,
   `-Dkit.playitdl`, `-Dkit.xboxdl`, `-Dkit.appupdate`).
2. `tools/release.sh <next version> "note" "note"` then commit and push. This rebuilds `launcher.jar` and writes
   `release/app.jar` + `release/version.json` (sha256 checked by the app).
- The app reads `https://raw.githubusercontent.com/schoolmcst/minecraft-server-app/main/release/version.json` at
  launch (5 s timeout, splash) and every 6 hours while open; while open it downloads to `app/app-pending.jar` and
  shows a "Restart app to update" bar. `launcher.jar` is a starter that runs the newest of itself and `app/app.jar`.
- A broken app.jar falls back to the starter, so a bad release can't brick the server; still test before shipping.
- Notes show to the owner in the activity feed after updating, so write them in plain words.

## Server software updates (automatic, in the app)
On every server start: newest stable Paper build for the installed Minecraft version, Geyser, Floodgate and
MCXboxBroadcast. A newer Minecraft version is only offered (Settings > Switch to Minecraft X) because Java players
must update their game to match.
