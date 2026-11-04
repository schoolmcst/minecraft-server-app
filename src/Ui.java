// Look and feel: colors, fonts and the small set of custom-drawn controls the app uses.

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.BasicScrollBarUI;

final class Ui {
    private Ui() { }

    // ------------------------------------------------------------------ palette

    static final Color BG = new Color(0x121418);
    static final Color SIDEBAR = new Color(0x181B20);
    static final Color CARD = new Color(0x1F2329);
    static final Color CARD_HI = new Color(0x272C34);
    static final Color FIELD = new Color(0x161A1F);
    static final Color LINE = new Color(0x2E343D);
    static final Color TEXT = new Color(0xECEFF3);
    static final Color MUTED = new Color(0x9BA3AE);
    static final Color FAINT = new Color(0x666E7A);
    static final Color GREEN = new Color(0x52B94D);
    static final Color GREEN_HI = new Color(0x62C95C);
    static final Color GREEN_DIM = new Color(0x1F3A22);
    static final Color RED = new Color(0xE5534B);
    static final Color RED_HI = new Color(0xF0665E);
    static final Color RED_DIM = new Color(0x3D1F1F);
    static final Color AMBER = new Color(0xE3B341);
    static final Color AMBER_DIM = new Color(0x3A3020);
    static final Color BLUE = new Color(0x58A6FF);
    static final Color BLUE_DIM = new Color(0x1C2B40);

    static final String FAMILY = pickFont("Segoe UI", "Inter", "SF Pro Text", "Helvetica Neue", "Ubuntu", "Cantarell", "DejaVu Sans", "Dialog");
    static final String MONO = pickFont("Cascadia Mono", "Consolas", "Menlo", "JetBrains Mono", "DejaVu Sans Mono", "Monospaced");

    static String pickFont(String... names) {
        java.util.Set<String> have = new java.util.HashSet<>(
                List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String n : names) if (have.contains(n)) return n;
        return "Dialog";
    }

    static Font font(float size) { return new Font(FAMILY, Font.PLAIN, 12).deriveFont(size); }
    static Font bold(float size) { return new Font(FAMILY, Font.BOLD, 12).deriveFont(size); }
    static Font mono(float size) { return new Font(MONO, Font.PLAIN, 12).deriveFont(size); }

    static void install() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
        Object[] kv = {
                "Panel.background", BG, "Label.foreground", TEXT, "Label.font", font(13),
                "Button.disabledText", FAINT, "ToolTip.background", CARD_HI, "ToolTip.foreground", TEXT,
                "ToolTip.border", new LineBorder(LINE), "ToolTip.font", font(12),
                "PopupMenu.background", CARD_HI, "PopupMenu.border", new LineBorder(LINE),
                "MenuItem.background", CARD_HI, "MenuItem.foreground", TEXT, "MenuItem.font", font(13),
                "MenuItem.selectionBackground", new Color(0x353C46), "MenuItem.selectionForeground", TEXT,
                "MenuItem.acceleratorForeground", MUTED, "MenuItem.border", new EmptyBorder(6, 10, 6, 10),
                "OptionPane.background", CARD, "OptionPane.messageForeground", TEXT,
                "TextField.selectionBackground", new Color(0x2F5A8A), "TextField.selectionForeground", TEXT,
                "TextPane.selectionBackground", new Color(0x2F5A8A), "TextArea.selectionBackground", new Color(0x2F5A8A),
                "ScrollPane.background", BG, "Viewport.background", BG,
        };
        for (int i = 0; i < kv.length; i += 2) UIManager.put(kv[i], kv[i + 1]);
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // ------------------------------------------------------------------ layout helpers

