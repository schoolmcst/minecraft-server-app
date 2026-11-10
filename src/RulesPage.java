// The Rules page: on/off switches for anti-cheat (survival servers) and everyday game rules.
// Game rules change right away while the server runs; anti-cheat changes need a restart (Paper reads them at start).

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class RulesPage {
    final JPanel root = new JPanel(new BorderLayout());
    private final App app;
    private final Map<Rules.Rule, Ui.Switch> ruleSwitches = new LinkedHashMap<>();
    private final JPanel banner = new JPanel(new BorderLayout());
    private final JLabel bannerText = Ui.label(" ", Ui.font(13.5f), Ui.TEXT);
    private final Ui.Btn restart = new Ui.Btn("Restart now", Ui.Kind.PRIMARY).small();
    private final Ui.Card antiCheatCard = new Ui.Card();
    private final JLabel rulesNote = Ui.label(" ", Ui.font(12.5f), Ui.FAINT);
    private boolean restartNeeded;

    private final Ui.Switch grim = new Ui.Switch(true), xray = new Ui.Switch(true), nether = new Ui.Switch(true),
            strong = new Ui.Switch(false), alerts = new Ui.Switch(true);

    RulesPage(App app) {
        this.app = app;
        root.setBackground(Ui.BG);
        root.setBorder(new EmptyBorder(4, 32, 32, 32));

        // restart banner
        Ui.Card b = new Ui.Card(new BorderLayout(12, 0));
        b.bg = new Color(0x2B2A1E);
        b.setBorder(new EmptyBorder(12, 16, 12, 12));
        b.add(bannerText, BorderLayout.CENTER);
        b.add(restart, BorderLayout.EAST);
        restart.addActionListener(e -> {
            restartNeeded = false;
            app.server.restart();
            refresh();
        });
        banner.setOpaque(false);
        banner.add(b);
        banner.setVisible(false);

        // anti-cheat
        acSwitch(grim, "anticheat", true);
        acSwitch(xray, "anticheat.xray", true);
        acSwitch(nether, "anticheat.xray.nether", true);
        acSwitch(strong, "anticheat.xray.strong", false);
        alerts.onChange(on -> Kit.set("anticheat.alerts", on));
        xray.onChange(on -> { nether.setEnabled(on); strong.setEnabled(on); nether.repaint(); strong.repaint(); });
        Ui.Stack ac = new Ui.Stack(14);
        ac.put(Ui.row(10, Ui.cardTitle("Anti-cheat"), Ui.tag("Survival servers", Ui.MUTED), Ui.glue()));
        ac.put(row(grim, "Stop hacked clients", "Grim Anticheat catches flying, speed, reach, kill aura and auto-clickers. "
                + "Bedrock and Xbox players are skipped so they never get kicked by mistake."));
        ac.put(row(xray, "Hide ores from X-ray", "Diamonds, iron, gold and other ores look like stone until a player is right "
                + "next to them."));
        ac.put(row(nether, "Hide Nether ores too", "Ancient debris (netherite), Nether gold and quartz look like netherrack."));
        ac.put(row(strong, "Stronger X-ray protection", "Also shows fake ores everywhere, so X-ray is useless. Uses a bit more "
                + "internet and computer power."));
        ac.put(row(alerts, "Tell me when someone might be cheating", "Shows up in Recent activity on the Dashboard."));
        antiCheatCard.add(ac);

        // game rules
        Ui.Card rules = new Ui.Card();
        Ui.Stack rs = new Ui.Stack(14);
        rs.put(Ui.row(10, Ui.cardTitle("Game rules"), rulesNote, Ui.glue()));
        for (Rules.Rule r : Rules.GAME) {
            Ui.Switch sw = new Ui.Switch(r.defaultOn());
            sw.onChange(on -> {
                Rules.set(app.server, r, on);
                app.server.event(Server.Kind.SYSTEM, r.label() + ": " + (on ? "on" : "off")
                        + (app.server.isRunning() ? "." : " (applies when the server starts)."));
            });
            ruleSwitches.put(r, sw);
            rs.put(row(sw, r.label(), r.help()));
        }
        rules.add(rs);

        Ui.Stack page = new Ui.Stack(16);
        page.put(banner);
        page.put(antiCheatCard);
        page.put(rules);
        root.add(page, BorderLayout.NORTH);

        Rules.onChange = () -> SwingUtilities.invokeLater(this::refreshRules);
    }

    private void acSwitch(Ui.Switch sw, String key, boolean def) {
        sw.onChange(on -> {
            Kit.set(key, on);
            Server.State st = app.server.state();
            if (st == Server.State.STOPPED || st == Server.State.CRASHED) {
                AntiCheat.sync((t, f) -> { }); // apply to the files now; Grim downloads on the next start if needed
            } else {
                restartNeeded = true;
            }
            refreshBanner();
        });
    }

    private static JComponent row(Ui.Switch sw, String title, String help) {
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(sw, BorderLayout.NORTH);
        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setOpaque(false);
        p.add(left, BorderLayout.WEST);
        p.add(new Ui.Stack(2).put(Ui.label(title, Ui.bold(14), Ui.TEXT)).put(Ui.para(help, Ui.font(12.5f), Ui.FAINT)), BorderLayout.CENTER);
        return p;
    }

    void refresh() {
        if (!Kit.hasServer()) return;
        antiCheatCard.setVisible(AntiCheat.applies());
        grim.setOn(Kit.getBool("anticheat", true));
        xray.setOn(Kit.getBool("anticheat.xray", true));
        nether.setOn(Kit.getBool("anticheat.xray.nether", true));
        strong.setOn(Kit.getBool("anticheat.xray.strong", false));
        alerts.setOn(Kit.getBool("anticheat.alerts", true));
        nether.setEnabled(xray.isOn());
        strong.setEnabled(xray.isOn());
        refreshRules();
        if (app.server.isRunning()) Rules.ask(app.server);
        refreshBanner();
    }

    private void refreshRules() {
        boolean running = app.server.isRunning();
        for (var e : ruleSwitches.entrySet()) e.getValue().setOn(Rules.shownOn(e.getKey(), running));
        rulesNote.setText(running ? "Changes happen right away." : "Changes happen when the server starts.");
    }

    void refreshBanner() {
        Server.State st = app.server.state();
        boolean running = st != Server.State.STOPPED && st != Server.State.CRASHED;
        if (!running) restartNeeded = false;
        banner.setVisible(restartNeeded);
        bannerText.setText("Anti-cheat changes need a restart. Players get kicked for a moment and can rejoin right after.");
        restart.setEnabled(app.server.isRunning());
        root.revalidate();
        root.repaint();
    }

    /** Called when the server starts or stops. */
    void serverChanged() {
        refreshBanner();
        refreshRules();
    }
}
