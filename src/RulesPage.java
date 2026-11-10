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
            strong = new Ui.Switch(false), alerts = new Ui.Switch(true), callout = new Ui.Switch(true),
            detect = new Ui.Switch(true), admins = new Ui.Switch(true), seeds = new Ui.Switch(true),
            kickHacked = new Ui.Switch(true), vanillaOnly = new Ui.Switch(false), graves = new Ui.Switch(true);
    private static final List<String> UNLOCK = List.of("15 minutes", "30 minutes", "1 hour", "2 hours", "Never (only the owner)");
    private static final int[] UNLOCK_MIN = {15, 30, 60, 120, 0};
    private final Ui.Choice unlock = new Ui.Choice(UNLOCK, "1 hour");
    private final Ui.Card gravesCard = new Ui.Card();
    private final XrayReport report;
    private final Ui.Field calloutText = new Ui.Field(AntiCheat.DEFAULT_CALLOUT, 24);
    private final JLabel calloutSaved = Ui.label(" ", Ui.font(12.5f), Ui.GREEN_HI);

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
        acSwitch(detect, "anticheat.xray.detect", true);
        acSwitch(admins, "anticheat.xray.admins", true);
        acSwitch(seeds, "anticheat.xray.seeds", true);
        detect.onChange(on -> { admins.setEnabled(on); admins.repaint(); });
        kickHacked.onChange(on -> Kit.set("anticheat.clients.kick", on));
        vanillaOnly.onChange(on -> Kit.set("anticheat.clients.vanillaOnly", on));
        alerts.onChange(on -> Kit.set("anticheat.alerts", on));
        callout.onChange(on -> { Kit.set("anticheat.callout", on); calloutText.setEnabled(on); });
        calloutText.setMaximumSize(new Dimension(360, 40));
        Ui.Btn saveCallout = new Ui.Btn("Save", Ui.Kind.SECONDARY).small();
        Runnable doSave = () -> {
            String t = calloutText.getText().trim();
            Kit.set("anticheat.callout.message", t.isEmpty() || t.equals(AntiCheat.DEFAULT_CALLOUT) ? null : t);
            calloutText.setText(AntiCheat.calloutMessage());
            calloutSaved.setText("Saved");
            javax.swing.Timer clear = new javax.swing.Timer(2000, e -> calloutSaved.setText(" "));
            clear.setRepeats(false);
            clear.start();
        };
        saveCallout.onClick(doSave);
        calloutText.addActionListener(e -> doSave.run());
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
        ac.put(row(detect, "Catch X-ray users by how they mine", "Watches every diamond, emerald and netherite find underground. "
                + "X-rayers give themselves away by turning toward ores they can't see and finding far more hidden veins than "
                + "luck allows. Honest miners, cave explorers and lucky streaks are not flagged. See the X-ray report below."));
        ac.put(row(admins, "Tell admins in the game", "Admins (ops) get a private message in chat when someone is flagged."));
        ac.put(row(seeds, "Shuffle ores in new areas", "Ores in newly explored land no longer match the world seed, so seed-based ore "
                + "finders (a kind of X-ray that works even when ores are hidden) point to the wrong places."));
        ac.put(row(kickHacked, "Kick hacked clients", "When a Java player joins, their game says what it is (normal Minecraft, Fabric, "
                + "Lunar Client...). Some hacked clients like Meteor or Wurst name themselves, and get kicked. You can see everyone's game "
                + "on the Players page. Careful cheaters can hide this, so the anti-cheat above still watches how they play."));
        ac.put(row(vanillaOnly, "Only allow normal Minecraft", "Also kicks players using modded games (Fabric, Forge). Lunar Client, "
                + "Badlion and Feather are still allowed. Bedrock and Xbox players are never affected."));
        ac.put(row(alerts, "Tell me when someone might be cheating", "Shows up in Recent activity on the Dashboard."));
        ac.put(row(callout, "Call out cheaters in chat", "Everyone sees a red message in chat when the anti-cheat catches someone "
                + "(once a minute at most). {player} is their name, {cheat} is what they did."));
        ac.put(Ui.row(10, Box.createHorizontalStrut(58), calloutText, saveCallout, calloutSaved, Ui.glue()));
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

        acSwitch(graves, "graves.enabled", true);
        graves.onChange(on -> { unlock.setEnabled(on); unlock.repaint(); });
        unlock.onChange(v -> {
            int i = UNLOCK.indexOf(v);
            Kit.set("graves.unlock", String.valueOf(UNLOCK_MIN[Math.max(0, i)]));
            Server.State st = app.server.state();
            if (st == Server.State.STOPPED || st == Server.State.CRASHED) AntiCheat.sync((t, f) -> { });
            else restartNeeded = true;
            refreshBanner();
        });
        gravesCard.add(new Ui.Stack(14)
                .put(Ui.row(10, Ui.cardTitle("Graves"), Ui.tag("Java + Bedrock + Xbox", Ui.MUTED), Ui.glue()))
                .put(row(graves, "Keep items safe in a grave", "When someone dies, their items and XP go into a grave (a head) where they "
                        + "died instead of dropping and disappearing. They get the coordinates in chat, and use or break the grave to get "
                        + "everything back, with armor put straight back on. Creepers, lava and water can't destroy it. Not needed if "
                        + "\"Keep items when you die\" is on."))
                .put(Ui.row(10, Box.createHorizontalStrut(58), Ui.label("Other players can open it after", Ui.font(13.5f), Ui.MUTED), unlock, Ui.glue())));

        report = new XrayReport(app);

        Ui.Stack page = new Ui.Stack(16);
        page.put(banner);
        page.put(antiCheatCard);
        page.put(gravesCard);
        page.put(report.card);
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
        callout.setOn(AntiCheat.callout());
        detect.setOn(Kit.getBool("anticheat.xray.detect", true));
        admins.setOn(AntiCheat.xrayAdmins());
        admins.setEnabled(detect.isOn());
        seeds.setOn(Kit.getBool("anticheat.xray.seeds", true));
        kickHacked.setOn(AntiCheat.kickHacked());
        gravesCard.setVisible(AntiCheat.applies());
        graves.setOn(Kit.getBool("graves.enabled", true));
        int um = AntiCheat.graveUnlockMinutes(), ui = 2;
        for (int i = 0; i < UNLOCK_MIN.length; i++) if (UNLOCK_MIN[i] == um) ui = i;
        unlock.setValue(UNLOCK.get(ui));
        unlock.setEnabled(graves.isOn());
        vanillaOnly.setOn(AntiCheat.kickModded());
        report.card.setVisible(AntiCheat.applies());
        report.refresh();
        calloutText.setText(AntiCheat.calloutMessage());
        calloutText.setEnabled(callout.isOn());
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
        if (report != null) report.refresh();
        refreshRules();
    }
}