    static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }

    /** Multi-line text that wraps to whatever width the layout gives it. */
    static JTextArea para(String text, Font f, Color c) {
        JTextArea t = new JTextArea(text) {
            @Override public Dimension getPreferredSize() {
                int w = getWidth() > 0 ? getWidth() : 420;
                javax.swing.text.View v = getUI().getRootView(this);
                v.setSize(w, Integer.MAX_VALUE);
                Insets in = getInsets();
                int h = (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + in.top + in.bottom;
                return new Dimension(Math.min(w, 420), h);
            }

            @Override public Dimension getMinimumSize() { return new Dimension(60, getPreferredSize().height); }

            @Override public void setBounds(int x, int y, int w, int h) {
                boolean widthChanged = w != getWidth();
                super.setBounds(x, y, w, h);
                if (widthChanged) SwingUtilities.invokeLater(this::revalidate);
            }
        };
        t.setFont(f);
        t.setForeground(c);
        t.setOpaque(false);
        t.setEditable(false);
        t.setFocusable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setBorder(null);
        t.setHighlighter(null);
        return t;
    }

    /** Vertical stack: each child takes the full width, with a gap between. */
    static final class Stack extends JPanel {
        private int row;
        private final int gap;

        Stack(int gap) {
            super(new GridBagLayout());
            setOpaque(false);
            this.gap = gap;
        }

        Stack put(Component c) { return put(c, false); }

        Stack put(Component c, boolean grow) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = row;
            g.weightx = 1;
            g.weighty = grow ? 1 : 0;
            g.fill = grow ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
            g.anchor = GridBagConstraints.NORTHWEST;
            g.insets = new Insets(row == 0 ? 0 : gap, 0, 0, 0);
            add(c, g);
            row++;
            return this;
        }

        Stack fill() {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = row++; g.weighty = 1; g.fill = GridBagConstraints.BOTH;
            JPanel p = new JPanel();
            p.setOpaque(false);
            add(p, g);
            return this;
        }
    }

    /** Horizontal row, vertically centered. */
    static JPanel row(int gap, Component... cs) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        boolean first = true;
        for (Component c : cs) {
            if (!first && gap > 0) p.add(Box.createHorizontalStrut(gap));
            first = false;
            if (c instanceof JComponent jc) jc.setAlignmentY(0.5f);
            p.add(c);
        }
        return p;
    }

    static Component glue() { return Box.createHorizontalGlue(); }

    /** Scrolls vertically only; the content always matches the window's width. */
    static JScrollPane scroll(JComponent content) {
        final class Holder extends JPanel implements Scrollable {
            Holder() { super(new BorderLayout()); }
            public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }
            public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }
            public boolean getScrollableTracksViewportWidth() { return true; }
            public boolean getScrollableTracksViewportHeight() {
                return getParent() instanceof JViewport vp && vp.getHeight() > getPreferredSize().height;
            }
        }
        Holder holder = new Holder();
        holder.setOpaque(true);
        holder.setBackground(BG);
        holder.add(content, BorderLayout.NORTH);
        JScrollPane s = new JScrollPane(holder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        s.setBorder(null);
        s.getViewport().setBackground(BG);
        styleScroll(s);
        return s;
    }

    static void styleScroll(JScrollPane s) {
        s.getVerticalScrollBar().setUnitIncrement(18);
        s.getVerticalScrollBar().setUI(new ThinBar());
        s.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        s.getVerticalScrollBar().setOpaque(false);
        s.getHorizontalScrollBar().setUI(new ThinBar());
        s.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 10));
    }

    static final class ThinBar extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { thumbColor = new Color(0x3A414B); trackColor = new Color(0, 0, 0, 0); }
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }
        private static JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(isThumbRollover() ? new Color(0x4A525E) : thumbColor);
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ card

    static class Card extends JPanel {
        Color bg = CARD;
        Color border = null;
        int arc = 16;

        Card(LayoutManager lm) {
            super(lm);
            setOpaque(false);
            setBorder(new EmptyBorder(18, 20, 18, 20));
        }

        Card() { this(new BorderLayout()); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
            if (border != null) {
                g2.setColor(border);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, arc, arc));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static JLabel cardTitle(String t) {
        JLabel l = label(t.toUpperCase(), bold(11.5f), MUTED);
        l.setBorder(new EmptyBorder(0, 0, 2, 0));
        return l;
    }

    // ------------------------------------------------------------------ buttons

    enum Kind { PRIMARY, DANGER, SECONDARY, GHOST, SUBTLE }

    static class Btn extends JButton {
        Kind kind;
        boolean hover, down;
        Icon leading;

        Btn(String text, Kind kind) {
            super(text);
            this.kind = kind;
            setFont(bold(13));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(9, 18, 9, 18));
            setKind(kind);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; down = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { down = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { down = false; repaint(); }
            });
        }

        Btn small() {
            setFont(bold(12));
            setBorder(new EmptyBorder(6, 12, 6, 12));
            return this;
        }

        Btn big() {
            setFont(bold(14));
            setBorder(new EmptyBorder(11, 24, 11, 24));
            return this;
        }

        void setKind(Kind k) {
            kind = k;
            setForeground(switch (k) {
                case PRIMARY, DANGER -> Color.WHITE;
                case GHOST -> BLUE;
                case SUBTLE -> MUTED;
                default -> TEXT;
            });
            repaint();
        }

        Btn onClick(Runnable r) { addActionListener(e -> r.run()); return this; }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            boolean on = isEnabled();
            Color fill = switch (kind) {
                case PRIMARY -> !on ? new Color(0x2B4A2C) : down ? new Color(0x449E40) : hover ? GREEN_HI : GREEN;
                case DANGER -> !on ? new Color(0x4A2A29) : down ? new Color(0xC9443D) : hover ? RED_HI : RED;
                case SECONDARY -> !on ? new Color(0x23272D) : down ? new Color(0x2C323A) : hover ? new Color(0x353C46) : new Color(0x2E343D);
                case GHOST, SUBTLE -> hover && on ? new Color(255, 255, 255, 14) : new Color(0, 0, 0, 0);
            };
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
            g2.dispose();
            if (!on && (kind == Kind.PRIMARY || kind == Kind.DANGER)) setForeground(new Color(255, 255, 255, 110));
            else setKind0();
            super.paintComponent(g);
        }

        private void setKind0() {
            Color want = switch (kind) {
                case PRIMARY, DANGER -> Color.WHITE;
                case GHOST -> BLUE;
                case SUBTLE -> MUTED;
                default -> TEXT;
            };
            if (!want.equals(getForeground())) setForeground(want);
        }
    }

    /** A button that copies text and briefly says "Copied". */
    static Btn copyButton(java.util.function.Supplier<String> text) { return copyButton("Copy", text); }

    static Btn copyButton(String label, java.util.function.Supplier<String> text) {
        Btn b = new Btn(label, Kind.SECONDARY).small();
        b.addActionListener(e -> {
            String t = text.get();
            if (t == null || t.isBlank()) return;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(t), null);
            b.setText("Copied");
            Timer timer = new Timer(1400, ev -> b.setText(label));
            timer.setRepeats(false);
            timer.start();
        });
        return b;
    }

    // ------------------------------------------------------------------ text field

    static class Field extends JTextField {
        String placeholder;

        Field(String placeholder, int cols) {
            super(cols);
            this.placeholder = placeholder;
            setFont(font(14));
            setForeground(TEXT);
            setCaretColor(TEXT);
            setOpaque(false);
            setBorder(new EmptyBorder(9, 12, 9, 12));
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(FIELD);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
            g2.setColor(hasFocus() ? new Color(0x3D6FA8) : LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10, 10));
            if (getText().isEmpty() && placeholder != null) {
                g2.setFont(getFont());
                g2.setColor(FAINT);
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(placeholder, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ------------------------------------------------------------------ dropdown

    static class Choice extends JComponent {
        private List<String> options;
        private String value;
        private boolean hover;
        private final List<Consumer<String>> listeners = new java.util.ArrayList<>();

        Choice(List<String> options, String value) {
            this.options = options;
            this.value = value;
            setFont(font(14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { if (isEnabled()) popup(); }
            });
        }

        String value() { return value; }

        void setOptions(List<String> o) { options = new java.util.ArrayList<>(o); revalidate(); repaint(); }

        void setValue(String v) { value = v; repaint(); }

        void onChange(Consumer<String> c) { listeners.add(c); }

        private void popup() {
            JPopupMenu m = new JPopupMenu();
            for (String o : options) {
                JMenuItem it = new JMenuItem(o);
                it.setFont(font(14));
                it.setPreferredSize(new Dimension(Math.max(getWidth(), 120), 34));
                it.addActionListener(e -> {
                    value = o;
                    repaint();
                    for (Consumer<String> c : listeners) c.accept(o);
                });
                m.add(it);
            }
            m.show(this, 0, getHeight() + 4);
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = 0;
            for (String o : options) w = Math.max(w, fm.stringWidth(o));
            return new Dimension(w + 52, fm.getHeight() + 18);
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(hover && isEnabled() ? new Color(0x1C2026) : FIELD);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
            g2.setColor(LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10, 10));
            g2.setFont(getFont());
            g2.setColor(isEnabled() ? TEXT : FAINT);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(value == null ? "" : value, 12, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            int cx = getWidth() - 20, cy = getHeight() / 2;
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(MUTED);
            g2.draw(new Line2D.Float(cx - 4, cy - 2, cx, cy + 2));
            g2.draw(new Line2D.Float(cx, cy + 2, cx + 4, cy - 2));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ switch

    static class Switch extends JComponent {
        private boolean on;
        private final List<Consumer<Boolean>> listeners = new java.util.ArrayList<>();

        Switch(boolean on) {
            this.on = on;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (!isEnabled()) return;
                    Switch.this.on = !Switch.this.on;
                    repaint();
                    for (Consumer<Boolean> c : listeners) c.accept(Switch.this.on);
                }
            });
        }

        boolean isOn() { return on; }

        void setOn(boolean v) { on = v; repaint(); }

        void onChange(Consumer<Boolean> c) { listeners.add(c); }

        @Override public Dimension getPreferredSize() { return new Dimension(44, 26); }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            int w = 44, h = 24, y = (getHeight() - h) / 2;
            g2.setColor(on ? (isEnabled() ? GREEN : new Color(0x2B4A2C)) : new Color(0x3A414B));
            g2.fill(new RoundRectangle2D.Float(0, y, w, h, h, h));
            g2.setColor(Color.WHITE);
            int d = h - 6;
            g2.fill(new Ellipse2D.Float(on ? w - d - 3 : 3, y + 3, d, d));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ status pill

    static class Pill extends JComponent {
        String text = "";
        Color color = FAINT;
        boolean pulse;
        private float phase;

        Pill() {
            setFont(bold(13));
            Timer t = new Timer(60, e -> { if (pulse) { phase += 0.12f; repaint(); } });
            t.start();
        }

        void set(String text, Color color, boolean pulse) {
            this.text = text;
            this.color = color;
            this.pulse = pulse;
            revalidate();
            repaint();
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 40, 32);
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            int h = 32, y = (getHeight() - h) / 2;
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 34));
            g2.fill(new RoundRectangle2D.Float(0, y, getWidth(), h, h, h));
            float a = pulse ? (float) (0.55 + 0.45 * Math.sin(phase)) : 1f;
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(255 * a)));
            g2.fill(new Ellipse2D.Float(14, y + h / 2f - 4.5f, 9, 9));
            g2.setColor(TEXT);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 30, y + (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** A small colored dot, used in lists. */
    static Icon dot(Color c, int size) {
        return new Icon() {
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                aa(g2);
                g2.setColor(c);
                g2.fill(new Ellipse2D.Float(x, y, size, size));
                g2.dispose();
            }
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
        };
    }

    /** Small rounded tag like "Bedrock" or "Admin". */
    static JComponent tag(String text, Color color) {
        JLabel l = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                aa(g2);
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(bold(11));
        l.setForeground(color);
        l.setBorder(new EmptyBorder(3, 8, 3, 8));
        return l;
    }

    // ------------------------------------------------------------------ progress line

    static class ProgressLine extends JComponent {
        double value = -1;
        boolean visible;
        private float t;

        ProgressLine() {
            Timer timer = new Timer(30, e -> { if (visible) { t += 0.02f; repaint(); } });
            timer.start();
        }

        void set(boolean visible, double value) { this.visible = visible; this.value = value; repaint(); }

        @Override public Dimension getPreferredSize() { return new Dimension(10, 3); }

        @Override protected void paintComponent(Graphics g) {
            if (!visible) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(new Color(0x23282F));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(GREEN);
            if (value >= 0) {
                g2.fillRect(0, 0, (int) (getWidth() * Math.min(1, value)), getHeight());
            } else {
                int w = getWidth() / 4;
                int x = (int) ((t % 1.0f) * (getWidth() + w)) - w;
                g2.fillRect(x, 0, w, getHeight());
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ icons

    /** Simple line icons for the sidebar. */
    static Icon navIcon(String which, Color c) {
        return new Icon() {
            public int getIconWidth() { return 20; }
            public int getIconHeight() { return 20; }
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                aa(g2);
                g2.translate(x, y);
                g2.setColor(c);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (which) {
                    case "home" -> {
                        Path2D p = new Path2D.Float();
                        p.moveTo(3, 9.5); p.lineTo(10, 3.5); p.lineTo(17, 9.5);
                        g2.draw(p);
                        g2.draw(new RoundRectangle2D.Float(5, 8.5f, 10, 8.5f, 2, 2));
                    }
                    case "players" -> {
                        g2.draw(new Ellipse2D.Float(6.5f, 3, 7, 7));
                        g2.draw(new Arc2D.Float(3.5f, 11.5f, 13, 11, 0, 180, Arc2D.OPEN));
                    }
                    case "console" -> {
                        g2.draw(new RoundRectangle2D.Float(2.5f, 3.5f, 15, 13, 3, 3));
                        Path2D p = new Path2D.Float();
                        p.moveTo(6, 8); p.lineTo(8.5, 10); p.lineTo(6, 12);
                        g2.draw(p);
                        g2.draw(new Line2D.Float(10.5f, 12.5f, 14, 12.5f));
                    }
                    case "online" -> {
                        g2.draw(new Ellipse2D.Float(2.5f, 2.5f, 15, 15));
                        g2.draw(new Ellipse2D.Float(6.5f, 2.5f, 7, 15));
                        g2.draw(new Line2D.Float(3, 10, 17, 10));
                    }
                    case "servers" -> {
                        g2.draw(new RoundRectangle2D.Float(3, 3, 14, 5.5f, 2, 2));
                        g2.draw(new RoundRectangle2D.Float(3, 11.5f, 14, 5.5f, 2, 2));
                        g2.fill(new Ellipse2D.Float(5.2f, 4.9f, 1.8f, 1.8f));
                        g2.fill(new Ellipse2D.Float(5.2f, 13.4f, 1.8f, 1.8f));
                    }
                    case "mods" -> {
                        Path2D p = new Path2D.Float();
                        p.moveTo(10, 2.5); p.lineTo(16.5, 6.2); p.lineTo(16.5, 13.8); p.lineTo(10, 17.5);
                        p.lineTo(3.5, 13.8); p.lineTo(3.5, 6.2); p.closePath();
                        g2.draw(p);
                        g2.draw(new Line2D.Float(3.5f, 6.2f, 10, 10));
                        g2.draw(new Line2D.Float(16.5f, 6.2f, 10, 10));
                        g2.draw(new Line2D.Float(10, 10, 10, 17.5f));
                    }
                    case "settings" -> {
                        g2.draw(new Line2D.Float(3, 6, 17, 6));
                        g2.draw(new Line2D.Float(3, 14, 17, 14));
                        g2.setColor(SIDEBAR);
                        g2.fill(new Ellipse2D.Float(10, 3.5f, 5, 5));
                        g2.fill(new Ellipse2D.Float(5, 11.5f, 5, 5));
                        g2.setColor(c);
                        g2.draw(new Ellipse2D.Float(10, 3.5f, 5, 5));
                        g2.draw(new Ellipse2D.Float(5, 11.5f, 5, 5));
                    }
                    default -> { }
                }
                g2.dispose();
            }
        };
    }

    /** The app icon: a pixel-art grass block. */
    static BufferedImage appIcon(int size) {
        String[] px = {
                "GGgGGGgG",
                "GgGGgGGG",
                "GGGgGGgG",
                "DGDDGDDG",
                "DdDDlDdD",
                "DDlDDdDD",
                "dDDDdDDl",
                "DDdDDDlD"};
        Color G = new Color(0x6CBF3E), g = new Color(0x4E9A2A), D = new Color(0x8B5A2B), d = new Color(0x6E4520),
                l = new Color(0xA0703C);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float pad = size * 0.06f, inner = size - 2 * pad, cell = inner / 8f;
        g2.setClip(new RoundRectangle2D.Float(pad, pad, inner, inner, size * 0.22f, size * 0.22f));
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                char ch = px[r].charAt(c);
                g2.setColor(switch (ch) { case 'G' -> G; case 'g' -> g; case 'd' -> d; case 'l' -> l; default -> D; });
                g2.fill(new Rectangle2D.Float(pad + c * cell, pad + r * cell, cell + 0.6f, cell + 0.6f));
            }
        g2.dispose();
        return img;
    }

    static List<Image> appIcons() {
        return List.of(appIcon(16), appIcon(24), appIcon(32), appIcon(48), appIcon(64), appIcon(128), appIcon(256));
    }
}
