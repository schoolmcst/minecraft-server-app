// What's happening inside the running game, for the Map, World, Health and player pages.
// On survival (Paper) servers the app's own plugin (XrayWatch, plugin/src) writes plugins/XrayWatch/live.json every
// 2 seconds (players and where they are, server speed, memory, CPU, graves), deaths.csv, graves.json, items.txt and
// inv/<name>.json on request. Modded (Fabric) servers have no plugin: only CPU use (measured here) and plain commands.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;

final class Game {
    private Game() { }

    // ------------------------------------------------------------------ data

    record Player(String name, String uuid, String world, String env, double x, double y, double z, double yaw, double health,
                  double maxHealth, int food, int level, int ping, String mode) {
        String nice() { return Server.nice(name); }
        String plain() { return name.replaceFirst("^\\.", ""); }
        boolean bedrock() { return name.startsWith("."); }
    }

    record World(String name, String env, long time, boolean storm, boolean thunder, int sx, int sy, int sz) { }

    record GraveSum(String id, String owner, String world, int x, int y, int z, int items) { }

    record Snapshot(long time, double tps, double mspt, long memUsed, long memMax, double cpu, double sysCpu, int cores,
                    int entities, int chunks, List<Player> players, List<World> worlds, List<GraveSum> graves, boolean gravesOn,
                    boolean filling, int fillTotal, int fillDone) {
        World main() { return worlds.isEmpty() ? null : worlds.get(0); }
        Player player(String name) {
            for (Player p : players) if (p.name().equalsIgnoreCase(name) || p.plain().equalsIgnoreCase(name)) return p;
            return null;
        }
    }

    /** One point on the Health page graph. tps/mspt/mem are NaN when unknown (modded servers). */
    record Sample(long time, double tps, double mspt, double memUsedGb, double memMaxGb, double cpu, int players) { }

    record Item(String id, int count, String name, int damage, int maxDamage, Map<String, Integer> ench, List<Item> inside) {
        String label() { return name != null ? name : Items.nameOf(id); }
    }

    record Inventory(String player, long time, double health, int food, int level, List<Item> inventory, List<Item> enderChest) { }

    record Death(long time, String player, String world, int x, int y, int z, String message) { }

    record Grave(String id, String owner, String uuid, String world, int x, int y, int z, int xp, long made, boolean returnOnJoin, List<Item> items) {
        int count() { int n = 0; for (Item i : items) if (i != null) n += i.count(); return n; }
    }

    static volatile Snapshot now;
    private static final List<Sample> history = new ArrayList<>();
    static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile Server server;
    private static long lastCpuNanos = -1, lastCpuAt;

    static Path dir() { return Kit.plugins().resolve("XrayWatch"); }

    /** True when this server type gets live data (the plugin): survival servers. */
    static boolean supported() { return Kit.hasServer() && !Kit.isFabric(); }

    static List<Sample> history() { synchronized (history) { return new ArrayList<>(history); } }

    static void clearHistory() { synchronized (history) { history.clear(); } now = null; }

    // ------------------------------------------------------------------ polling

    static void start(Server s) {
        server = s;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-live");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> { try { poll(); } catch (Throwable ignored) { } }, 1, 2, TimeUnit.SECONDS);
    }

    private static long lastMtime;

