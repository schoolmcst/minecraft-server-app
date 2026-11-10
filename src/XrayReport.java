// The "X-ray report" card on the Rules page: what XrayWatch (the app's own plugin) has seen.
// Reads <server>/plugins/XrayWatch/report.json (each player's score for the last 30 min / 3 h, saved every 30 s)
// and finds.csv (every diamond, emerald and netherite vein found underground, with coordinates).

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class XrayReport {
    final Ui.Card card = new Ui.Card();
    private final App app;
    private final Ui.Stack players = new Ui.Stack(8);
    private final Ui.Stack finds = new Ui.Stack(6);
    private final JLabel updated = Ui.label(" ", Ui.font(12.5f), Ui.FAINT);

    XrayReport(App app) {
        this.app = app;
        Ui.Btn refresh = new Ui.Btn("Refresh", Ui.Kind.GHOST).small().onClick(this::refresh);
        Ui.Btn folder = new Ui.Btn("Open full history", Ui.Kind.GHOST).small().onClick(() -> {
            Path csv = AntiCheat.xrayWatchDir().resolve("finds.csv");
            Kit.open(Files.exists(csv) ? csv : Kit.dir());
        });
        card.add(new Ui.Stack(12)
                .put(Ui.row(10, Ui.cardTitle("X-ray report"), updated, Ui.glue(), refresh, folder))
                .put(Ui.para("Score 0-100: how hard it is to explain someone's mining by luck. Under 30 is normal, 60 is 1 chance "
                        + "in 1,000, 80 or more is 1 in 10,000 (very likely X-ray). Before you act on it, go and look at their tunnels: "
                        + "copy a teleport command below and paste it in the game chat.", Ui.font(13), Ui.FAINT))
                .put(players)
                .put(Box.createVerticalStrut(4))
                .put(Ui.label("RECENT HIDDEN ORE FINDS", Ui.bold(11.5f), Ui.MUTED))
                .put(finds));
    }

    void refresh() {
        players.removeAll();
        finds.removeAll();
        if (!Kit.hasServer() || !AntiCheat.applies()) return;
        if (!AntiCheat.xrayDetect()) {
            players.put(Ui.para("X-ray detection is off. Turn on \"Catch X-ray users by how they mine\" above.", Ui.font(13.5f), Ui.MUTED));
            updated.setText(" ");
            relayout();
            return;
        }
        Path json = AntiCheat.xrayWatchDir().resolve("report.json");
        Object rep = null;
        try {
            if (Files.exists(json)) rep = Kit.Json.parse(Files.readString(json, StandardCharsets.UTF_8).trim());
        } catch (Exception ignored) { }
        if (rep == null) {
            updated.setText(" ");
            players.put(Ui.para(app.server.isRunning() || Files.exists(AntiCheat.xrayWatchJar())
                    ? "Nothing yet. Numbers show up here once players start mining underground."
                    : "Starts watching the next time the server starts.", Ui.font(13.5f), Ui.MUTED));
        } else {
            Object u = Kit.Json.get(rep, "updated");
            updated.setText(u instanceof Number n ? "updated " + ago(n.longValue()) : " ");
            List<Object> list = new ArrayList<>(Kit.Json.list(rep, "players"));
            list.sort(Comparator.comparingLong(o -> -num(o, "score")));
            int shown = 0;
            for (Object pl : list) {
                if (num(pl, "totalBlocks") == 0 && num(pl, "totalVeins") == 0) continue;
                players.put(playerRow(pl));
                if (++shown >= 12) break;
            }
            if (shown == 0) players.put(Ui.para("Nobody has mined underground yet since the server started.", Ui.font(13.5f), Ui.MUTED));
        }
        List<String[]> rows = readFinds(12);
        if (rows.isEmpty()) finds.put(Ui.para("No hidden diamond, emerald or netherite finds yet.", Ui.font(13), Ui.MUTED));
        for (String[] r : rows) finds.put(findRow(r));
        relayout();
    }

    private void relayout() {
        card.revalidate();
        card.repaint();
    }

    private static long num(Object o, String k) {
        Object v = Kit.Json.get(o, k);
        return v instanceof Number n ? n.longValue() : 0;
    }

    private JComponent playerRow(Object pl) {
        long score = num(pl, "score");
        String name = Server.nice(String.valueOf(Kit.Json.str(pl, "name")));
        String verdict;
        Color c;
        if (score >= 80) { verdict = "Very likely X-ray"; c = Ui.RED_HI; }
        else if (score >= 60) { verdict = "Suspicious"; c = Ui.AMBER; }
        else if (score >= 30) { verdict = "Keep an eye on"; c = Ui.BLUE; }
        else { verdict = "Normal"; c = Ui.GREEN_HI; }
        long minutes = Math.max(1, num(pl, "minutes"));
        String detail = "Last " + (minutes >= 120 ? (minutes / 60) + " hours" : minutes + " minutes") + ": dug straight to "
                + num(pl, "targetedVeins") + " hidden vein" + (num(pl, "targetedVeins") == 1 ? "" : "s") + ", "
                + num(pl, "hiddenVeins") + " hidden + " + num(pl, "openVeins") + " open veins in " + num(pl, "blocks")
                + " blocks mined.   Since the server started: " + num(pl, "totalBlocks") + " blocks, " + num(pl, "totalVeins") + " veins.";
        Ui.Card row = new Ui.Card(new BorderLayout(14, 0));
        row.bg = Ui.CARD_HI;
        row.arc = 12;
        row.setBorder(new EmptyBorder(10, 14, 10, 14));
        JComponent bar = new ScoreBar((int) score, c);
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(bar, BorderLayout.NORTH);
        row.add(left, BorderLayout.WEST);
        row.add(new Ui.Stack(3)
                .put(Ui.row(8, Ui.label(name, Ui.bold(14), Ui.TEXT), Ui.tag(verdict, c), Ui.glue()))
                .put(Ui.para(detail, Ui.font(12.5f), Ui.MUTED)), BorderLayout.CENTER);
        return row;
    }

    /** finds.csv rows that were hidden (or dug straight to), newest first. */
    private static List<String[]> readFinds(int max) {
        Path csv = AntiCheat.xrayWatchDir().resolve("finds.csv");
        List<String[]> out = new ArrayList<>();
        if (!Files.exists(csv)) return out;
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 1 && out.size() < max; i--) {
                String[] f = lines.get(i).split(",");
                if (f.length >= 10 && "true".equals(f[8])) out.add(f);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private JComponent findRow(String[] f) {
        // time,player,uuid,ore,world,x,y,z,hidden,blocks_since_last[,targeted]
        long t = 0;
        try { t = Long.parseLong(f[0]); } catch (NumberFormatException ignored) { }
        boolean targeted = f.length > 10 && "true".equals(f[10]);
        String ore = f[3].toLowerCase(Locale.ROOT).replace("deepslate_", "").replace("_ore", "").replace('_', ' ');
        String where = f[5] + " " + f[6] + " " + f[7];
        String world = f[4].contains("nether") ? " (Nether)" : f[4].contains("end") ? " (End)" : "";
        List<Component> parts = new ArrayList<>();
        parts.add(Ui.label(ago(t), Ui.font(12.5f), Ui.FAINT));
        parts.add(Ui.label(Server.nice(f[1]), Ui.bold(13), Ui.TEXT));
        parts.add(Ui.label(ore + " at " + where + world, Ui.font(13), Ui.MUTED));
        if (targeted) parts.add(Ui.tag("Dug straight to it", Ui.RED_HI));
        else parts.add(Ui.label(f[9] + " blocks after their last find", Ui.font(12), Ui.FAINT));
        parts.add(Ui.glue());
        int y = 0;
        try { y = Integer.parseInt(f[6]) + 1; } catch (NumberFormatException ignored) { }
        String tp = (world.isEmpty() ? "/tp " : "/execute in " + f[4] + " run tp @s ") + f[5] + " " + y + " " + f[7];
        Ui.Btn copy = Ui.copyButton("Copy teleport", () -> tp);
        copy.setToolTipText("Paste in the game chat (as an admin) to go and look: " + tp);
        parts.add(copy);
        return Ui.row(10, parts.toArray(new Component[0]));
    }

    static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    /** A small round score meter. */
    static final class ScoreBar extends JComponent {
        final int score;
        final Color color;

        ScoreBar(int score, Color color) {
            this.score = score;
            this.color = color;
            setToolTipText("Score " + score + " / 100");
        }

        @Override public Dimension getPreferredSize() { return new Dimension(46, 46); }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.aa(g2);
            g2.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(255, 255, 255, 25));
            g2.drawOval(4, 4, 38, 38);
            g2.setColor(color);
            g2.drawArc(4, 4, 38, 38, 90, -(int) Math.round(3.6 * Math.max(2, score)));
            g2.setFont(Ui.bold(13));
            FontMetrics fm = g2.getFontMetrics();
            String t = String.valueOf(score);
            g2.setColor(Ui.TEXT);
            g2.drawString(t, 23 - fm.stringWidth(t) / 2, 23 + fm.getAscent() / 2 - 2);
            g2.dispose();
        }
    }
}
