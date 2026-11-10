package xraywatch;
import java.io.File; import java.util.*;
import org.bukkit.*; import org.bukkit.block.Block; import org.bukkit.entity.Player; import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class Sim {
  // world: x 0..W, y Y0..Y1, z 0..W
  static final int W = 480, Y0 = -64, Y1 = -48;
  static byte[] grid; // 0 air,1 deepslate,2 diamond,3 lava
  static int idx(int x, int y, int z) { return ((y - Y0) * W + z) * W + x; }
  static boolean in(int x, int y, int z) { return x >= 0 && x < W && z >= 0 && z < W && y >= Y0 && y < Y1; }
  static byte at(int x, int y, int z) { return in(x, y, z) ? grid[idx(x, y, z)] : 1; }
  static void set(int x, int y, int z, byte v) { if (in(x, y, z)) grid[idx(x, y, z)] = v; }

  static final World WORLD = new World() { public String getName() { return "world"; } public Environment getEnvironment() { return Environment.NORMAL; }
    public Block getBlockAt(int x, int y, int z) { return new B(x, y, z); } public org.bukkit.entity.Item dropItemNaturally(Location l, org.bukkit.inventory.ItemStack i) { return null; }
    public Location getSpawnLocation() { return null; } public int getMinHeight() { return -64; } public int getMaxHeight() { return 320; } };
  record B(int x, int y, int z) implements Block {
    public Material getType() { return switch (at(x, y, z)) { case 0 -> Material.CAVE_AIR; case 2 -> Material.DEEPSLATE_DIAMOND_ORE; case 3 -> Material.LAVA; default -> Material.DEEPSLATE; }; }
    public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }
    public World getWorld() { return WORLD; }
    public Block getRelative(int a, int b, int c) { return new B(x + a, y + b, z + c); }
    public void setType(Material m) { } public Location getLocation() { return new Location(WORLD, x, y, z); }
    public boolean isEmpty() { return at(x, y, z) == 0; } public boolean isLiquid() { return at(x, y, z) == 3; } public boolean isReplaceable() { return isEmpty() || isLiquid(); }
  }
  static final class P implements Player {
    final String name; final UUID id = UUID.randomUUID(); final List<String> got = new ArrayList<>();
    P(String n) { name = n; }
    public UUID getUniqueId() { return id; } public GameMode getGameMode() { return GameMode.SURVIVAL; }
    public void sendMessage(String m) { got.add(m); } public boolean isOp() { return false; } public String getName() { return name; }
    public String getClientBrandName() { return "vanilla"; } public Location getLocation() { return null; } public void giveExp(int a) { } public org.bukkit.inventory.PlayerInventory getInventory() { return null; }
  }

  static boolean DIAGONAL = true;
  static XrayWatch plugin;
  static int mined;
  static void dig(P p, int x, int y, int z) {
    if (!in(x, y, z) || at(x, y, z) == 0 || at(x, y, z) == 3) return;
    plugin.onBreak(new BlockBreakEvent(new B(x, y, z), p));
    set(x, y, z, (byte) 0);
    mined++;
  }
  /** Mines a whole vein (flood fill over touching ore). */
  static int mineVein(P p, int x, int y, int z) {
    int n = 0; Deque<int[]> q = new ArrayDeque<>(); q.add(new int[]{x, y, z});
    while (!q.isEmpty()) { int[] c = q.poll(); if (at(c[0], c[1], c[2]) != 2) continue; dig(p, c[0], c[1], c[2]); n++;
      for (int[] d : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) q.add(new int[]{c[0]+d[0], c[1]+d[1], c[2]+d[2]}); }
    return n;
  }
  static boolean visibleOre(int x, int y, int z) { return at(x, y, z) == 2; }
  /** After opening (x,y,z), mine any ore now visible around it. */
  static int lookAround(P p, int x, int y, int z) {
    int veins = 0;
    for (int[] d : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) if (visibleOre(x+d[0], y+d[1], z+d[2])) { mineVein(p, x+d[0], y+d[1], z+d[2]); veins++; }
    return veins;
  }

  static void makeWorld(long seed, double veinsPerChunk, double caveChance) {
    Random r = new Random(seed);
    grid = new byte[W * W * (Y1 - Y0)];
    Arrays.fill(grid, (byte) 1);
    int chunks = (W / 16) * (W / 16);
    int veins = (int) (chunks * veinsPerChunk);
    for (int v = 0; v < veins; v++) {
      int x = r.nextInt(W), y = Y0 + 1 + r.nextInt(13), z = r.nextInt(W), size = 2 + r.nextInt(7);
      for (int i = 0; i < size; i++) { set(x, y, z, (byte) 2); x += r.nextInt(3) - 1; y += r.nextInt(3) - 1; z += r.nextInt(3) - 1; }
    }
    // caves: random worm tunnels of air
    int caves = (int) (chunks * caveChance);
    for (int c = 0; c < caves; c++) {
      int x = r.nextInt(W), y = Y0 + 2 + r.nextInt(12), z = r.nextInt(W);
      for (int i = 0; i < 60; i++) {
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int d = -1; d <= 1; d++) if (at(x+a, y+b, z+d) == 1) set(x+a, y+b, z+d, (byte) 0);
        x += r.nextInt(3) - 1; z += r.nextInt(3) - 1; if (r.nextInt(4) == 0) y += r.nextInt(3) - 1; y = Math.max(Y0 + 2, Math.min(Y1 - 3, y));
      }
    }
  }

  static String report(P p) {
    plugin.saveReport();
    return p.name;
  }

  /** Honest branch mining at y=-58: a main tunnel with side branches every 3 blocks, mining ores they see. */
  static void branchMiner(P p, int sx, int sz, int blocks) {
    int start = mined, y = -58, x = sx;
    while (mined - start < blocks) {
      for (int k = 0; k < 3 && mined - start < blocks; k++, x++) { dig(p, x, y, sz); dig(p, x, y + 1, sz); lookAround(p, x, y, sz); lookAround(p, x, y + 1, sz); }
      for (int side : new int[]{1, -1}) for (int d = 1; d <= 25 && mined - start < blocks; d++) { int z = sz + side * d; dig(p, x, y, z); dig(p, x, y + 1, z); lookAround(p, x, y, z); lookAround(p, x, y + 1, z); }
    }
  }

  /** Honest caving: walks caves and only mines ore visible from cave air. */
  static void caver(P p, int blocks) {
    int start = mined; Random r = new Random(7);
    for (int tries = 0; tries < 200000 && mined - start < blocks; tries++) {
      int x = r.nextInt(W), y = Y0 + 1 + r.nextInt(14), z = r.nextInt(W);
      if (at(x, y, z) != 2) continue;
      boolean open = false;
      for (int[] d : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) if (at(x+d[0], y+d[1], z+d[2]) == 0) open = true;
      if (!open) continue;
      // walking through the cave costs some stone (getting through narrow bits)
      for (int i = 0; i < 4; i++) dig(p, x + 2 + i, y, z + 2);
      mineVein(p, x, y, z);
    }
  }

  /** X-ray: tunnels straight to the nearest hidden diamond (it can see through stone). */
  static void xrayer(P p, int sx, int sz, int blocks) {
    int start = mined, x = sx, y = -58, z = sz;
    while (mined - start < blocks) {
      int[] best = null; int bd = Integer.MAX_VALUE;
      for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) for (int yy = Y0; yy < Y1; yy++) {
        if (at(x + dx, yy, z + dz) != 2) continue; int dist = Math.abs(dx) + Math.abs(dz) + Math.abs(yy - y);
        if (dist < bd && dist > 0) { bd = dist; best = new int[]{x + dx, yy, z + dz}; } }
      if (best == null) { x += 30; continue; }
      while (x != best[0] || z != best[2] || y != best[1]) {
        int dx = Math.abs(best[0] - x), dz = Math.abs(best[2] - z);
        if (DIAGONAL && dx > 0 && dz > 0) { if (dx >= dz) x += Integer.signum(best[0] - x); else z += Integer.signum(best[2] - z); }
        else if (x != best[0]) x += Integer.signum(best[0] - x); else if (z != best[2]) z += Integer.signum(best[2] - z); else y += Integer.signum(best[1] - y);
        if (at(x, y, z) == 2) break;
        dig(p, x, y, z); dig(p, x, y + 1, z);
        if (mined - start >= blocks) return;
      }
      mineVein(p, x, y, z);
      if (at(x, y, z) == 0 && (x != best[0] || y != best[1] || z != best[2])) mineVein(p, best[0], best[1], best[2]);
    }
  }

  public static void main(String[] a) throws Exception {
    double density = a.length > 0 ? Double.parseDouble(a[0]) : 4.0;
    File dir = new File(System.getProperty("xw.dir")); dir.mkdirs();
    java.util.logging.Logger log = java.util.logging.Logger.getLogger("XrayWatch");
    List<String> flags = new ArrayList<>();
    log.setUseParentHandlers(false);
    log.addHandler(new java.util.logging.Handler() { public void publish(java.util.logging.LogRecord r) { if (r.getMessage().startsWith("FLAG")) flags.add(r.getMessage()); } public void flush() {} public void close() {} });
    JavaPlugin.testServer = new Server() { public org.bukkit.plugin.PluginManager getPluginManager() { return (l, pl) -> {}; } public Collection<? extends Player> getOnlinePlayers() { return List.of(); }
      public Player getPlayer(UUID id) { return null; } public World getWorld(String n) { return WORLD; } };
    for (int seed = 1; seed <= 4; seed++) {
      makeWorld(seed, density, 1.2);
      plugin = new XrayWatch(); plugin.onEnable(); flags.clear();
      P honest = new P("Branch" + seed), caver = new P("Caver" + seed), cheat = new P("Xray" + seed), cheatSlow = new P("XraySneaky" + seed);
      branchMiner(honest, 20, 60, 700);
      caver(caver, 300);
      DIAGONAL = true; xrayer(cheat, 240, 240, 500);
      P cheatL = new P("XrayL" + seed); DIAGONAL = false; xrayer(cheatL, 100, 380, 500); DIAGONAL = true;
      // sneaky: x-rays but mixes in lots of normal branch mining
      xrayer(cheatSlow, 400, 400, 150); branchMiner(cheatSlow, 300, 140, 300);
      long now = System.currentTimeMillis();
      for (P p : List.of(honest, caver, cheat, cheatL, cheatSlow)) {
        var t = plugin.trackers.get(p.getUniqueId()); var s = t.stats(now, 30);
        System.out.printf("seed %d %-13s blocks=%4d hidden=%3d targeted=%3d open=%3d rate=%.2f/100 targeted/100=%.2f score=%3d level=%d%n",
            seed, p.name, s.blocks, s.hiddenVeins, s.targetedVeins, s.openVeins, s.rate, s.targetedWeight * 100.0 / Math.max(1, s.blocks), plugin.score(s), plugin.level(s));
      }
      for (String f : flags) System.out.println("   " + f);
      plugin.saveReport();
    }
  }
}
