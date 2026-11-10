// MapRender: draws a top-down picture of the Overworld for the Minecraft Server app's Map page, like an in-game map.
//
//  * Areas near players are drawn (and redrawn every few minutes, so new builds show up). Only loaded chunks are
//    read, a few per tick, so it never slows the game down.
//  * "xw map fill <radius>" also draws already-explored land around spawn, loading it quietly in the background.
//    New land is never generated.
//  * Pictures are 256x256-block PNG tiles in plugins/XrayWatch/map/<world>/<tx>_<tz>.png (one pixel per block).
// Colours come from each block's map colour (BlockData.getMapColor), shaded by height like vanilla maps.

package xraywatch;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

final class MapRender {
    static final int TILE = 256;
    private static final int NEAR = 7;                     // chunks around each player to keep drawn
    private static final long REDRAW_MS = 3 * 60_000L;     // redraw areas near players this often
    private static final long BUDGET_NS = 3_000_000L;      // at most 3 ms of drawing per tick
    private static final int MAX_LOADING = 6;

    private final XrayWatch plugin;
    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "XrayWatch-map"); t.setDaemon(true); return t; });

    // main thread only
    private final ArrayDeque<Job> nearQueue = new ArrayDeque<>();
    private final ArrayDeque<Job> fillQueue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Map<Long, Long> drawnAt = new HashMap<>();
    private int loading, ticks;
    private volatile int fillTotal, fillDone;
    private volatile boolean filling;
    private int fillDrawn;
    private boolean warned;

    // io thread only
    private final LinkedHashMap<String, Tile> tiles = new LinkedHashMap<>(64, 0.75f, true);

    record Job(World world, int cx, int cz, boolean load) { }

    static final class Tile { BufferedImage img; boolean dirty; }

    MapRender(XrayWatch plugin, File folder) {
        this.plugin = plugin;
        this.dir = new File(folder, "map");
        dir.mkdirs();
    }

    void shutdown() {
        io.submit(this::flushAll);
        io.shutdown();
        try { io.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    String statusJson() {
        return "{\"filling\":" + filling + ",\"fillTotal\":" + fillTotal + ",\"fillDone\":" + fillDone + "}";
    }

    private static long key(World w, int cx, int cz) { return ((long) w.getName().hashCode() << 42) ^ ((long) (cx & 0x1FFFFF) << 21) ^ (cz & 0x1FFFFF); }

    static boolean drawn(World w) { return String.valueOf(w.getEnvironment()).equals("NORMAL"); }

    // ------------------------------------------------------------------ every tick (main thread)

    void tick() {
        if (++ticks % 100 == 1) queueNearPlayers();
        long until = System.nanoTime() + BUDGET_NS;
        while (System.nanoTime() < until && !nearQueue.isEmpty()) {
            Job j = nearQueue.pollFirst();
            queued.remove(key(j.world, j.cx, j.cz));
            if (j.world.isChunkLoaded(j.cx, j.cz)) draw(j.world.getChunkAt(j.cx, j.cz));
        }
        while (System.nanoTime() < until && loading < MAX_LOADING && !fillQueue.isEmpty()) {
            Job j = fillQueue.pollFirst();
            if (j.world.isChunkLoaded(j.cx, j.cz)) {
                draw(j.world.getChunkAt(j.cx, j.cz));
                fillDone++;
                fillDrawn++;
                continue;
            }
            loading++;
            j.world.getChunkAtAsync(j.cx, j.cz, false).whenComplete((c, err) -> {
                Runnable done = () -> {
                    loading--;
                    fillDone++;
                    if (err != null && !warned) { warned = true; plugin.getLogger().warning("Map: couldn't load an area: " + err); }
                    if (c != null) {
                        try { draw(c); fillDrawn++; }
                        catch (Throwable t) { if (!warned) { warned = true; plugin.getLogger().warning("Map: couldn't draw an area: " + t); } }
                    }
                    if (fillQueue.isEmpty() && loading == 0 && filling) {
                        filling = false;
                        plugin.getLogger().info("MAP fill done areas=" + fillDrawn);
                    }
                };
                if (plugin.getServer().isPrimaryThread()) done.run();
                else plugin.getServer().getScheduler().runTask(plugin, done);
            });
        }
        if (filling && fillQueue.isEmpty() && loading == 0) {
            filling = false;
            plugin.getLogger().info("MAP fill done areas=" + fillDrawn);
        }
        if (ticks % 200 == 0) io.submit(this::flushAll);
    }

    private void queueNearPlayers() {
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Location l = p.getLocation();
            World w = l.getWorld();
            if (w == null || !drawn(w)) continue;
            int pcx = l.getBlockX() >> 4, pcz = l.getBlockZ() >> 4;
            for (int r = 0; r <= NEAR; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int cx = pcx + dx, cz = pcz + dz;
                        long k = key(w, cx, cz);
                        Long t = drawnAt.get(k);
                        if ((t != null && now - t < REDRAW_MS) || queued.contains(k)) continue;
                        if (!w.isChunkLoaded(cx, cz)) continue;
                        queued.add(k);
                        nearQueue.addLast(new Job(w, cx, cz, false));
                    }
                }
            }
        }
        if (drawnAt.size() > 50_000) drawnAt.clear();
    }

    /** Draws already-explored land within `radius` blocks of spawn (not drawn yet). */
    void fill(int radius) {
        World w = plugin.getServer().getWorlds().get(0);
        if (!drawn(w)) return;
        Location s = w.getSpawnLocation();
        int scx = s.getBlockX() >> 4, scz = s.getBlockZ() >> 4, rc = radius >> 4;
        List<int[]> all = new ArrayList<>();
        for (int r = 0; r <= rc; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r) all.add(new int[]{scx + dx, scz + dz});
        String wn = w.getName();
        plugin.getLogger().info("MAP fill checking areas=" + all.size());
        io.submit(() -> {
            List<int[]> missing = new ArrayList<>();
            for (int[] c : all) if (!hasPixels(wn, c[0], c[1])) missing.add(c);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                fillQueue.clear();
                for (int[] c : missing) fillQueue.addLast(new Job(w, c[0], c[1], true));
                fillTotal = missing.size();
                fillDone = 0;
                fillDrawn = 0;
                filling = !missing.isEmpty();
                plugin.getLogger().info("MAP fill queued areas=" + missing.size());
                if (missing.isEmpty()) plugin.getLogger().info("MAP fill done areas=0");
            });
        });
    }

    void stopFill() {
        fillQueue.clear();
        if (filling) { filling = false; plugin.getLogger().info("MAP fill done areas=" + fillDone); }
    }

    // ------------------------------------------------------------------ drawing one chunk (main thread)

    private void draw(Chunk c) {
        World w = c.getWorld();
        if (!drawn(w)) return;
        ChunkSnapshot s = c.getChunkSnapshot(true, false, false);
        int min = w.getMinHeight(), max = w.getMaxHeight() - 1;
        int[] px = new int[256];
        int[] height = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = Math.min(max, Math.max(min, s.getHighestBlockYAt(x, z) + 1));
                int rgb = 0, steps = 0, water = 0;
                while (y > min && steps++ < 40) {
                    Material m = s.getBlockType(x, y, z);
                    if (m.isAir()) { y--; continue; }
                    if (m == Material.WATER) {
                        if (water == 0) rgb = colour(s, x, y, z);
                        water++;
                        if (water > 12) break;
                        y--;
                        continue;
                    }
                    if (water > 0) break;
                    rgb = colour(s, x, y, z);
                    if (rgb != 0) break;
                    y--; // glass and other see-through blocks: look below
                }
                height[z * 16 + x] = water > 0 ? y + water : y;
                if (rgb == 0) { px[z * 16 + x] = 0; continue; }
                int shade;
                if (water > 0) shade = water <= 2 ? 255 : water <= 6 ? 220 : 180;
                else {
                    int north = z > 0 ? height[(z - 1) * 16 + x] : height[z * 16 + x];
                    shade = height[z * 16 + x] > north ? 255 : height[z * 16 + x] == north ? 220 : 180;
                }
                int r = ((rgb >> 16) & 255) * shade / 255, g = ((rgb >> 8) & 255) * shade / 255, b = (rgb & 255) * shade / 255;
                px[z * 16 + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        drawnAt.put(key(w, c.getX(), c.getZ()), System.currentTimeMillis());
        String wn = w.getName();
        int cx = c.getX(), cz = c.getZ();
        io.submit(() -> put(wn, cx, cz, px));
    }

    private static int colour(ChunkSnapshot s, int x, int y, int z) {
        try { return s.getBlockData(x, y, z).getMapColor().asRGB() & 0xFFFFFF; } catch (Throwable t) { return 0x707070; }
    }

    // ------------------------------------------------------------------ tiles (io thread)

    private Tile tile(String world, int tx, int tz) {
        String k = world + "/" + tx + "_" + tz;
        Tile t = tiles.get(k);
        if (t != null) return t;
        t = new Tile();
        File f = file(world, tx, tz);
        if (f.exists()) {
            try {
                BufferedImage read = ImageIO.read(f);
                if (read != null) {
                    t.img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
                    t.img.getGraphics().drawImage(read, 0, 0, null);
                }
            } catch (IOException ignored) { }
        }
        if (t.img == null) t.img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        tiles.put(k, t);
        while (tiles.size() > 48) {
            Map.Entry<String, Tile> old = tiles.entrySet().iterator().next();
            if (old.getValue().dirty) save(old.getKey(), old.getValue());
            tiles.remove(old.getKey());
        }
        return t;
    }

    private File file(String world, int tx, int tz) { return new File(new File(dir, safe(world)), tx + "_" + tz + ".png"); }

    static String safe(String world) { return world.replaceAll("[^A-Za-z0-9_.-]", "_"); }

    private void put(String world, int cx, int cz, int[] px) {
        int tx = Math.floorDiv(cx, 16), tz = Math.floorDiv(cz, 16);
        Tile t = tile(world, tx, tz);
        int ox = Math.floorMod(cx, 16) * 16, oz = Math.floorMod(cz, 16) * 16;
        t.img.setRGB(ox, oz, 16, 16, px, 0, 16);
        t.dirty = true;
    }

    private boolean hasPixels(String world, int cx, int cz) {
        int tx = Math.floorDiv(cx, 16), tz = Math.floorDiv(cz, 16);
        if (!file(world, tx, tz).exists() && !tiles.containsKey(world + "/" + tx + "_" + tz)) return false;
        Tile t = tile(world, tx, tz);
        int ox = Math.floorMod(cx, 16) * 16, oz = Math.floorMod(cz, 16) * 16;
        return (t.img.getRGB(ox + 8, oz + 8) >>> 24) != 0 || (t.img.getRGB(ox + 1, oz + 1) >>> 24) != 0;
    }

    private void flushAll() {
        for (Map.Entry<String, Tile> e : tiles.entrySet()) if (e.getValue().dirty) save(e.getKey(), e.getValue());
    }

    private void save(String key, Tile t) {
        int slash = key.lastIndexOf('/'), us = key.lastIndexOf('_');
        String world = key.substring(0, slash);
        int tx = Integer.parseInt(key.substring(slash + 1, us)), tz = Integer.parseInt(key.substring(us + 1));
        File f = file(world, tx, tz);
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            ImageIO.write(t.img, "png", tmp);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            t.dirty = false;
        } catch (IOException ignored) { }
    }
}
