// The World page: time and weather buttons, presets (one-click setups that switch back later), graves waiting to be
// opened (with a "Give back" button), and the death log.

import java.awt.*;
import java.awt.geom.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class WorldPage {
    final JPanel root = new JPanel(new BorderLayout());
    private final App app;
    private final JLabel worldNow = Ui.label(" ", Ui.font(13), Ui.MUTED);
    private final List<Ui.Btn> needsServer = new ArrayList<>();
    private final Ui.Stack presetList = new Ui.Stack(10);
    private final Ui.Card gravesCard = new Ui.Card(), deathsCard = new Ui.Card();
    private final Ui.Stack graveList = new Ui.Stack(8), deathList = new Ui.Stack(6);
    private final Ui.Field deathSearch = new Ui.Field("Search deaths (player or cause)", 20);
    private final Map<String, String> lengthChoice = new HashMap<>();
    private final Set<String> expanded = new HashSet<>();
    private String gravesKey = "", deathsKey = "";

    WorldPage(App app) {
        this.app = app;
        root.setBackground(Ui.BG);
        root.setBorder(new EmptyBorder(4, 32, 32, 32));

        // time and weather
        Ui.Card quick = new Ui.Card();
        JPanel time = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        time.setOpaque(false);
        time.add(Ui.label("Time", Ui.bold(13.5f), Ui.TEXT));
        time.add(Box.createHorizontalStrut(6));
        time.add(quickBtn("Day", "time set day", "Set the time to day."));
        time.add(quickBtn("Noon", "time set noon", "Set the time to noon."));
        time.add(quickBtn("Night", "time set night", "Set the time to night."));
        time.add(Box.createHorizontalStrut(18));
        time.add(Ui.label("Weather", Ui.bold(13.5f), Ui.TEXT));
        time.add(Box.createHorizontalStrut(6));
        time.add(quickBtn("Clear", "weather clear", "Cleared the weather."));
        time.add(quickBtn("Rain", "weather rain", "Made it rain."));
        time.add(quickBtn("Thunder", "weather thunder", "Started a thunderstorm."));
        quick.add(new Ui.Stack(12).put(Ui.row(10, Ui.cardTitle("Time and weather"), Ui.glue(), worldNow)).put(time));

        // presets
        Ui.Card presets = new Ui.Card();
        presets.add(new Ui.Stack(12)
                .put(Ui.cardTitle("Presets"))
                .put(Ui.para("One click switches several rules at once, and switches them back when it's over. Your normal choices on the Rules page stay saved.",
                        Ui.font(13), Ui.FAINT))
                .put(presetList));

        // graves
        gravesCard.add(new Ui.Stack(12).put(Ui.row(10, Ui.cardTitle("Graves waiting to be opened"), Ui.glue(),
                new Ui.Btn("Refresh", Ui.Kind.GHOST).small().onClick(() -> { gravesKey = ""; refreshGraves(); }))).put(graveList));

        // deaths
        deathSearch.setMaximumSize(new Dimension(300, 40));
        deathSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { deathsKey = ""; refreshDeaths(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { deathsKey = ""; refreshDeaths(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });
        deathsCard.add(new Ui.Stack(12).put(Ui.row(10, Ui.cardTitle("Deaths"), Ui.glue(), deathSearch)).put(deathList));

        Ui.Stack page = new Ui.Stack(16);
        page.put(quick);
        page.put(presets);
        page.put(gravesCard);
        page.put(deathsCard);
        root.add(page, BorderLayout.NORTH);

        Game.listeners.add(() -> SwingUtilities.invokeLater(() -> { if (root.isShowing()) live(); }));
        Items.listeners.add(() -> SwingUtilities.invokeLater(graveList::repaint));
    }

    private Ui.Btn quickBtn(String text, String command, String said) {
        Ui.Btn b = new Ui.Btn(text, Ui.Kind.SECONDARY).small().onClick(() -> Game.run(command, said));
        needsServer.add(b);
        return b;
    }

    void refresh() {
        if (!Kit.hasServer()) return;
        gravesCard.setVisible(Game.supported());
        deathsCard.setVisible(Game.supported());
        gravesKey = "";
        deathsKey = "";
        live();
        refreshPresets();
    }

    void serverChanged() {
        boolean on = app.server.isRunning();
        for (Ui.Btn b : needsServer) b.setEnabled(on);
        refreshPresets();
        live();
    }

    void tick() {
        if (root.isShowing() && Presets.active() != null) refreshPresets();
    }

    private void live() {
        Game.Snapshot s = Game.now;
        Game.World w = s == null ? null : s.main();
        if (w == null) worldNow.setText(app.server.isRunning() ? " " : "Start the server to use these");
        else {
            long t = w.time() % 24000;
            String part = t < 1000 ? "early morning" : t < 6000 ? "morning" : t < 9000 ? "midday" : t < 12000 ? "afternoon" : t < 13800 ? "sunset"
                    : t < 22500 ? "night" : "sunrise";
            worldNow.setText("Right now: " + part + (w.thunder() ? ", thunderstorm" : w.storm() ? ", raining" : ", clear skies"));
        }
        if (Game.supported()) { refreshGraves(); refreshDeaths(); }
    }

    // ------------------------------------------------------------------ presets

    private String presetKey = "";

    private void refreshPresets() {
        Presets.Preset active = Presets.active();
        String key = (active == null ? "" : active.id() + Presets.until()) + app.server.isRunning() + (active != null && Presets.until() > 0 ? (Presets.until() - System.currentTimeMillis()) / 60_000 : 0);
        if (key.equals(presetKey)) return;
        presetKey = key;
        presetList.removeAll();
        boolean on = app.server.isRunning();
        for (Presets.Preset p : Presets.ALL) {
            boolean isOn = active == p;
            Ui.Card row = new Ui.Card(new BorderLayout(14, 0));
            row.bg = isOn ? Ui.GREEN_DIM : Ui.CARD_HI;
            row.arc = 12;
            row.setBorder(new EmptyBorder(12, 14, 12, 12));
            Ui.Stack text = new Ui.Stack(3);
            JLabel name = Ui.label(p.name(), Ui.bold(14.5f), Ui.TEXT);
            text.put(isOn ? Ui.row(8, name, Ui.tag("ON", Ui.GREEN_HI), Ui.glue()) : name);
            text.put(Ui.para(p.blurb(), Ui.font(12.5f), Ui.MUTED));
            if (isOn) {
                long end = Presets.until();
                long mins = end == 0 ? -1 : Math.max(0, (end - System.currentTimeMillis() + 59_999) / 60_000);
                text.put(Ui.label(end == 0 ? "On until you stop it." : "Switches back " + Presets.describe(end) + " (in " + (mins >= 120 ? mins / 60 + " hours" : mins + " min") + ").",
                        Ui.font(12.5f), Ui.GREEN_HI));
            }
            row.add(text, BorderLayout.CENTER);
            JComponent actions;
            if (isOn) {
                actions = Ui.row(8, new Ui.Btn("Stop and switch back", Ui.Kind.SECONDARY).small().onClick(() -> { Presets.stop(app.server, true); presetKey = ""; refreshPresets(); }));
            } else {
                Ui.Choice len = new Ui.Choice(Presets.LENGTHS, lengthChoice.getOrDefault(p.id(), p.defaultLength()));
                len.onChange(v -> lengthChoice.put(p.id(), v));
                Ui.Btn start = new Ui.Btn("Start", Ui.Kind.PRIMARY).small().onClick(() -> {
                    Presets.start(app.server, p, len.value());
                    presetKey = "";
                    refreshPresets();
                });
                start.setEnabled(on);
                len.setEnabled(on);
                if (!on) start.setToolTipText("Start the server first");
                actions = Ui.row(8, len, start);
            }
            JPanel right = new JPanel(new GridBagLayout());
            right.setOpaque(false);
            right.add(actions);
            row.add(right, BorderLayout.EAST);
            presetList.put(row);
        }
        presetList.revalidate();
        presetList.repaint();
    }

    // ------------------------------------------------------------------ graves

    private void refreshGraves() {
        List<Game.Grave> graves = Game.graves();
        StringBuilder k = new StringBuilder();
        for (Game.Grave g : graves) k.append(g.id()).append(g.returnOnJoin()).append(expanded.contains(g.id()));
        long minute = System.currentTimeMillis() / 60_000;
        String key = k + "|" + minute + "|" + app.server.isRunning();
        if (key.equals(gravesKey)) return;
        gravesKey = key;
        graveList.removeAll();
        if (graves.isEmpty()) {
            graveList.put(Ui.para(AntiCheat.graves() ? "No graves right now. When someone dies, their items wait in a grave where they died, and show up here."
                    : "Graves are off (turn them on on the Rules page).", Ui.font(13), Ui.MUTED));
        }
        for (Game.Grave g : graves) graveList.put(graveRow(g));
        graveList.revalidate();
        graveList.repaint();
    }

    private JComponent graveRow(Game.Grave g) {
        Ui.Card row = new Ui.Card(new BorderLayout(0, 10));
        row.bg = Ui.CARD_HI;
        row.arc = 12;
        row.setBorder(new EmptyBorder(10, 14, 10, 10));
        JLabel who = Ui.label(Server.nice(g.owner()) + "'s grave", Ui.bold(14), Ui.TEXT);
        who.setIcon(MapPage.graveIcon());
        who.setIconTextGap(10);
        String where = g.x() + " " + g.y() + " " + g.z() + dimension(g.world());
        long age = System.currentTimeMillis() - g.made();
        String opens = Game.graveUnlockMinutes <= 0 ? "only " + Server.nice(g.owner()) + " can open it"
                : age >= Game.graveUnlockMinutes * 60_000L ? "anyone can open it now"
                : "opens for everyone in " + ((Game.graveUnlockMinutes * 60_000L - age) / 60_000 + 1) + " min";
        JLabel info = Ui.label(g.count() + " items" + (g.xp() > 0 ? " + XP" : "") + "  ·  at " + where + "  ·  " + ago(g.made()) + "  ·  " + opens, Ui.font(12.5f), Ui.MUTED);
        Ui.Btn show = new Ui.Btn(expanded.contains(g.id()) ? "Hide items" : "Items", Ui.Kind.GHOST).small().onClick(() -> {
            if (!expanded.remove(g.id())) expanded.add(g.id());
            gravesKey = "";
            refreshGraves();
        });
        Ui.Btn map = new Ui.Btn("Map", Ui.Kind.GHOST).small().onClick(() -> app.showOnMap(g.x(), g.z()));
        Ui.Btn tp = Ui.copyButton("Copy teleport", () -> "/execute in " + dimensionId(g.world()) + " run tp @s " + g.x() + " " + (g.y() + 1) + " " + g.z());
        tp.setToolTipText("Copies a command you can paste in Minecraft chat to go there");
        Ui.Btn back = new Ui.Btn(g.returnOnJoin() ? "Waiting for them to join" : "Give back", Ui.Kind.PRIMARY).small().onClick(() -> giveBack(app, g.id(), g.owner()));
        back.setEnabled(app.server.isRunning() && !g.returnOnJoin());
        if (!app.server.isRunning()) back.setToolTipText("Start the server first");
        row.add(Ui.row(8, who, Ui.glue(), show, map, tp, back), BorderLayout.NORTH);
        if (expanded.contains(g.id())) {
            JPanel grid = new JPanel(new GridLayout(0, 10, 4, 4));
            grid.setOpaque(false);
            for (Game.Item i : g.items()) if (i != null) grid.add(new PlayerTools.Slot(i, null));
            JPanel items = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            items.setOpaque(false);
            items.add(grid);
            Ui.Stack s = new Ui.Stack(8).put(info).put(items);
            row.add(s, BorderLayout.CENTER);
        } else {
            row.add(info, BorderLayout.CENTER);
        }
        return row;
    }

    static void giveBack(App app, String id, String owner) {
        if (!app.server.isRunning()) return;
        boolean online = app.server.online.contains(owner) || app.server.online.contains(owner.replaceFirst("^\\.", ""));
        if (!Dialogs.confirm(app.frame, "Give " + Server.nice(owner) + " their items back?",
                online ? "Everything in the grave goes straight into their inventory (armor goes back on), and the grave disappears."
                        : Server.nice(owner) + " isn't online, so they'll get everything the next time they join.",
                "Give back", Ui.Kind.PRIMARY)) return;
        Game.run("xw grave return " + id, null);
    }

    static String dimension(String world) {
        return world.endsWith("_nether") ? " in the Nether" : world.endsWith("_the_end") ? " in the End" : "";
    }

    static String dimensionId(String world) {
        return world.endsWith("_nether") ? "minecraft:the_nether" : world.endsWith("_the_end") ? "minecraft:the_end" : "minecraft:overworld";
    }

    static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60_000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return m / 60 + " h ago";
        LocalDate d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate();
        return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) + " " + d.getDayOfMonth();
    }

    // ------------------------------------------------------------------ deaths

    private void refreshDeaths() {
        List<Game.Death> all = Game.deaths();
        String q = deathSearch.getText().trim().toLowerCase(Locale.ROOT);
        String key = all.size() + (all.isEmpty() ? "" : String.valueOf(all.get(0).time())) + "|" + q + "|" + System.currentTimeMillis() / 60_000;
        if (key.equals(deathsKey)) return;
        deathsKey = key;
        deathList.removeAll();
        int shown = 0;
        for (Game.Death d : all) {
            if (!q.isEmpty() && !(d.message().toLowerCase(Locale.ROOT).contains(q) || d.player().toLowerCase(Locale.ROOT).contains(q))) continue;
            if (shown++ >= 60) break;
            deathList.put(deathRow(d));
        }
        if (all.isEmpty()) deathList.put(Ui.para("Nobody has died yet (since this was turned on).", Ui.font(13), Ui.MUTED));
        else if (shown == 0) deathList.put(Ui.para("No deaths match that.", Ui.font(13), Ui.MUTED));
        else if (shown > 60) deathList.put(Ui.label("Showing the latest 60. Search to find older ones.", Ui.font(12), Ui.FAINT));
        deathList.revalidate();
        deathList.repaint();
    }

    private JComponent deathRow(Game.Death d) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 2, 4, 2));
        JLabel when = Ui.label(ago(d.time()), Ui.font(12.5f), Ui.FAINT);
        when.setPreferredSize(new Dimension(84, 20));
        when.setToolTipText(Instant.ofEpochMilli(d.time()).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').substring(0, 16));
        String msg = Server.dropBedrockDots(d.message());
        JLabel what = Ui.label(msg, Ui.font(13.5f), Ui.TEXT);
        JLabel where = Ui.label(d.x() + " " + d.y() + " " + d.z() + dimension(d.world()), Ui.font(12.5f), Ui.MUTED);
        Ui.Btn map = new Ui.Btn("Map", Ui.Kind.GHOST).small().onClick(() -> app.showOnMap(d.x(), d.z()));
        map.setVisible(dimension(d.world()).isEmpty());
        row.add(when, BorderLayout.WEST);
        row.add(what, BorderLayout.CENTER);
        row.add(Ui.row(8, where, map), BorderLayout.EAST);
        return row;
    }
}
