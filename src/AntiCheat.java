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

    static boolean enabled() { return applies() && Kit.getBool("anticheat", true); }

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
        setAntiXray(enabled());
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

    /**
     * Sets anticheat.anti-xray.enabled in Paper's world defaults. The file only exists after the first start; until
     * then Paper's default (off) applies and the next start sets it.
     */
    static boolean setAntiXray(boolean on) {
        Path f = paperWorldDefaults();
        if (!Files.exists(f)) return false;
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            String out = withAntiXray(text, on);
            if (!out.equals(text)) Files.writeString(f, out, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Rewrites the "enabled:" line inside anticheat: > anti-xray:, keeping everything else as it was. */
    static String withAntiXray(String yaml, boolean on) {
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
                Matcher m = Pattern.compile("^(\\s+)enabled:\\s*(true|false)\\s*$").matcher(l);
                if (m.matches() && indent(l) > axIndent) {
                    lines[i] = m.group(1) + "enabled: " + on;
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
