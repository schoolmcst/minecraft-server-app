// Live: what the Minecraft Server app shows about the running game, written to plugins/XrayWatch every few seconds.
//
//  * live.json (every 2 s): players (where, health, food, ping...), server speed (TPS, tick time), memory, CPU,
//    each world's time and weather, and the graves waiting to be opened.
//  * deaths.csv: every player death, how ("blown up by Creeper") and where.
//  * items.txt: every item id in this Minecraft version (for the app's "Give items" picker).
//  * inv/<name>.json on request ("xw inv <name>"): a player's inventory and ender chest.
// Console commands for the app (also usable by admins in game): xw inv|tp|spawn|grave|graves|map ... (see command()).

package xraywatch;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

final class Live implements Listener {
    private final XrayWatch plugin;
    private final File folder;
    MapRender map;

    Live(XrayWatch plugin, File folder) {
        this.plugin = plugin;
        this.folder = folder;
        new File(folder, "inv").mkdirs();
    }

    // ------------------------------------------------------------------ live.json

    /** Runs on the main thread every 2 seconds. */
    void tick() {
        StringBuilder sb = new StringBuilder(2048);
        Runtime rt = Runtime.getRuntime();
        double[] tps;
        try { tps = plugin.getServer().getTPS(); } catch (Throwable t) { tps = new double[]{20, 20, 20}; }
        double mspt;
        try { mspt = plugin.getServer().getAverageTickTime(); } catch (Throwable t) { mspt = 0; }
        sb.append("{\"time\":").append(System.currentTimeMillis())
                .append(",\"plugin\":\"").append(XrayWatch.VERSION).append('"')
                .append(",\"tps\":[").append(f2(tps[0])).append(',').append(f2(tps.length > 1 ? tps[1] : tps[0])).append(',').append(f2(tps.length > 2 ? tps[2] : tps[0])).append(']')
                .append(",\"mspt\":").append(f2(mspt))
                .append(",\"memUsed\":").append(rt.totalMemory() - rt.freeMemory())
                .append(",\"memMax\":").append(rt.maxMemory())
                .append(",\"cpu\":").append(f2(cpu(false))).append(",\"sysCpu\":").append(f2(cpu(true)))
                .append(",\"cores\":").append(rt.availableProcessors());
        int entities = 0, chunks = 0;
        sb.append(",\"worlds\":[");
        boolean first = true;
        for (World w : plugin.getServer().getWorlds()) {
            if (!first) sb.append(',');
            first = false;
            int e = 0, c = 0;
            try { e = w.getEntityCount(); c = w.getChunkCount(); } catch (Throwable ignored) { }
            entities += e;
            chunks += c;
            Location s = w.getSpawnLocation();
            sb.append("{\"name\":\"").append(XrayWatch.json(w.getName())).append("\",\"env\":\"").append(w.getEnvironment())
                    .append("\",\"time\":").append(w.getTime()).append(",\"storm\":").append(w.hasStorm()).append(",\"thunder\":").append(w.isThundering())
                    .append(",\"spawn\":[").append(s.getBlockX()).append(',').append(s.getBlockY()).append(',').append(s.getBlockZ()).append(']')
                    .append(",\"entities\":").append(e).append(",\"chunks\":").append(c).append('}');
        }
        sb.append("],\"entities\":").append(entities).append(",\"chunks\":").append(chunks);
        sb.append(",\"players\":[");
        first = true;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!first) sb.append(',');
            first = false;
            Location l = p.getLocation();
            World w = l.getWorld();
            sb.append("{\"name\":\"").append(XrayWatch.json(p.getName())).append("\",\"uuid\":\"").append(p.getUniqueId())
                    .append("\",\"world\":\"").append(w == null ? "" : XrayWatch.json(w.getName())).append("\",\"env\":\"").append(w == null ? "" : w.getEnvironment())
                    .append("\",\"x\":").append(f1(l.getX())).append(",\"y\":").append(f1(l.getY())).append(",\"z\":").append(f1(l.getZ()))
                    .append(",\"yaw\":").append(f1(l.getYaw()))
                    .append(",\"health\":").append(f1(p.getHealth())).append(",\"maxHealth\":").append(f1(maxHealth(p)))
                    .append(",\"food\":").append(p.getFoodLevel()).append(",\"level\":").append(p.getLevel())
                    .append(",\"ping\":").append(p.getPing()).append(",\"mode\":\"").append(p.getGameMode()).append("\"}");
        }
        sb.append(']');
        Graves g = plugin.graves;
        sb.append(",\"graves\":").append(g == null ? "[]" : g.summaryJson()).append(",\"gravesOn\":").append(g != null && g.active);
        if (map != null) sb.append(",\"map\":").append(map.statusJson());
        sb.append('}');
        String out = sb.toString();
        plugin.async(() -> XrayWatch.writeAtomic(new File(folder, "live.json"), out));
    }

    @SuppressWarnings("deprecation")
    private static double maxHealth(Player p) {
        try { return p.getMaxHealth(); } catch (Throwable t) { return 20; }
    }

    private static double cpu(boolean system) {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean s) {
                double v = system ? s.getCpuLoad() : s.getProcessCpuLoad();
                return v < 0 ? 0 : v;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    static String f1(double d) { return Double.isFinite(d) ? String.format(Locale.ROOT, "%.1f", d) : "0"; }

    static String f2(double d) { return Double.isFinite(d) ? String.format(Locale.ROOT, "%.2f", d) : "0"; }

    // ------------------------------------------------------------------ deaths

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        Location l = p.getLocation();
        String msg;
        try { msg = e.getDeathMessage(); } catch (Throwable t) { msg = null; }
        if (msg == null || msg.isBlank()) msg = p.getName() + " died";
        msg = strip(msg);
        String line = System.currentTimeMillis() + "," + csv(p.getName()) + "," + csv(l.getWorld() == null ? "" : l.getWorld().getName())
                + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + "," + csv(msg) + "\n";
        plugin.async(() -> {
            File f = new File(folder, "deaths.csv");
            boolean header = !f.exists();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                if (header) w.write("time,player,world,x,y,z,message\n");
                w.write(line);
            } catch (IOException ignored) { }
        });
    }

    static String strip(String s) { return s.replaceAll("§[0-9A-Fa-fK-Ok-oRrXx]", "").replaceAll("[\\r\\n]", " ").trim(); }

    static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ------------------------------------------------------------------ items.txt

    void writeItems() {
        StringBuilder sb = new StringBuilder();
        for (Material m : Material.values()) {
            try {
                if (m.isLegacy() || m.isAir() || !m.isItem()) continue;
            } catch (Throwable t) { continue; }
            sb.append(m.name().toLowerCase(Locale.ROOT)).append('\n');
        }
        String out = sb.toString();
        plugin.async(() -> XrayWatch.writeAtomic(new File(folder, "items.txt"), out));
    }

    // ------------------------------------------------------------------ inventories

    static String fileName(String player) { return player.replaceAll("[^A-Za-z0-9_.-]", "_").toLowerCase(Locale.ROOT) + ".json"; }

    void dumpInventory(Player p) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"name\":\"").append(XrayWatch.json(p.getName())).append("\",\"time\":").append(System.currentTimeMillis())
                .append(",\"health\":").append(f1(p.getHealth())).append(",\"food\":").append(p.getFoodLevel()).append(",\"level\":").append(p.getLevel())
                .append(",\"inventory\":");
        items(sb, p.getInventory().getContents(), 0);
        sb.append(",\"enderChest\":");
        items(sb, p.getEnderChest().getContents(), 0);
        sb.append('}');
        String out = sb.toString();
        String name = p.getName();
        plugin.async(() -> {
            XrayWatch.writeAtomic(new File(new File(folder, "inv"), fileName(name)), out);
            plugin.getLogger().info("INV ready player=" + name);
        });
    }

    /** A list of item slots as JSON (null for an empty slot). Shulker boxes and bundles list what's inside. */
    static void items(StringBuilder sb, ItemStack[] slots, int depth) {
        sb.append('[');
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) sb.append(',');
            item(sb, slots[i], depth);
        }
        sb.append(']');
    }

    @SuppressWarnings("deprecation")
    static void item(StringBuilder sb, ItemStack it, int depth) {
        if (it == null || it.isEmpty()) { sb.append("null"); return; }
        sb.append("{\"id\":\"").append(it.getType().name().toLowerCase(Locale.ROOT)).append("\",\"n\":").append(it.getAmount());
        if (!it.hasItemMeta()) { sb.append('}'); return; }
        try {
            ItemMeta m = it.getItemMeta();
            if (m == null) { sb.append('}'); return; }
            if (m.hasDisplayName()) sb.append(",\"name\":\"").append(XrayWatch.json(strip(m.getDisplayName()))).append('"');
            if (m instanceof Damageable d && d.hasDamage()) sb.append(",\"dmg\":").append(d.getDamage()).append(",\"max\":").append(it.getType().getMaxDurability());
            Map<Enchantment, Integer> ench = new LinkedHashMap<>(m.getEnchants());
            if (m instanceof EnchantmentStorageMeta es) ench.putAll(es.getStoredEnchants());
            if (!ench.isEmpty()) {
                sb.append(",\"ench\":{");
                boolean first = true;
                for (Map.Entry<Enchantment, Integer> e : ench.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('"').append(XrayWatch.json(e.getKey().getKey().getKey())).append("\":").append(e.getValue());
                }
                sb.append('}');
            }
            if (depth == 0 && m instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox box) {
                sb.append(",\"inside\":");
                items(sb, box.getInventory().getContents(), depth + 1);
            } else if (depth == 0 && m instanceof BundleMeta bm && bm.hasItems()) {
                sb.append(",\"inside\":");
                items(sb, bm.getItems().toArray(new ItemStack[0]), depth + 1);
            }
        } catch (Throwable ignored) { }
        sb.append('}');
    }

    // ------------------------------------------------------------------ commands from the app

    /** Handles "xw <sub> ..." commands for the app. Returns false if it isn't one of these. */
    boolean command(CommandSender s, String[] a) {
        if (a.length == 0) return false;
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "inv" -> {
                Player p = a.length > 1 ? plugin.getServer().getPlayerExact(a[1]) : null;
                if (p == null) { s.sendMessage("§7Nobody called " + (a.length > 1 ? a[1] : "?") + " is online."); plugin.getLogger().info("INV offline player=" + (a.length > 1 ? a[1] : "?")); return true; }
                dumpInventory(p);
                return true;
            }
            case "tp" -> { // xw tp <player> <x> <z> [world]: onto the highest ground there
                if (a.length < 4) { s.sendMessage("§7Use: /xw tp <player> <x> <z> [world]"); return true; }
                Player p = plugin.getServer().getPlayerExact(a[1]);
                if (p == null) { s.sendMessage("§7" + a[1] + " isn't online."); return true; }
                World w = a.length > 4 ? plugin.getServer().getWorld(a[4]) : p.getLocation().getWorld();
                if (w == null) w = plugin.getServer().getWorlds().get(0);
                int x, z;
                try { x = (int) Math.floor(Double.parseDouble(a[2])); z = (int) Math.floor(Double.parseDouble(a[3])); }
                catch (NumberFormatException e) { s.sendMessage("§7Those aren't coordinates."); return true; }
                int y = w.getHighestBlockYAt(x, z) + 1;
                if (String.valueOf(w.getEnvironment()).equals("NETHER")) y = Math.min(y, 120);
                p.teleport(new Location(w, x + 0.5, y, z + 0.5));
                p.sendMessage("§7An admin moved you to §f" + x + " " + y + " " + z + "§7.");
                plugin.getLogger().info("TP player=" + p.getName() + " world=" + w.getName() + " x=" + x + " y=" + y + " z=" + z);
                return true;
            }
            case "spawn" -> {
                Player p = a.length > 1 ? plugin.getServer().getPlayerExact(a[1]) : null;
                if (p == null) { s.sendMessage("§7Nobody by that name is online."); return true; }
                World w = plugin.getServer().getWorlds().get(0);
                Location l = w.getSpawnLocation();
                p.teleport(new Location(w, l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5));
                p.sendMessage("§7An admin sent you to spawn.");
                plugin.getLogger().info("TP player=" + p.getName() + " world=" + w.getName() + " x=" + l.getBlockX() + " y=" + l.getBlockY() + " z=" + l.getBlockZ());
                return true;
            }
            case "grave" -> { // xw grave return <id>
                Graves g = plugin.graves;
                if (g == null) { s.sendMessage("§7Graves are off."); return true; }
                if (a.length < 3 || !a[1].equalsIgnoreCase("return")) { s.sendMessage("§7Use: /xw grave return <id>"); return true; }
                s.sendMessage(g.giveBack(a[2]));
                return true;
            }
            case "graves" -> { // xw graves on|off: for a while (presets), not saved
                Graves g = plugin.graves;
                if (g == null) { s.sendMessage("§7Graves are turned off in the app."); return true; }
                g.active = a.length < 2 || !a[1].equalsIgnoreCase("off");
                plugin.getLogger().info("GRAVES " + (g.active ? "on" : "off"));
                return true;
            }
            case "map" -> { // xw map fill <radius>
                if (map == null) { s.sendMessage("§7The map is off."); return true; }
                if (a.length >= 2 && a[1].equalsIgnoreCase("fill")) {
                    int r = 1000;
                    try { if (a.length > 2) r = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) { }
                    map.fill(Math.max(64, Math.min(r, 20_000)));
                    return true;
                }
                if (a.length >= 2 && a[1].equalsIgnoreCase("stop")) { map.stopFill(); return true; }
                s.sendMessage("§7Use: /xw map fill <radius> | stop");
                return true;
            }
            default -> { return false; }
        }
    }
}
