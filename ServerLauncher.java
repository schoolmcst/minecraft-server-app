// Minecraft server launcher: Paper (Java Edition) + Geyser/Floodgate (Bedrock crossplay).
//
// This is the source code of launcher.jar, included so you can see exactly what it does.
// The start scripts run the prebuilt launcher.jar; you never need to touch this file.
//
// Each start it:
//   1. downloads the latest stable Paper server + Geyser + Floodgate (first run, or with --update)
//   2. asks you to accept the Minecraft EULA (first run only)
//   3. backs up your worlds (keeps the newest 5)
//   4. starts the server and shows a tidy console: status, joins/leaves, chat and real problems.
//      The full server log is always in logs/latest.log. Start with --full-log to see all of it here.
//
// Downloads come only from the official sources: fill.papermc.io and download.geysermc.org.

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

public class ServerLauncher {

    static final String USER_AGENT =
            "home-minecraft-server-kit/2.0 (personal Paper+Geyser launcher; https://docs.papermc.io/misc/downloads-service/)";
    static final String FILL = System.getProperty("kit.fill", "https://fill.papermc.io/v3/projects/paper");
    static final String GEYSER = System.getProperty("kit.geyser", "https://download.geysermc.org/v2/projects");
    static final Path DIR = Paths.get("").toAbsolutePath();
    static final Path PAPER_JAR = DIR.resolve("paper.jar");
    static final Path VERSION_FILE = DIR.resolve("paper-version.txt");
    static final Path PLUGINS = DIR.resolve("plugins");
    static final int KEEP_BACKUPS = 5;
    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    static PrintStream out = System.out;

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, consoleCharset());

        String ram = "4G";
        boolean update = false, fullLog = false;
        for (String a : args) {
            if (a.equalsIgnoreCase("--update")) update = true;
            else if (a.equalsIgnoreCase("--full-log")) fullLog = true;
            else if (a.matches("(?i)\\d+[mg]")) ram = a.toUpperCase();
        }

        header();

        int javaVersion = Runtime.version().feature();

        // 1. Server software
        try {
            if (!Files.exists(PAPER_JAR) || update) downloadPaper();
            Files.createDirectories(PLUGINS);
            if (!Files.exists(PLUGINS.resolve("Geyser-Spigot.jar")) || update)
                downloadPlugin("geyser", "Geyser-Spigot.jar", "Geyser (Bedrock support)");
            if (!Files.exists(PLUGINS.resolve("floodgate-spigot.jar")) || update)
                downloadPlugin("floodgate", "floodgate-spigot.jar", "Floodgate (no Java account needed)");
        } catch (IOException | InterruptedException e) {
            if (!Files.exists(PAPER_JAR)) {
                downloadFailedHelp(e);
                System.exit(1);
            }
            say("! Couldn't check for updates (" + describe(e) + ") - using what you have.");
        }

        String mcVersion = Files.exists(VERSION_FILE) ? Files.readString(VERSION_FILE).trim() : "";
        int needed = requiredJava(mcVersion);
        if (javaVersion < needed) {
            say("! This Minecraft version needs Java " + needed + " (this launcher found Java " + javaVersion + ").");
            say("  Delete the  runtime  folder and start again to get the right Java.");
            System.exit(1);
        }

        // 2. EULA
        if (!acceptEula()) System.exit(0);

        // 3. Backups
        backupWorlds();

        // 4. Run
        System.exit(runServer(ram, mcVersion, fullLog));
    }

    // ------------------------------------------------------------------ console look

    static Charset consoleCharset() {
        String enc = System.getProperty("stdout.encoding");
        try { return enc != null ? Charset.forName(enc) : Charset.defaultCharset(); }
        catch (Exception e) { return Charset.defaultCharset(); }
    }

    static final String RULE = "  ------------------------------------------------------------";

    static void header() {
        out.println();
        out.println("  ============================================================");
        out.println("     MINECRAFT SERVER                     Java  +  Bedrock");
        out.println("  ============================================================");
        out.println();
    }

    static synchronized void say(String s) {
        clearStatus();
        out.println("  " + s);
    }

    // a single self-updating status line ("Starting server... 12s")
    static String statusLine = null;

    static synchronized void status(String s) {
        clearStatus();
        statusLine = "  " + s;
        out.print(statusLine);
        out.flush();
    }

    static synchronized void clearStatus() {
        if (statusLine == null) return;
        out.print("\r" + " ".repeat(statusLine.length()) + "\r");
        out.flush();
        statusLine = null;
    }

    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    static synchronized void event(String s) {
        clearStatus();
        String t = LocalTime.now().format(CLOCK);
        out.println("  " + " ".repeat(Math.max(0, 8 - t.length())) + t + "   " + s);
    }

    static void printDashboard(String mcVersion, long seconds) {
        List<String> ips = lanAddresses();
        String lan = ips.isEmpty() ? null : ips.get(0);
        synchronized (ServerLauncher.class) {
            clearStatus();
            out.println("  SERVER IS ONLINE" + (seconds > 0 ? "  (started in " + seconds + "s)" : "")
                    + (mcVersion.isEmpty() ? "" : "      Minecraft " + mcVersion));
            out.println();
            out.println(row("  HOW TO JOIN", "Java edition", "Bedrock edition"));
            out.println(row("  On this PC", "localhost", "127.0.0.1  port 19132"));
            if (lan != null) out.println(row("  Same Wi-Fi", lan, lan + "  port 19132"));
            out.println(row("  Anywhere else", "see README (playit.gg, free)", ""));
            out.println();
            out.println("  TYPE HERE (no slash)");
            out.println("    op <name>                 make someone an admin");
            out.println("    whitelist add <name>      let a Java player join");
            out.println("    fwhitelist add <name>     let a Bedrock player join");
            out.println("    list                      who's online");
            out.println("    stop                      save the world and shut down");
            out.println();
            out.println(RULE);
        }
    }

    static String row(String label, String java, String bedrock) {
        return String.format("%-27s%-21s%s", label, java, bedrock);
    }

    // ------------------------------------------------------------------ running the server

    static final Pattern LOG = Pattern.compile("^\\[(\\d{1,2}:\\d{2}:\\d{2})(?: [\\w-]+)? ?(\\w+)\\]: (.*)$");
    static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    static final Pattern DONE = Pattern.compile("^Done \\(([\\d.,]+)s\\)!");
    static final Pattern JOIN = Pattern.compile("^(\\S+) joined the game");
    static final Pattern LEAVE = Pattern.compile("^(\\S+) left the game");
    static final Pattern NOT_WHITELISTED = Pattern.compile("^(?:Disconnecting )?(\\S+?)(?: \\(/[^)]*\\))?(?: \\[[^\\]]*\\])? lost connection: .*(?:not white-?listed|whitelist)", Pattern.CASE_INSENSITIVE);
    static final Pattern NOISE = Pattern.compile(
            "UUID of player|logged in with entity id|lost connection|^Disconnecting|issued server command"
            + "|moved too quickly|moved wrongly|^\\[(?:Geyser-Spigot|floodgate|Geyser)\\] .*(?:connect|logged in|joined|ping|Started Geyser)"
            + "|ThreadedAnvilChunkStorage|^Saving chunks|^Saving players|^Saving worlds|^Flushing|^Closing"
            + "|^Stopping the server|^ThreadedChunkStorage|^Waiting for|^Shutting down|^\\[ChunkHolderManager\\]"
            + "|^\\[ChunkTaskScheduler\\]|^\\[MoonriseCommon\\]|^Flushed|^Saved (?:chunk|entity|POI)|^All dimensions are saved"
            + "|^Stopping server|^Disabling|^Unloading|^\\[spark\\]");

    static volatile boolean ready = false;
    static volatile boolean stopping = false;
    static long lastLagWarning = 0;

    static int runServer(String ram, String mcVersion, boolean fullLog) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.addAll(List.of(
                "-Xms" + ram, "-Xmx" + ram,
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
                "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC",
                "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
                "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
                "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dterminal.jline=false", "-Dterminal.ansi=false",
                "-jar", PAPER_JAR.toString(), "--nogui"));

        Process p = new ProcessBuilder(cmd)
                .directory(DIR.toFile())
                .redirectInput(ProcessBuilder.Redirect.INHERIT)  // you type straight into the server
                .redirectErrorStream(true)
                .start();

        long started = System.currentTimeMillis();
        Deque<String> recent = new ArrayDeque<>(); // hidden lines, shown if startup fails

        Thread spinner = new Thread(() -> {
            while (!ready && !stopping) {
                long s = (System.currentTimeMillis() - started) / 1000;
                status("Starting the server...  " + s + "s   (the first start takes a minute or two)");
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        });
        spinner.setDaemon(true);
        if (!fullLog) spinner.start();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = r.readLine()) != null) {
                String line = ANSI.matcher(raw).replaceAll("");
                if (fullLog) {
                    out.println(line);
                    if (!ready && DONE.matcher(stripLog(line)).find()) ready = true;
                    continue;
                }
                handleLine(line, recent, mcVersion, started);
            }
        }
        int code = p.waitFor();
        ready = true; // stop the spinner
        clearStatus();

        out.println();
        if (code == 0 && (stopping || ready)) {
            say("Server stopped. Your world is saved.");
        } else {
            say("The server stopped unexpectedly. Last messages:");
            out.println();
            for (String l : recent) out.println("    " + l);
            out.println();
            say("Full details are in  logs\\latest.log");
        }
        return code;
    }

    static String stripLog(String line) {
        Matcher m = LOG.matcher(line);
        return m.find() ? m.group(3) : line;
    }

    static void handleLine(String line, Deque<String> recent, String mcVersion, long started) {
        if (line.isBlank()) return;
        recent.addLast(line);
        if (recent.size() > 25) recent.removeFirst();

        Matcher m = LOG.matcher(line);
        String level = m.find() ? m.group(2).toUpperCase(Locale.ROOT) : "";
        String msg = m.matches() ? m.group(3).trim() : line.trim();
        boolean structured = !level.isEmpty();

        // problems we can explain in plain words
        if (msg.contains("FAILED TO BIND TO PORT") || msg.contains("Address already in use")) {
            say("! The server's port is already in use. Is another server window already open?");
            return;
        }
        if (msg.contains("Can't keep up!")) {
            long now = System.currentTimeMillis();
            if (now - lastLagWarning > 5 * 60_000) {
                lastLagWarning = now;
                event("! The server is lagging (your PC is struggling to keep up)");
            }
            return;
        }

        if (!ready) {
            Matcher d = DONE.matcher(msg);
            if (d.find()) {
                ready = true;
                long secs = (System.currentTimeMillis() - started) / 1000;
                printDashboard(mcVersion, secs);
                event("Waiting for players...");
                return;
            }
            if (level.equals("ERROR") && !msg.startsWith("[")) say("! " + msg);
            return; // hide the rest of the startup chatter
        }

        if (!structured) return; // stack traces etc. live in logs/latest.log

        if (msg.startsWith("Stopping server") || msg.startsWith("Stopping the server")) {
            if (!stopping) { stopping = true; event("Saving the world and shutting down..."); }
            return;
        }

        Matcher j = JOIN.matcher(msg);
        if (j.find()) { event("+ " + j.group(1) + " joined"); return; }
        Matcher l = LEAVE.matcher(msg);
        if (l.find()) { event("- " + l.group(1) + " left"); return; }
        Matcher w = NOT_WHITELISTED.matcher(msg);
        if (w.find()) {
            String name = w.group(1);
            if (name.startsWith(".")) {
                event("x " + name.substring(1) + " (Bedrock) tried to join but isn't on the whitelist."
                        + "  Type:  fwhitelist add " + name.substring(1));
            } else {
                event("x " + name + " tried to join but isn't on the whitelist.  Type:  whitelist add " + name);
            }
            return;
        }

        if (NOISE.matcher(msg).find()) return;

        if (msg.startsWith("[Not Secure] ")) msg = msg.substring("[Not Secure] ".length());

        switch (level) {
            case "ERROR", "FATAL" -> event("! " + msg);
            case "WARN" -> { if (!msg.startsWith("[")) event("! " + msg); }
            default -> event(msg);
        }
    }

    static List<String> lanAddresses() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = (ni.getDisplayName() + " " + ni.getName()).toLowerCase(Locale.ROOT);
                if (name.matches(".*(virtual|vmware|vbox|hyper-v|docker|wsl|tailscale|zerotier|vpn|utun|tun|radmin|hamachi).*")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.add(a.getHostAddress());
            }
        } catch (SocketException ignored) { }
        return ips;
    }

    // ------------------------------------------------------------------ downloads

    static void downloadPaper() throws IOException, InterruptedException {
        status("Finding the latest Minecraft server...");
        Object project = Json.parse(getString(FILL));
        List<String> versions = new ArrayList<>();
        Object groups = Json.get(project, "versions");
        if (groups instanceof Map<?, ?> m) {
            for (Object list : m.values())
                if (list instanceof List<?> l) for (Object v : l) versions.add(String.valueOf(v));
        }
        if (versions.isEmpty()) throw new IOException("Paper's download service returned no versions");

        int tried = 0;
        for (String v : versions) {
            if (++tried > 8) break;
            Map<?, ?> build = latestStable(Json.parse(getString(FILL + "/versions/" + v + "/builds")));
            if (build == null) continue;
            Object dl = Json.get(build, "downloads", "server:default");
            String url = (String) Json.get(dl, "url");
            String sha = (String) Json.get(dl, "checksums", "sha256");
            status("Downloading the Minecraft " + v + " server...");
            Path tmp = download(url, PAPER_JAR.resolveSibling("paper.jar.part"));
            if (sha != null && !sha.equalsIgnoreCase(sha256(tmp))) {
                Files.deleteIfExists(tmp);
                throw new IOException("the download was corrupted (checksum mismatch) - try again");
            }
            Files.move(tmp, PAPER_JAR, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(VERSION_FILE, v + System.lineSeparator());
            say("Got the Minecraft " + v + " server (Paper build " + Json.get(build, "id") + ")");
            return;
        }
        throw new IOException("no stable Paper build found");
    }

    /** Newest build whose channel is STABLE, or null. */
    static Map<?, ?> latestStable(Object builds) {
        Map<?, ?> best = null;
        long bestId = Long.MIN_VALUE;
        if (!(builds instanceof List<?> list)) return null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> b)) continue;
            if (!"STABLE".equals(b.get("channel"))) continue;
            Object id = b.get("id");
            long n = id instanceof Number num ? num.longValue() : 0;
            if (best == null || n > bestId) { best = b; bestId = n; }
        }
        return best;
    }

    static void downloadPlugin(String project, String fileName, String label) throws IOException, InterruptedException {
        status("Downloading " + label + "...");
        Path target = PLUGINS.resolve(fileName);
        Path tmp = download(GEYSER + "/" + project + "/versions/latest/builds/latest/downloads/spigot",
                target.resolveSibling(fileName + ".part"));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        say("Got " + label);
    }

    static String getString(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + host(url));
        return r.body();
    }

    static Path download(String url, Path to) throws IOException, InterruptedException {
        HttpResponse<Path> r = HTTP.send(request(url), HttpResponse.BodyHandlers.ofFile(to));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(to);
            throw new IOException("HTTP " + r.statusCode() + " from " + host(url));
        }
        if (Files.size(to) < 1024) {
            Files.deleteIfExists(to);
            throw new IOException("download from " + host(url) + " was empty");
        }
        return to;
    }

    static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    static String host(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return url; }
    }

    static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static String describe(Exception e) {
        if (e instanceof ConnectException || e instanceof java.nio.channels.UnresolvedAddressException
                || e.getCause() instanceof ConnectException) return "couldn't connect - are you online?";
        if (e instanceof HttpTimeoutException) return "the download timed out";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    static void downloadFailedHelp(Exception e) {
        out.println();
        say("! Couldn't download the server: " + describe(e));
        say("  Check your internet connection and start again.");
        say("  If it keeps failing, download it by hand:");
        say("    1. Get the latest Paper jar from  https://papermc.io/downloads/paper");
        say("    2. Put it in this folder and rename it to  paper.jar");
        say("    3. Get Geyser and Floodgate (Spigot/Paper versions) from https://geysermc.org/download");
        say("       and put them in the  plugins  folder.");
    }

    // ------------------------------------------------------------------ java version

    static int requiredJava(String mcVersion) {
        try {
            int major = Integer.parseInt(mcVersion.split("[.\\-]")[0]);
            return major >= 26 ? 25 : 21; // 26.x needs Java 25; 1.20.5-1.21.x need Java 21
        } catch (Exception e) {
            return 21;
        }
    }

    // ------------------------------------------------------------------ EULA

    static boolean acceptEula() throws IOException {
        Path eula = DIR.resolve("eula.txt");
        if (Files.exists(eula) && Files.readString(eula).contains("eula=true")) return true;
        clearStatus();
        out.println();
        say("ONE LAST THING (first start only)");
        say("Minecraft servers require accepting Mojang's EULA:  https://aka.ms/MinecraftEULA");
        out.print("  Type  yes  and press Enter to accept: ");
        out.flush();
        String answer = readLine();
        if (answer == null || !answer.trim().toLowerCase(Locale.ROOT).startsWith("y")) {
            say("No problem - the server can't run without it. Start again whenever you're ready.");
            return false;
        }
        Files.writeString(eula, "# Accepted via the start script on " + LocalDate.now()
                + " (https://aka.ms/MinecraftEULA)" + System.lineSeparator() + "eula=true" + System.lineSeparator());
        out.println();
        return true;
    }

    /** Reads one line from the keyboard without buffering ahead, so later input reaches the server console. */
    static String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        InputStream raw = new FileInputStream(FileDescriptor.in); // unbuffered: System.in would read ahead
        while ((c = raw.read()) != -1 && c != '\n') b.write(c);
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.UTF_8).replace("\r", "");
    }

    // ------------------------------------------------------------------ backups

    static void backupWorlds() {
        List<Path> worlds;
        try (Stream<Path> s = Files.list(DIR)) {
            worlds = s.filter(Files::isDirectory)
                      .filter(d -> Files.exists(d.resolve("level.dat")))
                      .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            return;
        }
        if (worlds.isEmpty()) return; // first run, nothing to back up yet

        Path backups = DIR.resolve("backups");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path zip = backups.resolve("worlds-" + stamp + ".zip");
        status("Backing up your world...");
        try {
            Files.createDirectories(backups);
            try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
                for (Path w : worlds) {
                    try (Stream<Path> files = Files.walk(w)) {
                        for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                            if (f.getFileName().toString().equals("session.lock")) continue;
                            zout.putNextEntry(new ZipEntry(DIR.relativize(f).toString().replace('\\', '/')));
                            Files.copy(f, zout);
                            zout.closeEntry();
                        }
                    }
                }
            }
            pruneBackups(backups);
            say("World backed up  (backups\\" + zip.getFileName() + ")");
        } catch (IOException e) {
            say("! Backup skipped (" + e.getMessage() + ")");
            try { Files.deleteIfExists(zip); } catch (IOException ignored) { }
        }
    }

    static void pruneBackups(Path backups) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.list(backups)) {
            all = s.filter(p -> p.getFileName().toString().matches("worlds-.*\\.zip"))
                   .sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (int i = KEEP_BACKUPS; i < all.size(); i++) Files.deleteIfExists(all.get(i));
    }

    // ------------------------------------------------------------------ tiny JSON reader

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) throws IOException {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IOException("unexpected data from the download service");
            return v;
        }

        /** Walks nested objects by key; returns null if any step is missing. */
        static Object get(Object node, String... path) {
            Object cur = node;
            for (String k : path) {
                if (!(cur instanceof Map<?, ?> m)) return null;
                cur = m.get(k);
            }
            return cur;
        }

        private Object value() throws IOException {
            if (i >= s.length()) throw err();
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private Map<String, Object> object() throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') throw err();
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err();
            }
        }

        private List<Object> array() throws IOException {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err();
            }
        }

        private String string() throws IOException {
            if (s.charAt(i) != '"') throw err();
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private Object number() throws IOException {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw err();
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }

        private void expect(String word) throws IOException {
            if (!s.startsWith(word, i)) throw err();
            i += word.length();
        }

        private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private IOException err() { return new IOException("couldn't read the download service's reply"); }
    }
}
