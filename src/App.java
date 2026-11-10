// The app window: sidebar, header with the big Start/Stop button, and the pages.

import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;

final class App {

    final Server server = new Server();
    final Online online = new Online();
    final JFrame frame = new JFrame(Kit.APP_NAME);

    private final CardLayout pages = new CardLayout();
    private final JPanel pageHolder = new JPanel(pages);
    private final Map<String, NavItem> nav = new LinkedHashMap<>();
    private final JLabel pageTitle = Ui.label("", Ui.bold(22), Ui.TEXT);
    private final JLabel pageSub = Ui.label("", Ui.font(13), Ui.MUTED);
    private final Ui.Pill pill = new Ui.Pill();
    private final Ui.Btn startBtn = new Ui.Btn("Start server", Ui.Kind.PRIMARY).big();
    private final Ui.Btn restartBtn = new Ui.Btn("Restart", Ui.Kind.SECONDARY);
    private final Ui.ProgressLine progressLine = new Ui.ProgressLine();
    private String current = "home";

    private final SettingsPage settings = new SettingsPage();
    private final DashboardPage dashboard = new DashboardPage();
    private final PlayersPage players = new PlayersPage();
    private final ConsolePage console = new ConsolePage();
    private final OnlinePage onlinePage = new OnlinePage();
    private final ServersPage serversPage = new ServersPage(this);
    private final ModsPage modsPage = new ModsPage(this);
    private RulesPage rulesPage;

    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    static volatile App INSTANCE;

    App() {
        INSTANCE = this;
        server.changeListeners.add(() -> ui(this::refreshAll));
        server.eventListeners.add(e -> ui(() -> { console.add(e); dashboard.addEvent(e); }));
        server.rawListeners.add(console::raw);
        server.eulaPrompt = this::askEula;
        server.onXboxCode = code -> ui(() -> xboxCodeDialog(code));
        server.beforeStart = () -> { Online.applyGeyserPort(); online.goLive(); };
        server.onStopped = online::stopAgent;
        online.serverRunning = () -> server.state() == Server.State.STARTING || server.state() == Server.State.RUNNING;
        online.listeners.add(() -> ui(this::refreshAll));
        online.log = (k, s) -> server.event(k, s);
    }

