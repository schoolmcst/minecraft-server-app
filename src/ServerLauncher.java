// Minecraft Server app: Paper (Java Edition) + Geyser/Floodgate (Bedrock crossplay) + playit.gg (online play).
//
// This folder is the source code of launcher.jar, included so you can see exactly what it does.
// You never need to touch it. Downloads come only from the official sources:
//   fill.papermc.io (server), download.geysermc.org (Bedrock support),
//   github.com/playit-cloud (playit program) and api.playit.gg (online play).

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
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

        Kit.loadSettings();
        boolean alreadyOpen = !acquireLock();

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

    private static boolean acquireLock() {
        try {
            Path f = Kit.DIR.resolve(".app.lock");
            FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = ch.tryLock();
            return lock != null;
        } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
            return true; // if locking isn't possible, don't block the app
        }
    }
}
