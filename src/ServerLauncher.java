// Minecraft Server app: Paper (Java Edition) + Geyser/Floodgate (Bedrock crossplay) + playit.gg (online play)
// + MCXboxBroadcast (console players).
//
// This folder is the source code of the app, included so you can see exactly what it does.
// You never need to touch it. Downloads come only from official sources:
//   fill.papermc.io (server), download.geysermc.org (Bedrock support), github.com (playit, MCXboxBroadcast,
//   and this app's own updates from github.com/schoolmcst/minecraft-server-app) and api.playit.gg (online play).
//
// How app updates work: launcher.jar is a small starter. Each time it opens it checks the update repo; a newer app
// is downloaded (and checked against its SHA-256) into app/app.jar, and the starter runs whichever is newest.

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.jar.JarFile;
import javax.swing.*;

public class ServerLauncher {

    private static FileLock lock; // held for the life of the app so it can't be opened twice

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        System.setProperty("sun.java2d.d3d", "true");

        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.err.println("The Minecraft Server app needs a screen. Run it on a desktop computer.");
            System.exit(1);
        }

        // an old copy inside a server folder hands over to the installed app, so there's only ever one app
        if (!"1".equals(System.getProperty("kit.inner")) && System.getProperty("kit.dir") == null && handOverToInstalledApp()) return;

        if (!"1".equals(System.getProperty("kit.inner"))) {
            boolean alreadyOpen = !acquireLockWaitingForRestart();
            if (!alreadyOpen && startNewerApp(args)) return;
            run(args, alreadyOpen);
            return;
        }
        run(args, !acquireLock());
    }

    /** Without a console window (javaw), problems go to <app>/app-log.txt so they can be looked at later. */
    private static void startLog() {
        try {
            java.nio.file.Path log = Kit.HOME.resolve("app-log.txt");
            if (java.nio.file.Files.exists(log) && java.nio.file.Files.size(log) > 1_000_000)
                java.nio.file.Files.move(log, Kit.HOME.resolve("app-log.old.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (System.console() == null && !"1".equals(System.getProperty("kit.stderr"))) {
                java.io.PrintStream ps = new java.io.PrintStream(new java.io.FileOutputStream(log.toFile(), true), true, "UTF-8");
                System.setErr(ps);
            }
            System.err.println("---- " + java.time.LocalDateTime.now().withNano(0) + "  app " + Kit.VERSION + "  java " + System.getProperty("java.version"));
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                System.err.println("[" + java.time.LocalTime.now().withNano(0) + "] problem in " + t.getName() + ":");
                e.printStackTrace();
            });
        } catch (Exception ignored) { }
    }

    /** Applies a downloaded update, checks for a new one, and runs app/app.jar if it is newer than this starter. */
    private static boolean startNewerApp(String[] args) {
        Kit.loadSettings();
        Updater.applyPending();
        if (Kit.getBool("app.autoupdate", true) && !Boolean.getBoolean("kit.noupdate")) Updater.checkAtLaunch();

        String installed = Updater.installedVersion();
        if (installed == null || Updater.compare(installed, Kit.VERSION) <= 0) return false;
        try {
            URLClassLoader loader = new URLClassLoader(new URL[]{Updater.APP_JAR.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            System.setProperty("kit.inner", "1");
            Thread.currentThread().setContextClassLoader(loader);
            loader.loadClass("ServerLauncher").getMethod("main", String[].class).invoke(null, (Object) args);
            return true;
        } catch (Throwable t) {
            // a broken download must never stop the server from opening: fall back to this version
            System.clearProperty("kit.inner");
            Kit.set("app.lastError", "Couldn't start app " + installed + ": " + t);
            try { Files.deleteIfExists(Updater.APP_JAR); } catch (IOException ignored) { }
            return false;
        }
    }

    private static void run(String[] args, boolean alreadyOpen) {
        Kit.loadSettings();
        if (!alreadyOpen) startLog();
        SwingUtilities.invokeLater(() -> {
            Ui.install();
            if (alreadyOpen) {
                Dialogs.info(null, "Already open", "The Minecraft Server app is already running. Look for it on your taskbar.");
                System.exit(0);
            }
            App app = new App();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { app.server.killNow(); app.online.stopAgent(); }));
            app.open();
        });
    }

    private static boolean handOverToInstalledApp() {
        try {
            Path inst = Kit.installDir();
            if (inst == null || inst.equals(Kit.HOME) || !Kit.homeIsServer()) return false;
            Path jar = inst.resolve("launcher.jar");
            if (!Files.exists(jar)) return false;
            String java = ProcessHandle.current().info().command().orElse("javaw");
            if (Kit.WINDOWS) java = java.replaceAll("(?i)java\\.exe$", "javaw.exe");
            new ProcessBuilder(java, "-jar", jar.toString()).directory(inst.toFile()).start();
            System.exit(0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** After "Restart app to update", the old copy may need a few seconds to close. */
    private static boolean acquireLockWaitingForRestart() {
        Path marker = Kit.HOME.resolve(".app.restarting");
        boolean restarting = Files.exists(marker);
        for (int i = 0; ; i++) {
            if (acquireLock()) {
                try { Files.deleteIfExists(marker); } catch (IOException ignored) { }
                return true;
            }
            if (!restarting || i >= 60) return false;
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
    }

    private static boolean acquireLock() {
        try {
            Path f = Kit.HOME.resolve(".app.lock");
            FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = ch.tryLock();
            if (lock == null) ch.close();
            return lock != null;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true; // already held by the starter in this same process
        } catch (IOException e) {
            return true; // if locking isn't possible, don't block the app
        }
    }

    /** Reads the app version stored in a jar's manifest. */
    static String jarVersion(Path jar) {
        try (JarFile j = new JarFile(jar.toFile())) {
            return j.getManifest() == null ? null : j.getManifest().getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            return null;
        }
    }
}
