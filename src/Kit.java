// Shared basics: folders, settings, HTTP, JSON, opening links/folders.

import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

final class Kit {
    private Kit() { }

    static final String APP_NAME = "Minecraft Server";
    static final String VERSION = "4.9";
    static final String USER_AGENT =
            "home-minecraft-server-kit/" + VERSION + " (personal Paper+Geyser launcher; https://docs.papermc.io/misc/downloads-service/)";

    // ------------------------------------------------------------------ folders
    //
    // HOME is where the app itself lives (launcher.jar, app/, online/, servers.json). Each server has its own folder;
    // dir() is the server the app is looking at right now. Older installs kept the app inside the server folder,
    // so HOME can also be a server folder.

    static final Path HOME = findHome();
    private static volatile Path current = initialServer();

    private static Path findHome() {
        String h = System.getProperty("kit.home");
        if (h != null) return Paths.get(h).toAbsolutePath();
        String d = System.getProperty("kit.dir");
        if (d != null) return Paths.get(d).toAbsolutePath();
        try {
            Path jar = Paths.get(Kit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            if (dir.getFileName() != null && dir.getFileName().toString().equals("app") && Files.exists(dir.getParent().resolve("launcher.jar")))
                dir = dir.getParent();
            return dir;
        } catch (Exception e) {
            return Paths.get("").toAbsolutePath();
        }
    }

    private static Path initialServer() {
        String d = System.getProperty("kit.dir");
        return d != null ? Paths.get(d).toAbsolutePath() : null;
    }

    /** Where the installed app lives on Windows (%LOCALAPPDATA%\Programs\Minecraft Server). */
    static Path installDir() {
        String la = System.getenv("LOCALAPPDATA");
        return la == null ? null : Paths.get(la, "Programs", "Minecraft Server");
    }

    /** True when the app's own folder is also a server (installs from before the Servers page). */
    static boolean homeIsServer() { return looksLikeServer(HOME); }

    static boolean looksLikeServer(Path p) {
        return p != null && (Files.exists(p.resolve("server.properties")) || Files.exists(p.resolve("paper.jar"))
                || Files.exists(p.resolve("fabric-server.jar")) || Files.exists(p.resolve("eula.txt")));
    }

    /** The server folder the app is working on (null when there are no servers yet). */
    static Path dir() { return current; }

    static boolean hasServer() { return current != null; }

    static String type() { return get("server.type", Files.exists(dir().resolve("fabric-server.jar")) ? "fabric" : "paper"); }

    static boolean isFabric() { return hasServer() && "fabric".equals(type()); }

    static Path serverJar() { return dir().resolve(isFabric() ? "fabric-server.jar" : "paper.jar"); }

    static Path versionFile() { return dir().resolve("paper-version.txt"); }

    static Path plugins() { return dir().resolve("plugins"); }

    static Path mods() { return dir().resolve("mods"); }

    static Path backups() { return dir().resolve("backups"); }

    static final Path ONLINE = HOME.resolve("online");

    static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    static final boolean WINDOWS = OS.contains("win");
    static final boolean MAC = OS.contains("mac") || OS.contains("darwin");

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    // ------------------------------------------------------------------ settings
    //
    // App-wide settings (online play, updates, the server list) live in HOME/app-settings.properties.
    // Everything else belongs to one server and lives in <server>/launcher-settings.properties.

    private static final Properties APP = new Properties();
    private static final Properties SERVER = new Properties();

    static Path appSettingsFile() { return HOME.resolve(homeIsServer() ? "launcher-settings.properties" : "app-settings.properties"); }

    static Path serverSettingsFile() { return dir() == null ? null : dir().resolve("launcher-settings.properties"); }

    private static boolean appKey(String key) {
        return key.startsWith("app.") || key.startsWith("online.") || key.startsWith("servers.") || key.startsWith("window.");
    }

    private static boolean shared() { return dir() != null && appSettingsFile().equals(serverSettingsFile()); }

    static synchronized void loadSettings() {
        load(APP, appSettingsFile());
        if (current == null) current = Servers.initial();
        loadServerSettings();
        if (current != null) Servers.adoptOnlineSetup(current);
    }

    private static void loadServerSettings() {
        SERVER.clear();
        Path f = serverSettingsFile();
        if (f == null) return;
        if (shared()) { SERVER.putAll(APP); return; }
        load(SERVER, f);
        // settings older versions kept with the server that are really app-wide (online play) move to the app
        boolean moved = false;
        for (String k : SERVER.stringPropertyNames()) {
            if (appKey(k) && !APP.containsKey(k) && !k.startsWith("app.")) { APP.setProperty(k, SERVER.getProperty(k)); moved = true; }
        }
        if (moved) store(APP, appSettingsFile());
    }

    private static void load(Properties p, Path f) {
        p.clear();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) { }
    }

