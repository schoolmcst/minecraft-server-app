// The Mods page for modded (Fabric) servers: discover mods by category from Modrinth or CurseForge, read about them
// (description and pictures), install them, and manage the installed ones.

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

final class ModsPage {
    final JPanel root = new JPanel(new BorderLayout());
    private final App app;

    static final ModSource MODRINTH = new ModSource.Modrinth();
    static final ModSource CURSEFORGE = new ModSource.CurseForge();

    private ModSource source = Kit.get("app.mods.source", "Modrinth").equals("CurseForge") ? CURSEFORGE : MODRINTH;
    private boolean showInstalled;
    private String category;          // null = all
    private String sort;
    private int offset, total, gen;
    private String lastMc = "";
    private final List<ModSource.Hit> hits = new ArrayList<>();

    private final JPanel body = new JPanel(new BorderLayout());
    private final Ui.Field query = new Ui.Field("Search mods, or pick a category below", 26);
    private final Ui.Btn search = new Ui.Btn("Search", Ui.Kind.PRIMARY);
    private final Ui.Choice sortChoice = new Ui.Choice(List.of("Most downloaded"), "Most downloaded");
    private final JPanel chips = new JPanel(new Wrap(8, 8));
    private final JPanel grid = new JPanel(new Grid(290, 12));
    private final JTextArea note = Ui.para(" ", Ui.font(13), Ui.MUTED);
    private final Ui.Btn more = new Ui.Btn("Show more", Ui.Kind.SECONDARY);
    private final Ui.Stack installed = new Ui.Stack(8);

    ModsPage(App app) {
        this.app = app;
        root.setBackground(Ui.BG);
        root.setBorder(new EmptyBorder(4, 32, 32, 32));
        body.setOpaque(false);
        chips.setOpaque(false);
        grid.setOpaque(false);
        root.add(body, BorderLayout.NORTH);

        query.setMaximumSize(new Dimension(460, 44));
        query.addActionListener(e -> newSearch());
        search.addActionListener(e -> newSearch());
        sortChoice.onChange(v -> { sort = v; newSearch(); });
        more.addActionListener(e -> loadPage(false));
        sort = source.sorts().get(0);
        Thread trim = new Thread(Images::trimCache, "image-cache");
        trim.setDaemon(true);
        trim.start();
    }

    private String mc() {
        String v = Kit.mcVersion();
        return v.isEmpty() ? Kit.get("mc.target", "") : v;
    }

    /** Called when the page opens. */
    void refresh() {
        if (!Kit.hasServer() || !Kit.isFabric()) return;
        if (!mc().equals(lastMc)) { hits.clear(); lastMc = mc(); }
        showBrowse();
    }

    // ================================================================== browse

    private void showBrowse() {
        try {
            showBrowse0();
        } catch (RuntimeException e) {
            e.printStackTrace();
            body.removeAll();
            body.add(Ui.para("Something went wrong showing mods: " + e + "\nTell Claude: it's written in app-log.txt.", Ui.font(13.5f), Ui.RED_HI), BorderLayout.NORTH);
            body.revalidate();
            body.repaint();
        }
    }

    private void showBrowse0() {
        body.removeAll();
        Ui.Stack s = new Ui.Stack(14);
        s.put(topBar());
        if (showInstalled) {
            s.put(installedCard());
        } else if (source == CURSEFORGE && !ModSource.CurseForge.ready()) {
            s.put(curseForgeSetup());
        } else {
            sortChoice.setOptions(source.sorts());
            if (!source.sorts().contains(sort)) sort = source.sorts().get(0);
            sortChoice.setValue(sort);
            query.placeholder = "Search " + source.name() + ", or pick a category below";
            s.put(Ui.row(10, query, search, sortChoice, Ui.glue()));
            s.put(chips);
            s.put(note);
            s.put(grid);
            s.put(Ui.row(0, Ui.glue(), more, Ui.glue()));
            loadCategories();
            if (hits.isEmpty()) newSearch(); else renderGrid();
        }
        body.add(s, BorderLayout.NORTH);
        body.revalidate();
        body.repaint();
        scrollTop();
    }

