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

    static boolean softwareMissing() {
        return !Files.exists(Kit.PAPER_JAR)
                || !Files.exists(Kit.PLUGINS.resolve("Geyser-Spigot.jar"))
                || !Files.exists(Kit.PLUGINS.resolve("floodgate-spigot.jar"));
    }

    /** Downloads whatever is missing, or everything when {@code update} is true. */
    static void ensureSoftware(boolean update, Kit.Progress p) throws IOException, InterruptedException {
        if (!Files.exists(Kit.PAPER_JAR) || update) downloadPaper(p);
        Files.createDirectories(Kit.PLUGINS);
        if (!Files.exists(Kit.PLUGINS.resolve("Geyser-Spigot.jar")) || update)
            downloadPlugin("geyser", "Geyser-Spigot.jar", "Downloading Bedrock support (Geyser)", p);
        if (!Files.exists(Kit.PLUGINS.resolve("floodgate-spigot.jar")) || update)
            downloadPlugin("floodgate", "floodgate-spigot.jar", "Downloading Floodgate", p);
    }

    static void downloadPaper(Kit.Progress p) throws IOException, InterruptedException {
        p.update("Finding the latest Minecraft server", -1);
        Object project = Kit.Json.parse(Kit.getString(FILL));
        List<String> versions = new ArrayList<>();
        if (Kit.Json.get(project, "versions") instanceof Map<?, ?> m) {
            for (Object list : m.values())
                if (list instanceof List<?> l) for (Object v : l) versions.add(String.valueOf(v));
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
            Path tmp = Kit.download(url, Kit.PAPER_JAR, "Downloading the Minecraft " + v + " server", p);
            if (sha != null && !sha.equalsIgnoreCase(Kit.sha256(tmp))) {
                Files.deleteIfExists(tmp);
                throw new IOException("The download was damaged on the way. Please try again.");
            }
            Files.move(tmp, Kit.PAPER_JAR, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(Kit.VERSION_FILE, v + System.lineSeparator());
            Kit.set("paper.build", Kit.Json.get(build, "id"));
            return;
        }
        throw new IOException("No stable Paper server build is available right now");
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

    static void downloadPlugin(String project, String fileName, String label, Kit.Progress p)
            throws IOException, InterruptedException {
        Path target = Kit.PLUGINS.resolve(fileName);
        Path tmp = Kit.download(GEYSER + "/" + project + "/versions/latest/builds/latest/downloads/spigot", target, label, p);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
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

    static final Path EULA = Kit.DIR.resolve("eula.txt");

    static boolean eulaAccepted() {
        try { return Files.exists(EULA) && Files.readString(EULA).contains("eula=true"); }
        catch (IOException e) { return false; }
    }

    static void acceptEula() throws IOException {
        Files.writeString(EULA, "# Accepted in the Minecraft Server app on " + LocalDate.now()
                + " (https://aka.ms/MinecraftEULA)" + System.lineSeparator() + "eula=true" + System.lineSeparator());
    }

    // ------------------------------------------------------------------ server.properties

    static final Path PROPS = Kit.DIR.resolve("server.properties");

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
        List<String> lines = Files.exists(PROPS)
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
        Files.write(PROPS, lines, StandardCharsets.UTF_8);
    }

    /** Minecraft reads server.properties as UTF-8 and falls back to Latin-1; do the same. */
    static String readPropsText() throws IOException {
        if (!Files.exists(PROPS)) return "";
        byte[] b = Files.readAllBytes(PROPS);
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

    static final Path GEYSER_CONFIG = Kit.PLUGINS.resolve("Geyser-Spigot").resolve("config.yml");

    /** The broadcast-port in Geyser's config, or -1 if unknown. */
    static int geyserBroadcastPort() {
        try {
            if (!Files.exists(GEYSER_CONFIG)) return -1;
            Matcher m = Pattern.compile("(?m)^\\s*broadcast-port:\\s*\"?(\\d+)").matcher(Files.readString(GEYSER_CONFIG));
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /** Tells Geyser which public port Bedrock players use (needed behind playit). Returns true if it changed. */
    static boolean setGeyserBroadcastPort(int port) {
        try {
            if (!Files.exists(GEYSER_CONFIG)) return false;
            String yml = Files.readString(GEYSER_CONFIG, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?m)^(\\s*broadcast-port:\\s*)\"?(\\d*)\"?[ \\t]*$").matcher(yml);
            if (!m.find()) return false;
            if (m.group(2).equals(String.valueOf(port))) return false;
            String out = yml.substring(0, m.start()) + m.group(1) + port + yml.substring(m.end());
            Files.writeString(GEYSER_CONFIG, out, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ backups

    static final int KEEP_BACKUPS = 5;
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    static List<Path> worldFolders() {
        try (Stream<Path> s = Files.list(Kit.DIR)) {
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
        Files.createDirectories(Kit.BACKUPS);
        Path zip = Kit.BACKUPS.resolve(prefix + "-" + LocalDateTime.now().format(STAMP) + ".zip");
        try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            for (Path w : worlds) {
                try (Stream<Path> files = Files.walk(w)) {
                    for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                        if (f.getFileName().toString().equals("session.lock")) continue;
                        zout.putNextEntry(new ZipEntry(Kit.DIR.relativize(f).toString().replace('\\', '/')));
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
        if (!Files.isDirectory(Kit.BACKUPS)) return List.of();
        try (Stream<Path> s = Files.list(Kit.BACKUPS)) {
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
        for (String t : tops) deleteTree(Kit.DIR.resolve(t));
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory() || e.getName().contains("..")) continue;
                Path out = Kit.DIR.resolve(e.getName()).normalize();
                if (!out.startsWith(Kit.DIR)) continue;
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

    static final Path ICON_FILE = Kit.DIR.resolve("app-icon.ico");

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
        Path javaw = Kit.DIR.resolve("runtime").resolve("bin").resolve("javaw.exe");
        String target = Files.exists(javaw) ? javaw.toString()
                : ProcessHandle.current().info().command().orElse("javaw.exe").replace("java.exe", "javaw.exe");
        String ps = "$d=[Environment]::GetFolderPath('Desktop');"
                + "$s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $d 'Minecraft Server.lnk'));"
                + "$s.TargetPath=" + psq(target) + ";"
                + "$s.Arguments=" + psq("-jar \"" + Kit.DIR.resolve("launcher.jar") + "\"") + ";"
                + "$s.WorkingDirectory=" + psq(Kit.DIR.toString()) + ";"
                + "$s.IconLocation=" + psq(ICON_FILE + ",0") + ";"
                + "$s.Description='Minecraft Server';$s.Save();Write-Output $d";
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) throw new IOException(out.isEmpty() ? "PowerShell failed" : out);
        return out;
    }

    private static String psq(String s) { return "'" + s.replace("'", "''") + "'"; }
}
