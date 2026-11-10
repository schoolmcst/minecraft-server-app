package xraywatch;
import java.io.File; import java.util.*;
import org.bukkit.*; import org.bukkit.block.Block; import org.bukkit.entity.Player; import org.bukkit.event.block.*;
import org.bukkit.event.entity.*; import org.bukkit.event.player.PlayerInteractEvent; import org.bukkit.inventory.*;

/** Tests graves: dying, safe spots, owner-only, unlock, armor back on, full inventory, explosions, restarts. */
public class GraveSim {
  static final Map<String, Material> blocks = new HashMap<>();
  static final List<String> dropped = new ArrayList<>();
  static int fails = 0;
  static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

  static final World W = new World() {
    public String getName() { return "world"; } public Environment getEnvironment() { return Environment.NORMAL; }
    public Block getBlockAt(int x, int y, int z) { return new B(x, y, z); }
    public org.bukkit.entity.Item dropItemNaturally(Location l, ItemStack i) { dropped.add(i.toString()); return null; }
    public Location getSpawnLocation() { return new Location(this, 0, 70, 0); }
    public int getMinHeight() { return -64; } public int getMaxHeight() { return 320; }
  };
  record B(int x, int y, int z) implements Block {
    public Material getType() { Material m = blocks.get(x + ":" + y + ":" + z); return m != null ? m : (y < 64 ? Material.STONE : Material.AIR); }
    public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }
    public World getWorld() { return W; } public Block getRelative(int a, int b, int c) { return new B(x + a, y + b, z + c); }
    public void setType(Material m) { blocks.put(x + ":" + y + ":" + z, m); }
    public Location getLocation() { return new Location(W, x, y, z); }
    public boolean isEmpty() { return getType().isAir(); } public boolean isLiquid() { return getType() == Material.WATER || getType() == Material.LAVA; }
    public boolean isReplaceable() { return isEmpty() || isLiquid(); }
  }
  static final class Inv implements PlayerInventory {
    final List<ItemStack> slots = new ArrayList<>(); final int size; ItemStack helmet;
    Inv(int size) { this.size = size; }
    public HashMap<Integer, ItemStack> addItem(ItemStack... items) { HashMap<Integer, ItemStack> left = new HashMap<>(); int i = 0; for (ItemStack s : items) { if (slots.size() < size) slots.add(s); else left.put(i, s); i++; } return left; }
    public ItemStack getHelmet() { return helmet; } public ItemStack getChestplate() { return null; } public ItemStack getLeggings() { return null; } public ItemStack getBoots() { return null; }
    public void setHelmet(ItemStack i) { helmet = i; } public void setChestplate(ItemStack i) { } public void setLeggings(ItemStack i) { } public void setBoots(ItemStack i) { }
  }
  static final class P implements Player {
    final String name; final UUID id = UUID.randomUUID(); final List<String> msgs = new ArrayList<>(); Location loc; int xp; Inv inv = new Inv(36);
    P(String n) { name = n; }
    public UUID getUniqueId() { return id; } public GameMode getGameMode() { return GameMode.SURVIVAL; } public String getClientBrandName() { return "vanilla"; }
    public Location getLocation() { return loc; } public void giveExp(int a) { xp += a; } public PlayerInventory getInventory() { return inv; }
    public void sendMessage(String m) { msgs.add(m); } public boolean isOp() { return false; } public String getName() { return name; }
  }

  static XrayWatch plugin = new XrayWatch();
  static Graves graves(File dir, int unlock) { return new Graves(plugin, dir, unlock); }
  static PlayerDeathEvent die(Graves g, P p, int x, int y, int z, int xp, ItemStack... items) {
    p.loc = new Location(W, x + 0.5, y, z + 0.5);
    PlayerDeathEvent e = new PlayerDeathEvent(p, new ArrayList<>(List.of(items)), xp);
    g.onDeath(e); return e;
  }
  static void click(Graves g, P p, Block b) { g.onInteract(new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, b, EquipmentSlot.HAND)); }

  public static void main(String[] a) throws Exception {
    File dir = new File(System.getProperty("xw.dir")); dir.mkdirs();
    Graves g = graves(dir, 60);
    P steve = new P("Steve"), alex = new P(".AlexXbox");

    // 1. dies in an open cave: grave right there, drops cleared, XP kept
    PlayerDeathEvent e = die(g, steve, 10, 70, 10, 42, new ItemStack(Material.DIAMOND_SWORD, 1), new ItemStack(Material.DIAMOND_HELMET, 1), new ItemStack(Material.DIRT, 64));
    Block grave = W.getBlockAt(10, 70, 10);
    check(grave.getType() == Material.PLAYER_HEAD, "grave appears where Steve died");
    check(e.getDrops().isEmpty() && e.getDroppedExp() == 0, "nothing drops on the ground");
    check(steve.msgs.get(0).contains("10 70 10"), "Steve is told the coordinates: " + steve.msgs.get(0).replaceAll("§.", ""));

    // 2. someone else can't open it yet; explosions/pistons/water can't break it
    click(g, alex, grave);
    check(grave.getType() == Material.PLAYER_HEAD && alex.msgs.get(0).contains("opens for everyone in 60 minutes"), "Alex (Xbox) can't open it yet: " + alex.msgs.get(0).replaceAll("§.", ""));
    BlockBreakEvent br = new BlockBreakEvent(grave, alex); g.onBreak(br);
    check(br.isCancelled() && grave.getType() == Material.PLAYER_HEAD, "Alex can't break it either");
    List<Block> boom = new ArrayList<>(List.of(grave, W.getBlockAt(11, 70, 10))); g.onEntityExplode(new EntityExplodeEvent(boom));
    check(boom.size() == 1 && !boom.contains(grave), "a creeper explosion skips the grave");
    BlockFromToEvent flow = new BlockFromToEvent(W.getBlockAt(10, 71, 10), grave); g.onFlow(flow);
    check(flow.isCancelled(), "water/lava can't wash it away");
    BlockPistonExtendEvent pis = new BlockPistonExtendEvent(W.getBlockAt(9, 70, 10), List.of(grave)); g.onPistonExtend(pis);
    check(pis.isCancelled(), "pistons can't push it");

    // 3. survives a restart
    Graves g2 = graves(dir, 60);
    check(g2.graves.size() == 1, "grave is still there after a server restart");

    // 4. owner opens it (by breaking, like a Bedrock player might): helmet goes back on, items + XP back
    BlockBreakEvent own = new BlockBreakEvent(grave, steve); g2.onBreak(own);
    check(own.isCancelled() && grave.getType() == Material.AIR, "Steve opens it by breaking it; the head is gone");
    check(steve.inv.helmet != null && steve.inv.helmet.getType() == Material.DIAMOND_HELMET, "helmet goes straight back on");
    check(steve.inv.slots.size() == 2 && steve.xp == 42, "sword + dirt in inventory, 42 XP back");
    check(g2.graves.isEmpty() && Objects.requireNonNull(new File(dir, "graves").list()).length == 0, "grave file deleted");

    // 5. full inventory: extra items drop at the grave instead of vanishing
    Graves g3 = graves(dir, 60); P bob = new P("Bob"); bob.inv = new Inv(1);
    die(g3, bob, 20, 70, 20, 0, new ItemStack(Material.DIRT, 1), new ItemStack(Material.DIRT, 2), new ItemStack(Material.DIRT, 3));
    click(g3, bob, W.getBlockAt(20, 70, 20));
    check(bob.inv.slots.size() == 1 && dropped.size() == 2, "full inventory: 1 fits, 2 dropped at the grave");

    // 6. two deaths in the same spot make two graves side by side
    die(g3, bob, 30, 70, 30, 0, new ItemStack(Material.DIRT, 1)); die(g3, bob, 30, 70, 30, 0, new ItemStack(Material.DIRT, 1));
    check(g3.graves.size() == 2, "dying twice in the same place makes two graves");

    // 7. died inside lava/water: grave replaces the liquid. Died in the void: grave on the ground above or at spawn
    blocks.put("40:70:40", Material.LAVA); die(g3, bob, 40, 70, 40, 0, new ItemStack(Material.DIRT, 1));
    check(W.getBlockAt(40, 70, 40).getType() == Material.PLAYER_HEAD, "died in lava: grave in the lava pool");
    die(g3, bob, 50, -80, 50, 0, new ItemStack(Material.DIRT, 1));
    check(W.getBlockAt(50, 64, 50).getType() == Material.PLAYER_HEAD, "fell into the void: grave on top of the ground");

    // 8. keep inventory on: no grave
    int before = g3.graves.size(); PlayerDeathEvent k = new PlayerDeathEvent(bob, new ArrayList<>(List.of(new ItemStack(Material.DIRT, 1))), 5); k.setKeepInventory(true);
    bob.loc = new Location(W, 60, 70, 60); g3.onDeath(k);
    check(g3.graves.size() == before && !k.getDrops().isEmpty(), "keep inventory on: no grave made");

    // 9. after the unlock time anyone can open it
    Graves g4 = graves(new File(dir, "x"), 60); die(g4, steve, 70, 70, 70, 0, new ItemStack(Material.DIRT, 5));
    g4.graves.values().iterator().next().made -= 61 * 60_000L;
    click(g4, alex, W.getBlockAt(70, 70, 70));
    check(g4.graves.isEmpty() && alex.inv.slots.size() == 1, "after 60 minutes Alex can open Steve's grave");
    // 10. the app's "Give back" button: owner online gets items now; offline gets them when they join
    Map<UUID, Player> online = new HashMap<>();
    org.bukkit.plugin.java.JavaPlugin.testServer = new Server() {
      public org.bukkit.plugin.PluginManager getPluginManager() { return null; }
      public Collection<? extends Player> getOnlinePlayers() { return online.values(); }
      public Player getPlayer(UUID id) { return online.get(id); }
      public World getWorld(String n) { return W; }
      public org.bukkit.scheduler.BukkitScheduler getScheduler() { return new org.bukkit.scheduler.BukkitScheduler() {
        public org.bukkit.scheduler.BukkitTask runTaskTimer(org.bukkit.plugin.Plugin p, Runnable r, long d, long q) { return null; }
        public org.bukkit.scheduler.BukkitTask runTask(org.bukkit.plugin.Plugin p, Runnable r) { r.run(); return null; }
        public org.bukkit.scheduler.BukkitTask runTaskLater(org.bukkit.plugin.Plugin p, Runnable r, long d) { r.run(); return null; } }; }
    };
    Graves g5 = graves(new File(dir, "y"), 60); P cara = new P("Cara"); cara.loc = new Location(W, 0, 70, 0);
    die(g5, cara, 80, 70, 80, 7, new ItemStack(Material.DIAMOND_SWORD, 1));
    online.put(cara.getUniqueId(), cara);
    String id = g5.graves.values().iterator().next().id;
    String r = g5.giveBack(id);
    check(g5.graves.isEmpty() && cara.inv.slots.size() == 1 && cara.xp == 7 && W.getBlockAt(80, 70, 80).getType() == Material.AIR, "Give back (online): items + XP returned, grave removed: " + r.replaceAll("§.", ""));
    die(g5, cara, 90, 70, 90, 0, new ItemStack(Material.DIRT, 3));
    online.clear();
    String id2 = g5.graves.values().iterator().next().id;
    r = g5.giveBack(id2);
    check(g5.graves.size() == 1 && g5.graves.values().iterator().next().returnOnJoin, "Give back (offline): waits for them: " + r.replaceAll("§.", ""));
    Graves g6 = graves(new File(dir, "y"), 60);
    check(g6.graves.values().iterator().next().returnOnJoin, "the wait survives a restart");
    online.put(cara.getUniqueId(), cara);
    g6.onJoin(new org.bukkit.event.player.PlayerJoinEvent(cara));
    check(g6.graves.isEmpty() && cara.inv.slots.size() == 2, "they get the items when they join");
    Thread.sleep(300);
    String gj = new String(java.nio.file.Files.readAllBytes(new File(new File(dir, "y"), "graves.json").toPath()));
    check(gj.contains("\"graves\":[]"), "graves.json is kept up to date: " + gj);

    // 11. presets can switch graves off for a while
    g6.active = false; P dan = new P("Dan");
    PlayerDeathEvent off = die(g6, dan, 95, 70, 95, 0, new ItemStack(Material.DIRT, 1));
    check(g6.graves.isEmpty() && off.getDrops().size() == 1, "graves switched off: items drop normally");
    g6.active = true;
    die(g6, dan, 95, 70, 95, 0, new ItemStack(Material.DIRT, 1), new ItemStack(Material.DIAMOND_SWORD, 1));
    Thread.sleep(300);
    gj = new String(java.nio.file.Files.readAllBytes(new File(new File(dir, "y"), "graves.json").toPath()));
    check(gj.contains("\"owner\":\"Dan\"") && gj.contains("{\"id\":\"dirt\",\"n\":1}") && gj.contains("diamond_sword"), "graves.json lists the items: " + gj);
    System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
  }
}
