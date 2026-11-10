// Graves: when a player dies, their items and XP go into a grave (a player head) where they died instead of dropping
// on the ground and despawning. Works the same for Java and Bedrock/Xbox players (Geyser shows the head; using it or
// breaking it is how you open it), so it's only a block plus chat messages, no floating text.
//
//  * Only the owner can open it at first; after `unlock-minutes` anyone can (so items are never lost forever).
//  * Creepers, TNT, pistons, water and lava can't destroy it. Graves survive server restarts (plugins/XrayWatch/graves).
//  * Keep inventory on = no graves needed, so none are made.
//  * Logs "GRAVE made|opened ..." lines for the Minecraft Server app's activity feed.

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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class Graves implements Listener {
    private final XrayWatch plugin;
    private final File dir;
    final int unlockMinutes;
    /** "world:x:y:z" -> grave */
    final Map<String, Grave> graves = new HashMap<>();

    static final class Grave {
        String id, ownerName, world;
        UUID owner;
        int x, y, z, xp;
        long made;
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
        if (p == null || e.getKeepInventory()) return;
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
        graves.remove(g.key());
        block.setType(Material.AIR);
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
            Location loc = block.getLocation();
            for (ItemStack i : left.values()) { block.getWorld().dropItemNaturally(loc, i); dropped++; }
        }
        if (g.xp > 0) p.giveExp(g.xp);
        p.sendMessage(owner ? "§a☠ You got your items back" + (dropped > 0 ? " §7(your inventory was full, so some are on the ground)" : "") + "§a."
                : "§a☠ You opened " + g.ownerName + "'s grave.");
        plugin.getLogger().info("GRAVE opened player=" + p.getName() + " owner=" + g.ownerName + " world=" + g.world
                + " x=" + g.x + " y=" + g.y + " z=" + g.z);
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
    }
}
