// XrayWatch: finds players who use X-ray (hacked clients, X-ray texture packs, or seed-based ore finders) by how
// they mine. It is written for the Minecraft Server app and installed by it on survival servers.
//
// How it decides:
//  * Only mining underground counts (below y=40 in the Overworld, anywhere in the Nether), in survival/adventure.
//  * Every valuable ore it sees (diamond, ancient debris, deep emerald) is grouped into veins. For each NEW vein it
//    checks whether the ore was "hidden": none of its six neighbours was open air that the player didn't dig
//    themselves. Ores in cave walls or lava lakes are visible to anyone and never count against a player.
//  * Over the last 30 minutes (by default) it compares hidden veins found with stone-type blocks mined. Honest
//    branch mining finds roughly one diamond vein per 100-170 blocks; X-ray finds one every 20-40 because the player
//    digs straight to ores they can't see. Ancient debris counts double because it's much rarer.
//  * The strongest sign is TURNING TOWARD ORE YOU CAN'T SEE: the tunnel bends, then runs straight into (or stops
//    right on top of) a hidden vein. Honest tunnels run straight and find ores beside them. In testing, honest
//    branch miners did this at most once per ~650 blocks; X-rayers did it 3-11 times per ~370 blocks.
//  * For both signs it asks: "how likely is it that an honest miner gets this many in this many blocks?" (a Poisson
//    test against generous honest rates: 0.3 targeted veins and 2.0 hidden veins per 100 blocks). One lucky streak
//    stays well under the line; only a pattern luck can't explain is flagged.
//    Score = -20 x log10(chance): 60 = 1 in 1,000 by luck, 80 = 1 in 10,000.
// When a player crosses "suspicious" or "very likely", it logs a FLAG line (the app turns it into a message),
// tells online admins, and keeps a history of every vein found in plugins/XrayWatch/finds.csv for checking.
// /xraywatch (or /xw) [player | reset <player>] shows the numbers in game (admins only).
//
// It also reports which game client each Java player joined with (the "brand" their game sends: vanilla, fabric,
// forge, lunarclient... and some hacked clients name themselves). It logs "CLIENT player=... brand=..." and the app
// decides what to do (show it, warn, or kick). Bedrock/Xbox players (Geyser) are skipped.
//
// And it makes graves (Graves.java), and feeds the app's live pages: map, player positions, inventories, server
// speed, deaths (Live.java, MapRender.java).

