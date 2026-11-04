// The Servers page: list, switch, add existing folders.

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class ServersPage {
    final JPanel root = new JPanel(new BorderLayout());
    private final App app;
    private final Ui.Stack list = new Ui.Stack(10);

    ServersPage(App app) {
        this.app = app;
        root.setBackground(Ui.BG);
        root.setBorder(new EmptyBorder(4, 32, 32, 32));

        Ui.Card intro = new Ui.Card();
        Ui.Stack s = new Ui.Stack(12);
        s.put(Ui.row(10, Ui.cardTitle("Your servers"), Ui.glue(),
                new Ui.Btn("Add existing folder", Ui.Kind.SECONDARY).small().onClick(this::addExisting),
                new Ui.Btn("New server", Ui.Kind.PRIMARY).small().onClick(() -> NewServerDialog.open(app))));
        s.put(Ui.para("One server runs at a time. They all share your online addresses (Play online) and your Xbox setup, "
                + "so friends join the same way whichever server is on.", Ui.font(13.5f), Ui.MUTED));
        s.put(list);
        intro.add(s);
        root.add(new Ui.Stack(16).put(intro), BorderLayout.NORTH);
        refresh();
    }

    void refresh() {
        list.removeAll();
        List<Servers.Entry> entries = Servers.list();
        if (entries.isEmpty()) {
            Ui.Card c = new Ui.Card();
            c.bg = Ui.GREEN_DIM;
            c.add(new Ui.Stack(10).put(Ui.label("Make your first server", Ui.bold(17), Ui.TEXT))
                    .put(Ui.para("Pick survival (works with Java, Bedrock and Xbox) or modded, and the app sets the rest up.",
                            Ui.font(14), Ui.TEXT))
                    .put(Ui.row(0, new Ui.Btn("New server", Ui.Kind.PRIMARY).onClick(() -> NewServerDialog.open(app)), Ui.glue())));
            list.put(c);
        }
        for (Servers.Entry e : entries) list.put(row(e));
        list.revalidate();
        list.repaint();
    }

    private JComponent row(Servers.Entry e) {
        boolean isCurrent = e.path().equals(Kit.dir());
        boolean running = isCurrent && app.server.state() != Server.State.STOPPED && app.server.state() != Server.State.CRASHED;
        Properties st = settingsOf(e.path());
        boolean fabric = "fabric".equals(st.getProperty("server.type")) || Files.exists(e.path().resolve("fabric-server.jar"));
        String version = readVersion(e.path(), st);

        JLabel name = Ui.label(e.name(), Ui.bold(16), Ui.TEXT);
        name.setIcon(Ui.dot(running ? Ui.GREEN_HI : isCurrent ? Ui.BLUE : new Color(0x3A414B), 10));
        name.setIconTextGap(12);
        List<Component> top = new ArrayList<>(List.of(name,
                Ui.tag(fabric ? "Modded" : "Survival", fabric ? new Color(0xC9A0FF) : Ui.GREEN_HI)));
        if (!version.isEmpty()) top.add(Ui.tag("Minecraft " + version, Ui.MUTED));
        if (!"false".equals(st.getProperty("server.crossplay"))) top.add(Ui.tag("Java + Bedrock", Ui.BLUE));
        if (running) top.add(Ui.tag("Running", Ui.GREEN_HI));
        else if (isCurrent) top.add(Ui.tag("Open now", Ui.BLUE));
        if (!e.exists()) top.add(Ui.tag("Folder missing", Ui.RED_HI));
        top.add(Ui.glue());
        List<Component> bottom = new ArrayList<>();

        boolean busy = app.server.state() != Server.State.STOPPED && app.server.state() != Server.State.CRASHED;
        Ui.Btn open = new Ui.Btn(isCurrent ? "Open" : "Switch", isCurrent ? Ui.Kind.SECONDARY : Ui.Kind.PRIMARY)
                .small().onClick(() -> { if (isCurrent) app.show("home"); else app.switchTo(e.path()); });
        open.setEnabled(e.exists() && (isCurrent || !busy));
        if (!isCurrent && busy) open.setToolTipText("Stop the running server first");
        Ui.Btn folder = new Ui.Btn("Folder", Ui.Kind.SECONDARY).small().onClick(() -> Kit.open(e.path()));
        folder.setEnabled(e.exists());
        Ui.Btn rename = new Ui.Btn("Rename", Ui.Kind.SUBTLE).small().onClick(() -> {
            String n = JOptionPane.showInputDialog(app.frame, "New name for this server:", e.name());
            if (n != null && !n.isBlank()) {
                Servers.rename(e.path(), n.trim());
                if (isCurrent) app.refreshAll();
                refresh();
            }
        });
        Ui.Btn remove = new Ui.Btn("Remove", Ui.Kind.SUBTLE).small().onClick(() -> {
            if (!Dialogs.confirm(app.frame, "Remove " + e.name() + " from the list?",
                    "Nothing is deleted: the server's folder and world stay where they are, and you can add it back with "
                            + "\"Add existing folder\".", "Remove", Ui.Kind.DANGER)) return;
            Servers.remove(e.path());
            if (isCurrent) app.switchTo(Servers.initial());
            refresh();
        });
        remove.setEnabled(!running);
        JLabel path = Ui.label(App.shorten(e.path().toString(), 52), Ui.font(12), Ui.FAINT);
        path.setToolTipText(e.path().toString());
        bottom.addAll(List.of(path, Ui.glue(), rename, remove, folder, open));

        Ui.Card c = new Ui.Card();
        c.bg = isCurrent ? Ui.CARD_HI : Ui.CARD;
        c.border = isCurrent ? new Color(0x34404E) : null;
        c.arc = 14;
        c.setBorder(new EmptyBorder(14, 16, 14, 12));
        c.add(new Ui.Stack(8).put(Ui.row(8, top.toArray(new Component[0])))
                .put(Ui.row(8, bottom.toArray(new Component[0]))));
        return c;
    }

    private void addExisting() {
        JFileChooser fc = new JFileChooser(Servers.defaultParent().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Choose a server folder");
        if (fc.showOpenDialog(app.frame) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        if (!Kit.looksLikeServer(p)) {
            Dialogs.info(app.frame, "That doesn't look like a server", "Pick the folder that has server.properties in it "
                    + "(the one with the world folder).");
            return;
        }
        Servers.add(p, Servers.defaultName(p));
        refresh();
        app.switchTo(p);
    }

    static Properties settingsOf(Path dir) {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(dir.resolve("launcher-settings.properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Exception ignored) { }
        return p;
    }

    static String readVersion(Path dir, Properties st) {
        try {
            Path f = dir.resolve("paper-version.txt");
            if (Files.exists(f)) return Files.readString(f).trim();
        } catch (Exception ignored) { }
        return st.getProperty("mc.target", "");
    }
}

