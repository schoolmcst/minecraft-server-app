// The New server window.

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

// ======================================================================== New server

final class NewServerDialog {
    private NewServerDialog() { }

    static void open(App app) {
        if (app.server.state() != Server.State.STOPPED && app.server.state() != Server.State.CRASHED) {
            Dialogs.info(app.frame, "Stop the server first", "Only one server runs at a time. Stop the one that's running, "
                    + "then make the new one.");
            return;
        }
        JDialog d = new JDialog(app.frame, "New server", Dialog.ModalityType.APPLICATION_MODAL);
        d.setIconImages(Ui.appIcons());

        Ui.Field name = new Ui.Field("For example: Modded survival", 24);
        name.setText(Servers.list().isEmpty() ? "My server" : "");
        String[] type = {"paper"};
        Ui.Card survival = typeCard("Survival", "Works with Java, Bedrock and Xbox. Vanilla gameplay; supports plugins.", true);
        Ui.Card modded = typeCard("Modded", "Fabric mods from Modrinth. Every Java player needs the same mods.", false);
        Ui.Choice version = new Ui.Choice(List.of("Loading versions...", "                    "), "Loading versions...");
        version.setEnabled(false);
        Ui.Switch crossplay = new Ui.Switch(true);
        JLabel crossNote = Ui.label("Bedrock and Xbox friends can join.", Ui.font(12.5f), Ui.FAINT);
        Ui.Choice mode = new Ui.Choice(List.of("Survival", "Creative", "Adventure"), "Survival");
        Ui.Choice difficulty = new Ui.Choice(List.of("Peaceful", "Easy", "Normal", "Hard"), "Normal");
        boolean hasCurrent = Kit.hasServer() && Files.exists(Kit.dir().resolve("whitelist.json"));
        Ui.Switch copyPlayers = new Ui.Switch(hasCurrent);
        copyPlayers.setEnabled(hasCurrent);
        Path[] folder = {Servers.suggestFolder(name.getText().isBlank() ? "New server" : name.getText())};
        boolean[] folderPicked = {false};
        JLabel folderLabel = Ui.label(folder[0].toString(), Ui.font(12.5f), Ui.MUTED);
        JLabel status = Ui.label(" ", Ui.font(13), Ui.MUTED);
        Ui.Btn create = new Ui.Btn("Create server", Ui.Kind.PRIMARY);
        create.setEnabled(false);

        Map<String, List<String>> versions = new HashMap<>();
        Runnable loadVersions = () -> {
            String t = type[0];
            if (versions.containsKey(t)) { fill(version, versions.get(t)); create.setEnabled(true); return; }
            version.setEnabled(false);
            create.setEnabled(false);
            status.setText("Getting the list of Minecraft versions...");
            new Thread(() -> {
                try {
                    List<String> v = Setup.availableVersions(t);
                    SwingUtilities.invokeLater(() -> {
                        versions.put(t, v);
                        if (type[0].equals(t)) { fill(version, v); create.setEnabled(true); status.setText(" "); }
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> status.setText("Couldn't get versions: " + Kit.describe(e)));
                }
            }, "versions").start();
        };
        Runnable pickType = () -> {
            boolean fab = type[0].equals("fabric");
            survival.border = fab ? null : Ui.GREEN;
            modded.border = fab ? new Color(0xC9A0FF) : null;
            survival.repaint();
            modded.repaint();
            crossplay.setOn(!fab);
            crossNote.setText(fab ? "Off: Bedrock players can't see most modded things."
                    : "Bedrock and Xbox friends can join.");
            loadVersions.run();
        };
        clickable(survival, () -> { type[0] = "paper"; pickType.run(); });
        clickable(modded, () -> { type[0] = "fabric"; pickType.run(); });
        crossplay.onChange(on -> crossNote.setText(on
                ? (type[0].equals("fabric") ? "On: some modded things may look wrong on Bedrock." : "Bedrock and Xbox friends can join.")
                : "Only Java players can join."));
        name.getDocument().addDocumentListener((SimpleDoc) () -> {
            if (!folderPicked[0]) {
                folder[0] = Servers.suggestFolder(name.getText().isBlank() ? "New server" : name.getText());
                folderLabel.setText(folder[0].toString());
            }
        });
        Ui.Btn change = new Ui.Btn("Change...", Ui.Kind.GHOST).small().onClick(() -> {
            JFileChooser fc = new JFileChooser(Servers.defaultParent().toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Where should the server's folder go?");
            if (fc.showSaveDialog(d) != JFileChooser.APPROVE_OPTION) return;
            String n = name.getText().isBlank() ? "New server" : name.getText().replaceAll("[\\\\/:*?\"<>|]", "").trim();
            Path p = fc.getSelectedFile().toPath().resolve(n);
            folder[0] = p;
            folderPicked[0] = true;
            folderLabel.setText(p.toString());
        });

        JPanel types = new JPanel(new GridLayout(1, 2, 12, 0));
        types.setOpaque(false);
        types.add(survival);
        types.add(modded);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        int r = 0;
        formRow(form, r++, "Name", name);
        formRow(form, r++, "Minecraft version", version);
        formRow(form, r++, "Bedrock and Xbox", Ui.row(10, crossplay, crossNote));
        formRow(form, r++, "Game mode", mode);
        formRow(form, r++, "Difficulty", difficulty);
        formRow(form, r++, "Players", Ui.row(10, copyPlayers, Ui.label(hasCurrent
                ? "Copy the whitelist and admins from " + Kit.serverName() : "Add players after it's made", Ui.font(13), Ui.MUTED)));
        formRow(form, r++, "Folder", Ui.row(8, folderLabel, change));

        Ui.Stack body = new Ui.Stack(16);
        body.put(Ui.label("Make a new server", Ui.bold(20), Ui.TEXT));
        body.put(types);
        body.put(form);
        body.put(status);

        create.addActionListener(e -> {
            String n = name.getText().trim();
            if (n.isEmpty()) { status.setText("Give the server a name."); status.setForeground(Ui.AMBER); return; }
            if (Files.exists(folder[0]) && folder[0].toFile().list() != null && folder[0].toFile().list().length > 0) {
                status.setText("That folder isn't empty. Pick another name or folder.");
                status.setForeground(Ui.AMBER);
                return;
            }
            try {
                create(app, n, folder[0], type[0], version.value(), crossplay.isOn(), mode.value(), difficulty.value(), copyPlayers.isOn());
                d.dispose();
            } catch (Exception ex) {
                status.setText("Couldn't make the server: " + Kit.describe(ex));
                status.setForeground(Ui.RED_HI);
            }
        });

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Ui.CARD);
        p.setBorder(new EmptyBorder(24, 26, 20, 26));
        p.add(body, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        b.setOpaque(false);
        b.setBorder(new EmptyBorder(16, 0, 0, 0));
        b.add(Ui.row(10, new Ui.Btn("Cancel", Ui.Kind.SECONDARY).onClick(d::dispose), create));
        p.add(b, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.setSize(680, 640);
        d.setLocationRelativeTo(app.frame);
        pickType.run();
        d.setVisible(true);
    }

    interface SimpleDoc extends javax.swing.event.DocumentListener {
        void changed();
        default void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        default void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        default void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
    }

    private static void fill(Ui.Choice c, List<String> versions) {
        c.setOptions(versions.isEmpty() ? List.of("Newest") : versions);
        c.setValue(versions.isEmpty() ? "Newest" : versions.get(0));
        c.setEnabled(true);
    }

    private static Ui.Card typeCard(String title, String text, boolean selected) {
        Ui.Card c = new Ui.Card();
        c.bg = Ui.CARD_HI;
        c.border = selected ? Ui.GREEN : null;
        c.arc = 14;
        c.setBorder(new EmptyBorder(14, 16, 14, 16));
        JTextArea t = Ui.para(text, Ui.font(13), Ui.MUTED);
        c.add(new Ui.Stack(6).put(Ui.label(title, Ui.bold(16), Ui.TEXT)).put(t));
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return c;
    }

    private static void clickable(JComponent c, Runnable r) {
        java.awt.event.MouseAdapter m = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { r.run(); }
        };
        c.addMouseListener(m);
        for (Component k : c.getComponents()) addDeep(k, m);
    }

    private static void addDeep(Component c, java.awt.event.MouseListener m) {
        c.addMouseListener(m);
        if (c instanceof Container cc) for (Component k : cc.getComponents()) addDeep(k, m);
    }

    private static void formRow(JPanel form, int r, String label, JComponent c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = r; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(6, 0, 6, 20);
        form.add(Ui.label(label, Ui.font(14), Ui.TEXT), g);
        g = new GridBagConstraints();
        g.gridx = 1; g.gridy = r; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(6, 0, 6, 0); g.weightx = 1;
        if (c instanceof Ui.Field) g.fill = GridBagConstraints.HORIZONTAL;
        form.add(c, g);
    }

    /** Makes the folder and its starting files; the software downloads on the first Start, like any server. */
    static void create(App app, String name, Path dir, String type, String version, boolean crossplay, String mode,
                       String difficulty, boolean copyPlayers) throws Exception {
        Path from = Kit.dir();
        Files.createDirectories(dir);
        String motd = "\u00a7a" + name + " \u00a77- " + (crossplay ? "Java & Bedrock welcome" : "Java");
        Files.writeString(dir.resolve("server.properties"), String.join(System.lineSeparator(),
                "#Minecraft server properties",
                "#Made by the Minecraft Server app. The server adds any other options on first start.",
                "motd=" + Setup.escapeProp(motd),
                "server-port=25565",
                "gamemode=" + mode.toLowerCase(Locale.ROOT),
                "difficulty=" + difficulty.toLowerCase(Locale.ROOT),
                "hardcore=false", "pvp=true", "max-players=10", "online-mode=true",
                "white-list=true", "enforce-whitelist=true", "spawn-protection=0",
                "view-distance=10", "simulation-distance=8", "allow-flight=true", "enable-command-block=false", ""),
                StandardCharsets.ISO_8859_1);
        Properties st = new Properties();
        st.setProperty("server.name", name);
        st.setProperty("server.type", type);
        st.setProperty("server.crossplay", String.valueOf(crossplay));
        if (version != null && !version.isBlank() && !version.equals("Newest") && !version.startsWith("Loading"))
            st.setProperty("mc.target", version);
        if (from != null) {
            String ram = Kit.get("ram", null);
            if (ram != null) st.setProperty("ram", ram);
            for (String k : List.of("xbox.gamertag", "xbox.tag")) if (Kit.get(k, null) != null) st.setProperty(k, Kit.get(k, null));
        }
        try (var w = Files.newBufferedWriter(dir.resolve("launcher-settings.properties"), StandardCharsets.UTF_8)) {
            st.store(w, "Minecraft Server app settings");
        }
        if (from != null && copyPlayers) {
            for (String f : List.of("whitelist.json", "ops.json"))
                if (Files.exists(from.resolve(f))) Files.copy(from.resolve(f), dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        // the same Xbox account shows every server (copy the add-on and its sign-in, so no new code is needed)
        if (from != null && crossplay) {
            Path xbox = Setup.xboxJar();
            Path xboxData = xbox.getParent().resolve("mcxboxbroadcast");
            if (Files.exists(xbox)) {
                Path targetExt = ("fabric".equals(type) ? dir.resolve("config").resolve("Geyser-Fabric")
                        : dir.resolve("plugins").resolve("Geyser-Spigot")).resolve("extensions");
                Files.createDirectories(targetExt);
                Files.copy(xbox, targetExt.resolve(xbox.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                if (Files.isDirectory(xboxData)) copyTree(xboxData, targetExt.resolve("mcxboxbroadcast"));
            }
        }
        Servers.add(dir, name);
        app.switchTo(dir);
        app.server.event(Server.Kind.SYSTEM, "Made " + name + ". Press Start server: the first start downloads "
                + ("fabric".equals(type) ? "Fabric" : "the server") + " and builds a new world.");
    }

    static void copyTree(Path from, Path to) throws java.io.IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.collect(Collectors.toList())) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}

