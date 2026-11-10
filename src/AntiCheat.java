// Anti-cheat for survival (Paper) servers: Grim Anticheat (free, from Modrinth) catches movement and combat cheats
// (fly, speed, reach, kill aura...) and skips Bedrock/Xbox players by itself through Floodgate. Paper's own anti-X-ray
// hides ores until a player is next to them. Both are switched by one setting, "anticheat" (on by default).

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

final class AntiCheat {
    private AntiCheat() { }

    static final String PROJECT = "grimac";

    /** Anti-cheat only applies to survival (Paper) servers; mods often add movement Grim would take for cheating. */
    static boolean applies() { return Kit.hasServer() && !Kit.isFabric(); }

    /** Grim (hacked clients). */
    static boolean enabled() { return applies() && Kit.getBool("anticheat", true); }

    static boolean xray() { return applies() && Kit.getBool("anticheat.xray", true); }

    static boolean xrayNether() { return xray() && Kit.getBool("anticheat.xray.nether", true); }

    /** Engine mode 2: also sends fake ores, so X-ray shows ore everywhere. Costs a little more internet and CPU. */
    static boolean xrayStrong() { return Kit.getBool("anticheat.xray.strong", false); }

    static boolean alerts() { return Kit.getBool("anticheat.alerts", true); }

    /** XrayWatch, the app's own plugin: catches X-ray users by how they mine (see plugin/src). */
    static boolean xrayDetect() { return applies() && Kit.getBool("anticheat.xray.detect", true); }

    static boolean xrayAdmins() { return Kit.getBool("anticheat.xray.admins", true); }

    /** Paper's feature-seeds: ores in newly generated areas no longer match the world seed, so seed-based ore finders miss. */
    static boolean shuffleOres() { return applies() && Kit.getBool("anticheat.xray.seeds", true); }

    /** Graves: items wait where you died (XrayWatch makes them). Settings: graves.enabled, graves.unlock (minutes, 0 = owner only). */
    static boolean graves() { return applies() && Kit.getBool("graves.enabled", true); }

    static int graveUnlockMinutes() {
        try { return Integer.parseInt(Kit.get("graves.unlock", "60").trim()); } catch (NumberFormatException e) { return 60; }
    }

    /** XrayWatch runs when any of its jobs is on: X-ray detection, graves, or game-client checks. */
    static boolean pluginNeeded() { return xrayDetect() || graves() || (applies() && (kickHacked() || kickModded())); }

    static Path xrayWatchJar() { return Kit.plugins().resolve("XrayWatch.jar"); }

    static Path xrayWatchDir() { return Kit.plugins().resolve("XrayWatch"); }