package xraywatch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class XrayWatch extends JavaPlugin implements Listener {
    static final String VERSION = "1.3";

    static final Set<String> FILLER = Set.of("STONE", "DEEPSLATE", "TUFF", "ANDESITE", "DIORITE", "GRANITE", "COBBLED_DEEPSLATE",
            "NETHERRACK", "BASALT", "BLACKSTONE", "SMOOTH_BASALT", "CALCITE", "DRIPSTONE_BLOCK", "GRAVEL", "SOUL_SAND", "SOUL_SOIL",
            "MAGMA_BLOCK", "INFESTED_STONE", "INFESTED_DEEPSLATE");
    static final Set<String> OPEN = Set.of("AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA", "BUBBLE_COLUMN");

    /** Ore weight: how much a hidden vein of it says about X-ray. 0 = not valuable. */
    static double weight(String type, int y, boolean nether) {
        switch (type) {
            case "DIAMOND_ORE": case "DEEPSLATE_DIAMOND_ORE": return 1.0;
            case "ANCIENT_DEBRIS": return 2.0;
            case "EMERALD_ORE": case "DEEPSLATE_EMERALD_ORE": return y < 40 ? 1.0 : 0;
            default: return 0;
        }
    }

    // settings (plugins/XrayWatch/config.properties, written by the app)
    double honestRate = 2.0;                  // hidden veins per 100 blocks an honest miner might get (generous)
    double honestTargetedRate = 0.3;          // veins reached by turning toward them, per 100 blocks (generous)
    int suspiciousScore = 60, likelyScore = 80; // score = -20 * log10(chance it's luck)
    int minBlocks = 40, minVeins = 4, windowMinutes = 30, longWindowMinutes = 180;
    boolean notifyAdmins = true;
    boolean gravesOn = true, xrayDetect = true, mapOn = true;
    int graveUnlockMinutes = 60;

    final Map<UUID, Tracker> trackers = new ConcurrentHashMap<>();
    private ScheduledExecutorService saver;
    Graves graves;
    Live live;
    private File folder;

    // ------------------------------------------------------------------ lifecycle

    @Override public void onEnable() {
        folder = getDataFolder();
        folder.mkdirs();
        loadSettings();
        saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "XrayWatch-save");
            t.setDaemon(true);
            return t;
        });
        getServer().getPluginManager().registerEvents(this, this);
        if (gravesOn) {
            graves = new Graves(this, folder, graveUnlockMinutes);
            getServer().getPluginManager().registerEvents(graves, this);
            getLogger().info("Graves are on" + (graveUnlockMinutes > 0 ? " (anyone can open one after " + graveUnlockMinutes + " minutes)" : " (only the owner can open one)"));
        } else {
            new File(folder, "graves.json").delete();
        }
        live = new Live(this, folder);
        getServer().getPluginManager().registerEvents(live, this);
        live.writeItems();
        getServer().getScheduler().runTaskTimer(this, live::tick, 40L, 40L);
        if (mapOn) {
            live.map = new MapRender(this, folder);
            getServer().getScheduler().runTaskTimer(this, live.map::tick, 60L, 1L);
        }
        saver.scheduleWithFixedDelay(this::saveReport, 20, 30, TimeUnit.SECONDS);
        getLogger().info("Watching for X-ray (version " + VERSION + "; honest rate " + honestRate + " veins per 100 blocks, "
                + "suspicious at score " + suspiciousScore + ", very likely at " + likelyScore + ")");
    }

    @Override public void onDisable() {
        if (live != null && live.map != null) live.map.shutdown();
        if (saver != null) {
            saver.shutdown();
            try { saver.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }
        saveReport();
        new File(folder, "live.json").delete(); // the app knows the server is gone
    }

    /** Runs file writing off the game thread. */
    void async(Runnable r) {
        ScheduledExecutorService s = saver;
        if (s == null || s.isShutdown()) { r.run(); return; }
        try { s.execute(r); } catch (RejectedExecutionException e) { r.run(); }
    }

    /** Writes a file the app reads, all at once (the app never sees half a file). */
    static void writeAtomic(File f, String content) {
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    void loadSettings() {
        File f = new File(folder, "config.properties");
        Properties p = new Properties();
        if (f.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) { }
        }
        honestRate = Math.max(0.1, num(p, "honest-rate", honestRate));
        honestTargetedRate = Math.max(0.05, num(p, "honest-targeted-rate", honestTargetedRate));
        suspiciousScore = (int) num(p, "suspicious-score", suspiciousScore);
        likelyScore = Math.max(suspiciousScore, (int) num(p, "likely-score", likelyScore));
        minBlocks = (int) num(p, "min-blocks", minBlocks);
        minVeins = (int) num(p, "min-veins", minVeins);
        windowMinutes = (int) num(p, "window-minutes", windowMinutes);
        longWindowMinutes = Math.max(windowMinutes, (int) num(p, "long-window-minutes", longWindowMinutes));
        notifyAdmins = !"false".equalsIgnoreCase(p.getProperty("notify-admins", "true"));
        gravesOn = !"false".equalsIgnoreCase(p.getProperty("graves", "true"));
        mapOn = !"false".equalsIgnoreCase(p.getProperty("map", "true"));
        xrayDetect = !"false".equalsIgnoreCase(p.getProperty("xray-detect", "true"));
        graveUnlockMinutes = (int) num(p, "grave-unlock-minutes", graveUnlockMinutes);
    }

    private static double num(Properties p, String k, double def) {
        try { return Double.parseDouble(p.getProperty(k, String.valueOf(def)).trim()); } catch (NumberFormatException e) { return def; }
    }

    // ------------------------------------------------------------------ game client

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        if (!reportBrand(p) && saver != null) {
            // the game sometimes tells the server its name a moment after joining
            saver.schedule(() -> { if (!reportBrand(p)) getLogger().info("CLIENT player=" + p.getName() + " brand=unknown"); }, 3, TimeUnit.SECONDS);
        }
    }

    /** Logs the player's game client; false if the game hasn't said yet. */
    boolean reportBrand(Player p) {
        String brand;
        try { brand = p.getClientBrandName(); } catch (Throwable t) { return true; } // older server without the method
        if (brand == null || brand.isBlank()) return false;
        brand = brand.replaceAll("[\\r\\n]", " ").trim();
        if (brand.length() > 64) brand = brand.substring(0, 64);
        getLogger().info("CLIENT player=" + p.getName() + " brand=" + brand);
        return true;
    }

    // ------------------------------------------------------------------ watching

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!xrayDetect) return;
        Player p = e.getPlayer();
        if (p == null) return;
        String mode = String.valueOf(p.getGameMode());
        if (!mode.equals("SURVIVAL") && !mode.equals("ADVENTURE")) return;
        Block b = e.getBlock();
        String type = String.valueOf(b.getType());
        boolean nether = String.valueOf(b.getWorld().getEnvironment()).equals("NETHER");
        int x = b.getX(), y = b.getY(), z = b.getZ();
        boolean deep = nether || y < 40;
        if (!deep) return;
        double w = weight(type, y, nether);
        boolean filler = FILLER.contains(type);
        if (!filler && w == 0) return;

        Tracker t = trackers.computeIfAbsent(p.getUniqueId(), u -> new Tracker());
        long now = System.currentTimeMillis();
        String world = b.getWorld().getName();
        synchronized (t) {
            t.name = p.getName();
            if (filler) {
                t.fillerTimes.addLast(now);
                t.sinceVein++;
                t.totalBlocks++;
                t.dug(world, x, y, z, now);
                t.step(world, x, z);
                return;
            }
            // valuable ore
            t.dug(world, x, y, z, now);
            if (t.sameVein(world, x, y, z, now)) { t.lastOre(world, x, y, z, now); return; }
            t.lastOre(world, x, y, z, now);
            boolean exposed = false;
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] d : dirs) {
                String n = String.valueOf(b.getRelative(d[0], d[1], d[2]).getType());
                if (OPEN.contains(n) && !t.didDig(world, x + d[0], y + d[1], z + d[2])) { exposed = true; break; }
            }
            boolean targeted = !exposed && t.headedStraightAt(world, x, z);
            Find f = new Find(now, type, world, x, y, z, !exposed, t.sinceVein, w, targeted);
            t.sinceVein = 0;
            t.finds.addLast(f);
            while (t.finds.size() > 400) t.finds.removeFirst();
            t.totalVeins++;
            if (!exposed) t.totalHidden++;
            appendFind(p, f);
            evaluate(p, t, now);
        }
    }

    /** Works out the score over the time window and raises a flag when the level goes up (or every 10 min at "very likely"). */
    void evaluate(Player p, Tracker t, long now) {
        Stats s = worst(t, now);
        int level = level(s);
        t.score = score(s);
        t.level = level;
        if (level == 0) { t.flagged = 0; return; }
        boolean raise = level > t.flagged || (level == 2 && now - t.lastFlag > 10 * 60_000L);
        if (!raise) return;
        t.flagged = level;
        t.lastFlag = now;
        Find last = t.finds.peekLast();
        String word = level == 2 ? "likely" : "suspicious";
        getLogger().info("FLAG " + word + " player=" + p.getName() + " score=" + t.score + " veins=" + s.hiddenVeins + " targeted=" + s.targetedVeins
                + " weighted=" + String.format(Locale.ROOT, "%.1f", s.hiddenWeight) + " blocks=" + s.blocks
                + " tunnel=" + s.medianTunnel + " minutes=" + s.minutes
                + (last == null ? "" : " world=" + last.world + " x=" + last.x + " y=" + last.y + " z=" + last.z + " ore=" + last.type));
        if (notifyAdmins) {
            String msg = "§c[XrayWatch] §f" + p.getName() + (level == 2 ? " §cis very likely using X-ray" : " §6might be using X-ray")
                    + " §7(dug straight to " + s.targetedVeins + " hidden ore veins; " + s.hiddenVeins + " hidden veins in " + s.blocks + " blocks). /xw " + p.getName();
            for (Player op : getServer().getOnlinePlayers()) if (op.isOp() && !op.getUniqueId().equals(p.getUniqueId())) op.sendMessage(msg);
        }
        saveReportLater();
    }

    /** The numbers for the short window (a burst of X-ray) or the long one (slow, careful X-ray), whichever looks worse. */
    Stats worst(Tracker t, long now) {
        Stats a = t.stats(now, windowMinutes), b = t.stats(now, longWindowMinutes);
        Stats w = score(b) > score(a) ? b : a;
        w.minutes = w == b ? longWindowMinutes : windowMinutes;
        return w;
    }

    int level(Stats s) {
        if (s.blocks < minBlocks || s.hiddenVeins < minVeins) return 0;
        int sc = score(s);
        return sc >= likelyScore ? 2 : sc >= suspiciousScore ? 1 : 0;
    }

    /** 0-100: -20 x log10 of the chance an honest miner would find at least this many hidden veins in these blocks. */
    int score(Stats s) {
        if (s.blocks == 0 || s.hiddenWeight < 1) return 0;
        double byVeins = logPoissonTail((int) Math.floor(s.hiddenWeight), honestRate * s.blocks / 100.0);
        double byTurning = logPoissonTail((int) Math.floor(s.targetedWeight), honestTargetedRate * s.blocks / 100.0);
        int sc = (int) Math.round(Math.max(0, -20 * Math.min(byVeins, byTurning)));
        if (s.blocks < minBlocks || s.hiddenVeins < minVeins) sc = Math.min(sc, suspiciousScore - 1);
        return Math.min(100, sc);
    }

    /** log10 of P(X >= k) for X ~ Poisson(lambda). */
    static double logPoissonTail(int k, double lambda) {
        if (k <= 0) return 0;
        // P(X >= k) = 1 - sum_{i<k} p(i); for large k use the sum of the upper terms instead (more accurate)
        double logTerm = -lambda + k * Math.log(lambda) - logFactorial(k), sum = 0, term = 1;
        for (int i = k; i < k + 400; i++) {
            sum += term;
            term *= lambda / (i + 1);
            if (term < 1e-12 * sum) break;
        }
        double ln = logTerm + Math.log(sum);
        return Math.min(0, ln / Math.log(10));
    }

    static double logFactorial(int n) {
        double r = 0;
        for (int i = 2; i <= n; i++) r += Math.log(i);
        return r;
    }

    // ------------------------------------------------------------------ files the app reads

    private void appendFind(Player p, Find f) {
        File file = new File(folder, "finds.csv");
        boolean header = !file.exists();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            if (header) w.write("time,player,uuid,ore,world,x,y,z,hidden,blocks_since_last,targeted\n");
            w.write(f.time + "," + p.getName() + "," + p.getUniqueId() + "," + f.type + "," + f.world + "," + f.x + "," + f.y + "," + f.z
                    + "," + f.hidden + "," + f.tunnel + "," + f.targeted + "\n");
        } catch (IOException ignored) { }
        if (file.length() > 2_000_000) trimCsv(file);
    }

    private static void trimCsv(File file) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            List<String> keep = new ArrayList<>();
            keep.add(lines.get(0));
            keep.addAll(lines.subList(Math.max(1, lines.size() - 10_000), lines.size()));
            Files.write(file.toPath(), keep, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private volatile boolean savePending;

    private void saveReportLater() {
        if (savePending || saver == null) return;
        savePending = true;
        saver.schedule(() -> { savePending = false; saveReport(); }, 1, TimeUnit.SECONDS);
    }

    /** report.json: each player's numbers for the current window, for the app's X-ray report. */
    void saveReport() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("{\"updated\":").append(now).append(",\"windowMinutes\":").append(windowMinutes)
                .append(",\"honestRate\":").append(honestRate).append(",\"honestTargetedRate\":").append(honestTargetedRate).append(",\"suspicious\":").append(suspiciousScore)
                .append(",\"likely\":").append(likelyScore).append(",\"players\":[");
        boolean first = true;
        for (Map.Entry<UUID, Tracker> e : trackers.entrySet()) {
            Tracker t = e.getValue();
            synchronized (t) {
                Stats s = worst(t, now);
                t.score = score(s);
                t.level = level(s);
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(json(t.name)).append("\",\"uuid\":\"").append(e.getKey())
                        .append("\",\"score\":").append(t.score).append(",\"level\":").append(t.level)
                        .append(",\"blocks\":").append(s.blocks).append(",\"hiddenVeins\":").append(s.hiddenVeins)
                        .append(",\"openVeins\":").append(s.openVeins).append(",\"targetedVeins\":").append(s.targetedVeins).append(",\"rate\":").append(String.format(Locale.ROOT, "%.2f", s.rate))
                        .append(",\"medianTunnel\":").append(s.medianTunnel).append(",\"minutes\":").append(s.minutes)
                        .append(",\"totalBlocks\":").append(t.totalBlocks).append(",\"totalVeins\":").append(t.totalVeins)
                        .append(",\"totalHidden\":").append(t.totalHidden).append('}');
            }
        }
        sb.append("]}");
        try {
            Path tmp = new File(folder, "report.json.tmp").toPath();
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, new File(folder, "report.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    static String json(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c >= 0x20) b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ /xraywatch

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) { sender.sendMessage("§cOnly admins can use this."); return true; }
        if (live != null && live.command(sender, args)) return true;
        long now = System.currentTimeMillis();
        if (args.length >= 2 && args[0].equalsIgnoreCase("reset")) {
            for (Tracker t : trackers.values()) synchronized (t) {
                if (args[1].equalsIgnoreCase(t.name)) { t.reset(); sender.sendMessage("§aReset " + t.name + "."); return true; }
            }
            sender.sendMessage("§7No numbers for " + args[1] + " yet.");
            return true;
        }
        if (args.length >= 1 && !args[0].equalsIgnoreCase("top")) {
            for (Tracker t : trackers.values()) synchronized (t) {
                if (!args[0].equalsIgnoreCase(t.name)) continue;
                Stats s = worst(t, now);
                sender.sendMessage("§e" + t.name + " §7(last " + s.minutes + " min): §fscore " + score(s)
                        + "§7, " + s.targetedVeins + " dug straight to, " + s.hiddenVeins + " hidden + " + s.openVeins + " open veins, " + s.blocks + " blocks mined");
                int shown = 0;
                for (Iterator<Find> it = t.finds.descendingIterator(); it.hasNext() && shown < 6; shown++) {
                    Find f = it.next();
                    sender.sendMessage("§7 - " + f.type.toLowerCase(Locale.ROOT).replace('_', ' ') + " at " + f.x + " " + f.y + " " + f.z
                            + (f.targeted ? " §4(dug straight to it while hidden)" : f.hidden ? " §c(hidden, " + f.tunnel + " blocks after the last one)" : " §a(open)"));
                }
                return true;
            }
            sender.sendMessage("§7No mining numbers for " + args[0] + " yet.");
            return true;
        }
        List<Tracker> list = new ArrayList<>(trackers.values());
        list.sort((a, b) -> Integer.compare(b.score, a.score));
        sender.sendMessage("§eXrayWatch §7- highest scores (100 = almost certainly X-ray):");
        int n = 0;
        for (Tracker t : list) {
            if (n++ >= 5) break;
            synchronized (t) { sender.sendMessage("§7 " + t.name + ": §f" + score(worst(t, now))); }
        }
        if (list.isEmpty()) sender.sendMessage("§7 Nobody has mined underground yet.");
        return true;
    }

    // ------------------------------------------------------------------ data

    record Find(long time, String type, String world, int x, int y, int z, boolean hidden, int tunnel, double weight, boolean targeted) { }

    static final class Stats {
        int blocks, hiddenVeins, openVeins, medianTunnel, targetedVeins, minutes;
        double hiddenWeight, targetedWeight, rate;
    }

    static final class Tracker {
        String name = "?";
        final ArrayDeque<Long> fillerTimes = new ArrayDeque<>();
        final ArrayDeque<Find> finds = new ArrayDeque<>();
        /** Blocks this player dug recently, so air they made themselves doesn't count as "the ore was visible". */
        final LinkedHashMap<String, Long> dugAt = new LinkedHashMap<>(256, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 6000; }
        };
        final ArrayDeque<long[]> recentOres = new ArrayDeque<>(); // x, y, z, time, worldHash
        /** The player's tunnel seen from above: the columns they dug through, in order (most recent last). */
        final ArrayDeque<int[]> path = new ArrayDeque<>();
        String pathWorld = "";
        int sinceVein, score, level, flagged;

        void step(String w, int x, int z) {
            int[] last = path.peekLast();
            if (!w.equals(pathWorld) || (last != null && Math.abs(last[0] - x) + Math.abs(last[1] - z) > 2)) { path.clear(); pathWorld = w; }
            if (last != null && last[0] == x && last[1] == z) return; // same column (a 2-tall tunnel)
            path.addLast(new int[]{x, z});
            while (path.size() > 16) path.removeFirst();
        }

        /**
         * True when the tunnel turned within its last few blocks and then ran straight into this ore: digging toward
         * something you can't see. Honest tunnels run straight and find ores beside them, not after a turn.
         */
        boolean headedStraightAt(String w, int ox, int oz) {
            if (!w.equals(pathWorld) || path.size() < 3) return false;
            List<int[]> c = new ArrayList<>(path);
            int[] end = c.get(c.size() - 1), prev = c.get(c.size() - 2);
            int fx = Integer.signum(end[0] - prev[0]), fz = Integer.signum(end[1] - prev[1]);
            if (Math.abs(end[0] - prev[0]) + Math.abs(end[1] - prev[1]) != 1) return false;
            int ax = ox - end[0], az = oz - end[1];
            boolean ahead = ax == fx && az == fz;      // the ore is straight ahead
            boolean underfoot = ax == 0 && az == 0;    // the tunnel stopped right above/below it
            if (!ahead && !underfoot) return false;
            for (int i = c.size() - 2; i >= 1 && i >= c.size() - 12; i--) {
                int sx = c.get(i)[0] - c.get(i - 1)[0], sz = c.get(i)[1] - c.get(i - 1)[1];
                if (Math.abs(sx) + Math.abs(sz) == 1 && (sx != fx || sz != fz)) return true; // a turn not long before
            }
            return false;
        }
        long lastFlag, totalBlocks, totalVeins, totalHidden;

        static String key(String w, int x, int y, int z) { return w + ":" + x + ":" + y + ":" + z; }

        void dug(String w, int x, int y, int z, long now) { dugAt.put(key(w, x, y, z), now); }

        boolean didDig(String w, int x, int y, int z) {
            Long t = dugAt.get(key(w, x, y, z));
            return t != null && System.currentTimeMillis() - t < 60 * 60_000L;
        }

        /** Ores within 3 blocks of one dug in the last 10 minutes belong to the same vein. */
        boolean sameVein(String w, int x, int y, int z, long now) {
            for (long[] o : recentOres)
                if (o[4] == w.hashCode() && now - o[3] < 10 * 60_000L && Math.abs(o[0] - x) <= 3 && Math.abs(o[1] - y) <= 3 && Math.abs(o[2] - z) <= 3)
                    return true;
            return false;
        }

        void lastOre(String w, int x, int y, int z, long now) {
            recentOres.addLast(new long[]{x, y, z, now, w.hashCode()});
            while (recentOres.size() > 64) recentOres.removeFirst();
        }

        Stats stats(long now, int minutes) {
            long from = now - minutes * 60_000L;
            while (!fillerTimes.isEmpty() && fillerTimes.peekFirst() < now - 6 * 60 * 60_000L) fillerTimes.removeFirst();
            Stats s = new Stats();
            s.minutes = minutes;
            for (Long ft : fillerTimes) if (ft >= from) s.blocks++;
            List<Integer> tunnels = new ArrayList<>();
            for (Find f : finds) {
                if (f.time < from) continue;
                if (f.hidden) { s.hiddenVeins++; s.hiddenWeight += f.weight; tunnels.add(f.tunnel); } else s.openVeins++;
                if (f.targeted) { s.targetedVeins++; s.targetedWeight += f.weight; }
            }
            Collections.sort(tunnels);
            s.medianTunnel = tunnels.isEmpty() ? 0 : tunnels.get(tunnels.size() / 2);
            s.rate = s.blocks == 0 ? 0 : s.hiddenWeight * 100.0 / Math.max(s.blocks, 1);
            return s;
        }

        void reset() {
            fillerTimes.clear();
            finds.clear();
            recentOres.clear();
            sinceVein = 0;
            score = 0;
            level = 0;
            flagged = 0;
        }
    }
}