    static void ui(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r);
    }

    // ------------------------------------------------------------------ window

    void open() {
        frame.setIconImages(Ui.appIcons());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { close(); }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);
        root.add(sidebar(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Ui.BG);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(header(), BorderLayout.NORTH);
        JPanel barWrap = new JPanel(new BorderLayout());
        barWrap.setOpaque(false);
        barWrap.setBorder(new EmptyBorder(0, 32, 10, 32));
        barWrap.add(updateBar);
        barWrap.setVisible(false);
        top.add(barWrap, BorderLayout.SOUTH);
        main.add(top, BorderLayout.NORTH);
        pageHolder.setOpaque(false);
        addPage("servers", Ui.scroll(serversPage.root));
        addPage("home", Ui.scroll(dashboard.root));
        addPage("mods", Ui.scroll(modsPage.root));
        rulesPage = new RulesPage(this);
        addPage("rules", Ui.scroll(rulesPage.root));
        addPage("players", Ui.scroll(players.root));
        addPage("console", console.root);
        addPage("online", Ui.scroll(onlinePage.root));
        addPage("settings", Ui.scroll(settings.root));
        main.add(pageHolder, BorderLayout.CENTER);
        root.add(main, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(980, 660));
        frame.setSize(new Dimension(1120, 760));
        frame.setLocationRelativeTo(null);

        show(Kit.hasServer() ? "home" : "servers");
        refreshAll();
        updateNavForServer();
        new Timer(1000, e -> { refreshHeader(); dashboard.tick(); }).start();
        frame.setVisible(true);

        // app updates: say what's new after an update, then keep checking every few hours
        String just = Kit.get("app.justUpdated", null);
        if (just != null && Updater.compare(just, Kit.VERSION) == 0) {
            Kit.set("app.justUpdated", null);
            String notes = Kit.get("app.notes", "");
            server.event(Server.Kind.SYSTEM, "The app updated itself to version " + Kit.VERSION
                    + (notes.isBlank() ? "." : ": " + notes.replace("\n", "; ")));
        }
        Timer appTimer = new Timer(6 * 60 * 60 * 1000, e -> checkAppUpdate(false));
        appTimer.setInitialDelay(90_000);
        appTimer.start();

        if (Kit.hasServer()) server.event(Server.Kind.SYSTEM, Setup.softwareMissing()
                ? "Welcome! Press Start server. The first start downloads the server (about 100 MB)."
                : "Ready. Press Start server when you want to play.");

        // installed in its own folder: give it a Start menu entry and a desktop icon (once)
        if (Kit.WINDOWS && !Kit.homeIsServer() && !Kit.getBool("app.shortcuts2", false)) {
            Thread t = new Thread(() -> {
                try { Setup.createDesktopShortcut(); Kit.set("app.shortcuts2", true); } catch (Exception ignored) { }
            }, "shortcuts");
            t.setDaemon(true);
            t.start();
        }
    }

    // ------------------------------------------------------------------ app updates

    private final Ui.Card updateBar = new Ui.Card(new BorderLayout());
    private volatile boolean appChecking;

    void checkAppUpdate(boolean manual) {
        if (appChecking || (!manual && !Kit.getBool("app.autoupdate", true))) return;
        appChecking = true;
        settings.appNote.setText("Checking...");
        Thread t = new Thread(() -> {
            String msg;
            Updater.Release r = null;
            try {
                r = Updater.checkInBackground();
                msg = r != null ? "Version " + r.version() + " downloaded. It installs the next time the app opens."
                        : Files.exists(Updater.PENDING) ? "An update is downloaded and installs the next time the app opens."
                        : "You have the latest version.";
            } catch (Exception e) {
                msg = "Couldn't check: " + Kit.describe(e);
            }
            appChecking = false;
            String m2 = msg;
            Updater.Release r2 = r;
            ui(() -> {
                settings.appNote.setText(m2);
                if (r2 != null || Files.exists(Updater.PENDING)) showUpdateBar(r2);
            });
        }, "app-update");
        t.setDaemon(true);
        t.start();
    }

    private void showUpdateBar(Updater.Release r) {
        updateBar.removeAll();
        updateBar.bg = Ui.BLUE_DIM;
        updateBar.arc = 12;
        updateBar.setBorder(new EmptyBorder(10, 16, 10, 12));
        String v = r != null ? r.version() : ServerLauncher.jarVersion(Updater.PENDING);
        String notes = r != null && !r.notes().isEmpty() ? "  " + String.join("; ", r.notes()) : "";
        JLabel l = Ui.label("App update " + (v == null ? "" : v + " ") + "is ready." + shorten(notes, 90), Ui.font(13.5f), Ui.TEXT);
        Ui.Btn later = new Ui.Btn("Later", Ui.Kind.SUBTLE).small().onClick(() -> updateBar.getParent().setVisible(false));
        Ui.Btn now = new Ui.Btn("Restart app to update", Ui.Kind.PRIMARY).small().onClick(this::restartForUpdate);
        updateBar.add(Ui.row(10, l, Ui.glue(), later, now), BorderLayout.CENTER);
        updateBar.getParent().setVisible(true);
        updateBar.getParent().revalidate();
        updateBar.revalidate();
        updateBar.repaint();
    }

    private void restartForUpdate() {
        boolean running = server.state() != Server.State.STOPPED && server.state() != Server.State.CRASHED;
        if (running && !Dialogs.confirm(frame, "Restart the app now?",
                "The server stops while the app restarts (your world is saved), then you can start it again. "
                        + "Or choose Cancel and the update installs next time you open the app.", "Stop and restart", Ui.Kind.PRIMARY))
            return;
        JDialog wait = Dialogs.busy(frame, "Restarting the app...");
        Thread t = new Thread(() -> {
            if (running) server.stopAndWait(120_000);
            online.stopAgent();
            releaseLockForRestart();
            Updater.restartApp();
            ui(() -> { wait.dispose(); server.killNow(); frame.dispose(); System.exit(0); });
        }, "restart");
        t.setDaemon(true);
        t.start();
        wait.setVisible(true);
    }

    /** The new copy of the app must be able to take the "already open" lock. */
    private static void releaseLockForRestart() {
        try { Files.deleteIfExists(Kit.HOME.resolve(".app.restarting")); Files.createFile(Kit.HOME.resolve(".app.restarting")); }
        catch (Exception ignored) { }
    }

    private void close() {
        if (server.state() == Server.State.STOPPED || server.state() == Server.State.CRASHED) {
            online.stopAgent();
            exit();
            return;
        }
        if (server.state() == Server.State.PREPARING) {
            if (!Dialogs.confirm(frame, "Close the app?", "The server is still getting ready. Close anyway?",
                    "Close", Ui.Kind.DANGER)) return;
            exit();
            return;
        }
        if (!Dialogs.confirm(frame, "Stop the server and close?",
                "Players will be disconnected. Your world is saved before the app closes.", "Stop and close", Ui.Kind.DANGER))
            return;
        JDialog wait = Dialogs.busy(frame, "Saving the world and shutting down...");
        Thread t = new Thread(() -> {
            server.stopAndWait(120_000);
            online.stopAgent();
            ui(() -> { wait.dispose(); exit(); });
        }, "closing");
        t.setDaemon(true);
        t.start();
        wait.setVisible(true);
    }

    private void exit() {
        server.killNow();
        online.stopAgent();
        frame.dispose();
        System.exit(0);
    }


    private JDialog xboxDialog;

    private void xboxCodeDialog(String code) {
        if (xboxDialog != null) xboxDialog.dispose();
        JDialog d = new JDialog(frame, "Xbox sign-in", Dialog.ModalityType.MODELESS);
        xboxDialog = d;
        d.setIconImages(Ui.appIcons());
        Ui.Stack s = new Ui.Stack(12);
        s.put(Ui.label("Sign in the server's Xbox account", Ui.bold(18), Ui.TEXT));
        JTextArea t = Ui.para("Console support needs a Microsoft account to show your server to Xbox friends. Open "
                + "microsoft.com/link, enter this code, and sign in with a spare account (not your main one).", Ui.font(14), Ui.MUTED);
        t.setSize(new Dimension(400, 10));
        s.put(t);
        s.put(Ui.row(12, Ui.label(code, Ui.mono(26), Ui.TEXT), Ui.copyButton("Copy code", () -> code), Ui.glue()));
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Ui.CARD);
        p.setBorder(new EmptyBorder(24, 26, 20, 26));
        p.add(s, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        b.setOpaque(false);
        b.setBorder(new EmptyBorder(18, 0, 0, 0));
        b.add(Ui.row(10, new Ui.Btn("Later", Ui.Kind.SECONDARY).onClick(d::dispose),
                new Ui.Btn("Open microsoft.com/link", Ui.Kind.PRIMARY).onClick(() -> Kit.browse("https://www.microsoft.com/link"))));
        p.add(b, BorderLayout.SOUTH);
        p.setPreferredSize(new Dimension(470, p.getPreferredSize().height));
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
        // close it automatically once sign-in succeeds
        Timer close = new Timer(2000, null);
        close.addActionListener(e -> { if (server.xboxCode == null || !code.equals(server.xboxCode)) { d.dispose(); close.stop(); } });
        close.start();
    }

    private boolean askEula() {
        AtomicBoolean ok = new AtomicBoolean();
        try {
            SwingUtilities.invokeAndWait(() -> ok.set(Dialogs.eula(frame)));
        } catch (Exception e) {
            return false;
        }
        return ok.get();
    }

    // ------------------------------------------------------------------ sidebar

    private JComponent sidebar() {
        JPanel side = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Ui.SIDEBAR);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(0x23272E));
                g.fillRect(getWidth() - 1, 0, 1, getHeight());
            }
        };
        side.setPreferredSize(new Dimension(232, 10));
        side.setBorder(new EmptyBorder(20, 14, 18, 14));

        JLabel logo = new JLabel(new ImageIcon(Ui.appIcon(40)));
        JPanel brandText = new JPanel(new GridLayout(2, 1));
        brandText.setOpaque(false);
        brandText.add(Ui.label("Minecraft Server", Ui.bold(15), Ui.TEXT));
        brandText.add(Ui.label("Java + Bedrock", Ui.font(12), Ui.MUTED));
        JPanel brand = Ui.row(12, logo, brandText);
        brand.setBorder(new EmptyBorder(0, 6, 16, 0));

        Ui.Stack items = new Ui.Stack(4);
        items.put(switcher);
        items.put(Box.createVerticalStrut(8));
        addNav(items, "servers", "Servers", "servers");
        addNav(items, "home", "Dashboard", "home");
        addNav(items, "players", "Players", "players");
        addNav(items, "mods", "Mods", "mods");
        addNav(items, "rules", "Rules", "rules");
        addNav(items, "console", "Console", "console");
        addNav(items, "online", "Play online", "online");
        addNav(items, "settings", "Settings", "settings");

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(brand, BorderLayout.NORTH);
        top.add(items, BorderLayout.CENTER);

        Ui.Btn help = new Ui.Btn("Help & guide", Ui.Kind.SUBTLE).small()
                .onClick(() -> {
                    Path readme = Kit.HOME.resolve("README.md");
                    try {
                        if (Kit.WINDOWS) { new ProcessBuilder("notepad.exe", readme.toString()).start(); return; }
                    } catch (Exception ignored) { }
                    Kit.open(readme);
                });
        help.setHorizontalAlignment(SwingConstants.LEFT);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(help, BorderLayout.NORTH);
        JLabel ver = Ui.label("App " + Kit.VERSION, Ui.font(11.5f), Ui.FAINT);
        ver.setBorder(new EmptyBorder(6, 12, 0, 0));
        bottom.add(ver, BorderLayout.SOUTH);

        side.add(top, BorderLayout.NORTH);
        side.add(bottom, BorderLayout.SOUTH);
        return side;
    }

    /** Shows which server is open; click to see them all. */
    private final JComponent switcher = new JComponent() {
        boolean hover;
        {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Switch server");
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { App.this.show("servers"); }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(200, 58); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.aa(g2);
            g2.setColor(hover ? new Color(0x2A3038) : new Color(0x22272E));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.setColor(Ui.LINE);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.setFont(Ui.bold(11));
            g2.setColor(Ui.FAINT);
            g2.drawString("SERVER", 14, 20);
            g2.setFont(Ui.bold(14));
            g2.setColor(Ui.TEXT);
            String name = shorten(Kit.serverName(), 20);
            g2.drawString(name, 14, 40);
            String type = !Kit.hasServer() ? "" : Kit.isFabric() ? "Modded" : "Survival";
            g2.setFont(Ui.font(11.5f));
            g2.setColor(Kit.isFabric() ? new Color(0xC9A0FF) : Ui.GREEN_HI);
            g2.drawString(type, 14 + g2.getFontMetrics(Ui.bold(14)).stringWidth(name) + 8, 40);
            int cx = getWidth() - 18, cy = getHeight() / 2;
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(Ui.MUTED);
            g2.drawLine(cx - 3, cy - 5, cx + 1, cy - 1);
            g2.drawLine(cx + 1, cy - 1, cx - 3, cy + 3);
            g2.dispose();
        }
    };

    /** Mods only make sense on a modded server; most pages need a server to exist. */
    void updateNavForServer() {
        boolean has = Kit.hasServer();
        for (String id : List.of("home", "players", "rules", "console", "settings")) if (nav.get(id) != null) nav.get(id).setVisible(has);
        if (nav.get("mods") != null) nav.get("mods").setVisible(has && Kit.isFabric());
        switcher.repaint();
        startBtn.setVisible(has);
        pill.setVisible(has);
    }

    /** Opens another server. Only while the current one is stopped (one server runs at a time). */
    void switchTo(Path dir) {
        if (Objects.equals(dir, Kit.dir())) { refreshAll(); return; }
        Server.State st = server.state();
        if (st != Server.State.STOPPED && st != Server.State.CRASHED) {
            Dialogs.info(frame, "Stop the server first", "Only one server runs at a time. Stop " + Kit.serverName()
                    + ", then switch.");
            return;
        }
        Kit.switchServer(dir);
        server.resetForSwitch();
        console.clear();
        dashboard.clearEvents();
        updateNavForServer();
        refreshAll();
        players.refresh();
        settings.refresh();
        modsPage.refresh();
        rulesPage.refresh();
        serversPage.refresh();
        if (Kit.hasServer()) {
            server.event(Server.Kind.SYSTEM, "Opened " + Kit.serverName() + "."
                    + (Setup.softwareMissing() ? " Press Start server: the first start downloads what it needs." : ""));
            show("home");
        } else {
            show("servers");
        }
    }

    private void addNav(Ui.Stack items, String id, String text, String icon) {
        NavItem n = new NavItem(text, icon, () -> show(id));
        nav.put(id, n);
        items.put(n);
    }

    final class NavItem extends JComponent {
        final String text, icon;
        boolean selected, hover;
        String badge;

        NavItem(String text, String icon, Runnable onClick) {
            this.text = text;
            this.icon = icon;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { onClick.run(); }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(200, 42); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.aa(g2);
            if (selected || hover) {
                g2.setColor(selected ? new Color(0x262B33) : new Color(0x1F2329));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
            if (selected) {
                g2.setColor(Ui.GREEN);
                g2.fillRoundRect(0, 11, 4, getHeight() - 22, 4, 4);
            }
            Color c = selected ? Ui.TEXT : Ui.MUTED;
            Ui.navIcon(icon, c).paintIcon(this, g2, 16, (getHeight() - 20) / 2);
            g2.setFont(selected ? Ui.bold(14) : Ui.font(14));
            g2.setColor(c);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 48, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            if (badge != null) {
                g2.setFont(Ui.bold(11));
                fm = g2.getFontMetrics();
                int w = fm.stringWidth(badge) + 14, h = 20, x = getWidth() - w - 10, y = (getHeight() - h) / 2;
                g2.setColor(Ui.GREEN_DIM);
                g2.fillRoundRect(x, y, w, h, h, h);
                g2.setColor(Ui.GREEN_HI);
                g2.drawString(badge, x + 7, y + (h - fm.getHeight()) / 2 + fm.getAscent());
            }
            g2.dispose();
        }
    }

    private final Map<String, JComponent> pageViews = new HashMap<>();

    private void addPage(String id, JComponent view) {
        pageViews.put(id, view);
        pageHolder.add(view, id);
    }

    void show(String id) {
        boolean changed = !id.equals(current);
        current = id;
        pages.show(pageHolder, id);
        if (changed && pageViews.get(id) instanceof JScrollPane sp)
            SwingUtilities.invokeLater(() -> sp.getVerticalScrollBar().setValue(0));
        for (var e : nav.entrySet()) { e.getValue().selected = e.getKey().equals(id); e.getValue().repaint(); }
        switch (id) {
            case "servers" -> { pageTitle.setText("Servers"); pageSub.setText("Make new servers and switch between them"); serversPage.refresh(); }
            case "mods" -> { pageTitle.setText("Mods"); pageSub.setText("Fabric mods for " + Kit.serverName()); modsPage.refresh(); }
            case "home" -> { pageTitle.setText(Kit.serverName()); pageSub.setText("Your server at a glance"); }
            case "players" -> { pageTitle.setText("Players"); pageSub.setText("Who can join, who's online, and who's an admin"); players.refresh(); }
            case "console" -> { pageTitle.setText("Console"); pageSub.setText("What's happening on the server, and a place to type commands"); console.focus(); }
            case "online" -> { pageTitle.setText("Play online"); pageSub.setText("Let friends join from anywhere, for free"); }
            case "rules" -> { pageTitle.setText("Rules"); pageSub.setText("Turn anti-cheat and game rules on or off"); rulesPage.refresh(); }
            case "settings" -> { pageTitle.setText("Settings"); pageSub.setText("Game rules, memory, backups and updates"); settings.refresh(); }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ header

    private JComponent header() {
        JPanel h = new JPanel(new BorderLayout());
        h.setBackground(Ui.BG);
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 2));
        titles.setOpaque(false);
        titles.add(pageTitle);
        titles.add(pageSub);

        restartBtn.addActionListener(e -> server.restart());
        startBtn.addActionListener(e -> {
            switch (server.state()) {
                case STOPPED, CRASHED -> server.start();
                case RUNNING, STARTING -> server.stop();
                default -> { }
            }
        });
        JPanel actions = Ui.row(12, pill, restartBtn, startBtn);

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(22, 32, 18, 32));
        bar.add(titles, BorderLayout.WEST);
        bar.add(actions, BorderLayout.EAST);
        h.add(bar, BorderLayout.CENTER);
        h.add(progressLine, BorderLayout.SOUTH);
        return h;
    }

    private void refreshHeader() {
        Server.State s = server.state();
        long secs = (System.currentTimeMillis() - server.startedAt()) / 1000;
        switch (s) {
            case STOPPED -> pill.set("Offline", Ui.FAINT, false);
            case PREPARING -> pill.set(shorten(server.detail(), 46), Ui.AMBER, true);
            case STARTING -> pill.set("Starting...  " + secs + "s", Ui.AMBER, true);
            case RUNNING -> pill.set("Online" + (online.live() ? "  +  internet" : ""), Ui.GREEN, false);
            case STOPPING -> pill.set("Saving and stopping...", Ui.AMBER, true);
            case CRASHED -> pill.set("Stopped unexpectedly", Ui.RED, false);
        }
        startBtn.setEnabled(s != Server.State.PREPARING && s != Server.State.STOPPING);
        boolean on = s == Server.State.RUNNING || s == Server.State.STARTING;
        startBtn.setText(on ? "Stop server" : s == Server.State.STOPPING ? "Stopping..." : s == Server.State.PREPARING ? "Please wait" : "Start server");
        startBtn.setKind(on ? Ui.Kind.DANGER : Ui.Kind.PRIMARY);
        restartBtn.setVisible(s == Server.State.RUNNING);
        progressLine.set(s == Server.State.PREPARING || s == Server.State.STARTING || s == Server.State.STOPPING,
                s == Server.State.PREPARING ? server.progress() : -1);
        NavItem pn = nav.get("players");
        if (pn != null) {
            String b = s == Server.State.RUNNING && !server.online.isEmpty() ? String.valueOf(server.online.size()) : null;
            if (!Objects.equals(b, pn.badge)) { pn.badge = b; pn.repaint(); }
        }
    }

    /** "Paper build 241 + Geyser", or "Fabric + 3 mods" for a modded server. */
    static String softwareLine(boolean longForm) {
        String bedrock = Setup.crossplay() ? (longForm ? "   ·   Geyser + Floodgate" : " + Geyser") : "";
        if (Kit.isFabric()) {
            long mods = Mods.installed().stream().filter(m -> !m.file().getFileName().toString().matches("(?i)(geyser|floodgate).*")).count();
            return "Fabric" + (longForm && Kit.get("fabric.loader", null) != null ? " " + Kit.get("fabric.loader", null) : "")
                    + " + " + mods + (mods == 1 ? " mod" : " mods") + bedrock;
        }
        String build = Kit.get("paper.build", null);
        return "Paper" + (build != null ? " build " + build : "") + bedrock;
    }

    static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    void refreshAll() {
        refreshHeader();
        switcher.repaint();
        if (current.equals("home")) pageTitle.setText(Kit.serverName());
        if (!Kit.hasServer()) { onlinePage.refresh(); return; }
        dashboard.refresh();
        players.refreshState();
        console.refreshState();
        onlinePage.refresh();
        settings.refreshState();
        if (rulesPage != null) rulesPage.serverChanged();
    }

    static List<String> lanAddresses() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = (ni.getDisplayName() + " " + ni.getName()).toLowerCase(Locale.ROOT);
                if (name.matches(".*(virtual|vmware|vbox|hyper-v|docker|wsl|tailscale|zerotier|vpn|utun|tun|radmin|hamachi|vethernet).*")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.add(a.getHostAddress());
            }
        } catch (SocketException ignored) { }
        return ips;
    }

    static Color colorFor(Server.Kind k) {
        return switch (k) {
            case JOIN -> Ui.GREEN_HI;
            case LEAVE -> Ui.MUTED;
            case CHAT -> Ui.TEXT;
            case WARN -> Ui.AMBER;
            case ERROR -> Ui.RED_HI;
            case SYSTEM -> Ui.BLUE;
            case YOU -> new Color(0xC9A0FF);
            default -> new Color(0xC4CAD3);
        };
    }

    private static JPanel page() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Ui.BG);
        p.setBorder(new EmptyBorder(4, 32, 32, 32));
        return p;
    }

    // ================================================================== Dashboard

    final class DashboardPage {
        final JPanel root = page();
        private final JLabel statusBig = Ui.label("Offline", Ui.bold(24), Ui.TEXT);
        private final JLabel statusSmall = Ui.label("", Ui.font(13), Ui.MUTED);
        private final JLabel playersBig = Ui.label("0", Ui.bold(24), Ui.TEXT);
        private final JLabel playersSmall = Ui.label("", Ui.font(13), Ui.MUTED);
        private final JLabel versionBig = Ui.label("", Ui.bold(24), Ui.TEXT);
        private final JLabel versionSmall = Ui.label("", Ui.font(13), Ui.MUTED);
        private final JPanel joinRows = new JPanel(new GridBagLayout());
        private final Ui.Stack feed = new Ui.Stack(8);
        private final Deque<Server.Event> recent = new ArrayDeque<>();
        private final Ui.Card welcome = new Ui.Card();
        private String joinKey = "";

        DashboardPage() {
            JPanel stats = new JPanel(new GridLayout(1, 3, 16, 0));
            stats.setOpaque(false);
            stats.add(stat("Server", statusBig, statusSmall));
            stats.add(stat("Players online", playersBig, playersSmall));
            stats.add(stat("Minecraft version", versionBig, versionSmall));

            welcome.bg = Ui.GREEN_DIM;
            JTextArea wt = Ui.para("Press Start server to begin. The first start downloads the server software "
                    + "(about 100 MB) and asks you to accept Minecraft's EULA. After that, starting takes under a minute.",
                    Ui.font(14), Ui.TEXT);
            Ui.Stack ws = new Ui.Stack(6);
            ws.put(Ui.label("Welcome to your server", Ui.bold(16), Ui.TEXT));
            ws.put(wt);
            if (Kit.WINDOWS && !Files.exists(Kit.HOME.resolve("app-icon.ico")))
                ws.put(Ui.row(10, new Ui.Btn("Add a desktop shortcut", Ui.Kind.SECONDARY).small().onClick(settings::shortcut),
                        Ui.label("Opens this app with one click, no black window.", Ui.font(12.5f), Ui.MUTED), Ui.glue()));
            welcome.add(ws);

            Ui.Card join = new Ui.Card();
            joinRows.setOpaque(false);
            join.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) { rebuildJoin(); }
            });
            Ui.Stack js = new Ui.Stack(12);
            js.put(Ui.row(0, Ui.cardTitle("How friends join"), Ui.glue(),
                    Ui.label("Java: Multiplayer > Add Server.   Bedrock: Servers > Add Server.", Ui.font(12), Ui.FAINT)));
            js.put(joinRows);
            join.add(js);

            Ui.Card activity = new Ui.Card();
            Ui.Stack as = new Ui.Stack(12);
            Ui.Btn all = new Ui.Btn("Open console", Ui.Kind.GHOST).small().onClick(() -> show("console"));
            as.put(Ui.row(0, Ui.cardTitle("Recent activity"), Ui.glue(), all));
            as.put(feed);
            activity.add(as);

            Ui.Stack s = new Ui.Stack(16);
            s.put(welcome);
            s.put(stats);
            s.put(join);
            s.put(activity);
            root.add(s, BorderLayout.NORTH);
            rebuildJoin();
        }

        private JComponent stat(String title, JLabel big, JLabel small) {
            Ui.Card c = new Ui.Card();
            Ui.Stack s = new Ui.Stack(6);
            s.put(Ui.cardTitle(title));
            s.put(big);
            s.put(small);
            c.add(s);
            return c;
        }

        void tick() {
            if (server.state() == Server.State.RUNNING && server.readyAt() > 0) {
                statusSmall.setText("Up for " + duration(System.currentTimeMillis() - server.readyAt()));
            }
        }

        void refresh() {
            if (!Kit.hasServer()) return;
            welcome.setVisible(Setup.softwareMissing() || !Setup.eulaAccepted());
            Server.State st = server.state();
            switch (st) {
                case STOPPED -> { statusBig.setText("Offline"); statusBig.setForeground(Ui.TEXT); statusSmall.setText("Press Start server to play"); }
                case PREPARING -> { statusBig.setText("Getting ready"); statusBig.setForeground(Ui.AMBER); statusSmall.setText(shorten(server.detail(), 40)); }
                case STARTING -> { statusBig.setText("Starting"); statusBig.setForeground(Ui.AMBER); statusSmall.setText("Usually under a minute"); }
                case RUNNING -> { statusBig.setText("Online"); statusBig.setForeground(Ui.GREEN_HI); tick(); }
                case STOPPING -> { statusBig.setText("Stopping"); statusBig.setForeground(Ui.AMBER); statusSmall.setText("Saving the world"); }
                case CRASHED -> { statusBig.setText("Stopped"); statusBig.setForeground(Ui.RED_HI); statusSmall.setText("Something went wrong. See Console."); }
            }
            int max = 10;
            try { max = Integer.parseInt(Setup.readProps().getOrDefault("max-players", "10")); } catch (NumberFormatException ignored) { }
            playersBig.setText(st == Server.State.RUNNING ? server.online.size() + " / " + max : "-");
            playersSmall.setText(st != Server.State.RUNNING ? "Server is offline"
                    : server.online.isEmpty() ? "Nobody yet"
                    : shorten(String.join(", ", server.online.stream().map(n -> n.startsWith(".") ? n.substring(1) : n).toList()), 40));
            String v = Kit.mcVersion();
            versionBig.setText(v.isEmpty() ? "Not installed" : v);
            String build = Kit.get("paper.build", null);
            versionSmall.setText(v.isEmpty() ? "Downloads on first start" : softwareLine(false));
            rebuildJoin();
        }

        private boolean narrow() {
            Container c = joinRows.getParent();
            return c != null && c.getWidth() > 0 && c.getWidth() < 860;
        }

        private void rebuildJoin() {
            List<String> lan = lanAddresses();
            boolean narrow = narrow();
            String key = lan + "|" + Online.javaAddress() + "|" + Online.bedrockHost() + "|" + Online.bedrockPort() + "|"
                    + online.state() + "|" + online.live() + "|" + server.state() + "|" + narrow;
            if (key.equals(joinKey)) return;
            joinKey = key;
            joinRows.removeAll();
            if (narrow) { rebuildJoinStacked(lan); return; }
            int r = 0;
            header(r++);
            addRow(r++, "On this PC", "localhost", null, "127.0.0.1", "19132");
            if (!lan.isEmpty()) addRow(r++, "Same Wi-Fi", lan.get(0), null, lan.get(0), "19132");
            String ja = Online.javaAddress();
            if (ja != null && online.linked()) {
                String host = Online.bedrockHost();
                addRow(r++, "Anywhere", ja, null, host, host == null ? null : String.valueOf(Online.bedrockPort()));
                GridBagConstraints g = gbc(1, r++);
                g.gridwidth = 4;
                boolean live = online.live();
                String note = live ? "Live now: friends anywhere can join with these."
                        : server.isRunning() ? "Connecting to playit..." : "These go live when the server is running.";
                joinRows.add(Ui.label(note, Ui.font(12), live ? Ui.GREEN_HI : Ui.FAINT), g);
            } else {
                GridBagConstraints g = gbc(0, r);
                joinRows.add(Ui.label("Anywhere", Ui.bold(13), Ui.MUTED), g);
                g = gbc(1, r++);
                g.gridwidth = 4;
                Ui.Btn setup = new Ui.Btn("Set up online play (free)", Ui.Kind.GHOST).small().onClick(() -> show("online"));
                joinRows.add(Ui.row(8, Ui.label("Friends outside your home need online play turned on.", Ui.font(13), Ui.MUTED), setup), g);
            }
            GridBagConstraints filler = gbc(5, 0);
            filler.weightx = 1;
            joinRows.add(Box.createHorizontalStrut(1), filler);
            joinRows.revalidate();
            joinRows.repaint();
        }

        /** Narrow windows: each place gets its own block, Java and Bedrock on separate lines. */
        private void rebuildJoinStacked(List<String> lan) {
            int[] r = {0};
            java.util.function.BiConsumer<String, String[]> block = (label, a) -> {
                GridBagConstraints g = gbc(0, r[0]++);
                g.gridwidth = 3;
                g.insets = new Insets(r[0] == 1 ? 0 : 12, 0, 4, 0);
                joinRows.add(Ui.label(label, Ui.bold(13), Ui.MUTED), g);
                joinRows.add(Ui.label("Java", Ui.font(12.5f), Ui.FAINT), stackGbc(0, r[0]));
                joinRows.add(address(a[0]), stackGbc(1, r[0]));
                joinRows.add(Ui.copyButton(() -> a[0]), stackGbc(2, r[0]++));
                joinRows.add(Ui.label("Bedrock", Ui.font(12.5f), Ui.FAINT), stackGbc(0, r[0]));
                if (a[1] != null) {
                    joinRows.add(Ui.row(0, address(a[1]), Ui.label("  port ", Ui.font(13), Ui.FAINT), address(a[2])), stackGbc(1, r[0]));
                    joinRows.add(Ui.copyButton(() -> a[1]), stackGbc(2, r[0]++));
                } else {
                    joinRows.add(Ui.label(online.bedrockProblem() != null ? "Not available online" : "Not ready yet",
                            Ui.font(13), Ui.FAINT), stackGbc(1, r[0]++));
                }
            };
            block.accept("On this PC", new String[]{"localhost", "127.0.0.1", "19132"});
            if (!lan.isEmpty()) block.accept("Same Wi-Fi", new String[]{lan.get(0), lan.get(0), "19132"});
            String ja = Online.javaAddress();
            if (ja != null && online.linked()) {
                String host = Online.bedrockHost();
                block.accept("Anywhere", new String[]{ja, host, host == null ? null : String.valueOf(Online.bedrockPort())});
                boolean live = online.live();
                GridBagConstraints g = gbc(0, r[0]++);
                g.gridwidth = 3;
                joinRows.add(Ui.label(live ? "Live now: friends anywhere can join with these."
                        : server.isRunning() ? "Connecting to playit..." : "These go live when the server is running.",
                        Ui.font(12), live ? Ui.GREEN_HI : Ui.FAINT), g);
            } else {
                GridBagConstraints g = gbc(0, r[0]++);
                g.gridwidth = 3;
                g.insets = new Insets(12, 0, 4, 0);
                joinRows.add(Ui.label("Anywhere", Ui.bold(13), Ui.MUTED), g);
                g = gbc(0, r[0]++);
                g.gridwidth = 3;
                joinRows.add(new Ui.Btn("Set up online play (free)", Ui.Kind.GHOST).small().onClick(() -> show("online")), g);
            }
            GridBagConstraints filler = gbc(3, 0);
            filler.weightx = 1;
            joinRows.add(Box.createHorizontalStrut(1), filler);
            joinRows.revalidate();
            joinRows.repaint();
        }

        private GridBagConstraints stackGbc(int x, int y) {
            GridBagConstraints g = gbc(x, y);
            g.insets = new Insets(3, x == 0 ? 12 : 0, 3, x == 0 ? 16 : 10);
            return g;
        }

        private void header(int r) {
            joinRows.add(Box.createHorizontalStrut(110), gbc(0, r));
            joinRows.add(Ui.label("JAVA EDITION", Ui.bold(11), Ui.FAINT), gbc(1, r));
            joinRows.add(Ui.label("BEDROCK  (phones, consoles, Windows)", Ui.bold(11), Ui.FAINT), span(gbc(3, r), 2));
        }

        private GridBagConstraints span(GridBagConstraints g, int w) { g.gridwidth = w; return g; }

        private void addRow(int r, String label, String java, String unused, String bedHost, String bedPort) {
            joinRows.add(Ui.label(label, Ui.bold(13), Ui.MUTED), gbc(0, r));
            joinRows.add(Ui.row(8, address(java), Ui.copyButton(() -> java)), gbc(1, r));
            if (bedHost != null) {
                JComponent addr = Ui.row(0, address(bedHost), Ui.label("   port ", Ui.font(13), Ui.FAINT), address(bedPort));
                joinRows.add(addr, gbc(3, r));
                joinRows.add(Ui.copyButton(() -> bedHost), gbc(4, r));
            } else {
                joinRows.add(Ui.label(online.bedrockProblem() != null ? "Not available online" : "Not ready yet",
                        Ui.font(13), Ui.FAINT), span(gbc(3, r), 2));
            }
        }

        private JLabel address(String s) { return Ui.label(s, Ui.mono(14), Ui.TEXT); }

        private GridBagConstraints gbc(int x, int y) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = x;
            g.gridy = y;
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(5, 0, 5, x == 1 ? 24 : 10);
            return g;
        }

        void clearEvents() {
            recent.clear();
            feed.removeAll();
            feed.revalidate();
            feed.repaint();
        }

        void addEvent(Server.Event e) {
            if (e.text().startsWith("    ")) return; // crash detail lines live in the console
            recent.addLast(e);
            while (recent.size() > 7) recent.removeFirst();
            feed.removeAll();
            for (Server.Event ev : recent) feed.put(eventRow(ev));
            feed.revalidate();
            feed.repaint();
        }

        private JComponent eventRow(Server.Event e) {
            JLabel time = Ui.label(e.time().format(CLOCK), Ui.font(12.5f), Ui.FAINT);
            time.setPreferredSize(new Dimension(70, 18));
            JLabel text = Ui.label(shorten(e.text(), 120), Ui.font(13.5f), colorFor(e.kind()));
            text.setIcon(Ui.dot(colorFor(e.kind()), 7));
            text.setIconTextGap(10);
            JPanel p = new JPanel(new BorderLayout());
            p.setOpaque(false);
            p.add(time, BorderLayout.WEST);
            p.add(text, BorderLayout.CENTER);
            return p;
        }
    }

    static String duration(long ms) {
        long m = ms / 60000;
        if (m < 1) return "less than a minute";
        if (m < 60) return m + " min";
        long h = m / 60;
        return h + " h " + (m % 60) + " min";
    }

    // ================================================================== Players

    final class PlayersPage {
        final JPanel root = page();
        private final Ui.Field name = new Ui.Field("Player name or Xbox gamertag", 18);
        private final Ui.Choice edition = new Ui.Choice(List.of("Java edition", "Bedrock edition"), "Java edition");
        private final Ui.Btn add = new Ui.Btn("Add to whitelist", Ui.Kind.PRIMARY);
        private final JLabel offlineNote = Ui.label("Start the server to add or change players.", Ui.font(13), Ui.AMBER);
        private final Ui.Stack list = new Ui.Stack(8);
        private final JPanel rejected = new JPanel(new BorderLayout());
        private String lastKey = "";

        PlayersPage() {
            Ui.Card addCard = new Ui.Card();
            Ui.Stack s = new Ui.Stack(12);
            s.put(Ui.cardTitle("Let someone join"));
            s.put(Ui.para("Only people on the whitelist can join. Java players use their Minecraft name; "
                    + "Bedrock players (phones, tablets, consoles, Windows) use their Xbox gamertag.", Ui.font(13.5f), Ui.MUTED));
            name.setMaximumSize(new Dimension(320, 44));
            name.addActionListener(e -> addPlayer());
            add.addActionListener(e -> addPlayer());
            s.put(Ui.row(10, name, edition, add, Ui.glue()));
            s.put(offlineNote);
            addCard.add(s);

            rejected.setOpaque(false);

            Ui.Card listCard = new Ui.Card();
            Ui.Stack ls = new Ui.Stack(12);
            Ui.Btn refresh = new Ui.Btn("Refresh", Ui.Kind.GHOST).small().onClick(this::refresh);
            ls.put(Ui.row(0, Ui.cardTitle("Whitelist"), Ui.glue(), refresh));
            ls.put(list);
            listCard.add(ls);

            Ui.Stack page = new Ui.Stack(16);
            page.put(addCard);
            page.put(rejected);
            page.put(listCard);
            root.add(page, BorderLayout.NORTH);
            refresh();
        }

        private final Map<String, String> idFor = new HashMap<>();

        private void addPlayer() {
            String n = name.getText().trim().replaceAll("\\s+", " ");
            if (n.isEmpty() || !server.isRunning()) return;
            boolean bedrock = edition.value().startsWith("Bedrock");
            String known = bedrock ? Server.bedrockUuidFor(n) : null;
            if (known != null) server.command("fwhitelist add " + known);
            else if (bedrock) server.command("fwhitelist add " + (n.contains(" ") ? "\"" + n + "\"" : n));
            else server.command("whitelist add " + n.replace(" ", ""));
            name.setText("");
            later(this::refresh);
        }

        void refreshState() {
            if (!Kit.hasServer()) return;
            boolean on = server.isRunning();
            add.setEnabled(on);
            name.setEnabled(on);
            edition.setEnabled(on);
            edition.setVisible(Setup.crossplay());
            if (!Setup.crossplay()) edition.setValue("Java edition");
            offlineNote.setVisible(!on);
            String r = on ? server.lastRejected : null;
            rejected.removeAll();
            if (r != null && !whitelisted(r)) {
                Ui.Card c = new Ui.Card();
                c.bg = Ui.AMBER_DIM;
                boolean bedrock = server.lastRejectedBedrock;
                Ui.Btn yes = new Ui.Btn("Add " + r, Ui.Kind.PRIMARY).small().onClick(() -> {
                    String known = bedrock ? Server.bedrockUuidFor(r) : null;
                    server.command(known != null ? "fwhitelist add " + known : (bedrock ? "fwhitelist add " : "whitelist add ") + r);
                    server.lastRejected = null;
                    later(this::refresh);
                });
                Ui.Btn no = new Ui.Btn("Dismiss", Ui.Kind.SUBTLE).small().onClick(() -> { server.lastRejected = null; refreshState(); });
                c.add(Ui.row(10, Ui.label(r + (bedrock ? " (Bedrock)" : "") + " just tried to join but isn't on the whitelist.",
                        Ui.font(14), Ui.TEXT), Ui.glue(), no, yes));
                rejected.add(c);
            }
            rejected.revalidate();
            String key = on + "|" + server.online;
            if (!key.equals(lastKey)) { lastKey = key; refresh(); }
        }

        private boolean whitelisted(String n) {
            String id = Server.bedrockUuidFor(n);
            for (Map<?, ?> e : readJson("whitelist.json")) {
                if (id != null && id.equals(String.valueOf(e.get("uuid")))) return true;
                String wn = String.valueOf(e.get("name"));
                if (wn.equalsIgnoreCase(n) || wn.equalsIgnoreCase("." + n)) return true;
            }
            return false;
        }

        void refresh() {
            if (!Kit.hasServer()) return;
            // Everyone is matched by their player ID, so Xbox/Bedrock players show their gamertag whatever the
            // server calls them (with or without Floodgate's name prefix, or "unknown" when added by ID).
            Map<String, String> cacheName = new HashMap<>(); // uuid -> name the server knows them by
            for (Map<?, ?> e : readJson("usercache.json")) cacheName.put(String.valueOf(e.get("uuid")), String.valueOf(e.get("name")));
            Set<String> opIds = new HashSet<>(), opNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Map<?, ?> o : readJson("ops.json")) { opIds.add(String.valueOf(o.get("uuid"))); opNames.add(String.valueOf(o.get("name"))); }

            Map<String, PlayerInfo> byKey = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            idFor.clear();
            for (Map<?, ?> e : readJson("whitelist.json")) {
                String id = String.valueOf(e.get("uuid")), listedName = String.valueOf(e.get("name"));
                boolean bedrock = isBedrockId(id) || listedName.startsWith(".");
                String game = cacheName.getOrDefault(id, listedName);
                String display = bedrock ? bedrockDisplay(id, game, listedName) : game;
                if (bedrock) idFor.put(display.toLowerCase(Locale.ROOT), id);
                if (bedrock) Server.rememberBedrock(display, id);
                byKey.put(display, new PlayerInfo(display, isUnknown(game) ? null : game, bedrock, id, true));
            }
            for (String n : server.online) { // online right now (maybe not on the list, e.g. whitelist off)
                String plain = n.replaceFirst("^\\.", "");
                if (byKey.containsKey(plain)) continue;
                String id = Server.bedrockUuidFor(plain);
                boolean bedrock = n.startsWith(".") || id != null;
                byKey.put(plain, new PlayerInfo(plain, n, bedrock, id, false));
            }

            list.removeAll();
            if (byKey.isEmpty()) {
                list.put(Ui.para("Nobody yet. Add yourself first, then your friends. Tip: make yourself an admin "
                        + "so you can use commands in the game.", Ui.font(13.5f), Ui.MUTED));
            }
            boolean on = server.isRunning();
            for (PlayerInfo pi : byKey.values()) {
                boolean online = pi.game() != null && (server.online.contains(pi.game()) || server.online.contains(pi.display())
                        || server.online.contains("." + pi.display()));
                boolean op = (pi.id() != null && opIds.contains(pi.id())) || (pi.game() != null && opNames.contains(pi.game()));
                list.put(playerRow(pi, op, online, on));
            }
            list.revalidate();
            list.repaint();
        }

        record PlayerInfo(String display, String game, boolean bedrock, String id, boolean listed) { }

        private static boolean isBedrockId(String id) { return id != null && id.startsWith("00000000-0000-0000-"); }

        private static boolean isUnknown(String n) { return n == null || n.equals("unknown") || n.equals("null") || n.isBlank(); }

        private static String bedrockDisplay(String id, String game, String listedName) {
            if (!isUnknown(game)) return game.replaceFirst("^\\.", "");
            String known = Server.bedrockNameFor(id);
            if (known != null) return known;
            if (!isUnknown(listedName)) return listedName.replaceFirst("^\\.", "");
            return "Xbox player " + id.substring(19);
        }

        private JComponent playerRow(PlayerInfo p, boolean op, boolean isOnline, boolean serverOn) {
            String display = p.display();
            String n = p.game() != null ? p.game() : display; // the name commands need
            JLabel nameL = Ui.label(display, Ui.bold(14.5f), Ui.TEXT);
            nameL.setIcon(Ui.dot(isOnline ? Ui.GREEN_HI : new Color(0x3A414B), 9));
            nameL.setIconTextGap(12);
            List<Component> parts = new ArrayList<>(List.of(nameL));
            parts.add(Ui.tag(p.bedrock() ? "Bedrock / Xbox" : "Java", p.bedrock() ? Ui.BLUE : new Color(0xC9A0FF)));
            if (op) parts.add(Ui.tag("Admin", Ui.AMBER));
            if (isOnline) parts.add(Ui.tag("Online", Ui.GREEN_HI));
            if (!p.listed()) parts.add(Ui.tag("Not on whitelist", Ui.RED_HI));
            parts.add(Ui.glue());

            Ui.Btn admin = new Ui.Btn(op ? "Remove admin" : "Make admin", Ui.Kind.SECONDARY).small()
                    .onClick(() -> { server.command((op ? "deop " : "op ") + n); later(this::refresh); });
            Ui.Btn kick = new Ui.Btn("Kick", Ui.Kind.SECONDARY).small()
                    .onClick(() -> server.command("kick " + n));
            Ui.Btn remove = new Ui.Btn("Remove", Ui.Kind.SUBTLE).small().onClick(() -> {
                if (!Dialogs.confirm(frame, "Remove " + display + "?", display + " won't be able to join anymore.",
                        "Remove", Ui.Kind.DANGER)) return;
                if (p.bedrock()) server.command("fwhitelist remove " + (p.id() != null ? p.id() : display));
                else server.command("whitelist remove " + n);
                if (isOnline) server.command("kick " + n + " You were removed from the whitelist");
                later(this::refresh);
            });
            admin.setEnabled(serverOn && p.game() != null);
            if (p.game() == null) admin.setToolTipText("Available after they've joined once");
            kick.setEnabled(serverOn);
            remove.setEnabled(serverOn && p.listed());
            kick.setVisible(isOnline);
            parts.add(admin);
            parts.add(kick);
            parts.add(remove);

            Ui.Card row = new Ui.Card();
            row.bg = Ui.CARD_HI;
            row.arc = 12;
            row.setBorder(new EmptyBorder(10, 14, 10, 10));
            row.add(Ui.row(8, parts.toArray(new Component[0])));
            return row;
        }
    }

    static List<Map<?, ?>> readJson(String file) {
        try {
            if (!Kit.hasServer()) return List.of();
            Path p = Kit.dir().resolve(file);
            if (!Files.exists(p)) return List.of();
            List<Map<?, ?>> out = new ArrayList<>();
            for (Object o : (List<?>) Kit.Json.parse(Files.readString(p))) if (o instanceof Map<?, ?> m) out.add(m);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    static void later(Runnable r) {
        Timer t = new Timer(900, e -> r.run());
        t.setRepeats(false);
        t.start();
    }

    // ================================================================== Console

    final class ConsolePage {
        final JPanel root = new JPanel(new BorderLayout());
        private final JTextPane events = new JTextPane();
        private final JTextArea rawArea = new JTextArea();
        private final CardLayout views = new CardLayout();
        private final JPanel viewHolder = new JPanel(views);
        private final Ui.Switch fullLog = new Ui.Switch(false);
        private final Ui.Field input = new Ui.Field("Type a command, for example:  time set day", 30);
        private final Ui.Btn send = new Ui.Btn("Send", Ui.Kind.PRIMARY);
        private final List<String> history = new ArrayList<>();
        private int historyPos;
        private final StringBuilder rawPending = new StringBuilder();
        private final List<Ui.Btn> quick = new ArrayList<>();

        ConsolePage() {
            root.setBackground(Ui.BG);
            root.setBorder(new EmptyBorder(4, 32, 28, 32));

            events.setEditable(false);
            events.setBackground(Ui.CARD);
            events.setBorder(new EmptyBorder(14, 16, 14, 16));
            events.setFont(Ui.font(14));
            rawArea.setEditable(false);
            rawArea.setBackground(Ui.CARD);
            rawArea.setForeground(new Color(0xB8BFC9));
            rawArea.setFont(Ui.mono(12.5f));
            rawArea.setBorder(new EmptyBorder(14, 16, 14, 16));
            rawArea.setLineWrap(true);

            JScrollPane es = new JScrollPane(events);
            JScrollPane rs = new JScrollPane(rawArea);
            for (JScrollPane sp : List.of(es, rs)) {
                sp.setBorder(null);
                sp.getViewport().setBackground(Ui.CARD);
                Ui.styleScroll(sp);
            }
            viewHolder.setOpaque(false);
            viewHolder.add(es, "events");
            viewHolder.add(rs, "raw");

            Ui.Card box = new Ui.Card();
            box.setBorder(new EmptyBorder(6, 4, 6, 4));
            box.bg = Ui.CARD;
            box.add(viewHolder);

            fullLog.onChange(on -> views.show(viewHolder, on ? "raw" : "events"));
            JPanel top = Ui.row(10, Ui.label("Showing the important stuff. Everything is also saved in logs/latest.log.",
                    Ui.font(13), Ui.MUTED), Ui.glue(), Ui.label("Full server log", Ui.font(13), Ui.TEXT), fullLog);
            top.setBorder(new EmptyBorder(0, 2, 12, 2));

            for (String[] q : new String[][]{{"Who's online", "list"}, {"Save the world", "save-all"},
                    {"Make it day", "time set day"}, {"Clear the weather", "weather clear"}}) {
                Ui.Btn b = new Ui.Btn(q[0], Ui.Kind.SECONDARY).small().onClick(() -> server.command(q[1]));
                quick.add(b);
            }
            JPanel quickRow = Ui.row(8, quick.toArray(new Component[0]));
            quickRow.add(Box.createHorizontalGlue());
            quickRow.setBorder(new EmptyBorder(12, 0, 10, 0));

            input.addActionListener(e -> sendInput());
            send.addActionListener(e -> sendInput());
            input.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (history.isEmpty()) return;
                    if (e.getKeyCode() == KeyEvent.VK_UP) historyPos = Math.max(0, historyPos - 1);
                    else if (e.getKeyCode() == KeyEvent.VK_DOWN) historyPos = Math.min(history.size(), historyPos + 1);
                    else return;
                    input.setText(historyPos < history.size() ? history.get(historyPos) : "");
                }
            });
            JPanel inputRow = new JPanel(new BorderLayout(10, 0));
            inputRow.setOpaque(false);
            inputRow.add(input, BorderLayout.CENTER);
            inputRow.add(send, BorderLayout.EAST);

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setOpaque(false);
            bottom.add(quickRow, BorderLayout.NORTH);
            bottom.add(inputRow, BorderLayout.CENTER);

            root.add(top, BorderLayout.NORTH);
            root.add(box, BorderLayout.CENTER);
            root.add(bottom, BorderLayout.SOUTH);

            new Timer(250, e -> flushRaw()).start();
            refreshState();
        }

        void focus() { SwingUtilities.invokeLater(input::requestFocusInWindow); }

        void clear() {
            events.setText("");
            rawArea.setText("");
        }

        private void sendInput() {
            String t = input.getText().trim();
            if (t.isEmpty()) return;
            if (!server.isRunning()) {
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            history.add(t);
            historyPos = history.size();
            server.command(t);
            input.setText("");
        }

        void refreshState() {
            if (!Kit.hasServer()) return;
            boolean on = server.isRunning();
            send.setEnabled(on);
            input.placeholder = on ? "Type a command, for example:  time set day" : "Start the server to type commands";
            input.repaint();
            for (Ui.Btn b : quick) b.setEnabled(on);
        }

        void add(Server.Event e) {
            StyledDocument doc = events.getStyledDocument();
            SimpleAttributeSet time = new SimpleAttributeSet();
            StyleConstants.setForeground(time, Ui.FAINT);
            StyleConstants.setFontFamily(time, Ui.FAMILY);
            StyleConstants.setFontSize(time, 13);
            SimpleAttributeSet text = new SimpleAttributeSet();
            StyleConstants.setForeground(text, colorFor(e.kind()));
            StyleConstants.setFontFamily(text, e.text().startsWith("    ") ? Ui.MONO : Ui.FAMILY);
            StyleConstants.setFontSize(text, e.text().startsWith("    ") ? 12 : 14);
            if (e.kind() == Server.Kind.JOIN || e.kind() == Server.Kind.ERROR) StyleConstants.setBold(text, !e.text().startsWith("    "));
            try {
                JScrollBar bar = ((JScrollPane) events.getParent().getParent()).getVerticalScrollBar();
                boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
                doc.insertString(doc.getLength(), String.format("%-9s", e.time().format(CLOCK)) + "  ", time);
                doc.insertString(doc.getLength(), e.text() + "\n", text);
                if (doc.getLength() > 200_000) doc.remove(0, 50_000);
                if (atBottom) events.setCaretPosition(doc.getLength());
            } catch (BadLocationException | ClassCastException ignored) { }
        }

        /** Called from the server's output thread. */
        void raw(String line) {
            synchronized (rawPending) {
                rawPending.append(line).append('\n');
                if (rawPending.length() > 400_000) rawPending.delete(0, 200_000);
            }
        }

        private void flushRaw() {
            String chunk;
            synchronized (rawPending) {
                if (rawPending.length() == 0) return;
                chunk = rawPending.toString();
                rawPending.setLength(0);
            }
            rawArea.append(chunk);
            Document d = rawArea.getDocument();
            if (d.getLength() > 400_000) {
                try { d.remove(0, d.getLength() - 300_000); } catch (BadLocationException ignored) { }
            }
            rawArea.setCaretPosition(d.getLength());
        }
    }

    // ================================================================== Play online

    final class OnlinePage {
        final JPanel root = page();
        private final Ui.Stack body = new Ui.Stack(16);
        private String key = "";

        OnlinePage() {
            root.add(body, BorderLayout.NORTH);
            refresh();
        }

        void refresh() {
            String k = online.state() + "|" + online.message() + "|" + online.live() + "|" + online.autoShare() + "|"
                    + Online.javaAddress() + "|" + Online.bedrockHost() + "|" + online.bedrockProblem() + "|"
                    + server.state() + "|" + online.isWorking() + "|" + server.xboxCode + "|" + Kit.get("xbox.gamertag", "")
                    + "|" + (Kit.hasServer() && Files.exists(Setup.xboxJar())) + "|" + xboxBusy;
            if (k.equals(key)) return;
            key = k;
            body.removeAll();
            switch (online.state()) {
                case UNSUPPORTED -> unsupported();
                case NOT_LINKED -> intro();
                case LINKING -> linking();
                case WORKING -> working();
                case READY -> ready();
                case MANUAL -> manual();
                case ERROR -> error();
            }
            if (Kit.hasServer()) body.put(xboxCard());
            body.revalidate();
            body.repaint();
        }

        private Ui.Card card(Component... parts) {
            Ui.Card c = new Ui.Card();
            Ui.Stack s = new Ui.Stack(12);
            for (Component p : parts) s.put(p);
            c.add(s);
            return c;
        }

        private JComponent step(String n, String title, String text) {
            JLabel num = new JLabel(n, SwingConstants.CENTER) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    Ui.aa(g2);
                    g2.setColor(Ui.GREEN_DIM);
                    g2.fillOval(0, 0, getWidth(), getHeight());
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            num.setFont(Ui.bold(14));
            num.setForeground(Ui.GREEN_HI);
            num.setPreferredSize(new Dimension(32, 32));
            Ui.Stack t = new Ui.Stack(2);
            t.put(Ui.label(title, Ui.bold(14.5f), Ui.TEXT));
            t.put(Ui.para(text, Ui.font(13.5f), Ui.MUTED));
            JPanel p = new JPanel(new BorderLayout(14, 0));
            p.setOpaque(false);
            JPanel numHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            numHolder.setOpaque(false);
            numHolder.add(num);
            p.add(numHolder, BorderLayout.WEST);
            p.add(t, BorderLayout.CENTER);
            return p;
        }

        private volatile boolean xboxBusy;

        /** Xbox / PlayStation / Switch players: MCXboxBroadcast shows the server in their Friends tab. */
        private JComponent xboxCard() {
            Ui.Card c = new Ui.Card();
            Ui.Stack s = new Ui.Stack(12);
            s.put(Ui.cardTitle("Xbox, PlayStation and Switch players"));
            String tag = Kit.get("xbox.gamertag", null);
            String code = server.xboxCode;
            boolean installed = (Kit.hasServer() && Files.exists(Setup.xboxJar()));
            if (!Setup.crossplay()) {
                s.put(Ui.para("Bedrock and Xbox players are turned off for " + Kit.serverName() + ", so only Java players "
                        + "can join it.", Ui.font(13.5f), Ui.MUTED));
            } else if (!installed) {
                s.put(Ui.para("Consoles can't type in a server address. The free MCXboxBroadcast add-on fixes that: it signs in "
                        + "to an Xbox account and your server shows up in console friends' Friends tab. Use a spare Microsoft "
                        + "account; the add-on's makers warn the account could be banned.", Ui.font(13.5f), Ui.MUTED));
                Ui.Btn add = new Ui.Btn(xboxBusy ? "Downloading..." : "Add console support", Ui.Kind.SECONDARY).onClick(this::installXbox);
                add.setEnabled(!xboxBusy);
                s.put(Ui.row(10, add, Ui.glue()));
            } else if (code != null) {
                s.put(Ui.label("Sign in to finish setup", Ui.bold(16), Ui.AMBER));
                s.put(Ui.para("Go to microsoft.com/link, enter the code below, and sign in with the spare Microsoft account the "
                        + "server will use.", Ui.font(13.5f), Ui.MUTED));
                s.put(Ui.row(12, Ui.label(code, Ui.mono(22), Ui.TEXT), Ui.copyButton("Copy code", () -> code),
                        new Ui.Btn("Open microsoft.com/link", Ui.Kind.PRIMARY).small().onClick(() -> Kit.browse("https://www.microsoft.com/link")),
                        Ui.glue()));
            } else if (tag != null) {
                JLabel t = Ui.label("Console friends add  " + tag + "  as a friend on Xbox", Ui.bold(15), Ui.TEXT);
                t.setIcon(Ui.dot(server.isRunning() ? Ui.GREEN_HI : Ui.FAINT, 10));
                t.setIconTextGap(10);
                s.put(t);
                s.put(Ui.para("Then, while the server is running, it shows up in Minecraft under Play > Friends. They still "
                        + "need to be on the whitelist: add their gamertag as a Bedrock player on the Players page.",
                        Ui.font(13.5f), Ui.MUTED));
                s.put(Ui.row(10, Ui.copyButton("Copy gamertag", () -> tag), Ui.glue()));
            } else {
                s.put(Ui.para(server.isRunning()
                        ? "Console support is installed. Restart the server to sign in its Xbox account."
                        : "Console support is installed. Start the server, then sign in its Xbox account with the code shown here.",
                        Ui.font(13.5f), Ui.MUTED));
            }
            c.add(s);
            return c;
        }

        private void installXbox() {
            if (!Dialogs.confirm(frame, "Add console support?",
                    "This downloads the free MCXboxBroadcast add-on from GitHub. You'll sign in with a spare Microsoft "
                            + "account (not your main one); its makers warn the account could be banned.", "Add it", Ui.Kind.PRIMARY))
                return;
            xboxBusy = true;
            refreshSoon();
            Thread t = new Thread(() -> {
                String err = null;
                try {
                    Files.createDirectories(Setup.xboxJar().getParent());
                    Path tmp = Kit.download("https://github.com/MCXboxBroadcast/Broadcaster/releases/latest/download/MCXboxBroadcastExtension.jar",
                            Setup.xboxJar(), "Downloading console support", null);
                    Files.move(tmp, Setup.xboxJar(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    err = Kit.describe(e);
                }
                xboxBusy = false;
                String e2 = err;
                ui(() -> {
                    if (e2 != null) Dialogs.info(frame, "Couldn't add console support", e2);
                    else server.event(Server.Kind.SYSTEM, server.isRunning()
                            ? "Console support added. Restart the server to sign in its Xbox account."
                            : "Console support added. Start the server to sign in its Xbox account.");
                    key = "";
                    refresh();
                });
            }, "xbox-install");
            t.setDaemon(true);
            t.start();
        }

        private void refreshSoon() { ui(() -> { key = ""; refresh(); }); }

        private void unsupported() {
            body.put(card(Ui.cardTitle("Not available on this computer"),
                    Ui.para("Automatic online play works on Windows and Linux. On a Mac, install the playit program from "
                            + "playit.gg/download and add a Minecraft Java tunnel (port 25565) and a Minecraft Bedrock "
                            + "tunnel (port 19132).", Ui.font(14), Ui.MUTED),
                    Ui.row(0, new Ui.Btn("Open playit.gg/download", Ui.Kind.SECONDARY).onClick(() -> Kit.browse("https://playit.gg/download")), Ui.glue())));
        }

        private void intro() {
            Ui.Card hero = new Ui.Card();
            Ui.Stack s = new Ui.Stack(14);
            s.put(Ui.label("Play with friends anywhere", Ui.bold(20), Ui.TEXT));
            s.put(Ui.para("Right now only people on your Wi-Fi can join. Online play gives your server a public address "
                    + "using playit.gg, a free service built for game servers. No router settings, and your home IP "
                    + "stays private.", Ui.font(14.5f), Ui.MUTED));
            s.put(step("1", "Link your free playit account", "Your browser opens. Sign up or log in, then approve this PC."));
            s.put(step("2", "The app makes your addresses", "One for Java players and one for Bedrock players. This takes about a minute."));
            s.put(step("3", "Share them with friends", "They stay the same, and go live whenever your server is running."));
            Ui.Btn go = new Ui.Btn("Set up online play", Ui.Kind.PRIMARY).big().onClick(online::setUp);
            s.put(Ui.row(12, go, Ui.label("Free. Takes about 2 minutes.", Ui.font(13), Ui.FAINT), Ui.glue()));
            hero.add(s);
            body.put(hero);
        }

        private void linking() {
            Ui.Btn again = new Ui.Btn("Open the page again", Ui.Kind.SECONDARY).onClick(() -> {
                if (online.claimUrl() != null) Kit.browse(online.claimUrl());
            });
            Ui.Btn cancel = new Ui.Btn("Cancel", Ui.Kind.SUBTLE).onClick(online::cancel);
            Ui.Field url = new Ui.Field("", 30);
            url.setText(online.claimUrl() == null ? "" : online.claimUrl());
            url.setEditable(false);
            url.setFont(Ui.mono(13));
            body.put(card(Ui.cardTitle("Step 1 of 2"),
                    Ui.label("Approve this PC in your browser", Ui.bold(20), Ui.TEXT),
                    Ui.para("A playit.gg page just opened. Create a free account or log in, then approve the program. "
                            + "This screen moves on by itself as soon as you do.", Ui.font(14.5f), Ui.MUTED),
                    Ui.row(10, waitingDots("Waiting for you in the browser"), Ui.glue()),
                    Ui.row(10, again, cancel, Ui.glue()),
                    Ui.label("Browser didn't open? Copy this link into it:", Ui.font(12.5f), Ui.FAINT),
                    Ui.row(10, url, Ui.copyButton(online::claimUrl))));
        }

        private void working() {
            if (!online.isWorking() && online.linked()) {
                body.put(card(Ui.cardTitle("Almost done"),
                        Ui.label("Finish setting up online play", Ui.bold(20), Ui.TEXT),
                        Ui.para("This PC is linked to playit. One more step creates your addresses.", Ui.font(14.5f), Ui.MUTED),
                        Ui.row(10, new Ui.Btn("Finish setup", Ui.Kind.PRIMARY).onClick(online::retry), Ui.glue())));
                return;
            }
            body.put(card(Ui.cardTitle("Setting up"),
                    Ui.label("Getting your addresses ready", Ui.bold(20), Ui.TEXT),
                    Ui.row(10, waitingDots(online.message().isEmpty() ? "Working" : online.message()), Ui.glue()),
                    Ui.row(10, new Ui.Btn("Cancel", Ui.Kind.SUBTLE).onClick(online::cancel), Ui.glue())));
        }

        private void ready() {
            boolean live = online.live();
            boolean running = server.isRunning();
            Ui.Card status = new Ui.Card();
            status.bg = live ? Ui.GREEN_DIM : Ui.CARD;
            String title = live ? "You're online" : running ? "Connecting..." : "Ready. Goes live when the server runs";
            String text = live ? "Friends anywhere can join right now with the addresses below."
                    : running ? "Connecting the tunnel. This only takes a few seconds."
                    : "Your addresses are saved. They work whenever the server is running.";
            Ui.Stack ss = new Ui.Stack(6);
            JLabel tl = Ui.label(title, Ui.bold(20), Ui.TEXT);
            tl.setIcon(Ui.dot(live ? Ui.GREEN_HI : running ? Ui.AMBER : Ui.FAINT, 12));
            tl.setIconTextGap(12);
            ss.put(tl);
            ss.put(Ui.para(text, Ui.font(14), Ui.MUTED));
            status.add(ss);
            body.put(status);

            JPanel addrs = new JPanel(new GridLayout(1, 2, 16, 0));
            addrs.setOpaque(false);
            String ja = Online.javaAddress();
            addrs.add(addressCard("Java edition", "Multiplayer > Add Server > Server Address", ja, null, () -> ja));
            String bh = Online.bedrockHost();
            if (bh != null) {
                String bp = String.valueOf(Online.bedrockPort());
                addrs.add(addressCard("Bedrock edition", "Servers > Add Server, then type the address and port", bh, bp, () -> bh));
            } else {
                Ui.Card c = new Ui.Card();
                c.add(new Ui.Stack(10).put(Ui.cardTitle("Bedrock edition"))
                        .put(Ui.para(online.bedrockProblem() != null ? online.bedrockProblem() : "Not ready yet.",
                                Ui.font(13.5f), Ui.AMBER))
                        .put(Ui.row(0, new Ui.Btn("Check again", Ui.Kind.SECONDARY).small().onClick(online::retry), Ui.glue())));
                addrs.add(c);
            }
            body.put(addrs);

            Ui.Switch auto = new Ui.Switch(online.autoShare());
            auto.onChange(online::setAutoShare);
            Ui.Card opts = new Ui.Card();
            Ui.Stack os = new Ui.Stack(14);
            os.put(Ui.cardTitle("Options"));
            os.put(Ui.row(12, auto, Ui.label("Go online automatically when the server starts", Ui.font(14), Ui.TEXT), Ui.glue()));
            os.put(Ui.para("Remember: friends still need to be on the whitelist (Players page). Consoles (Xbox, PlayStation, "
                    + "Switch) can't type in addresses, so console players need a workaround; see the Help guide.",
                    Ui.font(13), Ui.FAINT));
            Ui.Btn manage = new Ui.Btn("Manage on playit.gg", Ui.Kind.SECONDARY).small().onClick(() -> Kit.browse("https://playit.gg/account/tunnels"));
            Ui.Btn check = new Ui.Btn("Re-check addresses", Ui.Kind.SECONDARY).small().onClick(online::retry);
            Ui.Btn unlink = new Ui.Btn("Unlink this PC", Ui.Kind.SUBTLE).small().onClick(() -> {
                if (Dialogs.confirm(frame, "Unlink playit?", "Online play turns off on this PC. You can set it up again any time.",
                        "Unlink", Ui.Kind.DANGER)) online.unlink();
            });
            check.setEnabled(!online.isWorking());
            os.put(Ui.row(10, manage, check, unlink, Ui.glue()));
            opts.add(os);
            body.put(opts);
        }

        private JComponent addressCard(String title, String hint, String addr, String port, java.util.function.Supplier<String> copy) {
            Ui.Card c = new Ui.Card();
            Ui.Stack s = new Ui.Stack(10);
            s.put(Ui.cardTitle(title));
            JLabel a = Ui.label(addr, Ui.mono(17), Ui.TEXT);
            s.put(a);
            if (port != null) s.put(Ui.row(6, Ui.label("Port", Ui.font(13), Ui.MUTED), Ui.label(port, Ui.mono(17), Ui.TEXT), Ui.glue()));
            s.put(Ui.row(10, Ui.copyButton("Copy address", copy),
                    port != null ? Ui.copyButton("Copy port", () -> port) : Box.createHorizontalStrut(0), Ui.glue()));
            s.put(Ui.label(hint, Ui.font(12.5f), Ui.FAINT));
            s.fill();
            c.add(s);
            return c;
        }

        private void manual() {
            body.put(card(Ui.cardTitle("One manual step"),
                    Ui.label("Add the tunnels on playit.gg", Ui.bold(20), Ui.TEXT),
                    Ui.para((online.message().isEmpty() ? "" : online.message() + " ")
                            + "On the playit website, open this agent and add two tunnels: \"Minecraft Java\" with local "
                            + "port 25565, and \"Minecraft Bedrock\" with local port 19132. Then come back and click Check again.",
                            Ui.font(14), Ui.MUTED),
                    Ui.row(10, new Ui.Btn("Open playit.gg", Ui.Kind.PRIMARY).onClick(() -> Kit.browse("https://playit.gg/account/agents")),
                            new Ui.Btn("Check again", Ui.Kind.SECONDARY).onClick(online::retry),
                            new Ui.Btn("Unlink", Ui.Kind.SUBTLE).onClick(online::unlink), Ui.glue())));
        }

        private void error() {
            body.put(card(Ui.cardTitle("Something went wrong"),
                    Ui.label("Online play isn't set up yet", Ui.bold(20), Ui.TEXT),
                    Ui.para(online.message(), Ui.font(14), Ui.RED_HI),
                    Ui.row(10, new Ui.Btn("Try again", Ui.Kind.PRIMARY).onClick(online::retry),
                            online.linked() ? new Ui.Btn("Unlink and start over", Ui.Kind.SUBTLE).onClick(online::unlink)
                                    : Box.createHorizontalStrut(0), Ui.glue())));
        }

        private JComponent waitingDots(String text) {
            JLabel l = Ui.label(text, Ui.font(14), Ui.AMBER);
            int[] n = {0};
            Timer t = new Timer(450, e -> l.setText(text + ".".repeat(n[0]++ % 4)));
            t.start();
            l.addHierarchyListener(e -> { if (!l.isShowing() && l.getParent() == null) t.stop(); });
            return l;
        }
    }

    // ================================================================== Settings

    final class SettingsPage {
        final JPanel root = page();
        private final Ui.Field motd = new Ui.Field("Message shown in the server list", 30);
        private final Ui.Choice gamemode = new Ui.Choice(List.of("Survival", "Creative", "Adventure"), "Survival");
        private final Ui.Choice difficulty = new Ui.Choice(List.of("Peaceful", "Easy", "Normal", "Hard"), "Normal");
        private final Ui.Choice maxPlayers = new Ui.Choice(List.of("2", "4", "5", "6", "8", "10", "12", "16", "20"), "10");
        private final Ui.Switch pvp = new Ui.Switch(true);
        private final Ui.Switch whitelist = new Ui.Switch(true);
        private final JLabel whitelistWarn = Ui.label("Anyone who knows the address can join.", Ui.font(12.5f), Ui.AMBER);
        private final JLabel saveNote = Ui.label(" ", Ui.font(13), Ui.MUTED);
        private final Ui.Btn saveBtn = new Ui.Btn("Save changes", Ui.Kind.PRIMARY);
        private final Ui.Btn restartBtn2 = new Ui.Btn("Restart now", Ui.Kind.SECONDARY).small();
        private final Ui.Choice ram;
        private final JLabel software = Ui.label("", Ui.font(14), Ui.TEXT);
        private final Ui.Btn update = new Ui.Btn("Check for updates", Ui.Kind.SECONDARY);
        private final Ui.Btn jump = new Ui.Btn("Switch to new Minecraft", Ui.Kind.PRIMARY);
        private final Ui.Switch autoServer = new Ui.Switch(Kit.getBool("server.autoupdate", true));
        private final Ui.Switch autoApp = new Ui.Switch(Kit.getBool("app.autoupdate", true));
        private final Ui.Switch antiCheat = new Ui.Switch(true);
        private final JLabel antiCheatNote = Ui.label(" ", Ui.font(12.5f), Ui.FAINT);
        private final Ui.Card acCard = new Ui.Card();
        private final Ui.Btn appCheck = new Ui.Btn("Check for app update", Ui.Kind.SECONDARY);
        final JLabel appNote = Ui.label("", Ui.font(12.5f), Ui.FAINT);
        private final JLabel updateNote = Ui.label("", Ui.font(12.5f), Ui.FAINT);
        private final Ui.Choice backupChoice = new Ui.Choice(List.of(""), "");
        private final JPanel backupHolder = new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        private final Ui.Btn backupNow = new Ui.Btn("Back up now", Ui.Kind.SECONDARY);
        private final Ui.Btn restore = new Ui.Btn("Restore...", Ui.Kind.SECONDARY);
        private final JLabel backupNote = Ui.label(" ", Ui.font(12.5f), Ui.FAINT);
        private List<Path> backups = List.of();

        SettingsPage() {
            long totalGb = Math.max(2, Kit.totalRamMb() / 1024);
            List<String> ramOpts = new ArrayList<>();
            for (int g : new int[]{2, 3, 4, 6, 8, 10, 12}) if (g <= Math.max(2, totalGb - 2)) ramOpts.add(g + " GB");
            if (ramOpts.isEmpty()) ramOpts.add("2 GB");
            ram = new Ui.Choice(ramOpts, Kit.ram().replace("G", " GB"));
            ram.onChange(v -> Kit.set("ram", v.replace(" GB", "G")));

            for (JComponent c : List.of(gamemode, difficulty, maxPlayers)) c.setPreferredSize(new Dimension(180, 40));
            motd.setMaximumSize(new Dimension(520, 44));
            whitelist.onChange(on -> whitelistWarn.setVisible(!on));
            saveBtn.addActionListener(e -> save());
            restartBtn2.addActionListener(e -> server.restart());

            Ui.Card game = new Ui.Card();
            JPanel form = new JPanel(new GridBagLayout());
            form.setOpaque(false);
            int r = 0;
            formRow(form, r++, "Server name (MOTD)", motd);
            formRow(form, r++, "Game mode", gamemode);
            formRow(form, r++, "Difficulty", difficulty);
            formRow(form, r++, "Max players", maxPlayers);
            formRow(form, r++, "Player vs player", pvp);
            formRow(form, r++, "Whitelist only", Ui.row(12, whitelist, whitelistWarn));
            Ui.Stack gs = new Ui.Stack(14);
            gs.put(Ui.cardTitle("Game"));
            gs.put(form);
            gs.put(Ui.row(12, saveBtn, saveNote, restartBtn2, Ui.glue()));
            game.add(gs);

            Ui.Card perf = new Ui.Card();
            Ui.Stack ps = new Ui.Stack(12);
            ps.put(Ui.cardTitle("Performance"));
            ps.put(Ui.row(12, Ui.label("Memory for the server", Ui.font(14), Ui.TEXT), ram, Ui.glue()));
            ps.put(Ui.para("This PC has about " + totalGb + " GB of memory. 4 GB is plenty for 1-5 players. Changes apply the next "
                    + "time the server starts.", Ui.font(13), Ui.FAINT));
            perf.add(ps);

            Ui.Card back = new Ui.Card();
            backupHolder.setOpaque(false);
            backupNow.addActionListener(e -> backupNow());
            restore.addActionListener(e -> restoreSelected());
            Ui.Stack bs = new Ui.Stack(12);
            bs.put(Ui.cardTitle("Backups"));
            bs.put(Ui.para("Your world is backed up automatically every time the server starts (the newest 5 are kept).",
                    Ui.font(13.5f), Ui.MUTED));
            bs.put(Ui.row(10, backupHolder, restore, backupNow,
                    new Ui.Btn("Open folder", Ui.Kind.GHOST).small().onClick(() -> {
                        try { Files.createDirectories(Kit.backups()); } catch (Exception ignored) { }
                        Kit.open(Kit.backups());
                    }), Ui.glue()));
            bs.put(backupNote);
            back.add(bs);

            Ui.Card soft = new Ui.Card();
            update.setText("Check now");
            update.addActionListener(e -> server.checkUpdatesNow(() -> ui(this::refresh)));
            jump.addActionListener(e -> {
                String v = Setup.newerMinecraft();
                if (v == null || !Dialogs.confirm(frame, "Switch to Minecraft " + v + "?",
                        "Java players will need Minecraft " + v + " to join after this (Bedrock and Xbox players aren't affected). "
                                + "Your world is backed up first and upgraded; it can't go back to the old version.",
                        "Switch to " + v, Ui.Kind.PRIMARY)) return;
                try { Setup.backupWorlds("before-" + v); } catch (Exception ignored) { }
                server.updateSoftware(() -> ui(this::refresh));
            });
            autoServer.onChange(on -> Kit.set("server.autoupdate", on));
            Ui.Stack ss = new Ui.Stack(12);
            ss.put(Ui.cardTitle("Server software"));
            ss.put(software);
            ss.put(Ui.row(12, autoServer, Ui.label("Update automatically every time the server starts", Ui.font(14), Ui.TEXT), Ui.glue()));
            ss.put(Ui.para("Keeps the Minecraft server, Bedrock support, Floodgate and console support current, so Bedrock "
                    + "and Xbox friends can still join after their game updates. New Minecraft versions wait for you, "
                    + "because Java players have to update their game to match.", Ui.font(13), Ui.FAINT));
            ss.put(Ui.row(12, update, jump, updateNote, Ui.glue()));
            soft.add(ss);

            Ui.Card appCard = new Ui.Card();
            autoApp.onChange(on -> Kit.set("app.autoupdate", on));
            appCheck.addActionListener(e -> checkAppUpdate(true));
            Ui.Stack as2 = new Ui.Stack(12);
            as2.put(Ui.cardTitle("This app"));
            as2.put(Ui.label("Version " + Kit.VERSION, Ui.font(14), Ui.TEXT));
            as2.put(Ui.row(12, autoApp, Ui.label("Update the app automatically", Ui.font(14), Ui.TEXT), Ui.glue()));
            as2.put(Ui.para("New versions are published to github.com/schoolmcst/minecraft-server-app. The app checks when it "
                    + "opens and every few hours; an update found while it's open installs the next time you open it.",
                    Ui.font(13), Ui.FAINT));
            as2.put(Ui.row(12, appCheck, appNote, Ui.glue()));
            appCard.add(as2);

            Ui.Card files = new Ui.Card();
            Ui.Stack fs = new Ui.Stack(12);
            fs.put(Ui.cardTitle("Shortcuts and files"));
            List<Component> fb = new ArrayList<>();
            if (Kit.WINDOWS) fb.add(new Ui.Btn("Add desktop shortcut", Ui.Kind.SECONDARY).onClick(this::shortcut));
            fb.add(new Ui.Btn("Open server folder", Ui.Kind.SECONDARY).onClick(() -> Kit.open(Kit.dir())));
            fb.add(new Ui.Btn("Open full log", Ui.Kind.SECONDARY).onClick(() -> {
                Path log = Kit.dir().resolve("logs").resolve("latest.log");
                Kit.open(Files.exists(log) ? log : Kit.dir());
            }));
            fb.add(Ui.glue());
            fs.put(Ui.row(10, fb.toArray(new Component[0])));
            files.add(fs);

            antiCheat.onChange(on -> {
                Kit.set("anticheat", on);
                boolean running = server.state() != Server.State.STOPPED && server.state() != Server.State.CRASHED;
                antiCheatNote.setText(running ? "Restart the server to " + (on ? "turn it on." : "turn it off.") : "");
                if (!running) AntiCheat.sync((t, f) -> { });
            });
            Ui.Stack acs = new Ui.Stack(12);
            acs.put(Ui.cardTitle("Anti-cheat"));
            acs.put(Ui.row(12, antiCheat, Ui.label("Stop cheaters", Ui.font(14), Ui.TEXT), antiCheatNote, Ui.glue()));
            acs.put(Ui.para("Grim Anticheat catches hacked clients (flying, speed, reach, kill aura, auto-clickers) and X-ray is "
                    + "blocked by hiding ores until a player is right next to them. Bedrock and Xbox players are skipped, so they "
                    + "never get kicked by mistake. When someone gets flagged, it shows up in Recent activity.", Ui.font(13), Ui.FAINT));
            acCard.add(acs);

            Ui.Stack page = new Ui.Stack(16);
            page.put(game);
            page.put(perf);
            page.put(back);
            page.put(soft);
            page.put(appCard);
            page.put(files);
            root.add(page, BorderLayout.NORTH);
            refresh();
        }

        private void formRow(JPanel form, int r, String label, JComponent c) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = r; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(6, 0, 6, 24);
            form.add(Ui.label(label, Ui.font(14), Ui.TEXT), g);
            g = new GridBagConstraints();
            g.gridx = 1; g.gridy = r; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(6, 0, 6, 0);
            g.weightx = 1;
            if (c instanceof Ui.Field) { g.fill = GridBagConstraints.HORIZONTAL; }
            form.add(c, g);
        }

        void refresh() {
            if (!Kit.hasServer()) return;
            Map<String, String> p = Setup.readProps();
            motd.setText(p.getOrDefault("motd", "A Minecraft Server"));
            gamemode.setValue(cap(p.getOrDefault("gamemode", "survival")));
            difficulty.setValue(cap(p.getOrDefault("difficulty", "normal")));
            maxPlayers.setValue(p.getOrDefault("max-players", "10"));
            pvp.setOn(!"false".equals(p.get("pvp")));
            boolean wl = !"false".equals(p.get("white-list"));
            whitelist.setOn(wl);
            whitelistWarn.setVisible(!wl);
            restartBtn2.setVisible(false);
            saveNote.setText(" ");
            acCard.setVisible(AntiCheat.applies());
            antiCheat.setOn(Kit.getBool("anticheat", true));
            antiCheatNote.setText(AntiCheat.enabled() && AntiCheat.installedLabel() == null ? "Turns on the next time the server starts." : "");
            refreshBackups();
            refreshState();
        }

        void refreshState() {
            if (!Kit.hasServer()) return;
            String v = Kit.mcVersion();
            String build = Kit.get("paper.build", null);
            software.setText(v.isEmpty() ? "Not downloaded yet (happens on the first start)."
                    : "Minecraft " + v + "   ·   " + softwareLine(true));
            boolean off = server.state() == Server.State.STOPPED || server.state() == Server.State.CRASHED;
            update.setEnabled(off);
            String newer = Setup.newerMinecraft();
            jump.setVisible(newer != null);
            if (newer != null) jump.setText("Switch to Minecraft " + newer);
            jump.setEnabled(off);
            updateNote.setText(off ? "" : server.state() == Server.State.PREPARING ? server.detail() : "Stop the server first.");
            restore.setEnabled(off && !backups.isEmpty());
            backupNow.setEnabled(server.state() != Server.State.PREPARING && server.state() != Server.State.STOPPING);
        }

        private void refreshBackups() {
            backups = Setup.listBackups();
            List<String> labels = new ArrayList<>();
            for (Path b : backups) labels.add(Setup.backupLabel(b));
            if (labels.isEmpty()) labels.add("No backups yet");
            backupChoice.setValue(labels.get(0));
            Ui.Choice c = new Ui.Choice(labels, labels.get(0));
            backupHolder.removeAll();
            backupHolder.add(c);
            backupHolder.putClientProperty("choice", c);
            backupHolder.revalidate();
            restore.setEnabled(!backups.isEmpty() && server.state() == Server.State.STOPPED);
        }

        private Path selectedBackup() {
            Ui.Choice c = (Ui.Choice) backupHolder.getClientProperty("choice");
            if (c == null) return null;
            for (Path b : backups) if (Setup.backupLabel(b).equals(c.value())) return b;
            return null;
        }

        private void save() {
            Map<String, String> ch = new LinkedHashMap<>();
            ch.put("motd", motd.getText());
            ch.put("gamemode", gamemode.value().toLowerCase(Locale.ROOT));
            ch.put("difficulty", difficulty.value().toLowerCase(Locale.ROOT));
            ch.put("max-players", maxPlayers.value());
            ch.put("pvp", String.valueOf(pvp.isOn()));
            ch.put("white-list", String.valueOf(whitelist.isOn()));
            ch.put("enforce-whitelist", String.valueOf(whitelist.isOn()));
            try {
                Setup.writeProps(ch);
                if (server.isRunning()) {
                    server.command("difficulty " + difficulty.value().toLowerCase(Locale.ROOT));
                    server.command("whitelist " + (whitelist.isOn() ? "on" : "off"));
                    saveNote.setText("Saved. Some changes apply after a restart.");
                    restartBtn2.setVisible(true);
                } else {
                    saveNote.setText("Saved.");
                }
                saveNote.setForeground(Ui.GREEN_HI);
                dashboard.refresh();
            } catch (Exception ex) {
                saveNote.setText("Couldn't save: " + Kit.describe(ex));
                saveNote.setForeground(Ui.RED_HI);
            }
        }

        private void backupNow() {
            backupNow.setEnabled(false);
            backupNote.setText("Backing up...");
            backupNote.setForeground(Ui.MUTED);
            boolean running = server.isRunning();
            Thread t = new Thread(() -> {
                String result;
                try {
                    if (running) {
                        server.send("save-off");
                        server.send("save-all flush");
                        Thread.sleep(4000);
                    }
                    Path z = Setup.backupWorlds("manual");
                    result = z == null ? "There's no world to back up yet." : "Saved " + z.getFileName() + ".";
                } catch (Exception e) {
                    result = "Backup failed: " + Kit.describe(e);
                } finally {
                    if (running) server.send("save-on");
                }
                String msg = result;
                ui(() -> {
                    backupNote.setText(msg);
                    backupNote.setForeground(msg.startsWith("Backup failed") ? Ui.RED_HI : Ui.GREEN_HI);
                    refreshBackups();
                    refreshState();
                });
            }, "backup");
            t.setDaemon(true);
            t.start();
        }

        private void restoreSelected() {
            Path b = selectedBackup();
            if (b == null) return;
            if (!Dialogs.confirm(frame, "Restore this backup?",
                    "Your world goes back to " + Setup.backupLabel(b).split(" {3}")[0]
                            + ". Your current world is backed up first, just in case.", "Restore", Ui.Kind.DANGER)) return;
            JDialog wait = Dialogs.busy(frame, "Restoring your world...");
            Thread t = new Thread(() -> {
                String err = null;
                try { Setup.restore(b); } catch (Exception e) { err = Kit.describe(e); }
                String e2 = err;
                ui(() -> {
                    wait.dispose();
                    backupNote.setText(e2 == null ? "Restored. Start the server to play." : "Restore failed: " + e2);
                    backupNote.setForeground(e2 == null ? Ui.GREEN_HI : Ui.RED_HI);
                    refreshBackups();
                });
            }, "restore");
            t.setDaemon(true);
            t.start();
            wait.setVisible(true);
        }

        void shortcut() {
            try {
                Setup.createDesktopShortcut();
                Dialogs.info(frame, "Shortcut added", "There's a \"Minecraft Server\" icon on your desktop now. It opens this app directly.");
            } catch (Exception e) {
                Dialogs.info(frame, "Couldn't add the shortcut", Kit.describe(e));
            }
        }

        private String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT); }
    }
}
