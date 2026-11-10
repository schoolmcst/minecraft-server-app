// The Health page: how the server is doing right now and over the last hour (server speed, memory, CPU), plus old
// logs and crash reports in one place, with plain-words hints about what went wrong.

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;

final class HealthPage {
    final JPanel root = new JPanel(new BorderLayout());
    private final App app;
    private final Tile speed = new Tile("Server speed"), tick = new Tile("Tick time"), memory = new Tile("Memory"), cpu = new Tile("CPU");
    private final Chart chart = new Chart();
    private final JLabel lagNote = Ui.label(" ", Ui.font(13), Ui.MUTED);
    private final JLabel chartNote = Ui.label(" ", Ui.font(12.5f), Ui.FAINT);

    // logs
    private final DefaultListModel<Path> files = new DefaultListModel<>();
    private final JList<Path> fileList = new JList<>(files);
    private final JTextPane text = new JTextPane();
    private final Ui.Field search = new Ui.Field("Find in this log", 18);
    private final Ui.Switch problemsOnly = new Ui.Switch(false);
    private final Ui.Stack hints = new Ui.Stack(8);
    private final JLabel fileNote = Ui.label(" ", Ui.font(12.5f), Ui.FAINT);
    private String loadedText = "";
    private int searchFrom;

    HealthPage(App app) {
        this.app = app;
        root.setBackground(Ui.BG);
        root.setBorder(new EmptyBorder(4, 32, 32, 32));

        JPanel tiles = new JPanel(new GridLayout(1, 4, 12, 0));
        tiles.setOpaque(false);
        tiles.add(speed);
        tiles.add(tick);
        tiles.add(memory);
        tiles.add(cpu);

        Ui.Card graph = new Ui.Card();
        chart.setPreferredSize(new Dimension(10, 430));
        chart.setMinimumSize(new Dimension(10, 430));
        graph.add(new Ui.Stack(10).put(Ui.row(10, Ui.cardTitle("The last hour"), Ui.glue(), chartNote)).put(lagNote).put(chart));

        // logs and crash reports
        fileList.setBackground(Ui.FIELD);
        fileList.setForeground(Ui.TEXT);
        fileList.setFixedCellHeight(30);
        fileList.setCellRenderer((l, p, i, sel, f) -> {
            JLabel c = Ui.label(" " + describeFile(p), Ui.font(13), sel ? Ui.TEXT : isCrash(p) ? Ui.RED_HI : Ui.MUTED);
            c.setOpaque(true);
            c.setBackground(sel ? new Color(0x2A3038) : Ui.FIELD);
            c.setBorder(new EmptyBorder(0, 8, 0, 8));
            return c;
        });
        fileList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) load(fileList.getSelectedValue()); });
        JScrollPane fl = new JScrollPane(fileList);
        fl.setBorder(BorderFactory.createLineBorder(Ui.LINE));
        Ui.styleScroll(fl);
        fl.setPreferredSize(new Dimension(230, 420));
        fl.setMinimumSize(new Dimension(230, 420));

        text.setEditable(false);
        text.setBackground(new Color(0x0F1114));
        text.setForeground(new Color(0xC4CAD3));
        text.setFont(Ui.mono(12.5f));
        text.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane tp = new JScrollPane(text);
        tp.setBorder(BorderFactory.createLineBorder(Ui.LINE));
        Ui.styleScroll(tp);
        tp.setPreferredSize(new Dimension(10, 420));
        tp.setMinimumSize(new Dimension(10, 420));

        search.setMaximumSize(new Dimension(260, 40));
        search.addActionListener(e -> find());
        problemsOnly.onChange(on -> show());
        Ui.Btn next = new Ui.Btn("Find next", Ui.Kind.SECONDARY).small().onClick(this::find);
        Ui.Btn folder = new Ui.Btn("Open folder", Ui.Kind.GHOST).small().onClick(() -> {
            Path p = fileList.getSelectedValue();
            Kit.open(p == null ? Kit.dir().resolve("logs") : p.getParent());
        });
        Ui.Btn reload = new Ui.Btn("Refresh", Ui.Kind.GHOST).small().onClick(this::refreshFiles);

        JPanel viewer = new JPanel(new BorderLayout(12, 0));
        viewer.setOpaque(false);
        viewer.add(fl, BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.setOpaque(false);
        right.add(Ui.row(8, search, next, Box.createHorizontalStrut(8), problemsOnly, Ui.label("Only warnings and errors", Ui.font(13), Ui.MUTED), Ui.glue(), fileNote), BorderLayout.NORTH);
        right.add(tp, BorderLayout.CENTER);
        viewer.add(right, BorderLayout.CENTER);

        Ui.Card logs = new Ui.Card();
        hints.setVisible(false);
        logs.add(new Ui.Stack(12)
                .put(Ui.row(10, Ui.cardTitle("Logs and crash reports"), Ui.glue(), reload, folder))
                .put(Ui.para("Everything the server wrote, day by day. Crash reports (in red) are made when the server falls over. "
                        + "If something went wrong, the box below explains it in plain words when it can.", Ui.font(13), Ui.FAINT))
                .put(hints)
                .put(viewer));

        Ui.Stack page = new Ui.Stack(16);
        page.put(tiles);
        page.put(graph);
        page.put(logs);
        root.add(page, BorderLayout.NORTH);

        Game.listeners.add(() -> SwingUtilities.invokeLater(() -> { if (root.isShowing()) live(); }));
    }

    void refresh() {
        if (!Kit.hasServer()) return;
        live();
        refreshFiles();
    }

    // ------------------------------------------------------------------ live numbers

    private void live() {
        Game.Snapshot s = Game.now;
        List<Game.Sample> h = Game.history();
        boolean modded = !Game.supported();
        if (s != null) {
            double t = Math.min(20, s.tps());
            speed.set(String.format(Locale.ROOT, "%.1f", t), "/ 20 TPS", t >= 19 ? "Smooth" : t >= 15 ? "A little laggy" : "Laggy", t >= 19 ? Ui.GREEN_HI : t >= 15 ? Ui.AMBER : Ui.RED_HI);
            tick.set(String.format(Locale.ROOT, "%.0f", s.mspt()), "ms per tick", s.mspt() < 40 ? "Plenty of room (50 is the limit)" : s.mspt() < 50 ? "Close to the limit" : "Over the limit: lag",
                    s.mspt() < 40 ? Ui.GREEN_HI : s.mspt() < 50 ? Ui.AMBER : Ui.RED_HI);
            double used = s.memUsed() / 1e9, max = s.memMax() / 1e9;
            memory.set(String.format(Locale.ROOT, "%.1f", used), String.format(Locale.ROOT, "/ %.1f GB", max), (int) (100 * used / Math.max(0.1, max)) + "% in use. Java tidies up now and then, so it goes up and down.", Ui.MUTED);
            cpu.set(String.format(Locale.ROOT, "%.0f%%", s.cpu() * 100), "of this PC", String.format(Locale.ROOT, "Whole PC: %.0f%%   ·   %d entities, %d areas loaded", s.sysCpu() * 100, s.entities(), s.chunks()), Ui.MUTED);
        } else if (modded && !h.isEmpty() && app.server.isRunning()) {
            Game.Sample last = h.get(h.size() - 1);
            for (Tile x : List.of(speed, tick, memory)) x.set("-", "", "Shown on survival servers only", Ui.FAINT);
            cpu.set(String.format(Locale.ROOT, "%.0f%%", last.cpu() * 100), "of this PC", "Server's share of the processor", Ui.MUTED);
        } else {
            for (Tile x : List.of(speed, tick, memory, cpu)) x.set("-", "", app.server.isRunning() ? "Waiting for the server..." : "Server is off", Ui.FAINT);
        }
        chart.data = h;
        chart.modded = modded;
        chart.repaint();
        chartNote.setText(h.isEmpty() ? "Starts filling in when the server runs" : "Hover to see exact numbers");
        lagNote.setText(lagStory(h));
    }

    /** "Lag started around 3:42 PM (8 players online)." from the history, or a calm line. */
    static String lagStory(List<Game.Sample> h) {
        if (h.size() < 5 || Double.isNaN(h.get(h.size() - 1).tps())) return h.isEmpty() ? " " : "Server speed is measured on survival servers.";
        long badStart = -1, lastBad = -1;
        int players = 0;
        for (int i = 0; i < h.size(); i++) {
            Game.Sample x = h.get(i);
            boolean bad = x.tps() < 18;
            if (bad) { if (badStart < 0 || x.time() - lastBad > 3 * 60_000L) { badStart = x.time(); players = x.players(); } lastBad = x.time(); }
        }
        if (badStart < 0) return "No lag in the last hour: the server kept up the whole time.";
        boolean still = h.get(h.size() - 1).time() - lastBad < 30_000;
        String start = App.CLOCK.format(Instant.ofEpochMilli(badStart).atZone(ZoneId.systemDefault()).toLocalTime());
        String end = App.CLOCK.format(Instant.ofEpochMilli(lastBad).atZone(ZoneId.systemDefault()).toLocalTime());
        return still ? "Lagging since about " + start + " (" + players + " online then). Lots of mobs, redstone or flying around new land are the usual causes."
                : "Last lag: " + start + (start.equals(end) ? "" : " to " + end) + " (" + players + " online then).";
    }

    /** A number with a label, like the dashboard's. */
    static final class Tile extends Ui.Card {
        final JLabel big = Ui.label("-", Ui.bold(24), Ui.TEXT), unit = Ui.label("", Ui.font(13), Ui.MUTED);
        final JTextArea sub = Ui.para(" ", Ui.font(12.5f), Ui.FAINT);

        @Override public Dimension getPreferredSize() { return new Dimension(150, super.getPreferredSize().height); }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        Tile(String title) {
            super(new BorderLayout());
            setBorder(new EmptyBorder(14, 16, 14, 16));
            add(new Ui.Stack(6).put(Ui.cardTitle(title)).put(Ui.row(6, big, unit, Ui.glue())).put(sub), BorderLayout.NORTH);
        }

        void set(String value, String u, String note, Color noteColor) {
            big.setText(value);
            unit.setText(u);
            sub.setText(note);
            sub.setForeground(noteColor);
        }
    }

    // ------------------------------------------------------------------ the graph

    /** Three small charts sharing one time axis (each with its own scale): server speed, memory, CPU. */
    static final class Chart extends JComponent {
        List<Game.Sample> data = List.of();
        boolean modded;
        private Point mouse;
        static final Color LINE = new Color(0x58A6FF), FILL = new Color(88, 166, 255, 34);
        static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("h:mm", Locale.US);

        Chart() {
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { mouse = e.getPoint(); repaint(); }
                @Override public void mouseExited(MouseEvent e) { mouse = null; repaint(); }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            int left = 52, right = 12, top = 6, gap = 26, bottomAxis = 22;
            int w = getWidth() - left - right;
            int each = (getHeight() - top - bottomAxis - 2 * gap) / 3;
            long now = System.currentTimeMillis(), from = now - 60 * 60_000L;
            double memMax = 2;
            for (Game.Sample s : data) if (!Double.isNaN(s.memMaxGb())) memMax = Math.max(memMax, s.memMaxGb());
            String[] titles = {"Server speed (TPS, 20 is perfect)", "Memory in use (GB)", "CPU (% of this PC)"};
            double[] maxes = {20, Math.ceil(memMax), 100};
            Integer hoverIdx = null;
            if (mouse != null && !data.isEmpty() && mouse.x >= left && mouse.x <= left + w) {
                long t = from + (long) ((mouse.x - left) / (double) w * (now - from));
                int best = -1;
                long bd = Long.MAX_VALUE;
                for (int i = 0; i < data.size(); i++) { long d = Math.abs(data.get(i).time() - t); if (d < bd) { bd = d; best = i; } }
                if (best >= 0 && bd < 60_000) hoverIdx = best;
            }
            for (int c = 0; c < 3; c++) {
                int y0 = top + c * (each + gap) + 16, hgt = each - 16;
                g.setFont(Ui.bold(12));
                g.setColor(Ui.MUTED);
                g.drawString(titles[c], left, y0 - 5);
                // grid
                g.setFont(Ui.font(11));
                for (int k = 0; k <= 2; k++) {
                    double v = maxes[c] * k / 2;
                    int y = y0 + hgt - (int) Math.round(hgt * k / 2.0);
                    g.setColor(new Color(0x262B32));
                    g.drawLine(left, y, left + w, y);
                    g.setColor(Ui.FAINT);
                    String lbl = v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
                    g.drawString(lbl, left - 8 - g.getFontMetrics().stringWidth(lbl), y + 4);
                }
                if (c == 0) { // where lag starts, labelled
                    int y15 = y0 + hgt - (int) Math.round(hgt * 15 / 20.0);
                    g.setColor(new Color(240, 102, 94, 130));
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
                    g.drawLine(left, y15, left + w, y15);
                    g.setStroke(new BasicStroke(1f));
                    g.setColor(new Color(240, 102, 94, 190));
                    g.drawString("laggy below this", left + w - g.getFontMetrics().stringWidth("laggy below this") - 4, y15 + 13);
                }
                if (modded && c < 2) {
                    g.setColor(Ui.FAINT);
                    g.drawString("Shown on survival servers only", left + 12, y0 + hgt / 2);
                    continue;
                }
                Path2D line = new Path2D.Double(), area = new Path2D.Double();
                boolean pen = false;
                double lastX = 0;
                long prevT = 0;
                for (Game.Sample s : data) {
                    double v = c == 0 ? Math.min(20, s.tps()) : c == 1 ? s.memUsedGb() : s.cpu() * 100;
                    if (Double.isNaN(v) || s.time() < from) { pen = false; continue; }
                    double x = left + (s.time() - from) / (double) (now - from) * w;
                    double y = y0 + hgt - Math.max(0, Math.min(1, v / maxes[c])) * hgt;
                    if (pen && s.time() - prevT > 15_000) { // a gap (server was off)
                        area.lineTo(lastX, y0 + hgt);
                        pen = false;
                    }
                    if (!pen) { line.moveTo(x, y); area.moveTo(x, y0 + hgt); area.lineTo(x, y); pen = true; }
                    else { line.lineTo(x, y); area.lineTo(x, y); }
                    lastX = x;
                    prevT = s.time();
                }
                if (pen) area.lineTo(lastX, y0 + hgt);
                if (c > 0) { g.setColor(FILL); g.fill(area); }
                g.setColor(LINE);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(line);
                g.setStroke(new BasicStroke(1f));
                if (hoverIdx != null) {
                    Game.Sample s = data.get(hoverIdx);
                    double v = c == 0 ? Math.min(20, s.tps()) : c == 1 ? s.memUsedGb() : s.cpu() * 100;
                    if (!Double.isNaN(v)) {
                        double x = left + (s.time() - from) / (double) (now - from) * w, y = y0 + hgt - Math.max(0, Math.min(1, v / maxes[c])) * hgt;
                        g.setColor(Ui.CARD);
                        g.fill(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
                        g.setColor(LINE);
                        g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
                    }
                }
            }
            if (data.isEmpty()) {
                g.setFont(Ui.font(13.5f));
                g.setColor(Ui.MUTED);
                String t = "The graph fills in while the server runs.";
                g.drawString(t, left + (w - g.getFontMetrics().stringWidth(t)) / 2, getHeight() / 2);
            }
            // time axis
            int axisY = getHeight() - 6;
            g.setFont(Ui.font(11));
            g.setColor(Ui.FAINT);
            for (int k = 0; k <= 4; k++) {
                long t = from + (now - from) * k / 4;
                String lbl = k == 4 ? "now" : HM.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()));
                int x = left + w * k / 4 - (k == 4 ? g.getFontMetrics().stringWidth(lbl) : k == 0 ? 0 : g.getFontMetrics().stringWidth(lbl) / 2);
                g.drawString(lbl, x, axisY);
            }
            // crosshair + tooltip
            if (hoverIdx != null) {
                Game.Sample s = data.get(hoverIdx);
                int x = left + (int) ((s.time() - from) / (double) (now - from) * w);
                g.setColor(new Color(255, 255, 255, 60));
                g.drawLine(x, top, x, getHeight() - bottomAxis);
                List<String> lines = new ArrayList<>();
                lines.add(App.CLOCK.format(Instant.ofEpochMilli(s.time()).atZone(ZoneId.systemDefault()).toLocalTime()));
                if (!Double.isNaN(s.tps())) lines.add(String.format(Locale.ROOT, "Speed %.1f TPS  (%.0f ms per tick)", Math.min(20, s.tps()), s.mspt()));
                if (!Double.isNaN(s.memUsedGb())) lines.add(String.format(Locale.ROOT, "Memory %.1f of %.1f GB", s.memUsedGb(), s.memMaxGb()));
                lines.add(String.format(Locale.ROOT, "CPU %.0f%%", s.cpu() * 100));
                lines.add(s.players() + (s.players() == 1 ? " player" : " players") + " online");
                g.setFont(Ui.font(12.5f));
                FontMetrics fm = g.getFontMetrics();
                int tw = 0;
                for (String l : lines) tw = Math.max(tw, fm.stringWidth(l));
                int bw = tw + 20, bh = lines.size() * fm.getHeight() + 14;
                int bx = x + 14 + bw > getWidth() ? x - 14 - bw : x + 14, by = Math.max(4, Math.min(getHeight() - bh - 4, (mouse == null ? 40 : mouse.y) - bh / 2));
                g.setColor(new Color(0x2A3038));
                g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, 10, 10));
                g.setColor(Ui.LINE);
                g.draw(new RoundRectangle2D.Float(bx, by, bw, bh, 10, 10));
                for (int i = 0; i < lines.size(); i++) {
                    g.setFont(i == 0 ? Ui.bold(12.5f) : Ui.font(12.5f));
                    g.setColor(i == 0 ? Ui.TEXT : new Color(0xC4CAD3));
                    g.drawString(lines.get(i), bx + 10, by + 7 + fm.getAscent() + i * fm.getHeight());
                }
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ logs

    static boolean isCrash(Path p) { return p != null && p.getParent() != null && p.getParent().getFileName().toString().equals("crash-reports"); }

    static String describeFile(Path p) {
        if (p == null) return "";
        String n = p.getFileName().toString();
        if (n.equals("latest.log")) return "Today (latest)";
        if (isCrash(p)) {
            Matcher m = Pattern.compile("crash-(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})\\.(\\d{2})").matcher(n);
            return m.find() ? "Crash " + m.group(2) + "/" + m.group(3) + " " + m.group(4) + ":" + m.group(5) : "Crash: " + n;
        }
        Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})-(\\d+)\\.log").matcher(n);
        if (m.find()) {
            LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            return d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
                    + " " + d.getDayOfMonth() + (m.group(4).equals("1") ? "" : "  (#" + m.group(4) + ")");
        }
        return n;
    }

    void refreshFiles() {
        Path sel = fileList.getSelectedValue();
        List<Path> list = new ArrayList<>();
        for (String dir : List.of("crash-reports", "logs")) {
            Path d = Kit.dir().resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    if (n.endsWith(".log") || n.endsWith(".log.gz") || n.endsWith(".txt")) list.add(p);
                }
            } catch (IOException ignored) { }
        }
        list.sort((a, b) -> {
            if (a.getFileName().toString().equals("latest.log")) return -1;
            if (b.getFileName().toString().equals("latest.log")) return 1;
            try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (IOException e) { return 0; }
        });
        files.clear();
        for (Path p : list.subList(0, Math.min(list.size(), 120))) files.addElement(p);
        if (sel != null && list.contains(sel)) fileList.setSelectedValue(sel, true);
        else if (!files.isEmpty()) fileList.setSelectedIndex(0);
        else { text.setText("No logs yet. They appear after the server has run."); hints.setVisible(false); }
    }

    private void load(Path p) {
        if (p == null) return;
        fileNote.setText("Loading...");
        new Thread(() -> {
            String t;
            try { t = read(p); } catch (IOException e) { t = "Couldn't read this file: " + Kit.describe(e); }
            String fin = t;
            List<String[]> found = LogHints.find(fin);
            SwingUtilities.invokeLater(() -> {
                loadedText = fin;
                searchFrom = 0;
                show();
                showHints(found, isCrash(p));
                try { fileNote.setText(String.format(Locale.ROOT, "%,d KB", Files.size(p) / 1024)); } catch (IOException e) { fileNote.setText(" "); }
            });
        }, "log-read").start();
    }

    static final int MAX_CHARS = 1_500_000;

    static String read(Path p) throws IOException {
        byte[] b;
        if (p.toString().endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) { b = in.readNBytes(40_000_000); }
        } else {
            b = Files.readAllBytes(p);
        }
        String s = new String(b, StandardCharsets.UTF_8).replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "");
        if (s.length() > MAX_CHARS) s = "(Only the last part of this big file is shown.)\n...\n" + s.substring(s.length() - MAX_CHARS);
        return s;
    }

    private void show() {
        String t = loadedText;
        if (problemsOnly.isOn()) {
            StringBuilder b = new StringBuilder();
            for (String l : t.split("\n")) if (l.contains("WARN") || l.contains("ERROR") || l.contains("FATAL") || l.contains("Exception")) b.append(l).append('\n');
            t = b.length() == 0 ? "No warnings or errors in this log." : b.toString();
        }
        StyledDocument doc = new DefaultStyledDocument();
        Style base = doc.addStyle("base", null);
        StyleConstants.setForeground(base, new Color(0xC4CAD3));
        Style warn = doc.addStyle("warn", base);
        StyleConstants.setForeground(warn, Ui.AMBER);
        Style err = doc.addStyle("err", base);
        StyleConstants.setForeground(err, Ui.RED_HI);
        try {
            if (t.length() > 400_000) doc.insertString(0, t, base); // too big to colour line by line quickly
            else for (String l : t.split("\n", -1)) {
                Style s = l.contains("ERROR") || l.contains("FATAL") || l.startsWith("\tat ") || l.contains("Exception") ? err : l.contains("WARN") ? warn : base;
                doc.insertString(doc.getLength(), l + "\n", s);
            }
        } catch (BadLocationException ignored) { }
        text.setDocument(doc);
        text.setCaretPosition(problemsOnly.isOn() || isCrash(fileList.getSelectedValue()) ? 0 : doc.getLength());
    }

    private void find() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        try {
            String all = text.getDocument().getText(0, text.getDocument().getLength()).toLowerCase(Locale.ROOT);
            int i = all.indexOf(q, searchFrom);
            if (i < 0) i = all.indexOf(q);
            if (i < 0) { fileNote.setText("Not found"); return; }
            searchFrom = i + q.length();
            text.requestFocusInWindow();
            text.select(i, i + q.length());
            text.setSelectionColor(new Color(0x3D6FA8));
            Rectangle2D r = text.modelToView2D(i);
            if (r != null) text.scrollRectToVisible(r.getBounds());
        } catch (BadLocationException ignored) { }
    }

    private void showHints(List<String[]> found, boolean crash) {
        hints.removeAll();
        if (found.isEmpty() && !crash) { hints.setVisible(false); hints.revalidate(); return; }
        if (found.isEmpty()) found = List.<String[]>of(new String[]{"The server crashed", "No known cause was found. The top of the report says what it was doing. Updating "
                + "(Settings > Check for updates) and removing recently added plugins or mods fixes most crashes."}) ;
        for (String[] h : found.subList(0, Math.min(3, found.size()))) {
            Ui.Card c = new Ui.Card();
            c.bg = crash ? Ui.RED_DIM : Ui.AMBER_DIM;
            c.arc = 12;
            c.setBorder(new EmptyBorder(10, 14, 10, 14));
            c.add(new Ui.Stack(3).put(Ui.label(h[0], Ui.bold(14), Ui.TEXT)).put(Ui.para(h[1], Ui.font(13), new Color(0xD6DAE0))));
            hints.put(c);
        }
        hints.setVisible(true);
        hints.revalidate();
        hints.repaint();
    }
}
