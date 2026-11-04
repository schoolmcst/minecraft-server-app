// Server software downloads, EULA, server.properties, world backups, desktop shortcut.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

final class Setup {
    private Setup() { }

    static final String FILL = System.getProperty("kit.fill", "https://fill.papermc.io/v3/projects/paper");
    static final String GEYSER = System.getProperty("kit.geyser", "https://download.geysermc.org/v2/projects");

    // ------------------------------------------------------------------ server software
    //
    // Two kinds of server: "paper" (survival with plugins; Geyser/Floodgate as plugins) and "fabric" (modded;
    // Geyser/Floodgate as Fabric mods). Bedrock support ("crossplay") can be turned off for a modded server.

    static final String FABRIC_META = System.getProperty("kit.fabricmeta", "https://meta.fabricmc.net/v2");

    static boolean crossplay() { return Kit.getBool("server.crossplay", true); }

    static String platform() { return Kit.isFabric() ? "fabric" : "spigot"; }

    static Path geyserJar() { return Kit.isFabric() ? Kit.mods().resolve("Geyser-Fabric.jar") : Kit.plugins().resolve("Geyser-Spigot.jar"); }

    static Path floodgateJar() { return Kit.isFabric() ? Kit.mods().resolve("Floodgate-Fabric.jar") : Kit.plugins().resolve("floodgate-spigot.jar"); }

    static Path geyserDir() {
        return Kit.isFabric() ? Kit.dir().resolve("config").resolve("Geyser-Fabric") : Kit.plugins().resolve("Geyser-Spigot");
    }

    static Path geyserConfig() { return geyserDir().resolve("config.yml"); }

    static Path xboxJar() { return geyserDir().resolve("extensions").resolve("MCXboxBroadcastExtension.jar"); }

    /** For Fabric, Geyser/Floodgate may come from GeyserMC (fixed names) or Modrinth (versioned names). */
    static boolean present(Path fixed, String prefix) {
        if (Files.exists(fixed)) return true;
        if (!Kit.isFabric()) return false;
        for (Mods.Installed m : Mods.installed())
            if (m.file().getFileName().toString().toLowerCase(Locale.ROOT).startsWith(prefix)) return true;
        return false;
    }

    static boolean softwareMissing() {
        if (!Kit.hasServer()) return true;
        if (!Files.exists(Kit.serverJar())) return true;
        if (Kit.isFabric() && !Mods.hasFabricApi()) return true;
        return crossplay() && (!present(geyserJar(), "geyser") || !present(floodgateJar(), "floodgate"));
    }

    /** Downloads whatever is missing, or everything when {@code update} is true. */
    static void ensureSoftware(boolean update, Kit.Progress p) throws IOException, InterruptedException {
        if (Kit.isFabric()) {
            if (!Files.exists(Kit.serverJar()) || update) downloadFabric(p);
            Files.createDirectories(Kit.mods());
            if (!Mods.hasFabricApi() || update) {
                p.update("Downloading Fabric API", -1);
                Mods.install("fabric-api", Kit.mcVersion(), p);
            }
        } else {
            if (!Files.exists(Kit.serverJar()) || update) downloadPaper(p);
            Files.createDirectories(Kit.plugins());
        }
        if (crossplay()) {
            if (!present(geyserJar(), "geyser") || update) downloadGeyser("geyser", geyserJar(), "Downloading Bedrock support (Geyser)", p);
            if (!present(floodgateJar(), "floodgate") || update) downloadGeyser("floodgate", floodgateJar(), "Downloading Floodgate", p);
        }
    }

