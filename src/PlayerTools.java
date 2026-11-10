// Things you can do to one player with clicks: see their inventory and ender chest, give them items, move them.
// Opened from the Players page ("Manage") and by clicking a player on the Map.

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

final class PlayerTools {
    private PlayerTools() { }

    private static final Map<String, JDialog> open = new HashMap<>();

    // ------------------------------------------------------------------ who "me" is

    /** The owner's own player name (for "Bring to me"), asked once and remembered. Null if they cancel. */
    static String me(App app, boolean askAgain) {
        String saved = Kit.get("app.me", null);
        if (saved != null && !askAgain) return saved;
        List<String> names = new ArrayList<>(app.server.online);
        JDialog d = new JDialog(app.frame, "Which player is you?", Dialog.ModalityType.APPLICATION_MODAL);
        d.setIconImages(Ui.appIcons());
        Ui.Field f = new Ui.Field("Your Minecraft name", 18);
        if (saved != null) f.setText(saved);
        String[] out = new String[1];
        Ui.Stack s = new Ui.Stack(12);
        s.put(Ui.label("Which player is you?", Ui.bold(18), Ui.TEXT));
        s.put(Ui.para("So \"Bring to me\" and \"Take me there\" know who you are. Pick yourself or type your name.", Ui.font(13.5f), Ui.MUTED));
        JPanel picks = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        picks.setOpaque(false);
        for (String n : names) picks.add(new Ui.Btn(Server.nice(n), Ui.Kind.SECONDARY).small().onClick(() -> { out[0] = n; d.dispose(); }));
        if (!names.isEmpty()) s.put(picks);
        s.put(f);
        Ui.Btn ok = new Ui.Btn("Save", Ui.Kind.PRIMARY).onClick(() -> { out[0] = f.getText().trim(); d.dispose(); });
        f.addActionListener(e -> ok.doClick());
        s.put(Ui.row(10, Ui.glue(), new Ui.Btn("Cancel", Ui.Kind.SECONDARY).onClick(d::dispose), ok));
        d.setContentPane(dialogBody(s, 440));
        d.pack();
        d.setLocationRelativeTo(app.frame);
        d.setVisible(true);
        if (out[0] == null || out[0].isBlank()) return null;
        Kit.set("app.me", out[0]);
        return out[0];
    }