    private JComponent topBar() {
        long n = Mods.installed().size();
        Ui.Btn discover = seg("Discover", !showInstalled, () -> { showInstalled = false; showBrowse(); });
        Ui.Btn mine = seg("Installed (" + n + ")", showInstalled, () -> { showInstalled = true; showBrowse(); });
        List<Component> parts = new ArrayList<>(List.of(discover, mine, Ui.glue()));
        if (!showInstalled) {
            parts.add(Ui.label("Mods from", Ui.font(13), Ui.MUTED));
            parts.add(seg("Modrinth", source == MODRINTH, () -> switchSource(MODRINTH)));
            parts.add(seg("CurseForge", source == CURSEFORGE, () -> switchSource(CURSEFORGE)));
            if (source == CURSEFORGE && ModSource.CurseForge.ready())
                parts.add(new Ui.Btn("Key", Ui.Kind.GHOST).small().onClick(this::changeKey));
        }
        return Ui.row(6, parts.toArray(new Component[0]));
    }

    private static Ui.Btn seg(String text, boolean on, Runnable r) {
        Ui.Btn b = new Ui.Btn(text, on ? Ui.Kind.SECONDARY : Ui.Kind.SUBTLE).small();
        b.onClick(r);
        return b;
    }

    private void switchSource(ModSource s) {
        if (s == source) return;
        source = s;
        Kit.set("app.mods.source", s.name());
        category = null;
        sort = s.sorts().get(0);
        hits.clear();
        chips.removeAll();
        showBrowse();
    }

