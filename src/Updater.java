// The app's own updates, published to GitHub like DarkZone's patches:
//   https://raw.githubusercontent.com/schoolmcst/minecraft-server-app/main/release/version.json
//   {"version": "3.2", "file": "app.jar", "sha256": "...", "notes": ["..."], "minJava": 21}

import java.awt.*;
import java.io.IOException;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class Updater {
    private Updater() { }

    static final String BASE = System.getProperty("kit.appupdate",
            "https://raw.githubusercontent.com/schoolmcst/minecraft-server-app/main/release/");
    static final Path APP_DIR = Kit.HOME.resolve("app");
    static final Path APP_JAR = APP_DIR.resolve("app.jar");
    static final Path PENDING = APP_DIR.resolve("app-pending.jar");

    record Release(String version, String file, String sha256, List<String> notes, int minJava) { }

    /** Newest app version available (app/app.jar or this starter). */
    static String installedVersion() {
        return Files.exists(APP_JAR) ? ServerLauncher.jarVersion(APP_JAR) : null;
    }

    static String runningVersion() { return Kit.VERSION; }

    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Moves a downloaded update into place. Only safe before app.jar is loaded. */
    static void applyPending() {
        if (!Files.exists(PENDING)) return;
        try {
            Files.move(PENDING, APP_JAR, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(PENDING); } catch (IOException ignored) { }
        }
    }

    static Release fetch(Duration timeout) throws IOException, InterruptedException {
        HttpRequest req = Kit.request(BASE + "version.json?t=" + System.currentTimeMillis()).timeout(timeout).build();
        HttpResponse<String> r = Kit.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("update server replied " + r.statusCode());
        Object j = Kit.Json.parse(r.body());
        String v = Kit.Json.str(j, "version");
        if (v == null) throw new IOException("update info had no version");
        List<String> notes = new ArrayList<>();
        for (Object n : Kit.Json.list(j, "notes")) notes.add(String.valueOf(n));
        Object mj = Kit.Json.get(j, "minJava");
        String file = Kit.Json.str(j, "file");
        return new Release(v, file == null ? "app.jar" : file, Kit.Json.str(j, "sha256"), notes,
                mj instanceof Number n ? n.intValue() : 21);
    }

    /** True if r is newer than everything on disk and can run here. */
    static boolean wanted(Release r) {
        String have = installedVersion();
        String newest = have != null && compare(have, Kit.VERSION) > 0 ? have : Kit.VERSION;
        if (compare(r.version(), newest) <= 0) return false;
        if (Files.exists(PENDING)) {
            String p = ServerLauncher.jarVersion(PENDING);
            if (p != null && compare(p, r.version()) >= 0) return false;
        }
        return Runtime.version().feature() >= r.minJava();
    }

    /** Downloads r to {@code to}, verifying its checksum and that it is a runnable app jar. */
    static void download(Release r, Path to) throws IOException, InterruptedException {
        Files.createDirectories(APP_DIR);
        Path tmp = Kit.download(BASE + r.file(), to, "Downloading app " + r.version(), null);
        try {
            if (r.sha256() != null && !r.sha256().equalsIgnoreCase(Kit.sha256(tmp)))
                throw new IOException("the update was damaged on the way");
            String v = ServerLauncher.jarVersion(tmp);
            if (v == null || compare(v, r.version()) != 0) throw new IOException("the update file didn't match its version");
            Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        Kit.set("app.justUpdated", r.version());
        Kit.set("app.notes", String.join("\n", r.notes()));
    }

    /** At launch (nothing loaded yet): download straight into app/app.jar. Quiet and quick when offline. */
    static void checkAtLaunch() {
        JWindow splash = splash();
        try {
            Release r = fetch(Duration.ofSeconds(5));
            Kit.set("app.lastCheck", System.currentTimeMillis());
            if (wanted(r)) {
                setSplash(splash, "Updating the app to " + r.version() + "...");
                download(r, APP_JAR);
            }
        } catch (Exception e) {
            // offline, or the update repo isn't there yet: just open the version we have
        } finally {
            if (splash != null) splash.dispose();
        }
    }

    /** While the app runs: download in the background; it's installed the next time the app opens. */
    static Release checkInBackground() throws IOException, InterruptedException {
        Release r = fetch(Duration.ofSeconds(15));
        Kit.set("app.lastCheck", System.currentTimeMillis());
        if (!wanted(r)) return null;
        download(r, PENDING);
        return r;
    }

    /** Restarts the app (used after an update downloads, when the server is off). */
    static void restartApp() {
        try {
            String java = ProcessHandle.current().info().command().orElse("java");
            if (Kit.WINDOWS) java = java.replaceAll("(?i)java\\.exe$", "javaw.exe");
            new ProcessBuilder(java, "-jar", Kit.HOME.resolve("launcher.jar").toString())
                    .directory(Kit.HOME.toFile()).start();
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ tiny splash

    private static JLabel splashText;

    private static JWindow splash() {
        try {
            JWindow w = new JWindow();
            JPanel p = new JPanel(new BorderLayout(14, 0));
            p.setBackground(new Color(0x181B20));
            p.setBorder(new EmptyBorder(18, 22, 18, 26));
            p.add(new JLabel(new ImageIcon(Ui.appIcon(40))), BorderLayout.WEST);
            JPanel t = new JPanel(new GridLayout(2, 1));
            t.setOpaque(false);
            JLabel title = new JLabel("Minecraft Server");
            title.setForeground(new Color(0xECEFF3));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
            splashText = new JLabel("Checking for updates...");
            splashText.setForeground(new Color(0x9BA3AE));
            splashText.setFont(splashText.getFont().deriveFont(Font.PLAIN, 12.5f));
            t.add(title);
            t.add(splashText);
            p.add(t, BorderLayout.CENTER);
            w.setContentPane(p);
            w.setSize(330, 82);
            w.setLocationRelativeTo(null);
            w.setAlwaysOnTop(true);
            w.setVisible(true);
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setSplash(JWindow w, String text) {
        if (w == null || splashText == null) return;
        SwingUtilities.invokeLater(() -> splashText.setText(text));
    }
}