    /** Installs, updates or removes XrayWatch and writes its settings. Returns a feed line or null. */
    static String syncXrayWatch() {
        try {
            if (!pluginNeeded()) {
                Files.deleteIfExists(xrayWatchJar());
                return null;
            }
            byte[] jar;
            try (var in = AntiCheat.class.getResourceAsStream("/xraywatch.jar")) {
                if (in == null) return null; // a development build without the plugin inside
                jar = in.readAllBytes();
            }
            boolean fresh = !Files.exists(xrayWatchJar());
            if (fresh || !Arrays.equals(jar, Files.readAllBytes(xrayWatchJar()))) {
                Files.createDirectories(Kit.plugins());
                Path tmp = xrayWatchJar().resolveSibling("XrayWatch.jar.part");
                Files.write(tmp, jar);
                Files.move(tmp, xrayWatchJar(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(xrayWatchDir());
            Files.writeString(xrayWatchDir().resolve("config.properties"),
                    "# Written by the Minecraft Server app (Rules page). Changes here are replaced on the next start.\n"
                            + "notify-admins=" + xrayAdmins() + "\n"
                            + "xray-detect=" + xrayDetect() + "\n"
                            + "graves=" + graves() + "\n"
                            + "grave-unlock-minutes=" + graveUnlockMinutes() + "\n", StandardCharsets.UTF_8);
            return fresh ? (xrayDetect() ? "X-ray detection is on: the server now watches how players mine. " : "")
                    + (graves() ? "Graves are on: when someone dies, their items wait for them where they died." : "") : null;
        } catch (IOException e) {
            return "Couldn't set up X-ray detection: " + Kit.describe(e);
        }
    }

    static List<Path> grimJars(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".jar") && (n.startsWith("grim") || n.contains("grimac"));
            }).collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    static Path disabledDir() { return Kit.plugins().resolve("disabled"); }

    /**
     * Run before each start: installs or updates Grim when anti-cheat is on, parks it when it's off, and sets
     * anti-X-ray to match. Returns a line for the activity feed, or null. Never throws (offline = keep what's there).
     */
    static String sync(Kit.Progress p) {
        if (!applies()) return null;
        String said = null;
        try {
            Files.createDirectories(Kit.plugins());
            if (enabled()) {
                // bring back a parked copy first, so an offline start still has anti-cheat
                for (Path j : grimJars(disabledDir())) Files.move(j, Kit.plugins().resolve(j.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                try {
                    said = installOrUpdate(p);
                } catch (Exception e) {
                    if (grimJars(Kit.plugins()).isEmpty()) said = "Couldn't download the anti-cheat (Grim): " + Kit.describe(e);
                }
            } else {
                List<Path> on = grimJars(Kit.plugins());
                if (!on.isEmpty()) {
                    Files.createDirectories(disabledDir());
                    for (Path j : on) Files.move(j, disabledDir().resolve(j.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    said = "Anti-cheat is off.";
                }
            }
        } catch (IOException e) {
            said = "Couldn't set up the anti-cheat: " + Kit.describe(e);
        }
        configureXray();
        String xw = syncXrayWatch();
        if (xw != null) said = said == null ? xw : said + " " + xw;
        return said;
    }

    private static String installOrUpdate(Kit.Progress p) throws IOException, InterruptedException {
        String mc = Kit.mcVersion();
        String url = Mods.API + "/project/" + PROJECT + "/version?loaders=" + ModSource.enc("[\"paper\"]")
                + "&game_versions=" + ModSource.enc("[\"" + mc + "\"]");
        List<?> versions = (List<?>) Kit.Json.parse(Kit.getString(url));
        if (versions.isEmpty()) {
            return grimJars(Kit.plugins()).isEmpty() ? "The anti-cheat (Grim) doesn't support Minecraft " + mc + " yet, so it's off for now." : null;
        }
        Object v = versions.get(0);
        for (Object o : versions) if ("release".equals(Kit.Json.str(o, "version_type"))) { v = o; break; }
        String id = Kit.Json.str(v, "id");
        List<Path> have = grimJars(Kit.plugins());
        if (!have.isEmpty() && id != null && id.equals(Kit.get("grim.version", ""))) return null;

        Object file = null;
        for (Object f : Kit.Json.list(v, "files")) if (Boolean.TRUE.equals(Kit.Json.get(f, "primary"))) file = f;
        if (file == null && !Kit.Json.list(v, "files").isEmpty()) file = Kit.Json.list(v, "files").get(0);
        if (file == null) throw new IOException("no file to download");
        String name = Kit.Json.str(file, "filename");
        if (name == null || name.contains("/") || name.contains("\\")) throw new IOException("strange file name");
        Path target = Kit.plugins().resolve(name);
        Path tmp = Kit.download(Kit.Json.str(file, "url"), target, "Downloading the anti-cheat (Grim)", p);
        String sha1 = Kit.Json.str(file, "hashes", "sha1");
        if (sha1 != null && !sha1.equalsIgnoreCase(Images.sha1(Files.readAllBytes(tmp)))) {
            Files.deleteIfExists(tmp);
            throw new IOException("the download was damaged");
        }
        for (Path old : have) if (!old.equals(target)) Files.deleteIfExists(old);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        Kit.set("grim.version", id);
        String num = Kit.Json.str(v, "version_number");
        return have.isEmpty() ? "Anti-cheat is on (Grim " + (num == null ? "" : num) + ")." : null;
    }

    /** Version label of the installed Grim, for the Settings page. */
    static String installedLabel() {
        List<Path> j = grimJars(Kit.plugins());
        if (j.isEmpty()) return null;
        return j.get(0).getFileName().toString().replaceFirst("(?i)\\.jar$", "");
    }

    // ------------------------------------------------------------------ anti-X-ray (Paper's own)

    static Path paperWorldDefaults() { return Kit.dir().resolve("config").resolve("paper-world-defaults.yml"); }

    /** Sets Paper's anti-X-ray to match the switches: the world defaults (Overworld) and the Nether's own file. */
    static void configureXray() {
        String mode = xrayStrong() ? "2" : "1";
        editYaml(paperWorldDefaults(), y -> withSection(withAntiXray(withAntiXray(y, "enabled", String.valueOf(xray())), "engine-mode", mode),
                "feature-seeds", "generate-random-seeds-for-all", String.valueOf(shuffleOres())));
        for (Path f : netherConfigs()) {
            editYaml(f, y -> {
                if (!y.matches("(?s).*(^|\n)anticheat:\\s*\n.*")) {
                    if (!xrayNether()) return y;
                    y = y.stripTrailing() + "\n" + NETHER_BLOCK + "\n";
                }
                return withAntiXray(withAntiXray(y, "enabled", String.valueOf(xrayNether())), "engine-mode", mode);
            });
        }
    }

    /** Netherite and Nether ores, hidden behind netherrack (Paper's suggested Nether setup). */
    static final String NETHER_BLOCK = String.join("\n",
            "anticheat:",
            "  anti-xray:",
            "    enabled: true",
            "    engine-mode: 1",
            "    hidden-blocks:",
            "    - ancient_debris",
            "    - nether_gold_ore",
            "    - nether_quartz_ore",
            "    - chest",
            "    lava-obscures: false",
            "    max-block-height: 128",
            "    replacement-blocks:",
            "    - basalt",
            "    - blackstone",
            "    - gravel",
            "    - netherrack",
            "    - soul_sand",
            "    - soul_soil",
            "    update-radius: 2",
            "    use-permission: false");

    /** The Nether's paper-world.yml: world/dimensions/minecraft/the_nether (Paper 26.x) or world_nether (older). */
    static List<Path> netherConfigs() {
        String level = Setup.readProps().getOrDefault("level-name", "world");
        List<Path> out = new ArrayList<>();
        for (Path p : List.of(Kit.dir().resolve(level).resolve("dimensions").resolve("minecraft").resolve("the_nether").resolve("paper-world.yml"),
                Kit.dir().resolve(level + "_nether").resolve("paper-world.yml")))
            if (Files.exists(p)) out.add(p);
        return out;
    }

    private static void editYaml(Path f, java.util.function.UnaryOperator<String> change) {
        if (!Files.exists(f)) return; // made by Paper on the first start; set on the next one
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String out = change.apply(text);
            if (!out.equals(text)) Files.writeString(f, out, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    static String withAntiXray(String yaml, boolean on) { return withAntiXray(yaml, "enabled", String.valueOf(on)); }

    /** Rewrites one "key: value" line inside anticheat: > anti-xray:, keeping everything else as it was. */
    static String withAntiXray(String yaml, String key, String value) {
        String[] lines = yaml.split("\n", -1);
        int anticheat = -1, ax = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.matches("^anticheat:\\s*$")) { anticheat = i; continue; }
            if (anticheat >= 0 && ax < 0 && l.matches("^\\S.*")) anticheat = -1; // left the section
            if (anticheat >= 0 && ax < 0 && l.matches("^\\s+anti-xray:\\s*$")) { ax = i; continue; }
            if (ax >= 0) {
                int axIndent = indent(lines[ax]);
                if (!l.isBlank() && indent(l) <= axIndent) break;
                Matcher m = Pattern.compile("^(\\s+)" + Pattern.quote(key) + ":\\s*(\\S+)\\s*$").matcher(l);
                if (m.matches() && indent(l) > axIndent) {
                    lines[i] = m.group(1) + key + ": " + value;
                    return String.join("\n", lines);
                }
            }
        }
        return yaml;
    }

    /** Rewrites "key: value" inside a top-level section ("section:" at the start of a line). */
    static String withSection(String yaml, String section, String key, String value) {
        String[] lines = yaml.split("\n", -1);
        boolean in = false;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.matches("^" + Pattern.quote(section) + ":\\s*$")) { in = true; continue; }
            if (in && !l.isBlank() && indent(l) == 0) break;
            if (in) {
                Matcher m = Pattern.compile("^(\\s+)" + Pattern.quote(key) + ":\\s*(\\S+)\\s*$").matcher(l);
                if (m.matches()) {
                    lines[i] = m.group(1) + key + ": " + value;
                    return String.join("\n", lines);
                }
            }
        }
        return yaml;
    }

    private static int indent(String l) {
        int i = 0;
        while (i < l.length() && l.charAt(i) == ' ') i++;
        return i;
    }

    // ------------------------------------------------------------------ alerts

    /** Grim's console alert, e.g. "Grim » Steve failed Reach (x3) 3.2 blocks". Colors are already stripped. */
    static final Pattern ALERT = Pattern.compile("(?i)^\\s*(?:\\[[^\\]]*\\]\\s*)?grim\\S*\\s*[»>:]+\\s*\\.?([A-Za-z0-9_.]{2,20}) failed ([A-Za-z][^()]{0,40}?)\\s*\\(x(\\d+)\\)");

    private static final Map<String, Long> lastSaid = new HashMap<>();

    /** Turns an alert into a friendly feed line, at most once a minute per player and cheat. */
    static String alert(String msg, java.util.function.UnaryOperator<String> nice) {
        Matcher m = ALERT.matcher(msg);
        if (!m.find()) return null;
        String who = m.group(1), check = m.group(2).trim();
        String key = who.toLowerCase(Locale.ROOT) + "|" + check.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        synchronized (lastSaid) {
            Long t = lastSaid.get(key);
            if (t != null && now - t < 60_000) return "";
            lastSaid.put(key, now);
        }
        return nice.apply(who) + " might be cheating (" + friendly(check) + "). The anti-cheat is watching them.";
    }

    // ------------------------------------------------------------------ chat call-out

    static final String DEFAULT_CALLOUT = "{player} is a cheater!";

    static boolean callout() { return Kit.getBool("anticheat.callout", true); }

    static String calloutMessage() {
        String m = Kit.get("anticheat.callout.message", DEFAULT_CALLOUT);
        return m == null || m.isBlank() ? DEFAULT_CALLOUT : m;
    }

    private static final Map<String, Long> lastCalledOut = new HashMap<>();

    /**
     * The command that tells everyone in chat (Java and Bedrock) that a flagged player is a cheater, or null when
     * this alert isn't one, the call-out is off, or this player was already called out in the last minute.
     */
    static String calloutCommand(String msg, java.util.function.UnaryOperator<String> nice) {
        if (!callout()) return null;
        Matcher m = ALERT.matcher(msg);
        if (!m.find()) return null;
        String who = nice.apply(m.group(1));
        long now = System.currentTimeMillis();
        synchronized (lastCalledOut) {
            Long t = lastCalledOut.get(who.toLowerCase(Locale.ROOT));
            if (t != null && now - t < 60_000) return null;
            lastCalledOut.put(who.toLowerCase(Locale.ROOT), now);
        }
        return calloutFor(who, shortName(m.group(2).trim()));
    }

    /** The red chat message for a caught player. */
    static String calloutFor(String who, String cheat) {
        String text = "\u26A0 " + calloutMessage().replace("{player}", who).replace("{cheat}", cheat);
        return "tellraw @a {\"text\":\"" + jsonEscape(text) + "\",\"color\":\"red\",\"bold\":true}";
    }

    static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    static String shortName(String check) {
        String c = check.toLowerCase(Locale.ROOT);
        if (c.contains("reach")) return "reach";
        if (c.contains("killaura") || c.contains("aim")) return "kill aura";
        if (c.contains("autoclick") || c.contains("cps")) return "auto-clicker";
        if (c.contains("fly") || c.contains("simulation") || c.contains("prediction")) return "flying/speed";
        if (c.contains("speed") || c.contains("timer")) return "speed";
        if (c.contains("nofall") || c.contains("groundspoof")) return "no fall damage";
        if (c.contains("velocity") || c.contains("knockback")) return "no knockback";
        if (c.contains("scaffold") || c.contains("place") || c.contains("fastbreak")) return "fast building";
        return "hacks";
    }

    // ------------------------------------------------------------------ game clients (XrayWatch "CLIENT" lines)

    /** "[XrayWatch] CLIENT player=Steve brand=fabric" */
    static final Pattern CLIENT = Pattern.compile("^\\[XrayWatch\\] CLIENT player=\\.?(\\S+) brand=(.*)$");

    enum ClientKind { NORMAL, PVP, MODDED, HACKED, UNKNOWN, BEDROCK }

    /** Hacked clients that put their own name in the brand. Careful cheaters hide it, so this only catches the careless. */
    static final List<String> HACKED = List.of("meteor", "wurst", "liquidbounce", "impact", "aristois", "rusherhack", "future",
            "inertia", "sigma", "novoline", "kami", "salhack", "phobos", "konas", "bleachhack", "thunderhack", "prestige",
            "boze", "coffee", "lambda", "3arthh4ck", "earthhack", "forgehax", "wolfram", "doomsday", "argon", "vape");
    /** Well-known PvP/performance clients that are fine on most servers. */
    static final List<String> PVP = List.of("lunarclient", "lunar", "badlion", "feather", "labymod", "pvplounge", "salwyrr");

    static ClientKind kindOf(String brand) {
        if (brand == null || brand.isBlank() || brand.equalsIgnoreCase("unknown")) return ClientKind.UNKNOWN;
        String b = brand.toLowerCase(Locale.ROOT);
        if (b.contains("geyser") || b.contains("floodgate")) return ClientKind.BEDROCK;
        for (String h : HACKED) if (b.contains(h)) return ClientKind.HACKED;
        for (String v : PVP) if (b.contains(v)) return ClientKind.PVP;
        if (b.equals("vanilla")) return ClientKind.NORMAL;
        if (b.contains("fabric") || b.contains("forge") || b.contains("quilt") || b.contains("optifine")) return ClientKind.MODDED;
        return ClientKind.MODDED; // anything else isn't plain Minecraft
    }

    /** A short, friendly name for the client, e.g. "Fabric", "Lunar Client", "Meteor (hacked client)". */
    static String clientLabel(String brand) {
        String b = brand == null ? "" : brand.trim();
        String l = b.toLowerCase(Locale.ROOT);
        return switch (kindOf(b)) {
            case NORMAL -> "Normal Minecraft";
            case BEDROCK -> "Bedrock";
            case UNKNOWN -> "Unknown game";
            case PVP -> l.contains("lunar") ? "Lunar Client" : l.contains("badlion") ? "Badlion" : l.contains("feather") ? "Feather"
                    : l.contains("laby") ? "LabyMod" : b;
            case HACKED -> {
                for (String h : HACKED) if (l.contains(h)) yield Character.toUpperCase(h.charAt(0)) + h.substring(1) + " (hacked client)";
                yield b + " (hacked client)";
            }
            case MODDED -> l.contains("fabric") ? "Fabric (modded)" : l.contains("neoforge") ? "NeoForge (modded)"
                    : l.contains("forge") ? "Forge (modded)" : l.contains("quilt") ? "Quilt (modded)" : b + " (modded)";
        };
    }

    static boolean kickHacked() { return Kit.getBool("anticheat.clients.kick", true); }

    /** Only allow normal Minecraft (and well-known PvP clients): kicks Fabric/Forge and unknown modded games. */
    static boolean kickModded() { return Kit.getBool("anticheat.clients.vanillaOnly", false); }

    static String kickReason(ClientKind k) {
        return k == ClientKind.HACKED ? "Hacked clients aren't allowed on this server."
                : "This server only allows normal Minecraft (or Lunar/Badlion/Feather). Turn off your mods to join.";
    }

    // ------------------------------------------------------------------ graves (XrayWatch "GRAVE" lines)

    static final Pattern GRAVE = Pattern.compile("^\\[XrayWatch\\] GRAVE (made|opened) player=\\.?(\\S+)(?: owner=\\.?(\\S+))?(?: items=(\\d+))?"
            + "(?: xp=\\d+)? world=(\\S+) x=(-?\\d+) y=(-?\\d+) z=(-?\\d+)");

    /** A friendly activity line for a grave, or null if the line isn't about graves. */
    static String graveLine(String msg, java.util.function.UnaryOperator<String> nice) {
        Matcher m = GRAVE.matcher(msg);
        if (!m.find()) return null;
        String who = nice.apply(m.group(2)), where = m.group(6) + " " + m.group(7) + " " + m.group(8)
                + (m.group(5).contains("nether") ? " in the Nether" : m.group(5).contains("end") ? " in the End" : "");
        if (m.group(1).equals("made")) return who + " died. Their items (" + m.group(4) + ") are safe in a grave at " + where + ".";
        String owner = m.group(3) == null ? who : nice.apply(m.group(3));
        return owner.equalsIgnoreCase(who) ? who + " got their items back from their grave." : who + " opened " + owner + "'s grave.";
    }

    // ------------------------------------------------------------------ XrayWatch flags

    /** "[XrayWatch] FLAG likely player=Steve score=93 veins=8 targeted=6 weighted=8.0 blocks=180 tunnel=22 minutes=30 world=world x=1 y=-58 z=2 ore=..." */
    static final Pattern XRAY_FLAG = Pattern.compile("^\\[XrayWatch\\] FLAG (likely|suspicious) player=\\.?(\\S+) score=(\\d+) veins=(\\d+) targeted=(\\d+)"
            + ".*? blocks=(\\d+).*? minutes=(\\d+)(?: world=(\\S+) x=(-?\\d+) y=(-?\\d+) z=(-?\\d+))?");

    record XrayFlag(boolean likely, String player, int score, int veins, int targeted, int blocks, int minutes, String where) {
        String feedLine() {
            return player + (likely ? " is very likely using X-ray" : " might be using X-ray") + ": in the last " + minutes
                    + " minutes they dug straight to " + targeted + " hidden ore vein" + (targeted == 1 ? "" : "s") + " (" + veins
                    + " hidden veins in " + blocks + " blocks mined). Score " + score + "/100."
                    + (where == null ? "" : " Latest at " + where + ".") + " See the X-ray report on the Rules page.";
        }
    }

    static XrayFlag xrayFlag(String msg, java.util.function.UnaryOperator<String> nice) {
        Matcher m = XRAY_FLAG.matcher(msg);
        if (!m.find()) return null;
        String where = m.group(9) == null ? null : m.group(9) + " " + m.group(10) + " " + m.group(11) + ("world".equals(m.group(8)) ? "" : " (" + m.group(8) + ")");
        return new XrayFlag("likely".equals(m.group(1)), nice.apply(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7)), where);
    }

    static String friendly(String check) {
        String c = check.toLowerCase(Locale.ROOT);
        if (c.contains("reach")) return "hitting from too far away";
        if (c.contains("killaura") || c.contains("aim")) return "kill aura / aimbot";
        if (c.contains("autoclick") || c.contains("cps")) return "auto-clicker";
        if (c.contains("fly") || c.contains("simulation") || c.contains("prediction")) return "moving in an impossible way, like flying or speed";
        if (c.contains("speed") || c.contains("timer")) return "speed";
        if (c.contains("nofall") || c.contains("groundspoof")) return "no fall damage";
        if (c.contains("velocity") || c.contains("knockback")) return "no knockback";
        if (c.contains("scaffold") || c.contains("place") || c.contains("fastbreak")) return "building or mining too fast";
        if (c.contains("badpackets") || c.contains("crash")) return "a hacked client";
        return check;
    }
}
