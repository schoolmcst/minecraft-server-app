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
    static final String VERSION = "3.2";
    static final String USER_AGENT =
            "home-minecraft-server-kit/" + VERSION + " (personal Paper+Geyser launcher; https://docs.papermc.io/misc/downloads-service/)";

    static final Path DIR = Paths.get(System.getProperty("kit.dir", "")).toAbsolutePath();
    static final Path PAPER_JAR = DIR.resolve("paper.jar");
    static final Path VERSION_FILE = DIR.resolve("paper-version.txt");
    static final Path PLUGINS = DIR.resolve("plugins");
    static final Path BACKUPS = DIR.resolve("backups");
    static final Path ONLINE = DIR.resolve("online");

    static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    static final boolean WINDOWS = OS.contains("win");
    static final boolean MAC = OS.contains("mac") || OS.contains("darwin");

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    // ------------------------------------------------------------------ settings (launcher-settings.properties)

    static final Path SETTINGS_FILE = DIR.resolve("launcher-settings.properties");
    private static final Properties SETTINGS = new Properties();

    static synchronized void loadSettings() {
        try (Reader r = Files.newBufferedReader(SETTINGS_FILE, StandardCharsets.UTF_8)) {
            SETTINGS.load(r);
        } catch (IOException ignored) { }
    }

    static synchronized String get(String key, String def) { return SETTINGS.getProperty(key, def); }

    static synchronized boolean getBool(String key, boolean def) {
        String v = SETTINGS.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    static synchronized void set(String key, Object value) {
        if (value == null) SETTINGS.remove(key); else SETTINGS.setProperty(key, String.valueOf(value));
        try (Writer w = Files.newBufferedWriter(SETTINGS_FILE, StandardCharsets.UTF_8)) {
            SETTINGS.store(w, "Minecraft Server app settings");
        } catch (IOException ignored) { }
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
        try { return Files.exists(VERSION_FILE) ? Files.readString(VERSION_FILE).trim() : ""; }
        catch (IOException e) { return ""; }
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