    static JPanel dialogBody(JComponent content, int width) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Ui.CARD);
        p.setBorder(new EmptyBorder(22, 24, 20, 24));
        p.add(content, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(width, p.getPreferredSize().height));
        return p;
    }

    // ------------------------------------------------------------------ moving players

    static void bringToMe(App app, String player) {
        String me = me(app, false);
        if (me == null) return;
        if (me.equalsIgnoreCase(player)) { Dialogs.info(app.frame, "That's you", "Pick someone else to bring to you."); return; }
        Game.run("tp " + player + " " + me, "Brought " + Server.nice(player) + " to " + Server.nice(me) + ".");
    }

    static void takeMeTo(App app, String player) {
        String me = me(app, false);
        if (me == null) return;
        Game.run("tp " + me + " " + player, "Took " + Server.nice(me) + " to " + Server.nice(player) + ".");
    }

    static void toSpawn(String player) {
        Game.run("xw spawn " + player, "Sent " + Server.nice(player) + " to spawn.");
    }

    static void toPlayer(String player, String target) {
        Game.run("tp " + player + " " + target, "Sent " + Server.nice(player) + " to " + Server.nice(target) + ".");
    }

    static void toPlace(String player, int x, int z, String world, String what) {
        Game.run("xw tp " + player + " " + x + " " + z + (world == null ? "" : " " + world),
                "Sent " + Server.nice(player) + " to " + (what != null ? what : x + ", " + z) + ".");
    }

    /** A menu of everywhere this player can be sent: you, spawn, other players. */
    static JPopupMenu sendMenu(App app, String player) {
        JPopupMenu m = new JPopupMenu();
        add(m, "Bring to me", () -> bringToMe(app, player));
        add(m, "Take me to them", () -> takeMeTo(app, player));
        if (Game.supported()) add(m, "Send to spawn", () -> toSpawn(player));
        List<String> others = new ArrayList<>();
        for (String n : app.server.online) if (!n.equalsIgnoreCase(player)) others.add(n);
        if (!others.isEmpty()) {
            m.addSeparator();
            for (String n : others) add(m, "Send to " + Server.nice(n), () -> toPlayer(player, n));
        }
        m.addSeparator();
        add(m, "Change who \"me\" is...", () -> me(app, true));
        return m;
    }

    static void add(JPopupMenu m, String text, Runnable r) {
        JMenuItem it = new JMenuItem(text);
        it.setFont(Ui.font(13.5f));
        it.addActionListener(e -> r.run());
        m.add(it);
    }

    // ------------------------------------------------------------------ the player window

    static void open(App app, String player) {
        String key = player.toLowerCase(Locale.ROOT);
        JDialog existing = open.get(key);
        if (existing != null && existing.isDisplayable()) { existing.toFront(); return; }
        JDialog d = new JDialog(app.frame, Server.nice(player), Dialog.ModalityType.MODELESS);
        d.setIconImages(Ui.appIcons());
        open.put(key, d);
        PlayerView v = new PlayerView(app, player, d);
        d.setContentPane(v.root);
        d.setSize(new Dimension(640, 640));
        d.setMinimumSize(new Dimension(560, 520));
        d.setLocationRelativeTo(app.frame);
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { v.timer.stop(); Game.listeners.remove(v.onLive); Items.listeners.remove(v.onIcons); open.remove(key); }
        });
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setVisible(true);
        v.load();
    }

    static final class PlayerView {
        final JPanel root = new JPanel(new BorderLayout());
        final App app;
        final String player;
        final JLabel status = Ui.label(" ", Ui.font(13), Ui.MUTED);
        final JLabel note = Ui.label(" ", Ui.font(12.5f), Ui.FAINT);
        final JPanel invHolder = new JPanel(new BorderLayout());
        final JPanel valuables = new JPanel(new ModsPage.Wrap(6, 4));
        final javax.swing.Timer timer;
        final Runnable onLive, onIcons;
        Game.Inventory inv;
        boolean ender;

        PlayerView(App app, String player, JDialog d) {
            this.app = app;
            this.player = player;
            root.setBackground(Ui.BG);
            root.setBorder(new EmptyBorder(18, 20, 18, 20));

            JLabel name = Ui.label(Server.nice(player), Ui.bold(20), Ui.TEXT);
            Ui.Btn give = new Ui.Btn("Give items", Ui.Kind.PRIMARY).small().onClick(() -> giveDialog(app, player));
            Ui.Btn send = new Ui.Btn("Teleport", Ui.Kind.SECONDARY).small();
            send.onClick(() -> sendMenu(app, player).show(send, 0, send.getHeight() + 4));
            Ui.Btn kick = new Ui.Btn("Kick", Ui.Kind.SUBTLE).small().onClick(() -> {
                if (Dialogs.confirm(app.frame, "Kick " + Server.nice(player) + "?", "They can rejoin right away.", "Kick", Ui.Kind.DANGER))
                    Game.run("kick " + player, "Kicked " + Server.nice(player) + ".");
            });
            Ui.Stack head = new Ui.Stack(6);
            head.put(Ui.row(10, name, Ui.glue(), kick, send, give));
            head.put(status);

            Ui.Choice which = new Ui.Choice(List.of("Inventory", "Ender chest"), "Inventory");
            which.onChange(v -> { ender = v.startsWith("Ender"); showInv(); });
            Ui.Btn refresh = new Ui.Btn("Refresh", Ui.Kind.GHOST).small().onClick(this::load);
            valuables.setOpaque(false);
            invHolder.setOpaque(false);

            Ui.Card card = new Ui.Card();
            Ui.Stack cs = new Ui.Stack(12);
            cs.put(Ui.row(10, which, Ui.glue(), note, refresh));
            cs.put(invHolder);
            cs.put(valuables);
            card.add(cs);

            Ui.Stack page = new Ui.Stack(16);
            page.put(head);
            page.put(card);
            root.add(page, BorderLayout.NORTH);

            onLive = () -> SwingUtilities.invokeLater(this::refreshStatus);
            onIcons = () -> SwingUtilities.invokeLater(invHolder::repaint);
            Game.listeners.add(onLive);
            Items.listeners.add(onIcons);
            timer = new javax.swing.Timer(10_000, e -> load());
            timer.start();
            refreshStatus();
            if (!Game.supported()) {
                invHolder.add(Ui.para("Seeing inventories works on survival (Paper) servers. On a modded server you can still give items and teleport.", Ui.font(13.5f), Ui.MUTED));
                which.setVisible(false);
                refresh.setVisible(false);
            }
        }

        void refreshStatus() {
            Game.Snapshot s = Game.now;
            Game.Player p = s == null ? null : s.player(player);
            if (p == null) {
                status.setText(app.server.online.contains(player) || app.server.online.contains(player.replaceFirst("^\\.", "")) ? "Online" : "Not online right now");
                return;
            }
            String where = p.env().equals("NETHER") ? " in the Nether" : p.env().equals("THE_END") ? " in the End" : "";
            status.setText(String.format(Locale.ROOT, "Health %.0f/%.0f   ·   Food %d/20   ·   Level %d   ·   Ping %d ms   ·   at %d %d %d%s", p.health(), p.maxHealth(),
                    p.food(), p.level(), p.ping(), (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()), where)
                    + (p.mode().equals("SURVIVAL") ? "" : "   ·   " + p.mode().charAt(0) + p.mode().substring(1).toLowerCase(Locale.ROOT)));
        }

        void load() {
            if (!Game.supported()) return;
            note.setText("Loading...");
            Game.requestInventory(player, i -> SwingUtilities.invokeLater(() -> {
                if (i == null) { note.setText(app.server.isRunning() ? "Couldn't see their inventory (are they online?)" : "The server is off"); return; }
                inv = i;
                note.setText("Updated " + App.CLOCK.format(java.time.LocalTime.now()));
                showInv();
            }));
        }

        void showInv() {
            invHolder.removeAll();
            valuables.removeAll();
            if (inv == null) { invHolder.revalidate(); return; }
            List<Game.Item> items = ender ? inv.enderChest() : inv.inventory();
            invHolder.add(ender ? grid(items, 0, 27, 9) : playerGrid(items), BorderLayout.CENTER);
            Map<String, Integer> v = valuables(ender ? inv.enderChest() : concat(inv.inventory(), inv.enderChest()));
            if (!v.isEmpty()) {
                valuables.add(Ui.label(ender ? "In the ender chest:" : "Valuables (inventory + ender chest, including shulker boxes):", Ui.font(12.5f), Ui.FAINT));
                for (var e : v.entrySet()) valuables.add(Ui.tag(e.getKey() + ": " + e.getValue(), e.getKey().startsWith("Diamond") ? new Color(0x5FE0D8) : e.getKey().startsWith("Netherite") ? new Color(0xB59A9A) : Ui.GREEN_HI));
            }
            root.revalidate();
            root.repaint();
        }
    }

    static List<Game.Item> concat(List<Game.Item> a, List<Game.Item> b) { List<Game.Item> l = new ArrayList<>(a); l.addAll(b); return l; }

    /** Totals of the things people X-ray for, counting what's inside shulker boxes and bundles, and blocks as 9. */
    static Map<String, Integer> valuables(List<Game.Item> items) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<String, Object[]> what = new LinkedHashMap<>();
        what.put("diamond", new Object[]{"Diamonds", 1});
        what.put("diamond_block", new Object[]{"Diamonds", 9});
        what.put("diamond_ore", new Object[]{"Diamonds", 1});
        what.put("deepslate_diamond_ore", new Object[]{"Diamonds", 1});
        what.put("netherite_ingot", new Object[]{"Netherite ingots", 1});
        what.put("netherite_block", new Object[]{"Netherite ingots", 9});
        what.put("ancient_debris", new Object[]{"Ancient debris", 1});
        what.put("netherite_scrap", new Object[]{"Netherite scrap", 1});
        what.put("emerald", new Object[]{"Emeralds", 1});
        what.put("emerald_block", new Object[]{"Emeralds", 9});
        count(items, what, out);
        return out;
    }

    private static void count(List<Game.Item> items, Map<String, Object[]> what, Map<String, Integer> out) {
        if (items == null) return;
        for (Game.Item i : items) {
            if (i == null) continue;
            Object[] w = what.get(i.id());
            if (w != null) out.merge((String) w[0], i.count() * (Integer) w[1], Integer::sum);
            count(i.inside(), what, out);
        }
    }

    /** The player's inventory laid out like the game: armor and off-hand, the 27 main slots, then the hotbar. */
    static JComponent playerGrid(List<Game.Item> items) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setOpaque(false);
        JPanel armor = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        armor.setOpaque(false);
        int[] order = {39, 38, 37, 36};
        String[] names = {"Helmet", "Chestplate", "Leggings", "Boots"};
        for (int i = 0; i < 4; i++) armor.add(new Slot(at(items, order[i]), names[i]));
        armor.add(Box.createHorizontalStrut(16));
        armor.add(new Slot(at(items, 40), "Off-hand"));
        p.add(armor, BorderLayout.NORTH);
        p.add(grid(items, 9, 27, 9), BorderLayout.CENTER);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(6, 0, 0, 0));
        bar.add(grid(items, 0, 9, 9), BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    static Game.Item at(List<Game.Item> l, int i) { return i < l.size() ? l.get(i) : null; }

    static JComponent grid(List<Game.Item> items, int from, int count, int cols) {
        JPanel g = new JPanel(new GridLayout(0, cols, 4, 4));
        g.setOpaque(false);
        for (int i = from; i < from + count; i++) g.add(new Slot(at(items, i), null));
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        wrap.add(g);
        return wrap;
    }

    /** One inventory slot: the item's picture, how many, a durability bar, and a purple tint for enchanted things. */
    static final class Slot extends JComponent {
        final Game.Item item;
        final String empty;

        Slot(Game.Item item, String emptyLabel) {
            this.item = item;
            this.empty = emptyLabel;
            setToolTipText(item == null ? emptyLabel : tooltip(item));
            ToolTipManager.sharedInstance().setInitialDelay(150);
        }

        @Override public Dimension getPreferredSize() { return new Dimension(48, 48); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            g.setColor(new Color(0x181C21));
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8));
            g.setColor(Ui.LINE);
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 8, 8));
            if (item == null) { g.dispose(); return; }
            BufferedImage icon = Items.icon(item.id());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(icon, 8, 8, 32, 32, null);
            if (!item.ench().isEmpty()) {
                g.setColor(new Color(170, 90, 255, 46));
                g.fill(new RoundRectangle2D.Float(2, 2, getWidth() - 4, getHeight() - 4, 7, 7));
            }
            if (item.maxDamage() > 0 && item.damage() > 0) {
                double left = 1 - item.damage() / (double) item.maxDamage();
                g.setColor(Color.BLACK);
                g.fillRect(8, 41, 32, 3);
                g.setColor(Color.getHSBColor((float) (left / 3), 1f, 0.9f));
                g.fillRect(8, 41, (int) Math.round(32 * left), 2);
            }
            if (item.count() > 1) {
                String n = String.valueOf(item.count());
                g.setFont(Ui.bold(12.5f));
                FontMetrics fm = g.getFontMetrics();
                int x = getWidth() - fm.stringWidth(n) - 4, y = getHeight() - 5;
                g.setColor(new Color(0, 0, 0, 200));
                g.drawString(n, x + 1, y + 1);
                g.setColor(Color.WHITE);
                g.drawString(n, x, y);
            }
            if (item.inside() != null && item.inside().stream().anyMatch(Objects::nonNull)) {
                g.setColor(Ui.AMBER);
                g.fillOval(getWidth() - 10, 4, 6, 6);
            }
            g.dispose();
        }
    }

    static String tooltip(Game.Item i) {
        StringBuilder b = new StringBuilder("<html><b>").append(esc(i.label())).append("</b>");
        if (i.count() > 1) b.append(" &times; ").append(i.count());
        if (i.name() != null) b.append("<br><span style='color:#9BA3AE'>").append(esc(Items.nameOf(i.id()))).append("</span>");
        for (var e : i.ench().entrySet()) b.append("<br><span style='color:#B9A0FF'>").append(esc(Items.enchantName(e.getKey()))).append(' ').append(Items.roman(e.getValue())).append("</span>");
        if (i.maxDamage() > 0 && i.damage() > 0) b.append("<br>Durability ").append(i.maxDamage() - i.damage()).append(" / ").append(i.maxDamage());
        if (i.inside() != null) {
            Map<String, Integer> totals = new TreeMap<>();
            for (Game.Item x : i.inside()) if (x != null) totals.merge(x.label(), x.count(), Integer::sum);
            b.append("<br><br><b>Inside:</b>");
            if (totals.isEmpty()) b.append(" nothing");
            for (var e : totals.entrySet()) b.append("<br>").append(esc(e.getKey())).append(" &times; ").append(e.getValue());
        }
        return b.append("</html>").toString();
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    // ------------------------------------------------------------------ giving items

    static void giveDialog(App app, String preselect) {
        JDialog d = new JDialog(app.frame, "Give items", Dialog.ModalityType.MODELESS);
        d.setIconImages(Ui.appIcons());
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Ui.BG);
        root.setBorder(new EmptyBorder(18, 20, 18, 20));

        Ui.Field search = new Ui.Field("Search items (diamond, bed, rocket...)", 22);
        List<String> all = Items.all();
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(-1);
        list.setFixedCellWidth(104);
        list.setFixedCellHeight(78);
        list.setBackground(Ui.CARD);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ListCellRenderer<>() {
            final ItemCell cell = new ItemCell();
            public Component getListCellRendererComponent(JList<? extends String> l, String id, int i, boolean sel, boolean focus) {
                cell.id = id;
                cell.selected = sel;
                cell.setToolTipText(Items.nameOf(id));
                return cell;
            }
        });
        Runnable filter = () -> {
            String q = search.getText().trim().toLowerCase(Locale.ROOT);
            model.clear();
            List<String> hits = new ArrayList<>();
            for (String id : all) {
                if (q.isEmpty() || id.contains(q.replace(' ', '_')) || Items.nameOf(id).toLowerCase(Locale.ROOT).contains(q)) hits.add(id);
            }
            if (q.isEmpty()) hits.sort(Comparator.comparingInt(id -> { int i = Items.BASIC.indexOf(id); return i < 0 ? 10_000 : i; }));
            else hits.sort(Comparator.comparingInt((String id) -> searchRank(id, q)).thenComparingInt(String::length));
            model.addAll(hits);
            if (!hits.isEmpty()) list.setSelectedIndex(0);
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter.run(); }
            public void removeUpdate(DocumentEvent e) { filter.run(); }
            public void changedUpdate(DocumentEvent e) { filter.run(); }
        });
        filter.run();
        Runnable repaint = () -> SwingUtilities.invokeLater(list::repaint);
        Items.listeners.add(repaint);
        JScrollPane sp = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createLineBorder(Ui.LINE));
        Ui.styleScroll(sp);
        sp.getViewport().setBackground(Ui.CARD);

        List<String> online = new ArrayList<>(app.server.online);
        String pre = preselect;
        if (pre == null && !online.isEmpty()) pre = online.get(0);
        List<String> shown = new ArrayList<>();
        for (String n : online) shown.add(Server.nice(n));
        if (pre != null && !online.contains(pre)) { online.add(0, pre); shown.add(0, Server.nice(pre)); }
        Ui.Choice who = new Ui.Choice(shown.isEmpty() ? List.of("Nobody online") : shown, pre == null ? "Nobody online" : Server.nice(pre));
        Ui.Choice amount = new Ui.Choice(List.of("1", "8", "16", "32", "64", "128", "256"), "1");
        JLabel picked = Ui.label(" ", Ui.bold(14), Ui.TEXT);
        list.addListSelectionListener(e -> { String id = list.getSelectedValue(); picked.setText(id == null ? " " : Items.nameOf(id)); });
        JLabel done = Ui.label(" ", Ui.font(13), Ui.GREEN_HI);
        Ui.Btn give = new Ui.Btn("Give", Ui.Kind.PRIMARY);
        give.onClick(() -> {
            String id = list.getSelectedValue();
            int wi = shown.indexOf(who.value());
            if (id == null || wi < 0) return;
            String target = online.get(wi);
            int n = Integer.parseInt(amount.value());
            if (Game.run("give " + target + " minecraft:" + id + " " + n, "Gave " + Server.nice(target) + " " + n + " × " + Items.nameOf(id) + ".")) {
                done.setForeground(Ui.GREEN_HI);
                done.setText("Gave " + n + " × " + Items.nameOf(id));
            } else {
                done.setForeground(Ui.AMBER);
                done.setText("Start the server first");
            }
        });
        give.setEnabled(!online.isEmpty());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) give.doClick(); }
        });
        search.addActionListener(e -> give.doClick());

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(search, BorderLayout.NORTH);
        if (!Items.haveGame())
            top.add(Ui.para("Item pictures come from Minecraft on this PC. Open Minecraft " + (Kit.hasServer() ? Kit.mcVersion() : "") + " once in the launcher to see them.",
                    Ui.font(12.5f), Ui.FAINT), BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(Ui.row(10, picked, Ui.glue(), done), BorderLayout.NORTH);
        bottom.add(Ui.row(10, Ui.label("Give to", Ui.font(13.5f), Ui.MUTED), who, Ui.label("How many", Ui.font(13.5f), Ui.MUTED), amount, Ui.glue(),
                new Ui.Btn("Close", Ui.Kind.SECONDARY).onClick(d::dispose), give), BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setSize(new Dimension(720, 600));
        d.setMinimumSize(new Dimension(560, 460));
        d.setLocationRelativeTo(app.frame);
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { Items.listeners.remove(repaint); }
        });
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setVisible(true);
        search.requestFocusInWindow();
    }

    /** Best matches first: the exact item, then names starting with it, then a word starting with it, then anything containing it. */
    static int searchRank(String id, String q) {
        String name = Items.nameOf(id).toLowerCase(Locale.ROOT), qi = q.replace(' ', '_');
        if (id.equals(qi) || name.equals(q)) return 0;
        if (id.startsWith(qi) || name.startsWith(q)) return 1;
        if (("_" + id).contains("_" + qi) || (" " + name).contains(" " + q)) return 2;
        return 3;
    }

    /** One tile in the item picker: picture with the name under it. */
    static final class ItemCell extends JComponent {
        String id;
        boolean selected;

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            if (selected) {
                g.setColor(new Color(0x2C3A2C));
                g.fill(new RoundRectangle2D.Float(3, 3, getWidth() - 6, getHeight() - 6, 10, 10));
                g.setColor(Ui.GREEN);
                g.draw(new RoundRectangle2D.Float(3.5f, 3.5f, getWidth() - 7, getHeight() - 7, 10, 10));
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(Items.icon(id), (getWidth() - 32) / 2, 10, 32, 32, null);
            g.setFont(Ui.font(11.5f));
            g.setColor(selected ? Ui.TEXT : Ui.MUTED);
            String n = Items.nameOf(id);
            FontMetrics fm = g.getFontMetrics();
            while (fm.stringWidth(n) > getWidth() - 10 && n.length() > 4) n = n.substring(0, n.length() - 2);
            if (!n.equals(Items.nameOf(id))) n = n.substring(0, n.length() - 1) + "…";
            g.drawString(n, (getWidth() - fm.stringWidth(n)) / 2, 60);
            g.dispose();
        }
    }
}
