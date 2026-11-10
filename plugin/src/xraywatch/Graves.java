// Graves: when a player dies, their items and XP go into a grave (a player head) where they died instead of dropping
// on the ground and despawning. Works the same for Java and Bedrock/Xbox players (Geyser shows the head; using it or
// breaking it is how you open it), so it's only a block plus chat messages, no floating text.
//
//  * Only the owner can open it at first; after `unlock-minutes` anyone can (so items are never lost forever).
//  * Creepers, TNT, pistons, water and lava can't destroy it. Graves survive server restarts (plugins/XrayWatch/graves).
//  * Keep inventory on = no graves needed, so none are made.
//  * Logs "GRAVE made|opened|returned ..." lines for the Minecraft Server app's activity feed, and keeps graves.json
//    (every grave and what's in it) for the app's Graves list. "xw grave return <id>" gives a grave's items back to its
//    owner from the app (right away, or the next time they join).

package xraywatch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class Graves implements Listener {
    private final XrayWatch plugin;
    private final File dir;
    final int unlockMinutes;
    /** "world:x:y:z" -> grave */
    final Map<String, Grave> graves = new HashMap<>();
    /** Off for a while when a preset (like "Hardcore weekend") turns graves off; not saved. */
    volatile boolean active = true;

    static final class Grave {
        String id, ownerName, world;
        UUID owner;
        int x, y, z, xp;
        long made;
        boolean returnOnJoin;
        final List<ItemStack> items = new ArrayList<>();

        String key() { return world + ":" + x + ":" + y + ":" + z; }
    }

    Graves(XrayWatch plugin, File folder, int unlockMinutes) {
        this.plugin = plugin;
        this.dir = new File(folder, "graves");
        this.unlockMinutes = unlockMinutes;
        dir.mkdirs();
        load();
    }

    // ------------------------------------------------------------------ dying

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (p == null || e.getKeepInventory() || !active) return;
        List<ItemStack> drops = e.getDrops();
        List<ItemStack> keep = new ArrayList<>();
        for (ItemStack i : drops) if (i != null && !i.isEmpty()) keep.add(i);
        int xp = e.getDroppedExp();
        if (keep.isEmpty() && xp <= 0) return;

        Location at = p.getLocation();
        World w = at.getWorld();
        Block spot = findSpot(w, at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (spot == null) return; // nowhere safe: items drop like normal

        Grave g = new Grave();
        g.id = UUID.randomUUID().toString().substring(0, 8);
        g.owner = p.getUniqueId();
        g.ownerName = p.getName();
        g.world = w.getName();
        g.x = spot.getX();
        g.y = spot.getY();
        g.z = spot.getZ();
        g.xp = xp;
        g.made = System.currentTimeMillis();
        g.items.addAll(keep);
        try {
            save(g);
        } catch (IOException ex) {
            plugin.getLogger().warning("Couldn't save a grave, so the items drop normally: " + ex);
            return;
        }
        spot.setType(Material.PLAYER_HEAD);
        graves.put(g.key(), g);
        drops.clear();
        e.setDroppedExp(0);
        int count = 0;
        for (ItemStack i : g.items) count += i.getAmount();
        p.sendMessage("§6☠ Your items are safe in a grave at §f" + g.x + " " + g.y + " " + g.z + where(w)
                + "§6. Go back and use it (or break it) to get everything back." + (unlockMinutes > 0
                ? " §7Only you can open it for " + unlockMinutes + " minutes." : ""));
        plugin.getLogger().info("GRAVE made player=" + g.ownerName + " items=" + count + " xp=" + g.xp + " world=" + g.world
                + " x=" + g.x + " y=" + g.y + " z=" + g.z);
        changed();
    }

    private static String where(World w) {
        String env = String.valueOf(w.getEnvironment());
        return env.equals("NETHER") ? " in the Nether" : env.equals("THE_END") ? " in the End" : "";
    }

    /** The nearest place a grave can go: an open block (air, water, lava, grass...) near where they died, never a grave. */
    Block findSpot(World w, int x, int y, int z) {
        int min = w.getMinHeight() + 1, max = w.getMaxHeight() - 1;
        if (y < min) {
            // fell into the void: put it on the highest ground in that column, or at the world spawn
            for (int yy = max; yy >= min; yy--) {
                Block b = w.getBlockAt(x, yy, z);
                if (!b.getType().isAir() && !b.isLiquid()) { y = yy + 1; break; }
            }
            if (y < min) {
                Location s = w.getSpawnLocation();
                x = s.getBlockX();
                y = Math.max(min, s.getBlockY());
                z = s.getBlockZ();
            }
        }
        y = Math.min(Math.max(y, min), max);
        for (int r = 0; r <= 4; r++) {
            for (int dy = 0; dy <= 6; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring by ring, closest first
                        int yy = y + dy;
                        if (yy > max) continue;
                        Block b = w.getBlockAt(x + dx, yy, z + dz);
                        if (graves.containsKey(w.getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ())) continue;
                        if (b.isEmpty() || b.isLiquid() || b.isReplaceable()) return b;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ opening

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Grave g = at(e.getClickedBlock());
        if (g == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return; // the event comes once per hand
        open(e.getPlayer(), g, e.getClickedBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Grave g = at(e.getBlock());
        if (g == null) return;
        e.setCancelled(true);
        open(e.getPlayer(), g, e.getBlock());
    }

    void open(Player p, Grave g, Block block) {
        if (p == null) return;
        long locked = g.made + unlockMinutes * 60_000L - System.currentTimeMillis();
        boolean owner = p.getUniqueId().equals(g.owner);
        if (!owner && unlockMinutes > 0 && locked > 0) {
            long mins = Math.max(1, (locked + 59_999) / 60_000);
            p.sendMessage("§7This is §f" + g.ownerName + "§7's grave. It opens for everyone in " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
            return;
        }
        if (!owner && unlockMinutes <= 0) {
            p.sendMessage("§7This is §f" + g.ownerName + "§7's grave. Only they can open it.");
            return;
        }
        int dropped = take(p, g, block);
        p.sendMessage(owner ? "§a☠ You got your items back" + (dropped > 0 ? " §7(your inventory was full, so some are on the ground)" : "") + "§a."
                : "§a☠ You opened " + g.ownerName + "'s grave.");
        plugin.getLogger().info("GRAVE opened player=" + p.getName() + " owner=" + g.ownerName + " world=" + g.world
                + " x=" + g.x + " y=" + g.y + " z=" + g.z);
    }

    /** Removes the grave and gives everything in it to the player (armor back on). Returns how many stacks didn't fit and dropped at their feet/the grave. */
    int take(Player p, Grave g, Block block) {
        graves.remove(g.key());
        if (block != null && block.getType() == Material.PLAYER_HEAD) block.setType(Material.AIR);
        new File(dir, g.id + ".grave").delete();

        PlayerInventory inv = p.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack i : g.items) {
            String t = String.valueOf(i.getType());
            // put armor straight back on if that slot is empty
            if (t.endsWith("_HELMET") || t.equals("TURTLE_HELMET")) { if (empty(inv.getHelmet())) { inv.setHelmet(i); continue; } }
            else if (t.endsWith("_CHESTPLATE") || t.equals("ELYTRA")) { if (empty(inv.getChestplate())) { inv.setChestplate(i); continue; } }
            else if (t.endsWith("_LEGGINGS")) { if (empty(inv.getLeggings())) { inv.setLeggings(i); continue; } }
            else if (t.endsWith("_BOOTS")) { if (empty(inv.getBoots())) { inv.setBoots(i); continue; } }
            rest.add(i);
        }
        int dropped = 0;
        if (!rest.isEmpty()) {
            Map<Integer, ItemStack> left = inv.addItem(rest.toArray(new ItemStack[0]));
            Location loc = p.getLocation();
            for (ItemStack i : left.values()) { loc.getWorld().dropItemNaturally(loc, i); dropped++; }
        }
        if (g.xp > 0) p.giveExp(g.xp);
        changed();
        return dropped;
    }

    /** "xw grave return <id>" from the app: gives a grave's items to its owner now, or when they next join. */
    String giveBack(String id) {
        Grave g = null;
        for (Grave x : graves.values()) if (x.id.equalsIgnoreCase(id)) g = x;
        if (g == null) return "§7No grave with id " + id + " (maybe it was already opened).";
        Player owner = plugin.getServer().getPlayer(g.owner);
        if (owner != null) {
            World w = plugin.getServer().getWorld(g.world);
            Block b = w == null ? null : w.getBlockAt(g.x, g.y, g.z);
            int dropped = take(owner, g, b);
            owner.sendMessage("§a☠ An admin gave you back the items from your grave" + (dropped > 0 ? " §7(your inventory was full, so some are on the ground)" : "") + "§a.");
            plugin.getLogger().info("GRAVE returned owner=" + g.ownerName + " world=" + g.world + " x=" + g.x + " y=" + g.y + " z=" + g.z);
            return "§aGave " + g.ownerName + " their items back.";
        }
        g.returnOnJoin = true;
        try { save(g); } catch (IOException ignored) { }
        plugin.getLogger().info("GRAVE return-later owner=" + g.ownerName + " world=" + g.world + " x=" + g.x + " y=" + g.y + " z=" + g.z);
        changed();
        return "§7" + g.ownerName + " isn't online; they get their items the next time they join.";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        List<Grave> mine = new ArrayList<>();
        for (Grave g : graves.values()) if (g.returnOnJoin && p.getUniqueId().equals(g.owner)) mine.add(g);
        if (mine.isEmpty()) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            for (Grave g : mine) {
                if (!graves.containsValue(g)) continue;
                World w = plugin.getServer().getWorld(g.world);
                int dropped = take(p, g, w == null ? null : w.getBlockAt(g.x, g.y, g.z));
                p.sendMessage("§a☠ An admin gave you back the items from your grave" + (dropped > 0 ? " §7(your inventory was full, so some are on the ground)" : "") + "§a.");
                plugin.getLogger().info("GRAVE returned owner=" + g.ownerName + " world=" + g.world + " x=" + g.x + " y=" + g.y + " z=" + g.z);
            }
        }, 40L);
    }

    private static boolean empty(ItemStack i) { return i == null || i.isEmpty(); }

    Grave at(Block b) {
        if (b == null) return null;
        Grave g = graves.get(b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ());
        if (g == null) return null;
        if (b.getType() != Material.PLAYER_HEAD) { // something removed it anyway (an admin, /fill...): put it back
            b.setType(Material.PLAYER_HEAD);
        }
        return g;
    }

    // ------------------------------------------------------------------ nothing destroys a grave

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> at(b) != null); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> at(b) != null); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFlow(BlockFromToEvent e) { if (at(e.getToBlock()) != null) e.setCancelled(true); }

    // ------------------------------------------------------------------ saving

    void save(Grave g) throws IOException {
        Properties p = new Properties();
        p.setProperty("owner", g.owner.toString());
        p.setProperty("name", g.ownerName);
        p.setProperty("world", g.world);
        p.setProperty("x", String.valueOf(g.x));
        p.setProperty("y", String.valueOf(g.y));
        p.setProperty("z", String.valueOf(g.z));
        p.setProperty("xp", String.valueOf(g.xp));
        p.setProperty("made", String.valueOf(g.made));
        p.setProperty("count", String.valueOf(g.items.size()));
        if (g.returnOnJoin) p.setProperty("return-on-join", "true");
        for (int i = 0; i < g.items.size(); i++) p.setProperty("item." + i, Base64.getEncoder().encodeToString(g.items.get(i).serializeAsBytes()));
        File tmp = new File(dir, g.id + ".grave.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            p.store(w, "A grave made by XrayWatch for the Minecraft Server app. Deleted when it's opened.");
        }
        Files.move(tmp.toPath(), new File(dir, g.id + ".grave").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    void load() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".grave"));
        if (files == null) return;
        for (File f : files) {
            Properties p = new Properties();
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
                Grave g = new Grave();
                g.id = f.getName().replace(".grave", "");
                g.owner = UUID.fromString(p.getProperty("owner"));
                g.ownerName = p.getProperty("name", "?");
                g.world = p.getProperty("world");
                g.x = Integer.parseInt(p.getProperty("x"));
                g.y = Integer.parseInt(p.getProperty("y"));
                g.z = Integer.parseInt(p.getProperty("z"));
                g.xp = Integer.parseInt(p.getProperty("xp", "0"));
                g.made = Long.parseLong(p.getProperty("made", "0"));
                g.returnOnJoin = "true".equals(p.getProperty("return-on-join"));
                int n = Integer.parseInt(p.getProperty("count", "0"));
                for (int i = 0; i < n; i++) {
                    String s = p.getProperty("item." + i);
                    if (s != null) g.items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(s)));
                }
                graves.put(g.key(), g);
            } catch (Exception ex) {
                plugin.getLogger().warning("Couldn't read grave " + f.getName() + ": " + ex);
            }
        }
        if (!graves.isEmpty()) plugin.getLogger().info(graves.size() + " grave(s) waiting to be opened");
        changed();
    }

    // ------------------------------------------------------------------ for the app

    /** Short list for live.json. */
    String summaryJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Grave g : graves.values()) {
            if (!first) sb.append(',');
            first = false;
            int n = 0;
            for (ItemStack i : g.items) n += i.getAmount();
            sb.append("{\"id\":\"").append(g.id).append("\",\"owner\":\"").append(XrayWatch.json(g.ownerName)).append("\",\"world\":\"").append(XrayWatch.json(g.world))
                    .append("\",\"x\":").append(g.x).append(",\"y\":").append(g.y).append(",\"z\":").append(g.z).append(",\"items\":").append(n).append('}');
        }
        return sb.append(']').toString();
    }

    /** graves.json: every grave with everything in it, written whenever graves change. */
    void changed() {
        StringBuilder sb = new StringBuilder("{\"unlockMinutes\":").append(unlockMinutes).append(",\"graves\":[");
        boolean first = true;
        List<Grave> list = new ArrayList<>(graves.values());
        list.sort(Comparator.comparingLong(g -> -g.made));
        for (Grave g : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(g.id).append("\",\"owner\":\"").append(XrayWatch.json(g.ownerName)).append("\",\"uuid\":\"").append(g.owner)
                    .append("\",\"world\":\"").append(XrayWatch.json(g.world)).append("\",\"x\":").append(g.x).append(",\"y\":").append(g.y).append(",\"z\":").append(g.z)
                    .append(",\"xp\":").append(g.xp).append(",\"made\":").append(g.made).append(",\"returnOnJoin\":").append(g.returnOnJoin).append(",\"items\":");
            Live.items(sb, g.items.toArray(new ItemStack[0]), 0);
            sb.append('}');
        }
        sb.append("]}");
        String out = sb.toString();
        plugin.async(() -> XrayWatch.writeAtomic(new File(dir.getParentFile(), "graves.json"), out));
    }
}
