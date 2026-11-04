// Pictures from mod sites (icons, screenshots). Modrinth serves WebP, which Java can't read by itself, so the first
// time pictures are needed the app downloads the TwelveMonkeys WebP reader (from Maven Central, checked by SHA-1)
// into <app>/lib and plugs it into ImageIO. Pictures are cached in memory and in <app>/cache/images.

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.swing.*;

final class Images {
    private Images() { }

    static final String MAVEN = System.getProperty("kit.maven", "https://repo1.maven.org/maven2");
    static final String TM = "3.15.3";
    /** group path, artifact, sha1 */
    private static final String[][] JARS = {
            {"com/twelvemonkeys/common", "common-lang", "cc3cb2ae7cce198d2abcd7980d4260eeae2424b6"},
            {"com/twelvemonkeys/common", "common-io", "10659a682276e7fa32330383913e5eacde0e02d0"},
            {"com/twelvemonkeys/common", "common-image", "80c79a6ec6b8fd1e2384c0506cee94fa6aa21746"},
            {"com/twelvemonkeys/imageio", "imageio-core", "894902c2c8fa073c7fa57e4392c841364233fd3c"},
            {"com/twelvemonkeys/imageio", "imageio-metadata", "1a7b75d18f504d008f8d0c13ab6b04ff212b9cee"},
            {"com/twelvemonkeys/imageio", "imageio-webp", "58c3d9b704460c6afb8f496eb86435d164a84006"},
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "images");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, BufferedImage> MEMORY = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 160; }
    });
    private static volatile boolean webpTried;
    private static volatile boolean webpOk;

    static Path libDir() { return Kit.HOME.resolve("lib"); }

    static Path cacheDir() { return Kit.HOME.resolve("cache").resolve("images"); }

    /** Makes ImageIO able to read WebP. Safe to call many times; only the first call does work. */
    static synchronized boolean ensureWebp() {
        if (webpTried) return webpOk;
        webpTried = true;
        if (ImageIO.getImageReadersByFormatName("webp").hasNext()) return webpOk = true;
        try {
            Files.createDirectories(libDir());
            List<URL> urls = new ArrayList<>();
            for (String[] j : JARS) {
                Path f = libDir().resolve(j[1] + "-" + TM + ".jar");
                if (!Files.exists(f) || !j[2].equalsIgnoreCase(sha1(Files.readAllBytes(f)))) {
                    byte[] b = Kit.getBytes(MAVEN + "/" + j[0] + "/" + j[1] + "/" + TM + "/" + j[1] + "-" + TM + ".jar");
                    if (!j[2].equalsIgnoreCase(sha1(b))) throw new IOException(j[1] + " was damaged on the way");
                    Path tmp = f.resolveSibling(f.getFileName() + ".part");
                    Files.write(tmp, b);
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
                urls.add(f.toUri().toURL());
            }
            webpOk = plugIn(urls);
        } catch (Exception e) {
            System.err.println("[images] WebP support unavailable: " + Kit.describe(e));
            webpOk = false;
        }
        return webpOk;
    }

    /** Registers the WebP reader found in these jars with ImageIO. */
    static boolean plugIn(List<URL> urls) {
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), Images.class.getClassLoader());
        IIORegistry reg = IIORegistry.getDefaultInstance();
        for (ImageReaderSpi spi : ServiceLoader.load(ImageReaderSpi.class, loader)) {
            if (spi.getClass().getName().startsWith("com.twelvemonkeys.imageio.plugins.webp")) reg.registerServiceProvider(spi, ImageReaderSpi.class);
        }
        return ImageIO.getImageReadersByFormatName("webp").hasNext();
    }

    /** Loads a picture in the background and hands it to {@code done} on the Swing thread (null if it couldn't). */
    static void load(String url, Consumer<BufferedImage> done) {
        if (url == null || url.isBlank()) { done.accept(null); return; }
        BufferedImage hit = MEMORY.get(url);
        if (hit != null) { done.accept(hit); return; }
        POOL.submit(() -> {
            BufferedImage img = null;
            try { img = fetch(url); } catch (Exception e) { System.err.println("[images] " + url + ": " + Kit.describe(e)); }
            if (img != null) MEMORY.put(url, img);
            BufferedImage out = img;
            SwingUtilities.invokeLater(() -> done.accept(out));
        });
    }

    private static BufferedImage fetch(String url) throws IOException, InterruptedException {
        Path cached = cacheDir().resolve(sha1(url.getBytes(StandardCharsets.UTF_8)));
        byte[] data;
        if (Files.exists(cached)) {
            data = Files.readAllBytes(cached);
        } else {
            data = Kit.getBytes(url);
            try {
                Files.createDirectories(cacheDir());
                Files.write(cached, data);
            } catch (IOException ignored) { }
        }
        if (isWebp(data)) ensureWebp();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) throw new IOException("not a picture the app can show");
        return img;
    }

    static boolean isWebp(byte[] d) {
        return d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
    }

    /** Keeps the cache from growing forever: deletes the oldest pictures past ~150 MB. */
    static void trimCache() {
        try (var s = Files.list(cacheDir())) {
            List<Path> files = new ArrayList<>(s.toList());
            files.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
            long total = 0;
            for (Path p : files) total += p.toFile().length();
            for (Path p : files) {
                if (total < 150L * 1024 * 1024) break;
                total -= p.toFile().length();
                Files.deleteIfExists(p);
            }
        } catch (Exception ignored) { }
    }

    static String sha1(byte[] b) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ components

    /** Draws a picture scaled to fit (cover or contain), with rounded corners; shows a soft box until it loads. */
    static class Pic extends JComponent {
        BufferedImage img;
        final int w, h, arc;
        boolean cover = true;
        String fallback = "";
        private boolean failed;

        Pic(int w, int h, int arc) {
            this.w = w;
            this.h = h;
            this.arc = arc;
        }

        Pic url(String url) {
            img = null;
            failed = false;
            repaint();
            load(url, i -> { img = i; failed = i == null; repaint(); });
            return this;
        }

        @Override public Dimension getPreferredSize() { return new Dimension(w, h); }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.aa(g2);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int W = getWidth(), H = getHeight();
            Shape clip = new java.awt.geom.RoundRectangle2D.Float(0, 0, W, H, arc, arc);
            g2.setColor(new Color(0x2E343D));
            g2.fill(clip);
            if (img != null) {
                g2.clip(clip);
                double s = cover ? Math.max(W / (double) img.getWidth(), H / (double) img.getHeight())
                        : Math.min(W / (double) img.getWidth(), H / (double) img.getHeight());
                int dw = (int) Math.round(img.getWidth() * s), dh = (int) Math.round(img.getHeight() * s);
                g2.drawImage(img, (W - dw) / 2, (H - dh) / 2, dw, dh, null);
            } else if (!fallback.isEmpty()) {
                g2.setColor(failed ? Ui.FAINT : Ui.MUTED);
                g2.setFont(Ui.bold(Math.max(11, Math.min(W, H) / 2.4f)));
                FontMetrics fm = g2.getFontMetrics();
                String t = fallback.substring(0, 1).toUpperCase(Locale.ROOT);
                g2.drawString(t, (W - fm.stringWidth(t)) / 2, (H - fm.getHeight()) / 2 + fm.getAscent());
            }
            g2.dispose();
        }
    }
}
