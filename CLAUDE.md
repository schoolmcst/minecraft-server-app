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
Fabric servers use fabric-server.jar from meta.fabricmc.net, mods from Modrinth or CurseForge (ModSource.java;
CurseForge needs the owner's API key, saved as app.curseforge.key; ModsPage.java shows categories, cards, details with
pictures). Modrinth pictures are WebP: Images.java downloads the TwelveMonkeys reader from Maven Central into <app>/lib
(SHA-1 pinned). Geyser/Floodgate run as
Fabric mods (config/Geyser-Fabric). Test overrides: -Dkit.home, -Dkit.dir, -Dkit.fabricmeta, -Dkit.modrinth, -Dkit.curseforge, -Dkit.maven.

Anti-cheat (AntiCheat.java, survival/Paper servers only, setting `anticheat`, default on): every start installs or
updates Grim Anticheat (Modrinth project grimac, stable release preferred) into plugins/, parks it in plugins/disabled
when off, and sets anticheat.anti-xray.enabled in config/paper-world-defaults.yml. Grim alerts ("Grim » X failed Y (xN)")
become feed lines, at most once a minute per player and check. Settings: anticheat (Grim), anticheat.xray,
anticheat.xray.nether (adds an anti-xray block to world/dimensions/minecraft/the_nether/paper-world.yml),
anticheat.xray.strong (engine-mode 2), anticheat.alerts, anticheat.callout (+ .message, {player} {cheat}: red tellraw
to everyone, once a minute per player). RulesPage.java shows them plus game rules (Rules.java,
26.x snake_case names) saved as rule.<name> and re-sent after "Done"; replies "Gamerule X is ... set to: Y" are read
into Rules.live and hidden from the feed. Real Paper 26.3 replies "Game rule X is currently set to Y" (checked on a real
server; "System chat: " is stripped first).

XrayWatch (plugin/, our own Paper plugin, embedded in app.jar as /xraywatch.jar by tools/release.sh, installed into
plugins/ by AntiCheat.syncXrayWatch; setting anticheat.xray.detect): scores players on hidden diamond/debris/deep-emerald
veins and on "turning toward ore you can't see", with a Poisson test (score = -20*log10 chance; 60 suspicious, 80 likely),
over 30 min and 3 h. Logs "[XrayWatch] FLAG likely|suspicious player=... score=..." (AntiCheat.XRAY_FLAG -> feed +
call-out), writes plugins/XrayWatch/report.json + finds.csv (XrayReport.java shows them on the Rules page), /xw in game.
Build: plugin/build.sh compiles against plugin/stubs (a minimal Paper API stand-in; every API call was checked identical
to a build against the real paper-api 26.3 jar). plugin/test/xraywatch/Sim.java simulates honest miners and X-rayers.
XrayWatch 1.1 also logs "CLIENT player=X brand=Y" on join (Player.getClientBrandName); Server.onClient classifies it
(AntiCheat.kindOf: NORMAL/PVP/MODDED/HACKED by name list), shows it on the Players page, kicks HACKED
(anticheat.clients.kick, default on) and MODDED when anticheat.clients.vanillaOnly is on. Bedrock is skipped.
Graves (plugin/src/xraywatch/Graves.java, XrayWatch 1.2; settings graves.enabled default on, graves.unlock minutes,
0 = owner only): on death items+XP go into a PLAYER_HEAD block (saved in plugins/XrayWatch/graves/*.grave as base64
ItemStack.serializeAsBytes, survives restarts); owner uses or breaks it to collect (armor re-equipped); protected from
explosions/pistons/liquids; logs "GRAVE made|opened" -> AntiCheat.graveLine feed lines. The plugin is installed when any
of its jobs is on (AntiCheat.pluginNeeded); xray-detect=false in its config turns off only the mining checks.
plugin/test/xraywatch/GraveSim.java tests graves (19 checks).
anticheat.xray.seeds sets feature-seeds.generate-random-seeds-for-all (ores in new chunks stop matching the seed).

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
