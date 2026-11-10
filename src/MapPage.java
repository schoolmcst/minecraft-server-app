// The Map page: a top-down map of the Overworld (drawn by the plugin, see plugin/src/xraywatch/MapRender.java) with
// everyone's position, graves, spawn and bases the owner names. Drag to move, scroll to zoom, click a player to manage
// them, right-click anywhere to teleport someone there or mark a base.

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class MapPage {
    final JPanel root = new JPanel(new BorderLayout(16, 0));
    private final App app;
    private final MapView view = new MapView();
    private final Ui.Stack side = new Ui.Stack(12);
    private final JLabel fillNote = Ui.label(" ", Ui.font(12.5f), Ui.FAINT);
    private final Ui.Btn fill = new Ui.Btn("Draw explored land", Ui.Kind.SECONDARY).small();
    private final JPanel unsupported = new JPanel(new BorderLayout());
    private final JPanel body = new JPanel(new BorderLayout(16, 0));

    record Base(String name, int x, int z) { }

    MapPage(App app) {
        this.app = app;
        root.setBackground(Ui.BG);
        root.setBorder(new EmptyBorder(4, 32, 24, 32));
        body.setOpaque(false);

        Ui.Card mapCard = new Ui.Card(new BorderLayout());
        mapCard.setBorder(new EmptyBorder(6, 6, 6, 6));
        mapCard.add(view, BorderLayout.CENTER);
        body.add(mapCard, BorderLayout.CENTER);

        fill.onClick(() -> {
            Game.Snapshot s = Game.now;
            if (s != null && s.filling()) { Game.run("xw map stop", "Stopped drawing the map."); return; }
            if (!Game.run("xw map fill 1500", null)) Dialogs.info(app.frame, "Start the server first", "The map is drawn by the server while it runs.");
        });
        JScrollPane sp = Ui.scroll(side);
        sp.setPreferredSize(new Dimension(270, 10));
        body.add(sp, BorderLayout.EAST);

        unsupported.setOpaque(false);
        Ui.Card u = new Ui.Card();
        u.add(new Ui.Stack(8).put(Ui.label("The live map is for survival servers", Ui.bold(16), Ui.TEXT))
                .put(Ui.para("It's drawn by the app's own server plugin, which runs on survival (Paper) servers. Modded servers can't run it.", Ui.font(13.5f), Ui.MUTED)));
        unsupported.add(u, BorderLayout.NORTH);

        root.add(body, BorderLayout.CENTER);
        Game.listeners.add(() -> SwingUtilities.invokeLater(this::live));
        new javax.swing.Timer(4000, e -> { if (root.isShowing()) view.reloadTiles(); }).start();
    }

    void refresh() {
        boolean ok = Game.supported();
        root.removeAll();
        root.add(ok ? body : unsupported, BorderLayout.CENTER);
        if (ok) {
            view.reloadTiles();
            if (!view.centered) view.centerOnSpawn();
            live();
        }
        root.revalidate();
        root.repaint();
    }

    void centerOn(double x, double z) { view.centerOn(x, z); }

    /** Called when the app switches servers. */
    void reset() {
        view.tiles.clear();
        view.centered = false;
    }

    // ------------------------------------------------------------------ bases (saved per server)

    static List<Base> bases() {
        List<Base> out = new ArrayList<>();
        String s = Kit.get("map.bases", "");
        for (String part : s.split(";")) {
            String[] f = part.split("\\|");
            if (f.length != 3) continue;
            try { out.add(new Base(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static void saveBases(List<Base> list) {
        StringBuilder b = new StringBuilder();
        for (Base x : list) b.append(b.length() > 0 ? ";" : "").append(x.name().replaceAll("[|;]", " ")).append('|').append(x.x()).append('|').append(x.z());
        Kit.set("map.bases", b.length() == 0 ? null : b.toString());
    }

    void addBase(int x, int z) {
        String name = JOptionPane.showInputDialog(app.frame, "Name this place (for example \"Steve's base\" or \"Village\"):", "Mark a place", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        List<Base> l = bases();
        l.add(new Base(name.trim(), x, z));
        saveBases(l);
        live();
    }

    // ------------------------------------------------------------------ side panel

    private String sideKey = "";

    private void live() {
        if (!Game.supported()) return;
        view.repaint();
        Game.Snapshot s = Game.now;
        String key = s == null ? "off" : s.players().stream().map(p -> p.name() + p.env()).toList() + "|" + s.graves().size() + "|" + bases() + "|" + s.filling() + s.fillDone() / 50;
        if (key.equals(sideKey)) return;
        sideKey = key;
        side.removeAll();
        side.put(Ui.cardTitle("Players"));
        if (s == null) side.put(Ui.para(app.server.isRunning() ? "Waiting for the server..." : "The server is off. This is the map as it was last drawn.", Ui.font(13), Ui.MUTED));
        else if (s.players().isEmpty()) side.put(Ui.para("Nobody is online.", Ui.font(13), Ui.MUTED));
        else for (Game.Player p : s.players()) side.put(playerRow(p));

        side.put(Box.createVerticalStrut(4));
        side.put(Ui.row(8, Ui.cardTitle("Places"), Ui.glue(), new Ui.Btn("Spawn", Ui.Kind.GHOST).small().onClick(view::centerOnSpawn)));
        List<Base> bases = bases();
        if (bases.isEmpty()) side.put(Ui.para("Right-click the map to mark a base or any place.", Ui.font(12.5f), Ui.FAINT));
        for (Base b : bases) {
            JLabel l = Ui.label(b.name(), Ui.font(13.5f), Ui.TEXT);
            l.setIcon(flagIcon());
            l.setIconTextGap(8);
            side.put(clickRow(l, b.x() + ", " + b.z(), () -> view.centerOn(b.x(), b.z())));
        }
        if (s != null && !s.graves().isEmpty()) {
            side.put(Box.createVerticalStrut(4));
            side.put(Ui.cardTitle("Graves"));
            for (Game.GraveSum g : s.graves()) {
                if (!overworld(g.world(), s)) continue;
                JLabel l = Ui.label(Server.nice(g.owner()), Ui.font(13.5f), Ui.TEXT);
                l.setIcon(graveIcon());
                l.setIconTextGap(8);
                side.put(clickRow(l, g.items() + " items", () -> view.centerOn(g.x(), g.z())));
            }
        }
        side.put(Box.createVerticalStrut(6));
        side.put(Ui.cardTitle("Drawing the map"));
        side.put(Ui.para("Areas near players are drawn automatically. To add land people explored before, draw it once (it's slow on purpose so the game doesn't lag).",
                Ui.font(12.5f), Ui.FAINT));
        fill.setText(s != null && s.filling() ? "Stop drawing" : "Draw explored land");
        fill.setEnabled(s != null);
        fillNote.setText(s != null && s.filling() ? "Drawing... " + s.fillDone() + " of " + s.fillTotal() + " areas" : " ");
        side.put(Ui.row(8, fill, Ui.glue()));
        side.put(fillNote);
        side.revalidate();
        side.repaint();
    }

    static boolean overworld(String world, Game.Snapshot s) {
        Game.World w = s == null ? null : s.main();
        return w == null || w.name().equals(world);
    }

    private JComponent playerRow(Game.Player p) {
        JLabel l = Ui.label(p.nice(), Ui.font(13.5f), Ui.TEXT);
        l.setIcon(Ui.dot(p.bedrock() ? Ui.BLUE : Ui.GREEN_HI, 9));
        l.setIconTextGap(10);
        String where = p.env().equals("NETHER") ? "in the Nether" : p.env().equals("THE_END") ? "in the End" : (int) p.x() + ", " + (int) p.z();
        boolean here = !p.env().equals("NETHER") && !p.env().equals("THE_END");
        return clickRow(l, where, here ? () -> view.centerOn(p.x(), p.z()) : () -> PlayerTools.open(app, p.name()));
    }

    private static JComponent clickRow(JLabel l, String sub, Runnable click) {
        JPanel row = new JPanel(new BorderLayout(8, 0)) {
            boolean hover;
            {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) { click.run(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                if (!hover) return;
                Graphics2D g2 = (Graphics2D) g.create();
                Ui.aa(g2);
                g2.setColor(Ui.CARD_HI);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                g2.dispose();
            }
        };
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 8, 6, 8));
        row.add(l, BorderLayout.CENTER);
        row.add(Ui.label(sub, Ui.font(12), Ui.FAINT), BorderLayout.EAST);
        return row;
    }

    static Icon flagIcon() {
        return new Icon() {
            public int getIconWidth() { return 12; }
            public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics g, int x, int y) { drawFlag((Graphics2D) g, x + 2, y + 13, 1f); }
        };
    }

    static Icon graveIcon() {
        return new Icon() {
            public int getIconWidth() { return 12; }
            public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics g, int x, int y) { drawGrave((Graphics2D) g, x + 6, y + 7, 1f); }
        };
    }

    static void drawFlag(Graphics2D g, float x, float y, float s) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.aa(g2);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1.6f * s));
        g2.draw(new Line2D.Float(x, y, x, y - 12 * s));
        Path2D f = new Path2D.Float();
        f.moveTo(x, y - 12 * s); f.lineTo(x + 9 * s, y - 9.5 * s); f.lineTo(x, y - 7 * s); f.closePath();
        g2.setColor(new Color(0xFF7A45));
        g2.fill(f);
        g2.dispose();
    }

    static void drawGrave(Graphics2D g, float cx, float cy, float s) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.aa(g2);
        RoundRectangle2D r = new RoundRectangle2D.Float(cx - 5 * s, cy - 7 * s, 10 * s, 13 * s, 8 * s, 8 * s);
        g2.setColor(new Color(0xB8BEC6));
        g2.fill(r);
        g2.setColor(new Color(0x30353C));
        g2.setStroke(new BasicStroke(1.3f * s));
        g2.draw(new Line2D.Float(cx, cy - 4 * s, cx, cy + 3 * s));
        g2.draw(new Line2D.Float(cx - 2.5f * s, cy - 1.5f * s, cx + 2.5f * s, cy - 1.5f * s));
        g2.dispose();
    }

    // ------------------------------------------------------------------ the map itself

    final class MapView extends JComponent {
        final Map<String, BufferedImage> tiles = new HashMap<>();
        final Map<String, Long> tileTime = new HashMap<>();
        double cx, cz, scale = 1; // world block at the centre; screen pixels per block
        boolean centered;
        private Point drag;
        private double dragCx, dragCz;
        private Point mouse;

        MapView() {
            setOpaque(true);
            setBackground(new Color(0x0E1013));
            setToolTipText("");
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) { drag = e.getPoint(); dragCx = cx; dragCz = cz; }
                    if (e.isPopupTrigger()) popup(e);
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) popup(e);
                    if (drag != null && drag.distance(e.getPoint()) < 4 && SwingUtilities.isLeftMouseButton(e)) click(e.getPoint());
                    drag = null;
                    setCursor(Cursor.getDefaultCursor());
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (drag == null) return;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    cx = dragCx - (e.getX() - drag.x) / scale;
                    cz = dragCz - (e.getY() - drag.y) / scale;
                    centered = true;
                    reloadTiles();
                    repaint();
                }
                @Override public void mouseMoved(MouseEvent e) { mouse = e.getPoint(); repaint(); }
                @Override public void mouseExited(MouseEvent e) { mouse = null; repaint(); }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    double wx = toWorldX(e.getX()), wz = toWorldZ(e.getY());
                    double ns = Math.max(0.125, Math.min(8, scale * Math.pow(1.25, -e.getPreciseWheelRotation())));
                    scale = ns;
                    cx = wx - (e.getX() - getWidth() / 2.0) / scale;
                    cz = wz - (e.getY() - getHeight() / 2.0) / scale;
                    reloadTiles();
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        double toWorldX(int sx) { return cx + (sx - getWidth() / 2.0) / scale; }
        double toWorldZ(int sy) { return cz + (sy - getHeight() / 2.0) / scale; }
        double toScreenX(double wx) { return getWidth() / 2.0 + (wx - cx) * scale; }
        double toScreenY(double wz) { return getHeight() / 2.0 + (wz - cz) * scale; }

        void centerOn(double x, double z) { cx = x; cz = z; centered = true; reloadTiles(); repaint(); }

        void centerOnSpawn() {
            Game.Snapshot s = Game.now;
            Game.World w = s == null ? null : s.main();
            if (w != null) { centerOn(w.sx(), w.sz()); return; }
            centerOn(0, 0);
            centered = false;
        }

        Path tileDir() {
            Game.Snapshot s = Game.now;
            String world = s != null && s.main() != null ? s.main().name() : Kit.get("level-name-cache", "world");
            if (s != null && s.main() != null && !world.equals(Kit.get("level-name-cache", null))) Kit.set("level-name-cache", world);
            return Game.dir().resolve("map").resolve(world.replaceAll("[^A-Za-z0-9_.-]", "_"));
        }

        /** Loads the tiles in view that are new or changed on disk. */
        void reloadTiles() {
            if (!Kit.hasServer() || getWidth() == 0) return;
            Path dir = tileDir();
            int tx0 = (int) Math.floor(toWorldX(0) / 256), tx1 = (int) Math.floor(toWorldX(getWidth()) / 256);
            int tz0 = (int) Math.floor(toWorldZ(0) / 256), tz1 = (int) Math.floor(toWorldZ(getHeight()) / 256);
            if ((tx1 - tx0 + 1) * (tz1 - tz0 + 1) > 400) return; // zoomed far out: keep what's loaded
            boolean changed = false;
            for (int tx = tx0; tx <= tx1; tx++)
                for (int tz = tz0; tz <= tz1; tz++) {
                    String k = tx + "_" + tz;
                    Path f = dir.resolve(k + ".png");
                    try {
                        if (!Files.exists(f)) continue;
                        long m = Files.getLastModifiedTime(f).toMillis();
                        if (Objects.equals(tileTime.get(k), m)) continue;
                        BufferedImage img = ImageIO.read(f.toFile());
                        if (img != null) { tiles.put(k, img); tileTime.put(k, m); changed = true; }
                    } catch (Exception ignored) { }
                }
            if (tiles.size() > 600) { tiles.clear(); tileTime.clear(); }
            if (changed) repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int tx0 = (int) Math.floor(toWorldX(0) / 256), tx1 = (int) Math.floor(toWorldX(getWidth()) / 256);
            int tz0 = (int) Math.floor(toWorldZ(0) / 256), tz1 = (int) Math.floor(toWorldZ(getHeight()) / 256);
            for (int tx = tx0; tx <= tx1; tx++)
                for (int tz = tz0; tz <= tz1; tz++) {
                    BufferedImage img = tiles.get(tx + "_" + tz);
                    if (img == null) continue;
                    double x = toScreenX(tx * 256.0), y = toScreenY(tz * 256.0);
                    g.drawImage(img, (int) Math.floor(x), (int) Math.floor(y), (int) Math.ceil(256 * scale) + 1, (int) Math.ceil(256 * scale) + 1, null);
                }
            Ui.aa(g);
            if (tiles.isEmpty()) {
                g.setFont(Ui.font(14));
                g.setColor(Ui.MUTED);
                String t = app.server.isRunning() ? "The map fills in around players as they explore." : "Start the server to draw the map.";
                g.drawString(t, (getWidth() - g.getFontMetrics().stringWidth(t)) / 2, getHeight() / 2);
            }
            Game.Snapshot s = Game.now;
            // spawn
            Game.World w = s == null ? null : s.main();
            if (w != null) {
                double x = toScreenX(w.sx() + 0.5), y = toScreenY(w.sz() + 0.5);
                star(g, x, y, 7);
                label(g, "Spawn", x, y + 18, Ui.AMBER);
            }
            for (Base b : bases()) {
                double x = toScreenX(b.x() + 0.5), y = toScreenY(b.z() + 0.5);
                drawFlag(g, (float) x - 1, (float) y + 2, 1.4f);
                label(g, b.name(), x, y + 18, Color.WHITE);
            }
            if (s != null) {
                for (Game.GraveSum gr : s.graves()) {
                    if (!overworld(gr.world(), s)) continue;
                    drawGrave(g, (float) toScreenX(gr.x() + 0.5), (float) toScreenY(gr.z() + 0.5), 1.3f);
                }
                for (Game.Player p : s.players()) {
                    if (!overworld(p.world(), s)) continue;
                    double x = toScreenX(p.x()), y = toScreenY(p.z());
                    Color c = p.bedrock() ? Ui.BLUE : Ui.GREEN_HI;
                    // facing arrow (yaw 0 = south/+z, 90 = west/-x)
                    double a = Math.toRadians(p.yaw());
                    double dx = -Math.sin(a), dz = Math.cos(a);
                    Path2D arrow = new Path2D.Double();
                    arrow.moveTo(x + dx * 13, y + dz * 13);
                    arrow.lineTo(x + dz * 5, y - dx * 5);
                    arrow.lineTo(x - dz * 5, y + dx * 5);
                    arrow.closePath();
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 170));
                    g.fill(arrow);
                    g.setColor(Color.BLACK);
                    g.fill(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
                    g.setColor(c);
                    g.fill(new Ellipse2D.Double(x - 5.5, y - 5.5, 11, 11));
                    label(g, p.nice(), x, y - 14, Color.WHITE);
                }
            }
            // scale + coordinates
            g.setFont(Ui.font(12));
            String coords = mouse == null ? "" : "x " + (int) Math.floor(toWorldX(mouse.x)) + "   z " + (int) Math.floor(toWorldZ(mouse.y));
            String hint = "Drag to move · scroll to zoom · right-click for options";
            g.setColor(new Color(0, 0, 0, 150));
            g.fill(new RoundRectangle2D.Float(8, getHeight() - 30, Math.max(120, g.getFontMetrics().stringWidth(coords.isEmpty() ? hint : coords) + 20), 22, 8, 8));
            g.setColor(Ui.TEXT);
            g.drawString(coords.isEmpty() ? hint : coords, 18, getHeight() - 15);
            g.dispose();
        }

        private void label(Graphics2D g, String text, double x, double y, Color c) {
            g.setFont(Ui.bold(12));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text);
            g.setColor(new Color(0, 0, 0, 160));
            g.fill(new RoundRectangle2D.Double(x - w / 2.0 - 5, y - fm.getAscent() + 1, w + 10, fm.getHeight(), 8, 8));
            g.setColor(c);
            g.drawString(text, (float) (x - w / 2.0), (float) y + 1);
        }

        private void star(Graphics2D g, double x, double y, double r) {
            Path2D p = new Path2D.Double();
            for (int i = 0; i < 10; i++) {
                double a = Math.PI / 2 + i * Math.PI / 5, rr = i % 2 == 0 ? r : r * 0.45;
                double px = x + Math.cos(a) * rr, py = y - Math.sin(a) * rr;
                if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
            }
            p.closePath();
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2.5f));
            g.draw(p);
            g.setColor(Ui.AMBER);
            g.fill(p);
        }

        /** What's under the mouse: a player, grave or base. */
        Object hit(Point pt) {
            Game.Snapshot s = Game.now;
            if (s != null) {
                for (Game.Player p : s.players())
                    if (overworld(p.world(), s) && pt.distance(toScreenX(p.x()), toScreenY(p.z())) < 10) return p;
                for (Game.GraveSum gr : s.graves())
                    if (overworld(gr.world(), s) && pt.distance(toScreenX(gr.x() + 0.5), toScreenY(gr.z() + 0.5)) < 10) return gr;
            }
            for (Base b : bases()) if (pt.distance(toScreenX(b.x() + 0.5), toScreenY(b.z() - 3)) < 12) return b;
            return null;
        }

        @Override public String getToolTipText(MouseEvent e) {
            Object h = hit(e.getPoint());
            if (h instanceof Game.Player p) return "<html><b>" + PlayerTools.esc(p.nice()) + "</b><br>Health " + Math.round(p.health()) + "/" + Math.round(p.maxHealth())
                    + " · Food " + p.food() + " · Level " + p.level() + "<br>" + (int) p.x() + " " + (int) p.y() + " " + (int) p.z() + "<br><i>Click to manage</i></html>";
            if (h instanceof Game.GraveSum gr) return "<html><b>" + PlayerTools.esc(Server.nice(gr.owner())) + "'s grave</b><br>" + gr.items() + " items at "
                    + gr.x() + " " + gr.y() + " " + gr.z() + "<br><i>Right-click to give the items back</i></html>";
            if (h instanceof Base b) return "<html><b>" + PlayerTools.esc(b.name()) + "</b><br>" + b.x() + ", " + b.z() + "</html>";
            return null;
        }

        void click(Point pt) {
            if (hit(pt) instanceof Game.Player p) PlayerTools.open(app, p.name());
        }

        void popup(MouseEvent e) {
            int x = (int) Math.floor(toWorldX(e.getX())), z = (int) Math.floor(toWorldZ(e.getY()));
            JPopupMenu m = new JPopupMenu();
            Object h = hit(e.getPoint());
            Game.Snapshot s = Game.now;
            if (h instanceof Game.Player p) {
                PlayerTools.add(m, "Manage " + p.nice() + "...", () -> PlayerTools.open(app, p.name()));
                m.addSeparator();
            } else if (h instanceof Game.GraveSum gr) {
                PlayerTools.add(m, "Give " + Server.nice(gr.owner()) + " their items back", () -> WorldPage.giveBack(app, gr.id(), gr.owner()));
                m.addSeparator();
            } else if (h instanceof Base b) {
                PlayerTools.add(m, "Remove \"" + b.name() + "\"", () -> { List<Base> l = bases(); l.remove(b); saveBases(l); live(); });
                m.addSeparator();
            }
            String world = s != null && s.main() != null ? s.main().name() : null;
            String what = h instanceof Base b ? b.name() : x + ", " + z;
            if (s != null && !s.players().isEmpty()) {
                JMenu tp = new JMenu("Teleport here");
                tp.setFont(Ui.font(13.5f));
                for (Game.Player p : s.players()) {
                    JMenuItem it = new JMenuItem(p.nice());
                    it.setFont(Ui.font(13.5f));
                    it.addActionListener(ev -> PlayerTools.toPlace(p.name(), x, z, world, what));
                    tp.add(it);
                }
                m.add(tp);
            }
            PlayerTools.add(m, "Mark a place here...", () -> addBase(x, z));
            PlayerTools.add(m, "Copy coordinates (" + x + ", " + z + ")", () ->
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(x + " ~ " + z), null));
            m.show(this, e.getX(), e.getY());
        }
    }
}