    private static void poll() {
        Server s = server;
        boolean running = s != null && Kit.hasServer() && s.state() == Server.State.RUNNING;
        Snapshot before = now;
        if (!running) {
            now = null;
            lastCpuNanos = -1;
            if (before != null) fire();
            return;
        }
        if (supported()) {
            Path live = dir().resolve("live.json");
            try {
                long m = Files.getLastModifiedTime(live).toMillis();
                if (System.currentTimeMillis() - m > 15_000) { now = null; if (before != null) fire(); return; }
                if (m == lastMtime && before != null) return;
                lastMtime = m;
                Snapshot snap = parse(Files.readString(live, StandardCharsets.UTF_8));
                now = snap;
                add(new Sample(snap.time(), snap.tps(), snap.mspt(), snap.memUsed() / 1e9, snap.memMax() / 1e9, snap.cpu(), snap.players().size()));
                fire();
            } catch (IOException | RuntimeException e) {
                // not there yet (server still starting, or an older plugin)
            }
        } else {
            // modded server: CPU use of the server process, measured here
            Process p = s.process();
            if (p == null) return;
            long cpu = p.toHandle().info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L);
            long at = System.nanoTime();
            if (cpu >= 0 && lastCpuNanos >= 0) {
                double used = (cpu - lastCpuNanos) / (double) (at - lastCpuAt) / Runtime.getRuntime().availableProcessors();
                add(new Sample(System.currentTimeMillis(), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Math.max(0, Math.min(1, used)), s.online.size()));
                fire();
            }
            lastCpuNanos = cpu;
            lastCpuAt = at;
        }
    }

    private static void add(Sample x) {
        synchronized (history) {
            history.add(x);
            long cut = x.time() - 60 * 60_000L;
            while (!history.isEmpty() && history.get(0).time() < cut) history.remove(0);
        }
    }

    private static void fire() { for (Runnable r : listeners) r.run(); }

    static Snapshot parse(String json) throws IOException {
        Object o = Kit.Json.parse(json);
        List<?> tps = Kit.Json.list(o, "tps");
        List<Player> players = new ArrayList<>();
        for (Object p : Kit.Json.list(o, "players"))
            players.add(new Player(str(p, "name"), str(p, "uuid"), str(p, "world"), str(p, "env"), num(p, "x"), num(p, "y"), num(p, "z"), num(p, "yaw"),
                    num(p, "health"), num(p, "maxHealth"), (int) num(p, "food"), (int) num(p, "level"), (int) num(p, "ping"), str(p, "mode")));
        List<World> worlds = new ArrayList<>();
        for (Object w : Kit.Json.list(o, "worlds")) {
            List<?> sp = Kit.Json.list(w, "spawn");
            worlds.add(new World(str(w, "name"), str(w, "env"), (long) num(w, "time"), bool(w, "storm"), bool(w, "thunder"),
                    sp.size() > 0 ? (int) n(sp.get(0)) : 0, sp.size() > 1 ? (int) n(sp.get(1)) : 64, sp.size() > 2 ? (int) n(sp.get(2)) : 0));
        }
        List<GraveSum> graves = new ArrayList<>();
        for (Object g : Kit.Json.list(o, "graves"))
            graves.add(new GraveSum(str(g, "id"), str(g, "owner"), str(g, "world"), (int) num(g, "x"), (int) num(g, "y"), (int) num(g, "z"), (int) num(g, "items")));
        return new Snapshot((long) num(o, "time"), tps.isEmpty() ? 20 : n(tps.get(0)), num(o, "mspt"), (long) num(o, "memUsed"), (long) num(o, "memMax"),
                num(o, "cpu"), num(o, "sysCpu"), (int) num(o, "cores"), (int) num(o, "entities"), (int) num(o, "chunks"), players, worlds, graves,
                bool(o, "gravesOn"), bool(o, "map", "filling"), (int) num(Kit.Json.get(o, "map"), "fillTotal"), (int) num(Kit.Json.get(o, "map"), "fillDone"));
    }

    static String str(Object o, String... k) { String s = Kit.Json.str(o, k); return s == null ? "" : s; }

    static double num(Object o, String... k) { return n(Kit.Json.get(o, k)); }

    static double n(Object v) { return v instanceof Number x ? x.doubleValue() : 0; }

    static boolean bool(Object o, String... k) { return Boolean.TRUE.equals(Kit.Json.get(o, k)); }

    // ------------------------------------------------------------------ files

    static Item item(Object o) {
        if (!(o instanceof Map<?, ?>)) return null;
        Map<String, Integer> ench = new LinkedHashMap<>();
        if (Kit.Json.get(o, "ench") instanceof Map<?, ?> m) for (var e : m.entrySet()) ench.put(String.valueOf(e.getKey()), (int) n(e.getValue()));
        List<Item> inside = null;
        if (Kit.Json.get(o, "inside") instanceof List<?> l) { inside = new ArrayList<>(); for (Object x : l) inside.add(item(x)); }
        String name = Kit.Json.str(o, "name");
        return new Item(str(o, "id"), (int) num(o, "n"), name, (int) num(o, "dmg"), (int) num(o, "max"), ench, inside);
    }

    static List<Item> items(Object list) {
        List<Item> out = new ArrayList<>();
        if (list instanceof List<?> l) for (Object x : l) out.add(item(x));
        return out;
    }

    static List<Death> deaths() {
        Path f = dir().resolve("deaths.csv");
        List<Death> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 1 && out.size() < 1000; i--) {
                List<String> c = csv(lines.get(i));
                if (c.size() < 7) continue;
                try {
                    out.add(new Death(Long.parseLong(c.get(0)), c.get(1), c.get(2), Integer.parseInt(c.get(3)), Integer.parseInt(c.get(4)),
                            Integer.parseInt(c.get(5)), c.get(6)));
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        return out;
    }

    static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { b.append('"'); i++; }
                else if (c == '"') q = false;
                else b.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(b.toString()); b.setLength(0); }
            else b.append(c);
        }
        out.add(b.toString());
        return out;
    }

    static int graveUnlockMinutes = 60;

    static List<Grave> graves() {
        List<Grave> out = new ArrayList<>();
        Path f = dir().resolve("graves.json");
        if (!Files.exists(f)) return out;
        try {
            Object o = Kit.Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            graveUnlockMinutes = (int) num(o, "unlockMinutes");
            for (Object g : Kit.Json.list(o, "graves"))
                out.add(new Grave(str(g, "id"), str(g, "owner"), str(g, "uuid"), str(g, "world"), (int) num(g, "x"), (int) num(g, "y"), (int) num(g, "z"),
                        (int) num(g, "xp"), (long) num(g, "made"), bool(g, "returnOnJoin"), items(Kit.Json.get(g, "items"))));
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    /** Item ids the plugin listed (items.txt), or empty. */
    static List<String> pluginItems() {
        try {
            Path f = dir().resolve("items.txt");
            if (!Files.exists(f)) return List.of();
            List<String> out = new ArrayList<>();
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l.trim());
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ inventories

    private static final Map<String, List<Consumer<Inventory>>> waiting = new ConcurrentHashMap<>();

    static String invFile(String player) { return player.replaceAll("[^A-Za-z0-9_.-]", "_").toLowerCase(Locale.ROOT) + ".json"; }

    /** Asks the plugin for a player's inventory; calls back (on any thread) with it, or null if they're offline or it took too long. */
    static void requestInventory(String player, Consumer<Inventory> done) {
        Server s = server;
        if (s == null || !s.isRunning() || !supported()) { done.accept(null); return; }
        String key = player.toLowerCase(Locale.ROOT);
        waiting.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(done);
        s.send("xw inv " + player);
        // in case the reply line is missed: give up after 6 seconds
        CompletableFuture.delayedExecutor(6, TimeUnit.SECONDS).execute(() -> finishInventory(player, false));
    }

    private static void finishInventory(String player, boolean ready) {
        List<Consumer<Inventory>> list = waiting.remove(player.toLowerCase(Locale.ROOT));
        if (list == null) return;
        Inventory inv = ready ? readInventory(player) : null;
        for (Consumer<Inventory> c : list) c.accept(inv);
    }

    static Inventory readInventory(String player) {
        Path f = dir().resolve("inv").resolve(invFile(player));
        try {
            Object o = Kit.Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            return new Inventory(str(o, "name"), (long) num(o, "time"), num(o, "health"), (int) num(o, "food"), (int) num(o, "level"),
                    items(Kit.Json.get(o, "inventory")), items(Kit.Json.get(o, "enderChest")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ log lines from the plugin

    static final Pattern INV = Pattern.compile("^\\[XrayWatch\\] INV (ready|offline) player=(\\S+)");
    static final Pattern MAP = Pattern.compile("^\\[XrayWatch\\] MAP fill (checking|queued|done) areas=(\\d+)");
    static final Pattern QUIET = Pattern.compile("^\\[XrayWatch\\] (TP|GRAVES) ");

    /** Handles the plugin's lines meant for the app. Returns true if the line should stay out of the activity feed. */
    static boolean line(String msg, Server s) {
        if (!msg.startsWith("[XrayWatch] ")) return false;
        Matcher m = INV.matcher(msg);
        if (m.find()) { finishInventory(m.group(2), m.group(1).equals("ready")); return true; }
        Matcher mp = MAP.matcher(msg);
        if (mp.find()) {
            if (mp.group(1).equals("queued") && !mp.group(2).equals("0"))
                s.event(Server.Kind.SYSTEM, "Drawing the map: " + mp.group(2) + " explored areas to add. This happens slowly in the background so the game doesn't lag.");
            else if (mp.group(1).equals("done"))
                s.event(Server.Kind.SYSTEM, mp.group(2).equals("0") ? "The map already shows everything explored around spawn." : "Finished drawing the map.");
            return true;
        }
        return QUIET.matcher(msg).find();
    }

    /** Sends a command quietly and puts a plain-words line in the activity feed instead. */
    static boolean run(String command, String said) {
        Server s = server;
        if (s == null || !s.isRunning()) return false;
        if (!s.send(command)) return false;
        if (said != null) s.event(Server.Kind.YOU, said);
        return true;
    }
}