    /** Minecraft versions offered when making a new server, newest first. */
    static List<String> availableVersions(String type) throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        if ("fabric".equals(type)) {
            for (Object o : (List<?>) Kit.Json.parse(Kit.getString(FABRIC_META + "/versions/game")))
                if (Boolean.TRUE.equals(Kit.Json.get(o, "stable"))) out.add(Kit.Json.str(o, "version"));
        } else {
            Object project = Kit.Json.parse(Kit.getString(FILL));
            if (Kit.Json.get(project, "versions") instanceof Map<?, ?> m)
                for (Object list : m.values())
                    if (list instanceof List<?> l) for (Object v : l) {
                        String s = String.valueOf(v);
                        if (!s.contains("pre") && !s.contains("rc") && !s.contains("snapshot")) out.add(s);
                    }
        }
        out.sort((a, b) -> Updater.compare(b, a));
        return out.subList(0, Math.min(out.size(), 12));
    }

    static void downloadPaper(Kit.Progress p) throws IOException, InterruptedException {
        p.update("Finding the latest Minecraft server", -1);
        List<String> versions = new ArrayList<>();
        String target = Kit.get("mc.target", null);
        if (target != null) {
            versions.add(target);
        } else {
            Object project = Kit.Json.parse(Kit.getString(FILL));
            if (Kit.Json.get(project, "versions") instanceof Map<?, ?> m) {
                for (Object list : m.values())
                    if (list instanceof List<?> l) for (Object v : l) versions.add(String.valueOf(v));
            }
        }
        if (versions.isEmpty()) throw new IOException("Paper's download service returned no versions");

        int tried = 0;
        for (String v : versions) {
            if (++tried > 8) break;
            Map<?, ?> build = latestStable(Kit.Json.parse(Kit.getString(FILL + "/versions/" + v + "/builds")));
            if (build == null) continue;
            Object dl = Kit.Json.get(build, "downloads", "server:default");
            String url = Kit.Json.str(dl, "url");
            String sha = Kit.Json.str(dl, "checksums", "sha256");
            Path tmp = Kit.download(url, Kit.serverJar(), "Downloading the Minecraft " + v + " server", p);
            if (sha != null && !sha.equalsIgnoreCase(Kit.sha256(tmp))) {
                Files.deleteIfExists(tmp);
                throw new IOException("The download was damaged on the way. Please try again.");
            }
            Files.move(tmp, Kit.serverJar(), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(Kit.versionFile(), v + System.lineSeparator());
            Kit.set("paper.build", Kit.Json.get(build, "id"));
            Kit.set("paper.newer", null);
            Kit.set("mc.target", null);
            return;
        }
        throw new IOException(target != null ? "There's no stable server for Minecraft " + target + " yet"
                : "No stable Paper server build is available right now");
    }

    /** Fabric's server launcher: one jar that fetches Minecraft and the loader on its first run. */
    static void downloadFabric(Kit.Progress p) throws IOException, InterruptedException {
        p.update("Finding the Fabric server", -1);
        String mc = Kit.get("mc.target", null);
        if (mc == null) mc = availableVersions("fabric").get(0);
        String loader = firstStable(Kit.Json.parse(Kit.getString(FABRIC_META + "/versions/loader/" + mc)), "loader");
        String installer = firstStable(Kit.Json.parse(Kit.getString(FABRIC_META + "/versions/installer")), null);
        if (loader == null || installer == null) throw new IOException("Fabric doesn't support Minecraft " + mc + " yet");
        Path tmp = Kit.download(FABRIC_META + "/versions/loader/" + mc + "/" + loader + "/" + installer + "/server/jar",
                Kit.serverJar(), "Downloading the Fabric " + mc + " server", p);
        Files.move(tmp, Kit.serverJar(), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(Kit.versionFile(), mc + System.lineSeparator());
        Kit.set("fabric.loader", loader);
        Kit.set("mc.target", null);
    }

    private static String firstStable(Object list, String inner) {
        if (!(list instanceof List<?> l)) return null;
        for (Object o : l) {
            Object e = inner == null ? o : Kit.Json.get(o, inner);
            if (Boolean.TRUE.equals(Kit.Json.get(e, "stable"))) return Kit.Json.str(e, "version");
        }
        return null;
    }

    /** Newest build whose channel is STABLE, or null. */
    static Map<?, ?> latestStable(Object builds) {
        Map<?, ?> best = null;
        long bestId = Long.MIN_VALUE;
        if (!(builds instanceof List<?> list)) return null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> b)) continue;
            if (!"STABLE".equals(b.get("channel"))) continue;
            long n = b.get("id") instanceof Number num ? num.longValue() : 0;
            if (best == null || n > bestId) { best = b; bestId = n; }
        }
        return best;
    }

    /** Geyser or Floodgate for this server's platform (GeyserMC's site first, Modrinth as a fallback for Fabric). */
    static void downloadGeyser(String project, Path target, String label, Kit.Progress p)
            throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path tmp;
        try {
            tmp = Kit.download(GEYSER + "/" + project + "/versions/latest/builds/latest/downloads/" + platform(), target, label, p);
        } catch (IOException e) {
            if (!Kit.isFabric()) throw e;
            Mods.install(project, Kit.mcVersion(), p);
            return;
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        Kit.set(project + ".sha", Kit.sha256(target));
    }

    // ------------------------------------------------------------------ automatic updates

    static final String XBOX_RELEASES = System.getProperty("kit.xboxdl", "https://github.com/MCXboxBroadcast/Broadcaster/releases");

    /**
     * Keeps the server software current. Called before each start. Paper only gets new builds of the Minecraft
     * version you're on (a new Minecraft version means Java players must update too, so that's a choice: see
     * newerMinecraft()). Returns what changed, for the activity feed. Never throws: offline just means no update.
     */
    static List<String> autoUpdate(Kit.Progress p) {
        List<String> done = new ArrayList<>();
        try {
            if (!Kit.isFabric() && updatePaperBuild(p)) done.add("Minecraft server (Paper build " + Kit.get("paper.build", "?") + ")");
        } catch (Exception ignored) { }
        try {
            if (crossplay() && updateGeyserProject("geyser", geyserJar(), "Bedrock support", p)) done.add("Bedrock support (Geyser)");
        } catch (Exception ignored) { }
        try {
            if (crossplay() && updateGeyserProject("floodgate", floodgateJar(), "Floodgate", p)) done.add("Floodgate");
        } catch (Exception ignored) { }
        try {
            if (Files.exists(xboxJar()) && updateXbox(p)) done.add("console support (MCXboxBroadcast " + Kit.get("xbox.tag", "") + ")");
        } catch (Exception ignored) { }
        try { if (!Kit.isFabric()) checkNewerMinecraft(); } catch (Exception ignored) { }
        Kit.set("server.lastUpdateCheck", System.currentTimeMillis());
        return done;
    }

    /** Newest stable Paper build of the Minecraft version already installed. */
    static boolean updatePaperBuild(Kit.Progress p) throws IOException, InterruptedException {
        String v = Kit.mcVersion();
        if (v.isEmpty() || !Files.exists(Kit.serverJar())) return false;
        Map<?, ?> build = latestStable(Kit.Json.parse(Kit.getString(FILL + "/versions/" + v + "/builds")));
        if (build == null) return false;
        long id = build.get("id") instanceof Number n ? n.longValue() : -1;
        long have = parseLong(Kit.get("paper.build", "-1"));
        Object dl = Kit.Json.get(build, "downloads", "server:default");
        String sha = Kit.Json.str(dl, "checksums", "sha256");
        if (have >= id) return false;
        if (have < 0 && sha != null && sha.equalsIgnoreCase(Kit.sha256(Kit.serverJar()))) { Kit.set("paper.build", id); return false; }
        Path tmp = Kit.download(Kit.Json.str(dl, "url"), Kit.serverJar(), "Updating the Minecraft server", p);
        if (sha != null && !sha.equalsIgnoreCase(Kit.sha256(tmp))) { Files.deleteIfExists(tmp); return false; }
        Files.move(tmp, Kit.serverJar(), StandardCopyOption.REPLACE_EXISTING);
        Kit.set("paper.build", id);
        return true;
    }

    /** Remembers whether a newer Minecraft version (with a stable Paper build) exists. */
    static void checkNewerMinecraft() throws IOException, InterruptedException {
        String cur = Kit.mcVersion();
        if (cur.isEmpty()) return;
        Object project = Kit.Json.parse(Kit.getString(FILL));
        List<String> versions = new ArrayList<>();
        if (Kit.Json.get(project, "versions") instanceof Map<?, ?> m)
            for (Object list : m.values()) if (list instanceof List<?> l) for (Object v : l) versions.add(String.valueOf(v));
        String newer = null;
        int tried = 0;
        for (String v : versions) {
            if (Updater.compare(v, cur) <= 0 || v.contains("pre") || v.contains("rc") || v.contains("snapshot")) continue;
            if (++tried > 4) break;
            if (latestStable(Kit.Json.parse(Kit.getString(FILL + "/versions/" + v + "/builds"))) != null) { newer = v; break; }
        }
        Kit.set("paper.newer", newer);
    }

    static String newerMinecraft() { return Kit.get("paper.newer", null); }

    /** Geyser/Floodgate: download only when the published build differs from the file we have. */
    static boolean updateGeyserProject(String project, Path target, String label, Kit.Progress p)
            throws IOException, InterruptedException {
        if (!Files.exists(target)) return false;
        Object info = Kit.Json.parse(Kit.getString(GEYSER + "/" + project + "/versions/latest/builds/latest"));
        String sha = Kit.Json.str(info, "downloads", platform(), "sha256");
        String build = Kit.Json.str(info, "build");
        String key = project + ".sha";
        String have = Kit.get(key, null);
        if (have == null) { have = Kit.sha256(target); Kit.set(key, have); }
        if (sha == null || sha.equalsIgnoreCase(have)) return false;
        Path tmp = Kit.download(GEYSER + "/" + project + "/versions/latest/builds/latest/downloads/" + platform(), target,
                "Updating " + label, p);
        String got = Kit.sha256(tmp);
        if (!got.equalsIgnoreCase(sha)) { Files.deleteIfExists(tmp); return false; }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        Kit.set(key, got);
        if (build != null) Kit.set(project + ".build", build);
        return true;
    }

    /** MCXboxBroadcast: GitHub's "latest release" link redirects to the release tag. */
    static boolean updateXbox(Kit.Progress p) throws IOException, InterruptedException {
        java.net.http.HttpClient noRedirect = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofSeconds(15)).build();
        var r = noRedirect.send(Kit.request(XBOX_RELEASES + "/latest").build(), java.net.http.HttpResponse.BodyHandlers.discarding());
        String loc = r.headers().firstValue("location").orElse("");
        int i = loc.lastIndexOf("/tag/");
        if (i < 0) return false;
        String tag = loc.substring(i + 5);
        String have = Kit.get("xbox.tag", null);
        if (have == null) { Kit.set("xbox.tag", tag); return false; } // the jar we have came from the current release
        if (have.equals(tag)) return false;
        Path tmp = Kit.download(XBOX_RELEASES + "/download/" + tag + "/MCXboxBroadcastExtension.jar", xboxJar(),
                "Updating console support", p);
        Files.move(tmp, xboxJar(), StandardCopyOption.REPLACE_EXISTING);
        Kit.set("xbox.tag", tag);
        return true;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    static int requiredJava(String mcVersion) {
        try {
            int major = Integer.parseInt(mcVersion.split("[.\\-]")[0]);
            return major >= 26 ? 25 : 21; // 26.x needs Java 25; 1.20.5-1.21.x need Java 21
        } catch (Exception e) {
            return 21;
        }
    }

    // ------------------------------------------------------------------ EULA

    static Path eula() { return Kit.dir().resolve("eula.txt"); }

    static boolean eulaAccepted() {
        try { return Files.exists(eula()) && Files.readString(eula()).contains("eula=true"); }
        catch (IOException e) { return false; }
    }

    static void acceptEula() throws IOException {
        Files.writeString(eula(), "# Accepted in the Minecraft Server app on " + LocalDate.now()
                + " (https://aka.ms/MinecraftEULA)" + System.lineSeparator() + "eula=true" + System.lineSeparator());
    }

    // ------------------------------------------------------------------ server.properties

    static Path props() { return Kit.dir().resolve("server.properties"); }

    static Map<String, String> readProps() {
        Properties p = new Properties();
        try (Reader r = new StringReader(readPropsText())) {
            p.load(r);
        } catch (IOException ignored) { }
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
        return m;
    }

    /** Updates keys in place, keeping comments and order; appends keys that aren't there yet. */
    static void writeProps(Map<String, String> changes) throws IOException {
        List<String> lines = Files.exists(props())
                ? new ArrayList<>(readPropsText().lines().toList())
                : new ArrayList<>(List.of("#Minecraft server properties"));
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            String key = (eq < 0 ? t : t.substring(0, eq)).trim();
            if (changes.containsKey(key)) {
                lines.set(i, key + "=" + escapeProp(changes.get(key)));
                done.add(key);
            }
        }
        for (var e : changes.entrySet())
            if (!done.contains(e.getKey())) lines.add(e.getKey() + "=" + escapeProp(e.getValue()));
        Files.write(props(), lines, StandardCharsets.UTF_8);
    }

    /** Minecraft reads server.properties as UTF-8 and falls back to Latin-1; do the same. */
    static String readPropsText() throws IOException {
        if (!Kit.hasServer()) return "";
        if (!Files.exists(props())) return "";
        byte[] b = Files.readAllBytes(props());
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(b, StandardCharsets.ISO_8859_1);
        }
    }

    static String escapeProp(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (i == 0 && c == ' ') b.append("\\ ");
            else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ Geyser


    /** The broadcast-port in Geyser's config, or -1 if unknown. */
    static int geyserBroadcastPort() {
        try {
            if (!Files.exists(geyserConfig())) return -1;
            Matcher m = Pattern.compile("(?m)^\\s*broadcast-port:\\s*\"?(\\d+)").matcher(Files.readString(geyserConfig()));
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /** Tells Geyser which public port Bedrock players use (needed behind playit). Returns true if it changed. */
    static boolean setGeyserBroadcastPort(int port) {
        try {
            if (!Files.exists(geyserConfig())) return false;
            String yml = Files.readString(geyserConfig(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?m)^(\\s*broadcast-port:\\s*)\"?(\\d*)\"?[ \\t]*$").matcher(yml);
            if (!m.find()) return false;
            if (m.group(2).equals(String.valueOf(port))) return false;
            String out = yml.substring(0, m.start()) + m.group(1) + port + yml.substring(m.end());
            Files.writeString(geyserConfig(), out, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ backups

    static final int KEEP_BACKUPS = 5;
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    static List<Path> worldFolders() {
        if (!Kit.hasServer()) return List.of();
        try (Stream<Path> s = Files.list(Kit.dir())) {
            return s.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("level.dat")))
                    .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    static boolean hasWorld() { return !worldFolders().isEmpty(); }

    /** Zips every world folder into backups/. Returns the zip, or null if there is no world yet. */
    static Path backupWorlds(String prefix) throws IOException {
        List<Path> worlds = worldFolders();
        if (worlds.isEmpty()) return null;
        Files.createDirectories(Kit.backups());
        Path zip = Kit.backups().resolve(prefix + "-" + LocalDateTime.now().format(STAMP) + ".zip");
        try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            for (Path w : worlds) {
                try (Stream<Path> files = Files.walk(w)) {
                    for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                        if (f.getFileName().toString().equals("session.lock")) continue;
                        zout.putNextEntry(new ZipEntry(Kit.dir().relativize(f).toString().replace('\\', '/')));
                        try {
                            Files.copy(f, zout);
                        } catch (IOException locked) {
                            // a file the running server holds open; skip it rather than fail the whole backup
                        }
                        zout.closeEntry();
                    }
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(zip);
            throw e;
        }
        pruneBackups();
        return zip;
    }

    static List<Path> listBackups() {
        if (!Kit.hasServer()) return List.of();
        if (!Files.isDirectory(Kit.backups())) return List.of();
        try (Stream<Path> s = Files.list(Kit.backups())) {
            return s.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    static void pruneBackups() throws IOException {
        List<Path> auto = listBackups().stream()
                .filter(p -> p.getFileName().toString().startsWith("worlds-"))
                .collect(Collectors.toList());
        for (int i = KEEP_BACKUPS; i < auto.size(); i++) Files.deleteIfExists(auto.get(i));
    }

    /** Replaces the current worlds with the ones in {@code zip} (the current ones are backed up first). */
    static void restore(Path zip) throws IOException {
        backupWorlds("before-restore");
        Set<String> tops = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.contains("..")) continue;
                tops.add(name.split("/")[0]);
            }
        }
        for (String t : tops) deleteTree(Kit.dir().resolve(t));
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory() || e.getName().contains("..")) continue;
                Path out = Kit.dir().resolve(e.getName()).normalize();
                if (!out.startsWith(Kit.dir())) continue;
                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(f);
        }
    }

    static String backupLabel(Path zip) {
        String n = zip.getFileName().toString().replace(".zip", "");
        Matcher m = Pattern.compile("^(.*?)-(\\d{4}-\\d{2}-\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})$").matcher(n);
        String size = String.format("%.1f MB", zip.toFile().length() / (1024.0 * 1024));
        if (!m.find()) return n + "   (" + size + ")";
        String kind = switch (m.group(1)) {
            case "worlds" -> "Automatic";
            case "manual" -> "Manual";
            case "before-restore" -> "Before a restore";
            default -> m.group(1);
        };
        try {
            LocalDateTime t = LocalDateTime.parse(m.group(2) + "T" + m.group(3) + ":" + m.group(4) + ":" + m.group(5));
            String when = t.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US));
            return when + "   " + kind + "   (" + size + ")";
        } catch (Exception e) {
            return n + "   (" + size + ")";
        }
    }

    // ------------------------------------------------------------------ app icon + desktop shortcut

    static final Path ICON_FILE = Kit.HOME.resolve("app-icon.ico");

    static void writeIco(Path to) throws IOException {
        int[] sizes = {16, 24, 32, 48, 64, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(Ui.appIcon(s), "png", b);
            pngs.add(b.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeShort(0); d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) sizes.length));
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            d.writeByte(s >= 256 ? 0 : s); d.writeByte(s >= 256 ? 0 : s);
            d.writeByte(0); d.writeByte(0);
            d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) 32));
            d.writeInt(Integer.reverseBytes(pngs.get(i).length));
            d.writeInt(Integer.reverseBytes(offset));
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) d.write(p);
        Files.write(to, out.toByteArray());
    }

    /** Windows only: puts a "Minecraft Server" shortcut on the desktop that opens this app without a console. */
    static String createDesktopShortcut() throws IOException, InterruptedException {
        writeIco(ICON_FILE);
        Path javaw = Kit.HOME.resolve("runtime").resolve("bin").resolve("javaw.exe");
        String target = Files.exists(javaw) ? javaw.toString()
                : ProcessHandle.current().info().command().orElse("javaw.exe").replace("java.exe", "javaw.exe");
        // a desktop icon, plus a Start menu entry so it can be found by searching "Minecraft Server"
        String one = "$s=$w.CreateShortcut((Join-Path $d 'Minecraft Server.lnk'));"
                + "$s.TargetPath=" + psq(target) + ";"
                + "$s.Arguments=" + psq("-jar \"" + Kit.HOME.resolve("launcher.jar") + "\"") + ";"
                + "$s.WorkingDirectory=" + psq(Kit.HOME.toString()) + ";"
                + "$s.IconLocation=" + psq(ICON_FILE + ",0") + ";"
                + "$s.Description='Minecraft Server';$s.Save();";
        String ps = "$w=New-Object -ComObject WScript.Shell;"
                + "$d=[Environment]::GetFolderPath('Desktop');" + one
                + "$d=[Environment]::GetFolderPath('Programs');" + one
                + "Write-Output ([Environment]::GetFolderPath('Desktop'))";
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) throw new IOException(out.isEmpty() ? "PowerShell failed" : out);
        return out;
    }

    private static String psq(String s) { return "'" + s.replace("'", "''") + "'"; }
}
