// Minecraft items for the Give and inventory views: the list of item ids, their English names, and pictures.
//
// Pictures and names are read from the owner's OWN Minecraft game on this PC (.minecraft/versions/<version>/<version>.jar),
// the same way a resource-pack tool would; nothing is downloaded or shipped with the app. Blocks are drawn as a small
// 3D cube from their top and side textures. Without the game installed, items show a coloured letter tile instead.

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

final class Items {
    private Items() { }

    private static volatile ZipFile jar;
    private static volatile Path jarPath;
    private static volatile Map<String, String> lang;
    private static final Map<String, BufferedImage> icons = new ConcurrentHashMap<>();
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();
    private static final ExecutorService loader = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "item-icons"); t.setDaemon(true); return t; });
    static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Common items for when neither the server nor the game can list them (modded server, game not installed). */
    static final List<String> BASIC = List.of("diamond", "iron_ingot", "gold_ingot", "netherite_ingot", "emerald", "coal", "redstone",
            "lapis_lazuli", "copper_ingot", "stick", "oak_log", "oak_planks", "cobblestone", "stone", "dirt", "sand", "glass", "torch",
            "crafting_table", "furnace", "chest", "bed", "white_bed", "bread", "cooked_beef", "golden_apple", "golden_carrot", "apple",
            "diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "diamond_helmet", "diamond_chestplate", "diamond_leggings",
            "diamond_boots", "netherite_sword", "netherite_pickaxe", "iron_sword", "iron_pickaxe", "bow", "arrow", "crossbow", "shield",
            "elytra", "firework_rocket", "ender_pearl", "totem_of_undying", "experience_bottle", "enchanting_table", "anvil", "book",
            "bookshelf", "obsidian", "flint_and_steel", "water_bucket", "lava_bucket", "bucket", "saddle", "name_tag", "lead", "map",
            "compass", "clock", "shulker_box", "ender_chest", "beacon", "tnt", "string", "leather", "feather", "bone", "slime_ball",
            "oak_sapling", "wheat_seeds", "carrot", "potato", "cake", "cookie", "spyglass", "trident", "mace");

    // ------------------------------------------------------------------ the owner's Minecraft game

    /** The owner's Minecraft game jar for this server's version (or the newest one installed), or null. */
    static synchronized ZipFile gameJar() {
        Path want = findGameJar();
        if (want == null) return null;
        if (want.equals(jarPath) && jar != null) return jar;
        try {
            ZipFile z = new ZipFile(want.toFile());
            ZipFile old = jar;
            jar = z;
            jarPath = want;
            lang = null;
            icons.clear();
            if (old != null) try { old.close(); } catch (IOException ignored) { }
            return z;
        } catch (IOException e) {
            return null;
        }
    }

    static Path minecraftDir() {
        String override = System.getProperty("kit.minecraft");
        if (override != null) return Paths.get(override);
        String appdata = System.getenv("APPDATA");
        if (Kit.WINDOWS && appdata != null) return Paths.get(appdata, ".minecraft");
        String home = System.getProperty("user.home");
        if (Kit.MAC) return Paths.get(home, "Library", "Application Support", "minecraft");
        return Paths.get(home, ".minecraft");
    }

    static Path findGameJar() {
        Path versions = minecraftDir().resolve("versions");
        if (!Files.isDirectory(versions)) return null;
        String mc = Kit.hasServer() ? Kit.mcVersion() : null;
        if (mc != null) {
            Path exact = versions.resolve(mc).resolve(mc + ".jar");
            if (Files.isRegularFile(exact)) return exact;
        }
        // newest installed release-looking version that has a jar
        Path best = null;
        long bestTime = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(versions)) {
            for (Path d : ds) {
                Path j = d.resolve(d.getFileName() + ".jar");
                if (!Files.isRegularFile(j)) continue;
                long t = Files.getLastModifiedTime(j).toMillis();
                if (t > bestTime) { bestTime = t; best = j; }
            }
        } catch (IOException ignored) { }
        return best;
    }

    static boolean haveGame() { return gameJar() != null; }

    private static byte[] read(ZipFile z, String name) {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); } catch (IOException ex) { return null; }
    }

    // ------------------------------------------------------------------ the item list

    /** Every item id: from the server (plugin), else the game's own item list, else a short list of common items. */
    static List<String> all() {
        TreeSet<String> ids = new TreeSet<>(Game.pluginItems());
        ZipFile z = gameJar();
        if (ids.isEmpty() && z != null) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("assets/minecraft/items/") && n.endsWith(".json")) ids.add(n.substring(23, n.length() - 5));
            }
        }
        if (ids.isEmpty()) ids.addAll(BASIC);
        return new ArrayList<>(ids);
    }

    // ------------------------------------------------------------------ names

    static String nameOf(String id) {
        if (id == null) return "";
        Map<String, String> l = lang();
        if (l != null) {
            String n = l.get("item.minecraft." + id);
            if (n == null) n = l.get("block.minecraft." + id);
            if (n != null) return n;
        }
        StringBuilder b = new StringBuilder();
        for (String w : id.split("_")) if (!w.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    private static Map<String, String> lang() {
        Map<String, String> l = lang;
        if (l != null) return l;
        ZipFile z = gameJar();
        Map<String, String> m = new HashMap<>();
        if (z != null) {
            byte[] b = read(z, "assets/minecraft/lang/en_us.json");
            if (b != null) {
                try {
                    if (Kit.Json.parse(new String(b, StandardCharsets.UTF_8)) instanceof Map<?, ?> map)
                        for (var e : map.entrySet()) m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                } catch (IOException ignored) { }
            }
        }
        lang = m;
        return m;
    }

    static String enchantName(String key) {
        Map<String, String> l = lang();
        String n = l == null ? null : l.get("enchantment.minecraft." + key);
        return n != null ? n : nameOf(key);
    }

    static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    // ------------------------------------------------------------------ pictures

    /** A 32x32 picture of the item, or a placeholder while it loads (listeners are told when it's ready). */
    static BufferedImage icon(String id) {
        BufferedImage i = icons.get(id);
        if (i != null) return i;
        if (loading.add(id)) loader.submit(() -> {
            BufferedImage made = null;
            try { made = render(id); } catch (Throwable ignored) { }
            icons.put(id, made != null ? made : letter(id));
            loading.remove(id);
            for (Runnable r : listeners) r.run();
        });
        return letter(id);
    }

    private static final Map<String, BufferedImage> letters = new ConcurrentHashMap<>();

    /** A coloured tile with the item's first letters, when there's no picture. */
    static BufferedImage letter(String id) {
        return letters.computeIfAbsent(id, k -> {
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            Ui.aa(g);
            float hue = (k.hashCode() & 0xFFFF) / 65535f;
            g.setColor(Color.getHSBColor(hue, 0.35f, 0.42f));
            g.fill(new RoundRectangle2D.Float(2, 2, 28, 28, 8, 8));
            String[] w = k.split("_");
            String t = (w[0].isEmpty() ? "?" : w[0].substring(0, 1)) + (w.length > 1 && !w[w.length - 1].isEmpty() ? w[w.length - 1].substring(0, 1) : "");
            g.setFont(Ui.bold(12));
            g.setColor(new Color(255, 255, 255, 220));
            FontMetrics fm = g.getFontMetrics();
            String up = t.toUpperCase(Locale.ROOT);
            g.drawString(up, 16 - fm.stringWidth(up) / 2, 16 - fm.getHeight() / 2 + fm.getAscent());
            g.dispose();
            return img;
        });
    }

    /** Draws the item from the game's own model files: a flat item picture, or a little cube for blocks. */
    static BufferedImage render(String id) throws IOException {
        ZipFile z = gameJar();
        if (z == null) return null;
        String model = itemModel(z, id);
        if (model == null) model = "minecraft:item/" + id;
        Map<String, String> tex = new HashMap<>();
        String parentChain = resolveModel(z, model, tex, 0);
        if (tex.containsKey("layer0")) return flat(z, tex);
        boolean block = model.contains(":block/") || parentChain.contains("block/");
        if (block) {
            BufferedImage top = texture(z, pick(tex, "top", "end", "all", "texture", "particle"));
            BufferedImage side = texture(z, pick(tex, "front", "side", "all", "texture", "particle"));
            BufferedImage side2 = texture(z, pick(tex, "side", "all", "texture", "front", "particle"));
            if (top != null || side != null) return cube(top != null ? top : side, side != null ? side : top, side2 != null ? side2 : side != null ? side : top);
        }
        // last tries: plain textures with the item's name
        BufferedImage t = texture(z, "minecraft:item/" + id);
        if (t == null) t = texture(z, "minecraft:block/" + id);
        return t == null ? null : scale(t);
    }

    private static String pick(Map<String, String> tex, String... keys) {
        for (String k : keys) {
            String v = tex.get(k);
            for (int i = 0; v != null && v.startsWith("#") && i < 5; i++) v = tex.get(v.substring(1));
            if (v != null && !v.startsWith("#")) return v;
        }
        return null;
    }

    /** "assets/minecraft/items/<id>.json" -> the first model it points at. */
    private static String itemModel(ZipFile z, String id) {
        byte[] b = read(z, "assets/minecraft/items/" + id + ".json");
        if (b == null) return null;
        try { return firstModel(Kit.Json.parse(new String(b, StandardCharsets.UTF_8))); } catch (IOException e) { return null; }
    }

    private static String firstModel(Object o) {
        if (o instanceof Map<?, ?> m) {
            Object v = m.get("model");
            if (v instanceof String s) return s;
            // prefer the plain look: "fallback", then "model", then anything
            for (String k : List.of("fallback", "model", "on_false", "cases", "entries")) {
                String r = firstModel(m.get(k));
                if (r != null) return r;
            }
            for (Object x : m.values()) { String r = firstModel(x); if (r != null) return r; }
        } else if (o instanceof List<?> l) {
            for (Object x : l) { String r = firstModel(x); if (r != null) return r; }
        }
        return null;
    }

    /** Collects a model's textures, following its parents (the child's textures win). Returns the parent chain. */
    private static String resolveModel(ZipFile z, String model, Map<String, String> tex, int depth) {
        if (model == null || depth > 8) return "";
        String path = model.contains(":") ? model.substring(model.indexOf(':') + 1) : model;
        byte[] b = read(z, "assets/minecraft/models/" + path + ".json");
        if (b == null) return path;
        try {
            Object o = Kit.Json.parse(new String(b, StandardCharsets.UTF_8));
            if (Kit.Json.get(o, "textures") instanceof Map<?, ?> t)
                for (var e : t.entrySet()) tex.putIfAbsent(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            String parent = Kit.Json.str(o, "parent");
            return path + " " + (parent == null ? "" : resolveModel(z, parent, tex, depth + 1));
        } catch (IOException e) {
            return path;
        }
    }

    private static BufferedImage texture(ZipFile z, String ref) {
        if (ref == null) return null;
        String path = ref.contains(":") ? ref.substring(ref.indexOf(':') + 1) : ref;
        byte[] b = read(z, "assets/minecraft/textures/" + path + ".png");
        if (b == null) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b));
            if (img == null) return null;
            int s = Math.min(img.getWidth(), img.getHeight()); // animated textures are a tall strip: take the first frame
            BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            int tint = tint(path);
            if (tint != 0) tintInPlace(out, tint);
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    /** Grass and leaves are grey in the files; the game colours them by biome. Use the usual plains green. */
    private static int tint(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        if (n.equals("grass_block_top") || n.equals("short_grass") || n.startsWith("tall_grass") || n.contains("fern") || n.equals("grass_block_side_overlay")) return 0x7CBD6B;
        if (n.endsWith("_leaves") && !n.startsWith("cherry") && !n.startsWith("azalea") && !n.startsWith("flowering") && !n.startsWith("pale")) return n.startsWith("birch") ? 0x80A755 : n.startsWith("spruce") ? 0x619961 : 0x48B518;
        if (n.equals("vine") || n.equals("lily_pad")) return 0x48B518;
        return 0;
    }

    private static void tintInPlace(BufferedImage img, int rgb) {
        int tr = (rgb >> 16) & 255, tg = (rgb >> 8) & 255, tb = rgb & 255;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                int a = p >>> 24;
                if (a == 0) continue;
                int r = ((p >> 16) & 255) * tr / 255, g = ((p >> 8) & 255) * tg / 255, b = (p & 255) * tb / 255;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
    }

    private static BufferedImage flat(ZipFile z, Map<String, String> tex) {
        BufferedImage out = null;
        for (int i = 0; i < 4; i++) { // layered items (leather armor overlays, potions...)
            BufferedImage l = texture(z, pick(tex, "layer" + i));
            if (l == null) continue;
            if (out == null) out = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(l, 0, 0, 32, 32, null);
            g.dispose();
        }
        return out;
    }

    private static BufferedImage scale(BufferedImage t) {
        BufferedImage out = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(t, 0, 0, 32, 32, null);
        g.dispose();
        return out;
    }

    /** A little isometric block: top face, and the left and right sides a bit darker, like the game's inventory. */
    static BufferedImage cube(BufferedImage top, BufferedImage left, BufferedImage right) {
        int S = 64; // draw big, then shrink smoothly
        BufferedImage big = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        double cx = S / 2.0, h = S * 0.25, w = S * 0.5 - 2, side = S * 0.5 - 3;
        // top: unit square -> rhombus
        face(g, top, new AffineTransform(w / 16.0, h / 16.0, -w / 16.0, h / 16.0, cx, 2), 1.0);
        // left side
        face(g, left, new AffineTransform(w / 16.0, h / 16.0, 0, side / 16.0, cx - w, 2 + h), 0.72);
        // right side
        face(g, right, new AffineTransform(w / 16.0, -h / 16.0, 0, side / 16.0, cx, 2 + 2 * h), 0.55);
        g.dispose();
        BufferedImage out = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D o = out.createGraphics();
        o.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        o.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        o.drawImage(big, 0, 0, 32, 32, null);
        o.dispose();
        return out;
    }

    private static void face(Graphics2D g, BufferedImage t, AffineTransform at, double light) {
        if (t == null) return;
        BufferedImage src = t;
        if (t.getWidth() != 16) {
            src = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D s = src.createGraphics();
            s.drawImage(t, 0, 0, 16, 16, null);
            s.dispose();
        }
        if (light < 1) {
            BufferedImage d = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    int p = src.getRGB(x, y);
                    int r = (int) (((p >> 16) & 255) * light), gg = (int) (((p >> 8) & 255) * light), b = (int) ((p & 255) * light);
                    d.setRGB(x, y, (p & 0xFF000000) | (r << 16) | (gg << 8) | b);
                }
            src = d;
        }
        g.drawImage(src, at, null);
    }
}