    private static void store(Properties p, Path f) {
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                p.store(w, "Minecraft Server app settings");
            }
        } catch (IOException ignored) { }
    }

    /** Points the app at another server folder. */
    static synchronized void switchServer(Path dir) {
        current = dir == null ? null : dir.toAbsolutePath();
        loadServerSettings();
        if (current != null) setApp("app.current", current.toString());
    }

    static synchronized String get(String key, String def) {
        return (appKey(key) ? APP : SERVER).getProperty(key, def);
    }

    static synchronized boolean getBool(String key, boolean def) {
        String v = get(key, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    static synchronized void set(String key, Object value) {
        if (appKey(key) || shared()) {
            setIn(APP, key, value);
            store(APP, appSettingsFile());
            if (shared()) setIn(SERVER, key, value);
        } else {
            if (dir() == null) return;
            setIn(SERVER, key, value);
            store(SERVER, serverSettingsFile());
        }
    }

    private static void setApp(String key, Object value) {
        setIn(APP, key, value);
        store(APP, appSettingsFile());
    }

    private static void setIn(Properties p, String key, Object value) {
        if (value == null) p.remove(key); else p.setProperty(key, String.valueOf(value));
    }

    static long totalRamMb() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return os.getTotalMemorySize() / (1024 * 1024);
        } catch (Throwable t) {
            return 8192;
        }
    }

    static String defaultRam() {
        long mb = totalRamMb();
        if (mb <= 6 * 1024) return "2G";
        if (mb <= 9 * 1024) return "3G";
        return "4G";
    }

    static String ram() { return get("ram", defaultRam()); }

    static String mcVersion() {
        if (!hasServer()) return "";
        try { return Files.exists(versionFile()) ? Files.readString(versionFile()).trim() : ""; }
        catch (IOException e) { return ""; }
    }

    static String serverName() {
        if (!hasServer()) return "No server";
        return get("server.name", dir().getFileName() == null ? "Server" : dir().getFileName().toString());
    }

    // ------------------------------------------------------------------ HTTP

    static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5));
    }

    static String getString(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(request(url).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + host(url));
        return r.body();
    }

    /** GET with extra headers (name, value, name, value...). */
    static String getString(String url, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = request(url).timeout(Duration.ofSeconds(30));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new IOException("the site said the key isn't valid (HTTP " + r.statusCode() + ")");
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + host(url));
        return r.body();
    }

    static byte[] getBytes(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = HTTP.send(request(url).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + host(url));
        return r.body();
    }

    interface Progress { void update(String text, double fraction); }

    /** Downloads to a temp file next to {@code to}, reporting progress, and returns the temp file. */
    static Path download(String url, Path to, String label, Progress p) throws IOException, InterruptedException {
        Path tmp = to.resolveSibling(to.getFileName() + ".part");
        HttpResponse<InputStream> r = HTTP.send(request(url).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("HTTP " + r.statusCode() + " from " + host(url));
        }
        long total = r.headers().firstValueAsLong("content-length").orElse(-1);
        long done = 0, last = 0;
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (p != null && now - last > 120) {
                    last = now;
                    if (total > 0) p.update(label + "  " + (done * 100 / total) + "%", done / (double) total);
                    else p.update(label + "  " + (done / (1024 * 1024)) + " MB", -1);
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        if (Files.size(tmp) < 1024) {
            Files.deleteIfExists(tmp);
            throw new IOException("the download from " + host(url) + " was empty");
        }
        return tmp;
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

    static String describe(Throwable e) {
        if (e instanceof java.net.ConnectException || e instanceof java.nio.channels.UnresolvedAddressException
                || e.getCause() instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException)
            return "Couldn't connect. Check that this PC is online.";
        if (e instanceof HttpTimeoutException) return "The download timed out. Try again.";
        String m = e.getMessage();
        return m != null && !m.isBlank() ? m : e.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ open things

    static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) { }
        try {
            if (WINDOWS) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (MAC) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (IOException ignored) { }
    }

    static void open(Path p) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(p.toFile());
                return;
            }
        } catch (Exception ignored) { }
        try {
            if (WINDOWS) new ProcessBuilder("explorer", p.toString()).start();
            else if (MAC) new ProcessBuilder("open", p.toString()).start();
            else new ProcessBuilder("xdg-open", p.toString()).start();
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ tiny JSON reader / writer

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) throws IOException {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IOException("unexpected data in a reply from the internet");
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

        static String str(Object node, String... path) {
            Object v = get(node, path);
            return v == null ? null : String.valueOf(v);
        }

        static List<?> list(Object node, String... path) {
            Object v = get(node, path);
            return v instanceof List<?> l ? l : List.of();
        }

        /** JSON string literal. */
        static String q(String v) {
            if (v == null) return "null";
            StringBuilder b = new StringBuilder("\"");
            for (char c : v.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            return b.append('"').toString();
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

        private IOException err() { return new IOException("couldn't read a reply from the internet"); }
    }
}
