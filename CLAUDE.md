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
explosions/pistons/liquids; logs "GRAVE made|opened|returned|return-later" -> AntiCheat.graveLine feed lines.
xray-detect=false in its config turns off only the mining checks; map=false turns off map drawing.
plugin/test/xraywatch/GraveSim.java tests graves (26 checks, incl. give-back and the presets' graves off).
Live pages (5.3, XrayWatch 1.3, now always installed on survival servers): plugin/src/xraywatch/Live.java writes
plugins/XrayWatch/live.json every 2 s (players+positions+health, TPS, MSPT, memory, CPU, worlds' time/weather/spawn, grave
summary, map fill status), deaths.csv, items.txt, inv/<name>.json on "xw inv <name>" (logs "INV ready|offline"), and
handles "xw tp <p> <x> <z> [world]", "xw spawn <p>", "xw grave return <id>" (owner offline = return-on-join), "xw graves
on|off" (presets, not saved), "xw map fill <r>|stop". MapRender.java draws 256x256 PNG tiles (map/<world>/<tx>_<tz>.png)
from BlockData.getMapColor around players (3 ms/tick budget) and, for "fill", loads already-generated chunks with
getChunkAtAsync(x,z,false) (returns null for not-fully-generated chunks). Graves.java writes graves.json.
App side: Game.java polls live.json (history of the last hour; modded servers get CPU from the process only), parses the
plugin's INV/MAP/TP/GRAVES lines (hidden from the feed); MapPage, WorldPage (time/weather, Presets.java, graves, deaths),
HealthPage (+LogHints.java), PlayerTools.java (player window, give dialog, teleports; "me" = app.me), Items.java (item names
and pictures read from the owner's own .minecraft/versions/<v>/<v>.jar; -Dkit.minecraft overrides; never ship textures).
Presets save what they replace in preset.saved/active/until and switch back on time, at the next start if the end passed
while off, or by "Stop". Rules.ask also asks pvp, natural_health_regeneration and difficulty ("The difficulty is X").
Checked on real Paper 26.3: time markers day/noon/night/midnight, "Set the weather to rain and thunder", game rules pvp
and natural_health_regeneration exist; plugin bytecode identical when built against the real paper-api jar.
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