    private void loadCategories() {
        if (chips.getComponentCount() > 0) { paintChips(null); return; }
        ModSource s = source;
        new Thread(() -> {
            try {
                LinkedHashMap<String, String> c = s.categories();
                SwingUtilities.invokeLater(() -> { if (s == source) paintChips(c); });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> { if (s == source) note.setText("Couldn't load categories: " + Kit.describe(e)); });
            }
        }, "mod-categories").start();
    }

    private LinkedHashMap<String, String> lastCats = new LinkedHashMap<>();

    private void paintChips(LinkedHashMap<String, String> c) {
        if (c != null) lastCats = c;
        chips.removeAll();
        chips.add(chip("All", null));
        for (var e : lastCats.entrySet()) chips.add(chip(e.getValue(), e.getKey()));
        chips.revalidate();
        chips.repaint();
    }

    private Ui.Btn chip(String text, String id) {
        boolean on = Objects.equals(id, category);
        Ui.Btn b = new Ui.Btn(text, on ? Ui.Kind.PRIMARY : Ui.Kind.SECONDARY).small();
        b.onClick(() -> {
            category = id;
            paintChips(null);
            newSearch();
        });
        return b;
    }

    private void newSearch() {
        hits.clear();
        offset = 0;
        grid.removeAll();
        grid.revalidate();
        grid.repaint();
        loadPage(true);
    }

    private void loadPage(boolean fresh) {
        String mc = mc();
        if (mc.isEmpty()) { note.setText("Start the server once first, so the app knows its Minecraft version."); more.setVisible(false); return; }
        int my = ++gen;
        ModSource s = source;
        String q = query.getText().trim(), cat = category, so = sort;
        int off = fresh ? 0 : offset;
        note.setForeground(Ui.MUTED);
        note.setText(fresh ? "Loading mods..." : "Loading more...");
        search.setEnabled(false);
        more.setEnabled(false);
        new Thread(() -> {
            try {
                ModSource.Page p = s.search(q, cat, so, mc, off);
                SwingUtilities.invokeLater(() -> {
                    if (my != gen) return;
                    search.setEnabled(true);
                    more.setEnabled(true);
                    hits.addAll(p.hits());
                    offset = off + p.hits().size();
                    total = p.total();
                    renderGrid();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (my != gen) return;
                    search.setEnabled(true);
                    more.setEnabled(true);
                    note.setForeground(Ui.RED_HI);
                    note.setText("Couldn't load mods from " + s.name() + ": " + Kit.describe(e));
                });
            }
        }, "mod-search").start();
    }

    private void renderGrid() {
        String mc = mc();
        String where = category == null ? "" : " in " + lastCats.getOrDefault(category, ModSource.pretty(category));
        note.setForeground(Ui.MUTED);
        note.setText(hits.isEmpty() ? "No mods found" + where + " for Minecraft " + mc + "."
                : String.format("%,d mods%s for Minecraft %s. Click one to see what it does.", total, where, mc));
        Set<String> have = installedKeys();
        grid.removeAll();
        for (ModSource.Hit h : hits) grid.add(modCard(h, have.contains(source.installedKey(h))));
        more.setVisible(hits.size() < total && !hits.isEmpty());
        grid.revalidate();
        grid.repaint();
    }

    private static Set<String> installedKeys() {
        Set<String> have = new HashSet<>();
        for (Mods.Installed i : Mods.installed()) have.add(Kit.get("mod." + i.file().getFileName(), ""));
        return have;
    }

    private JComponent modCard(ModSource.Hit h, boolean isInstalled) {
        Ui.Card c = new Ui.Card(new BorderLayout(14, 0));
        c.bg = Ui.CARD;
        c.arc = 14;
        c.setBorder(new EmptyBorder(14, 14, 14, 14));
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Images.Pic icon = new Images.Pic(56, 56, 12);
        icon.fallback = h.title() == null ? "?" : h.title();
        icon.url(h.iconUrl());
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(icon, BorderLayout.NORTH);
        c.add(left, BorderLayout.WEST);

        Ui.Stack info = new Ui.Stack(4);
        info.put(Ui.label(App.shorten(h.title() == null ? "" : h.title(), 34), Ui.bold(14.5f), Ui.TEXT));
        String by = (h.author() == null ? "" : "by " + h.author() + "  ·  ") + compact(h.downloads()) + " downloads";
        info.put(Ui.label(by, Ui.font(12), Ui.FAINT));
        info.put(Ui.para(App.shorten(h.description() == null ? "" : h.description(), 120), Ui.font(12.5f), Ui.MUTED));
        List<Component> tags = new ArrayList<>();
        if (isInstalled) tags.add(Ui.tag("Installed", Ui.GREEN_HI));
        if (h.playersNeedIt()) tags.add(Ui.tag("Players need it too", Ui.AMBER));
        for (String cat : h.categories()) { if (tags.size() >= 3) break; tags.add(Ui.tag(cat, Ui.MUTED)); }
        JPanel tagRow = new JPanel(new Wrap(6, 6));
        tagRow.setOpaque(false);
        for (Component t : tags) tagRow.add(t);
        info.put(tagRow);
        c.add(info, BorderLayout.CENTER);

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { c.bg = Ui.CARD_HI; c.repaint(); }
            @Override public void mouseExited(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), c);
                if (!c.contains(p)) { c.bg = Ui.CARD; c.repaint(); }
            }
            @Override public void mouseClicked(MouseEvent e) { showDetail(h); }
        };
        listenAll(c, ma);
        return c;
    }

    private static void listenAll(Component c, MouseAdapter ma) {
        c.addMouseListener(ma);
        if (c instanceof Container k) for (Component ch : k.getComponents()) listenAll(ch, ma);
    }

    static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6).replace(".0M", "M");
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1e3).replace(".0K", "K");
        return String.valueOf(n);
    }

    // ================================================================== details

    private void showDetail(ModSource.Hit h) {
        ModSource s = source;
        body.removeAll();
        Ui.Stack st = new Ui.Stack(16);
        st.put(Ui.row(0, new Ui.Btn("←  Back to mods", Ui.Kind.GHOST).small().onClick(this::showBrowse), Ui.glue()));

        // header
        Ui.Card head = new Ui.Card(new BorderLayout(18, 0));
        Images.Pic icon = new Images.Pic(96, 96, 18);
        icon.fallback = h.title();
        icon.url(h.iconUrl());
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(icon, BorderLayout.NORTH);
        head.add(left, BorderLayout.WEST);
        Ui.Stack info = new Ui.Stack(6);
        info.put(Ui.label(h.title(), Ui.bold(22), Ui.TEXT));
        info.put(Ui.label((h.author() == null ? "" : "by " + h.author() + "  ·  ") + String.format("%,d downloads", h.downloads())
                + "  ·  from " + s.name(), Ui.font(13), Ui.FAINT));
        if (h.description() != null) info.put(Ui.para(h.description(), Ui.font(14), Ui.MUTED));
        List<Component> tags = new ArrayList<>();
        JPanel tagRow = new JPanel(new Wrap(6, 6));
        tagRow.setOpaque(false);
        for (String c : h.categories()) tagRow.add(Ui.tag(c, Ui.MUTED));
        info.put(tagRow);

        boolean have = installedKeys().contains(s.installedKey(h));
        Ui.Btn install = new Ui.Btn(have ? "Installed" : "Install", Ui.Kind.PRIMARY);
        install.setEnabled(!have);
        JTextArea msg = Ui.para(" ", Ui.font(13), Ui.MUTED);
        install.addActionListener(e -> install(s, h, install, msg));
        Ui.Btn site = new Ui.Btn("Open on " + s.name(), Ui.Kind.SECONDARY).onClick(() -> Kit.browse(h.pageUrl()));
        info.put(Box.createVerticalStrut(4));
        info.put(Ui.row(10, install, site, Ui.glue()));
        if (h.playersNeedIt())
            info.put(Ui.para("Players need this mod too: each Java player installs the same mod in their own Minecraft (with Fabric). "
                    + "Bedrock and Xbox players can't use it.", Ui.font(13), Ui.AMBER));
        info.put(msg);
        head.add(info, BorderLayout.CENTER);
        st.put(head);

        // pictures + description fill in when they arrive
        Ui.Stack more = new Ui.Stack(16);
        JTextArea loading = Ui.para("Loading pictures and description...", Ui.font(13), Ui.MUTED);
        more.put(loading);
        st.put(more);

        body.add(st, BorderLayout.NORTH);
        body.revalidate();
        body.repaint();
        scrollTop();

        new Thread(() -> {
            try {
                ModSource.Detail d = s.details(h);
                SwingUtilities.invokeLater(() -> {
                    more.removeAll();
                    if (d.blocked() != null) {
                        install.setEnabled(false);
                        install.setText("Can't install here");
                        msg.setForeground(Ui.AMBER);
                        msg.setText(d.blocked() + " Use \"Open on " + s.name() + "\" to get it.");
                    }
                    if (!d.gallery().isEmpty()) more.put(gallery(d.gallery()));
                    Ui.Card desc = new Ui.Card();
                    desc.add(new Ui.Stack(10).put(Ui.cardTitle("About this mod"))
                            .put(d.html().isBlank() ? Ui.para("This mod has no description.", Ui.font(13.5f), Ui.MUTED) : new Html(d.html())));
                    more.put(desc);
                    more.revalidate();
                    more.repaint();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> { loading.setForeground(Ui.RED_HI); loading.setText("Couldn't load the details: " + Kit.describe(e)); });
            }
        }, "mod-details").start();
    }

    private JComponent gallery(List<ModSource.Shot> shots) {
        Ui.Card c = new Ui.Card();
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        strip.setOpaque(false);
        for (int i = 0; i < shots.size(); i++) {
            int idx = i;
            Images.Pic p = new Images.Pic(256, 144, 12);
            p.url(shots.get(i).thumb());
            p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            if (shots.get(i).title() != null) p.setToolTipText(shots.get(i).title());
            p.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { viewer(shots, idx); }
            });
            strip.add(p);
        }
        JScrollPane sc = new JScrollPane(strip, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sc.setBorder(null);
        sc.setOpaque(false);
        sc.getViewport().setOpaque(false);
        Ui.styleScroll(sc);
        sc.getHorizontalScrollBar().setUnitIncrement(30);
        sc.setPreferredSize(new Dimension(100, 144 + 14));
        sc.setMinimumSize(new Dimension(100, 144 + 14));
        sc.getHorizontalScrollBar().setOpaque(false);
        sc.getHorizontalScrollBar().setBackground(Ui.CARD);
        // the page scrolls up/down with the wheel even when the mouse is over the pictures
        sc.setWheelScrollingEnabled(false);
        sc.addMouseWheelListener(e -> {
            Container outer = SwingUtilities.getAncestorOfClass(JScrollPane.class, sc.getParent());
            if (outer != null) outer.dispatchEvent(SwingUtilities.convertMouseEvent(sc, e, outer));
        });
        c.add(new Ui.Stack(10).put(Ui.row(8, Ui.cardTitle("Pictures"), Ui.label(shots.size() + "  ·  click one to make it bigger",
                Ui.font(12), Ui.FAINT), Ui.glue())).put(sc));
        return c;
    }

    /** A dark window showing one big picture, with back / next. */
    private void viewer(List<ModSource.Shot> shots, int start) {
        JDialog d = new JDialog(app.frame, "Pictures", true);
        int[] at = {start};
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(new Color(0x0D0F12));
        p.setBorder(new EmptyBorder(16, 16, 16, 16));
        Dimension size = new Dimension(Math.max(640, app.frame.getWidth() - 160), Math.max(420, app.frame.getHeight() - 200));
        Images.Pic big = new Images.Pic(size.width - 32, size.height - 110, 12) {
            @Override public Dimension getPreferredSize() { return new Dimension(10, 10); }
            @Override public Dimension getMinimumSize() { return new Dimension(10, 10); }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE); }
        };
        big.cover = false;
        JLabel caption = Ui.label(" ", Ui.font(13.5f), Ui.MUTED);
        Ui.Btn prev = new Ui.Btn("←  Back", Ui.Kind.SECONDARY).small();
        Ui.Btn next = new Ui.Btn("Next  →", Ui.Kind.SECONDARY).small();
        Ui.Btn close = new Ui.Btn("Close", Ui.Kind.SUBTLE).small();
        Runnable show = () -> {
            ModSource.Shot s = shots.get(at[0]);
            big.url(s.full());
            caption.setText((at[0] + 1) + " of " + shots.size() + (s.title() == null || s.title().isBlank() ? "" : "   " + s.title()));
            prev.setEnabled(at[0] > 0);
            next.setEnabled(at[0] < shots.size() - 1);
        };
        prev.onClick(() -> { if (at[0] > 0) { at[0]--; show.run(); } });
        next.onClick(() -> { if (at[0] < shots.size() - 1) { at[0]++; show.run(); } });
        close.onClick(d::dispose);
        p.add(big, BorderLayout.CENTER);
        p.add(Ui.row(10, caption, Ui.glue(), prev, next, close), BorderLayout.SOUTH);
        d.setContentPane(p);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.getRootPane().registerKeyboardAction(e -> prev.doClick(), KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.getRootPane().registerKeyboardAction(e -> next.doClick(), KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setSize(size);
        d.setLocationRelativeTo(app.frame);
        show.run();
        d.setVisible(true);
    }

    private void install(ModSource s, ModSource.Hit h, Ui.Btn button, JTextArea msg) {
        String mc = mc();
        if (mc.isEmpty()) { msg.setText("Start the server once first, so the app knows its Minecraft version."); return; }
        button.setEnabled(false);
        button.setText("Installing...");
        msg.setForeground(Ui.MUTED);
        msg.setText("Downloading " + h.title() + "...");
        new Thread(() -> {
            String text;
            boolean ok;
            try {
                List<String> added = s.install(h, mc, (t, f) -> SwingUtilities.invokeLater(() -> msg.setText(t)));
                ok = true;
                text = added.isEmpty() ? h.title() + " is already installed."
                        : "Installed " + String.join(", ", added) + "." + (app.server.isRunning() ? " Restart the server to load it." : " It loads when the server starts.");
            } catch (Exception e) {
                ok = false;
                text = "Couldn't install " + h.title() + ": " + Kit.describe(e);
            }
            boolean ok2 = ok;
            String t2 = text;
            SwingUtilities.invokeLater(() -> {
                button.setText(ok2 ? "Installed" : "Install");
                button.setEnabled(!ok2);
                msg.setText(t2);
                msg.setForeground(ok2 ? Ui.GREEN_HI : Ui.RED_HI);
                app.server.event(ok2 ? Server.Kind.SYSTEM : Server.Kind.WARN, t2);
            });
        }, "mod-install").start();
    }

    // ================================================================== installed

    private JComponent installedCard() {
        Ui.Card have = new Ui.Card();
        Ui.Stack list = installed;
        list.removeAll();
        List<Mods.Installed> mods = Mods.installed();
        if (mods.isEmpty()) list.put(Ui.para("No mods yet. Fabric API is added automatically on the first start. "
                + "Open Discover to find mods.", Ui.font(13.5f), Ui.MUTED));
        boolean off = app.server.state() == Server.State.STOPPED || app.server.state() == Server.State.CRASHED;
        for (Mods.Installed m : mods) {
            Ui.Btn rm = new Ui.Btn("Remove", Ui.Kind.SUBTLE).small().onClick(() -> {
                if (!Dialogs.confirm(app.frame, "Remove " + m.name() + "?", "The mod's file is deleted from this server. "
                        + "Things it added to the world may disappear.", "Remove", Ui.Kind.DANGER)) return;
                try { Mods.remove(m); } catch (Exception e) { Dialogs.info(app.frame, "Couldn't remove it", Kit.describe(e)); }
                showBrowse();
            });
            rm.setEnabled(off);
            if (!off) rm.setToolTipText("Stop the server first");
            List<Component> parts = new ArrayList<>(List.of(Ui.label(m.name(), Ui.bold(14), Ui.TEXT)));
            if (!m.version().isEmpty()) parts.add(Ui.tag(m.version(), Ui.MUTED));
            String fn = m.file().getFileName().toString();
            if (fn.startsWith("Geyser") || fn.startsWith("Floodgate")) parts.add(Ui.tag("Bedrock support", Ui.BLUE));
            if (Kit.get("mod." + fn, "").startsWith("cf:")) parts.add(Ui.tag("CurseForge", Ui.AMBER));
            parts.add(Ui.glue());
            parts.add(rm);
            list.put(rowCard(Ui.row(8, parts.toArray(new Component[0]))));
        }
        have.add(new Ui.Stack(12).put(Ui.row(10, Ui.cardTitle("Installed mods"), Ui.glue(),
                        new Ui.Btn("Open mods folder", Ui.Kind.GHOST).small().onClick(() -> {
                            try { Files.createDirectories(Kit.mods()); } catch (Exception ignored) { }
                            Kit.open(Kit.mods());
                        })))
                .put(list));
        return have;
    }

    private static JComponent rowCard(JComponent content) {
        Ui.Card c = new Ui.Card();
        c.bg = Ui.CARD_HI;
        c.arc = 12;
        c.setBorder(new EmptyBorder(10, 14, 10, 10));
        c.add(content);
        return c;
    }

    // ================================================================== CurseForge key

    private JComponent curseForgeSetup() {
        Ui.Card c = new Ui.Card();
        Ui.Field key = new Ui.Field("Paste your CurseForge API key here", 30);
        key.setMaximumSize(new Dimension(460, 44));
        JTextArea msg = Ui.para(" ", Ui.font(13), Ui.MUTED);
        Ui.Btn save = new Ui.Btn("Save key", Ui.Kind.PRIMARY);
        Runnable doSave = () -> saveKey(key.getText(), save, msg);
        save.onClick(doSave);
        key.addActionListener(e -> doSave.run());
        c.add(new Ui.Stack(12).put(Ui.cardTitle("Use CurseForge"))
                .put(Ui.para("CurseForge only lets apps in with a free key. You make it once and paste it here:", Ui.font(14), Ui.TEXT))
                .put(Ui.para("1.  Open the CurseForge console and sign in (the same account as the CurseForge app, or make a free one).\n"
                        + "2.  Accept the terms, then open \"API keys\" and copy your key.\n"
                        + "3.  Paste it below and press Save key.", Ui.font(13.5f), Ui.MUTED))
                .put(Ui.row(10, new Ui.Btn("Open the CurseForge console", Ui.Kind.SECONDARY)
                        .onClick(() -> Kit.browse("https://console.curseforge.com/")), Ui.glue()))
                .put(Ui.row(10, key, save, Ui.glue()))
                .put(msg)
                .put(Ui.para("Don't want to? Modrinth has most of the same mods and needs no key.", Ui.font(12.5f), Ui.FAINT)));
        return c;
    }

    private void saveKey(String k, Ui.Btn save, JTextArea msg) {
        String key = ModSource.CurseForge.clean(k);
        if (key.isEmpty()) { msg.setText("Paste the key first."); return; }
        save.setEnabled(false);
        msg.setForeground(Ui.MUTED);
        msg.setText("Checking the key...");
        new Thread(() -> {
            try {
                ModSource.CurseForge.test(key);
                SwingUtilities.invokeLater(() -> {
                    Kit.set("app.curseforge.key", key);
                    hits.clear();
                    chips.removeAll();
                    showBrowse();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    save.setEnabled(true);
                    msg.setForeground(Ui.RED_HI);
                    msg.setText(Kit.describe(e));
                });
            }
        }, "cf-key").start();
    }

    private void changeKey() {
        if (!Dialogs.confirm(app.frame, "Change the CurseForge key?", "The saved key is removed and you can paste a new one.",
                "Change key", Ui.Kind.PRIMARY)) return;
        Kit.set("app.curseforge.key", null);
        hits.clear();
        chips.removeAll();
        showBrowse();
    }

    // ================================================================== helpers

    private void scrollTop() {
        Runnable top = () -> {
            JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, root);
            if (sp != null) { sp.getViewport().setViewPosition(new Point(0, 0)); sp.getVerticalScrollBar().setValue(0); }
        };
        SwingUtilities.invokeLater(() -> { top.run(); SwingUtilities.invokeLater(top); });
    }

    /** A mod's description: simple HTML that wraps to the card's width; links open in the browser. */
    static final class Html extends JEditorPane {
        Html(String html) {
            HTMLEditorKit kit = new HTMLEditorKit() {
                private StyleSheet sheet;
                @Override public StyleSheet getStyleSheet() {
                    if (sheet == null) {
                        sheet = new StyleSheet();
                        sheet.addStyleSheet(super.getStyleSheet());
                        String fam = Ui.FAMILY;
                        sheet.addRule("body { color: #C9D1D9; font-family: '" + fam + "'; font-size: 14pt; margin: 0; }");
                        sheet.addRule("p { margin-top: 0; margin-bottom: 10px; }");
                        sheet.addRule("a { color: #58A6FF; text-decoration: none; }");
                        sheet.addRule("h2 { color: #ECEFF3; font-size: 19pt; margin-top: 14px; margin-bottom: 6px; }");
                        sheet.addRule("h3 { color: #ECEFF3; font-size: 17pt; margin-top: 12px; margin-bottom: 6px; }");
                        sheet.addRule("h4 { color: #ECEFF3; font-size: 15pt; margin-top: 10px; margin-bottom: 4px; }");
                        sheet.addRule("h1 { color: #ECEFF3; font-size: 21pt; margin-top: 14px; margin-bottom: 6px; }");
                        sheet.addRule("pre, code, tt { font-family: '" + Ui.MONO + "'; color: #E6C07B; }");
                        sheet.addRule("pre { margin-bottom: 10px; }");
                        sheet.addRule("blockquote { color: #9BA3AE; margin-left: 12px; }");
                        sheet.addRule("ul, ol { margin-top: 0; margin-bottom: 10px; margin-left: 22px; }");
                        sheet.addRule("li { margin-bottom: 3px; }");
                        sheet.addRule("td, th { color: #C9D1D9; padding: 3px; }");
                    }
                    return sheet;
                }
            };
            setEditorKit(kit);
            setEditable(false);
            setOpaque(false);
            setBorder(null);
            setText("<html><body>" + html + "</body></html>");
            setCaretPosition(0);
            addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) Kit.browse(e.getURL().toString());
            });
        }

        @Override public Dimension getPreferredSize() {
            int w = getWidth() > 0 ? getWidth() : 600;
            javax.swing.text.View v = getUI().getRootView(this);
            v.setSize(w, Integer.MAX_VALUE);
            return new Dimension(w, (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + 4);
        }

        @Override public Dimension getMinimumSize() { return new Dimension(60, getPreferredSize().height); }

        @Override public void setBounds(int x, int y, int w, int h) {
            boolean changed = w != getWidth();
            super.setBounds(x, y, w, h);
            if (changed) SwingUtilities.invokeLater(this::revalidate);
        }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }
    }

    /** Flow layout that wraps onto new lines and reports the right height. */
    static final class Wrap extends FlowLayout {
        Wrap(int hgap, int vgap) { super(FlowLayout.LEFT, hgap, vgap); }

        @Override public Dimension preferredLayoutSize(Container t) { return size(t, true); }

        @Override public Dimension minimumLayoutSize(Container t) { Dimension d = size(t, false); d.width = 50; return d; }

        private Dimension size(Container t, boolean pref) {
            synchronized (t.getTreeLock()) {
                int max = t.getWidth() > 0 ? t.getWidth() : (t.getParent() != null && t.getParent().getWidth() > 0 ? t.getParent().getWidth() : 900);
                Insets in = t.getInsets();
                max -= in.left + in.right;
                int x = 0, rowH = 0, h = 0, w = 0;
                for (Component c : t.getComponents()) {
                    if (!c.isVisible()) continue;
                    Dimension d = pref ? c.getPreferredSize() : c.getMinimumSize();
                    if (x > 0 && x + d.width > max) { h += rowH + getVgap(); w = Math.max(w, x); x = 0; rowH = 0; }
                    x += d.width + getHgap();
                    rowH = Math.max(rowH, d.height);
                }
                h += rowH;
                w = Math.max(w, x);
                w = Math.max(0, Math.min(w - getHgap(), max)); // no gap after the last item, never wider than the space
                return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
            }
        }

        @Override public void layoutContainer(Container t) {
            synchronized (t.getTreeLock()) {
                Insets in = t.getInsets();
                int max = t.getWidth() - in.left - in.right;
                int x = 0, y = in.top, rowH = 0;
                for (Component c : t.getComponents()) {
                    if (!c.isVisible()) continue;
                    Dimension d = c.getPreferredSize();
                    if (x > 0 && x + d.width > max) { y += rowH + getVgap(); x = 0; rowH = 0; }
                    c.setBounds(in.left + x, y, d.width, d.height);
                    x += d.width + getHgap();
                    rowH = Math.max(rowH, d.height);
                }
            }
        }
    }

    /** Cards in equal-width columns (as many as fit, each at least {@code min} wide); rows are as tall as their tallest card. */
    static final class Grid implements LayoutManager {
        final int min, gap;

        Grid(int min, int gap) { this.min = min; this.gap = gap; }

        private int cols(Container t) {
            int w = t.getWidth() > 0 ? t.getWidth() : 900;
            return Math.max(1, (w + gap) / (min + gap));
        }

        @Override public void addLayoutComponent(String n, Component c) { }

        @Override public void removeLayoutComponent(Component c) { }

        @Override public Dimension preferredLayoutSize(Container t) {
            synchronized (t.getTreeLock()) {
                int cols = cols(t), cw = colWidth(t, cols), h = 0, row = 0, rows = 0;
                Component[] cs = t.getComponents();
                for (int i = 0; i < cs.length; i++) {
                    row = Math.max(row, heightAt(cs[i], cw));
                    if (i % cols == cols - 1 || i == cs.length - 1) { h += row; rows++; row = 0; }
                }
                return new Dimension(Math.min(min, Math.max(1, t.getWidth())), h + Math.max(0, rows - 1) * gap);
            }
        }

        // the same as preferred, so a squeezed page never hides the cards
        @Override public Dimension minimumLayoutSize(Container t) { return preferredLayoutSize(t); }

        private int colWidth(Container t, int cols) {
            int w = t.getWidth() > 0 ? t.getWidth() : 900;
            return Math.max(min, (w - gap * (cols - 1)) / cols);
        }

        private static int heightAt(Component c, int w) {
            c.setSize(w, Math.max(1, c.getHeight()));
            deepLayout(c);
            deepLayout(c); // a second pass, once wrapped text knows its width
            return c.getPreferredSize().height;
        }

        private static void deepLayout(Component c) {
            if (!(c instanceof Container k)) return;
            k.doLayout();
            for (Component ch : k.getComponents()) deepLayout(ch);
        }

        @Override public void layoutContainer(Container t) {
            synchronized (t.getTreeLock()) {
                int cols = cols(t), cw = colWidth(t, cols), y = 0, row = 0;
                Component[] cs = t.getComponents();
                int start = 0;
                for (int i = 0; i < cs.length; i++) {
                    row = Math.max(row, heightAt(cs[i], cw));
                    if (i % cols == cols - 1 || i == cs.length - 1) {
                        for (int j = start; j <= i; j++) cs[j].setBounds((j - start) * (cw + gap), y, cw, row);
                        y += row + gap;
                        row = 0;
                        start = i + 1;
                    }
                }
            }
        }
    }
}
