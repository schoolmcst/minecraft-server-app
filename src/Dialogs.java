// Styled pop-up dialogs (confirm, info, EULA, "please wait").

import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class Dialogs {
    private Dialogs() { }

    private static JDialog base(Window owner, String title) {
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        d.setIconImages(Ui.appIcons());
        d.setResizable(false);
        d.getContentPane().setBackground(Ui.CARD);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        return d;
    }

    private static JPanel body(String title, String message) {
        Ui.Stack s = new Ui.Stack(10);
        s.put(Ui.label(title, Ui.bold(18), Ui.TEXT));
        if (message != null) {
            JTextArea t = Ui.para(message, Ui.font(14), Ui.MUTED);
            t.setSize(new Dimension(400, 10));
            s.put(t);
        }
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Ui.CARD);
        p.setBorder(new EmptyBorder(24, 26, 20, 26));
        p.add(s, BorderLayout.CENTER);
        return p;
    }

    private static void finish(JDialog d, JPanel content, JComponent buttons) {
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        b.setOpaque(false);
        b.add(buttons);
        b.setBorder(new EmptyBorder(18, 0, 0, 0));
        content.add(b, BorderLayout.SOUTH);
        d.setContentPane(content);
        content.setPreferredSize(new Dimension(460, content.getPreferredSize().height));
        d.pack();
        d.setLocationRelativeTo(d.getOwner());
    }

    static boolean confirm(Window owner, String title, String message, String okText, Ui.Kind okKind) {
        JDialog d = base(owner, title);
        AtomicBoolean ok = new AtomicBoolean();
        JPanel content = body(title, message);
        Ui.Btn cancel = new Ui.Btn("Cancel", Ui.Kind.SECONDARY).onClick(d::dispose);
        Ui.Btn yes = new Ui.Btn(okText, okKind).onClick(() -> { ok.set(true); d.dispose(); });
        finish(d, content, Ui.row(10, cancel, yes));
        d.getRootPane().setDefaultButton(yes);
        d.setVisible(true);
        return ok.get();
    }

    static void info(Window owner, String title, String message) {
        JDialog d = base(owner, title);
        JPanel content = body(title, message);
        Ui.Btn ok = new Ui.Btn("OK", Ui.Kind.PRIMARY).onClick(d::dispose);
        finish(d, content, ok);
        d.getRootPane().setDefaultButton(ok);
        d.setVisible(true);
    }

    static boolean eula(Window owner) {
        JDialog d = base(owner, "One quick thing");
        AtomicBoolean ok = new AtomicBoolean();
        JPanel content = body("Accept Minecraft's EULA",
                "Every Minecraft server has to accept Mojang's End User License Agreement before it can run. "
                        + "It's the standard agreement for playing Minecraft; you only need to do this once.");
        Ui.Btn read = new Ui.Btn("Read the EULA", Ui.Kind.GHOST).onClick(() -> Kit.browse("https://aka.ms/MinecraftEULA"));
        Ui.Btn cancel = new Ui.Btn("Not now", Ui.Kind.SECONDARY).onClick(d::dispose);
        Ui.Btn yes = new Ui.Btn("I accept", Ui.Kind.PRIMARY).onClick(() -> { ok.set(true); d.dispose(); });
        finish(d, content, Ui.row(10, read, cancel, yes));
        d.setVisible(true);
        return ok.get();
    }

    /** A "please wait" box. Call setVisible(true) after starting the work; dispose() it when done. */
    static JDialog busy(Window owner, String text) {
        JDialog d = new JDialog(owner, Kit.APP_NAME, Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Ui.Card c = new Ui.Card();
        c.bg = Ui.CARD_HI;
        c.setBorder(new EmptyBorder(26, 30, 26, 30));
        Ui.ProgressLine line = new Ui.ProgressLine();
        line.set(true, -1);
        line.setPreferredSize(new Dimension(320, 4));
        c.add(new Ui.Stack(16).put(Ui.label(text, Ui.bold(15), Ui.TEXT)).put(line));
        JPanel bg = new JPanel(new BorderLayout());
        bg.setBackground(Ui.BG);
        bg.add(c);
        d.setContentPane(bg);
        d.pack();
        d.setLocationRelativeTo(owner);
        return d;
    }
}
