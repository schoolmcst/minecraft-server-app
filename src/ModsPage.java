// The Mods page for modded (Fabric) servers.

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

// ======================================================================== Mods

final class ModsPage {
    final JPanel root = new JPanel(new BorderLayout());
    private final App app;
    private final Ui.Field query = new Ui.Field("Search Modrinth, for example: waystones", 26);
    private final Ui.Btn search = new Ui.Btn("Search", Ui.Kind.PRIMARY);
    private final Ui.Stack results = new Ui.Stack(8);
    private final Ui.Stack installed = new Ui.Stack(8);
    private final JLabel note = Ui.label(" ", Ui.font(13), Ui.MUTED);

    ModsPage(App app) {
        this.app = app;
        root.setBackground(Ui.BG);
        root.setBorder(new EmptyBorder(4, 32, 32, 32));

        query.setMaximumSize(new Dimension(420, 44));
        query.addActionListener(e -> doSearch());
        search.addActionListener(e -> doSearch());
        Ui.Card find = new Ui.Card();
        find.add(new Ui.Stack(12).put(Ui.cardTitle("Add mods"))
                .put(Ui.para("Search Modrinth for Fabric mods that work on servers. Required extra mods are added for you. "
                        + "New mods load the next time the server starts.", Ui.font(13.5f), Ui.MUTED))
                .put(Ui.row(10, query, search, Ui.glue())).put(note).put(results));

        Ui.Card have = new Ui.Card();
        have.add(new Ui.Stack(12).put(Ui.row(10, Ui.cardTitle("Installed mods"), Ui.glue(),
                        new Ui.Btn("Open mods folder", Ui.Kind.GHOST).small().onClick(() -> {
                            try { Files.createDirectories(Kit.mods()); } catch (Exception ignored) { }
                            Kit.open(Kit.mods());
                        })))
                .put(installed));

        root.add(new Ui.Stack(16).put(find).put(have), BorderLayout.NORTH);
    }

    private String mc() {
        String v = Kit.mcVersion();
        return v.isEmpty() ? Kit.get("mc.target", "") : v;
    }

    void refresh() {
        if (!Kit.hasServer()) return;
        installed.removeAll();
        List<Mods.Installed> list = Mods.installed();
        if (list.isEmpty()) installed.put(Ui.para("No mods yet. Fabric API is added automatically on the first start.",
                Ui.font(13.5f), Ui.MUTED));
        boolean off = app.server.state() == Server.State.STOPPED || app.server.state() == Server.State.CRASHED;
        for (Mods.Installed m : list) {
            Ui.Btn rm = new Ui.Btn("Remove", Ui.Kind.SUBTLE).small().onClick(() -> {
                if (!Dialogs.confirm(app.frame, "Remove " + m.name() + "?", "The mod's file is deleted from this server. "
                        + "Things it added to the world may disappear.", "Remove", Ui.Kind.DANGER)) return;
                try { Mods.remove(m); } catch (Exception e) { Dialogs.info(app.frame, "Couldn't remove it", Kit.describe(e)); }
                refresh();
            });
            rm.setEnabled(off);
            if (!off) rm.setToolTipText("Stop the server first");
            List<Component> parts = new ArrayList<>(List.of(Ui.label(m.name(), Ui.bold(14), Ui.TEXT)));
            if (!m.version().isEmpty()) parts.add(Ui.tag(m.version(), Ui.MUTED));
            if (m.file().getFileName().toString().startsWith("Geyser") || m.file().getFileName().toString().startsWith("Floodgate"))
                parts.add(Ui.tag("Bedrock support", Ui.BLUE));
            parts.add(Ui.glue());
            parts.add(rm);
            installed.put(rowCard(Ui.row(8, parts.toArray(new Component[0]))));
        }
        installed.revalidate();
        installed.repaint();
    }

    private void doSearch() {
        String q = query.getText().trim();
        String mc = mc();
        if (mc.isEmpty()) { note.setText("Start the server once first, so the app knows its Minecraft version."); return; }
        note.setText("Searching...");
        note.setForeground(Ui.MUTED);
        search.setEnabled(false);
        new Thread(() -> {
            try {
                List<Mods.Hit> hits = Mods.search(q, mc);
                SwingUtilities.invokeLater(() -> showResults(hits, mc));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> { note.setText("Couldn't search: " + Kit.describe(e)); search.setEnabled(true); });
            }
        }, "mod-search").start();
    }

    private void showResults(List<Mods.Hit> hits, String mc) {
        search.setEnabled(true);
        note.setText(hits.isEmpty() ? "Nothing found for Minecraft " + mc + "." : hits.size() + " mods for Minecraft " + mc + ":");
        results.removeAll();
        Set<String> have = new HashSet<>();
        for (Mods.Installed i : Mods.installed()) have.add(Kit.get("mod." + i.file().getFileName(), ""));
        for (Mods.Hit h : hits) {
            Ui.Btn add = new Ui.Btn("Install", Ui.Kind.SECONDARY).small();
            add.addActionListener(e -> install(h, mc, add));
            JTextArea desc = Ui.para(App.shorten(h.description() == null ? "" : h.description(), 160), Ui.font(12.5f), Ui.MUTED);
            List<Component> top = new ArrayList<>(List.of(Ui.label(h.title(), Ui.bold(14), Ui.TEXT),
                    Ui.label(String.format("%,d downloads", h.downloads()), Ui.font(12), Ui.FAINT)));
            if (h.playersNeedIt()) top.add(Ui.tag("Players need it too", Ui.AMBER));
            top.add(Ui.glue());
            top.add(add);
            results.put(rowCard(new Ui.Stack(4).put(Ui.row(8, top.toArray(new Component[0]))).put(desc)));
        }
        results.revalidate();
        results.repaint();
    }

    private void install(Mods.Hit h, String mc, Ui.Btn button) {
        button.setEnabled(false);
        button.setText("Installing...");
        new Thread(() -> {
            String msg;
            try {
                List<String> added = Mods.install(h.slug(), mc, (t, f) -> { });
                msg = added.isEmpty() ? h.title() + " is already installed."
                        : "Installed " + String.join(", ", added) + "." + (app.server.isRunning() ? " Restart the server to load it." : "");
                if (h.playersNeedIt()) msg += " Java players need " + h.title() + " installed too.";
            } catch (Exception e) {
                msg = "Couldn't install " + h.title() + ": " + Kit.describe(e);
            }
            String m2 = msg;
            SwingUtilities.invokeLater(() -> {
                button.setText(m2.startsWith("Couldn't") ? "Install" : "Installed");
                button.setEnabled(m2.startsWith("Couldn't"));
                note.setText(m2);
                note.setForeground(m2.startsWith("Couldn't") ? Ui.RED_HI : Ui.GREEN_HI);
                app.server.event(m2.startsWith("Couldn't") ? Server.Kind.WARN : Server.Kind.SYSTEM, m2);
                refresh();
            });
        }, "mod-install").start();
    }

    private static JComponent rowCard(JComponent content) {
        Ui.Card c = new Ui.Card();
        c.bg = Ui.CARD_HI;
        c.arc = 12;
        c.setBorder(new EmptyBorder(10, 14, 10, 10));
        c.add(content);
        return c;
    }
}
