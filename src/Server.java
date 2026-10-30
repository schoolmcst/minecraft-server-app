// Runs the Paper server process and turns its log into friendly events.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;

final class Server {

    enum State { STOPPED, PREPARING, STARTING, RUNNING, STOPPING, CRASHED }

    enum Kind { INFO, SYSTEM, JOIN, LEAVE, CHAT, WARN, ERROR, YOU }

    record Event(LocalTime time, Kind kind, String text) { }

    // ------------------------------------------------------------------ state

    private volatile State state = State.STOPPED;
    private volatile String detail = "";
    private volatile double progress = -1;
    private volatile long startedAt;
    private volatile long readyAt;
    private volatile Process proc;
    private volatile Writer stdin;
    private volatile boolean stopRequested, restartAfter;
    private long lastLagWarning;

    final Set<String> online = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Deque<String> recentRaw = new ArrayDeque<>();
    private final List<Event> events = new ArrayList<>();

    /** Called (on the UI thread, via the app) whenever something changes. */
    final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    final List<Consumer<Event>> eventListeners = new CopyOnWriteArrayList<>();
    final List<Consumer<String>> rawListeners = new CopyOnWriteArrayList<>();

    /** Asks the person to accept the EULA; must block until they answer. */
    volatile BooleanSupplier eulaPrompt = () -> false;
    /** Hooks for online play. */
    volatile Runnable beforeStart = () -> { };
    volatile Runnable onStarted = () -> { };
    volatile Runnable onStopped = () -> { };

    State state() { return state; }
    String detail() { return detail; }
    double progress() { return progress; }
    long startedAt() { return startedAt; }
    long readyAt() { return readyAt; }
    boolean isRunning() { return state == State.RUNNING; }
    boolean isBusy() { return state == State.PREPARING || state == State.STARTING || state == State.STOPPING; }
    synchronized List<Event> events() { return new ArrayList<>(events); }

    private void setState(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        if (s != State.PREPARING) progress = -1;
        fireChange();
    }

    private void fireChange() { for (Runnable r : changeListeners) r.run(); }

    void event(Kind k, String text) {
        Event e = new Event(LocalTime.now(), k, text);
        synchronized (this) {
            events.add(e);
            if (events.size() > 600) events.subList(0, 100).clear();
        }
        for (Consumer<Event> l : eventListeners) l.accept(e);
    }

    private void raw(String line) {
        synchronized (recentRaw) {
            recentRaw.addLast(line);
            if (recentRaw.size() > 30) recentRaw.removeFirst();
        }
        for (Consumer<String> l : rawListeners) l.accept(line);
    }

    // ------------------------------------------------------------------ start / stop

    void start() {
        if (state != State.STOPPED && state != State.CRASHED) return;
        stopRequested = false;
        setState(State.PREPARING, "Getting ready");
        Thread t = new Thread(this::doStart, "server-start");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        if (state != State.RUNNING && state != State.STARTING) return;
        stopRequested = true;
        setState(State.STOPPING, "Saving the world");
        event(Kind.SYSTEM, "Saving the world and shutting down...");
        if (!send("stop")) { Process p = proc; if (p != null) p.destroy(); }
        Thread watchdog = new Thread(() -> {
            Process p = proc;
            try {
                if (p != null && !p.waitFor(120, TimeUnit.SECONDS)) {
                    event(Kind.WARN, "The server didn't stop in 2 minutes, so it was closed.");
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) { }
        }, "stop-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    void restart() {
        if (state != State.RUNNING) return;
        restartAfter = true;
        stop();
    }

    /** For app shutdown: stop and wait. */
    void stopAndWait(long timeoutMs) {
        stop();
        Process p = proc;
        if (p == null) return;
        try { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        if (p.isAlive()) p.destroyForcibly();
    }

    void killNow() {
        Process p = proc;
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    boolean send(String command) {
        Writer w = stdin;
        if (w == null) return false;
        try {
            synchronized (this) {
                w.write(command + "\n");
                w.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs a command the person typed and shows it in the activity feed. */
    boolean command(String command) {
        String c = command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        if (c.isEmpty()) return false;
        if (state != State.RUNNING) return false;
        event(Kind.YOU, "> " + c);
        if (c.equalsIgnoreCase("stop")) { stop(); return true; }
        return send(c);
    }

    /** Downloads the newest server software while the server is off. */
    /** Runs the automatic update check now (server off). */
    void checkUpdatesNow(Runnable done) {
        if (state != State.STOPPED && state != State.CRASHED) return;
        setState(State.PREPARING, "Checking for updates");
        Thread t = new Thread(() -> {
            List<String> updated = Setup.autoUpdate(this::progress);
            event(Kind.SYSTEM, updated.isEmpty() ? "Everything is up to date." : "Updated: " + String.join(", ", updated) + ".");
            setState(State.STOPPED, "");
            if (done != null) done.run();
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    void updateSoftware(Runnable done) {
        if (state != State.STOPPED && state != State.CRASHED) return;
        setState(State.PREPARING, "Checking for updates");
        Thread t = new Thread(() -> {
            try {
                Setup.ensureSoftware(true, this::progress);
                event(Kind.SYSTEM, "Updated to the latest server for Minecraft " + Kit.mcVersion() + ".");
            } catch (Exception e) {
                event(Kind.ERROR, "Couldn't update: " + Kit.describe(e));
            }
            setState(State.STOPPED, "");
            if (done != null) done.run();
        }, "update");
        t.setDaemon(true);
        t.start();
    }

    private void progress(String text, double fraction) {
        detail = text;
        progress = fraction;
        fireChange();
    }

    private void doStart() {
        try {
            // 1. software
            if (Setup.softwareMissing()) {
                event(Kind.SYSTEM, "First start: downloading the server software (about 100 MB)...");
                try {
                    Setup.ensureSoftware(false, this::progress);
                } catch (IOException | InterruptedException e) {
                    fail("Couldn't download the server: " + Kit.describe(e));
                    return;
                }
                event(Kind.SYSTEM, "Downloaded the server for Minecraft " + Kit.mcVersion() + ".");
            }

            // 1b. keep it current (quick; skipped when offline)
            else if (Kit.getBool("server.autoupdate", true)) {
                progress("Checking for updates", -1);
                List<String> updated = Setup.autoUpdate(this::progress);
                if (!updated.isEmpty()) event(Kind.SYSTEM, "Updated: " + String.join(", ", updated) + ".");
                String newer = Setup.newerMinecraft();
                if (newer != null && !newer.equals(Kit.get("paper.newer.told", "")))  {
                    Kit.set("paper.newer.told", newer);
                    event(Kind.INFO, "Minecraft " + newer + " is out. When your Java friends have updated their game, "
                            + "switch in Settings > Server software.");
                }
            }

            // 2. Java version
            int need = Setup.requiredJava(Kit.mcVersion());
            int have = Runtime.version().feature();
            if (have < need) {
                fail("This Minecraft version needs Java " + need + ", but the app is running on Java " + have
                        + ". Delete the 'runtime' folder and open the app again to get the right Java.");
                return;
            }

            // 3. EULA
            if (!Setup.eulaAccepted()) {
                progress("Waiting for you to accept the EULA", -1);
                if (!eulaPrompt.getAsBoolean()) {
                    setState(State.STOPPED, "");
                    event(Kind.SYSTEM, "The server needs the EULA accepted before it can start.");
                    return;
                }
                Setup.acceptEula();
            }

            // 4. backup
            if (Setup.hasWorld()) {
                progress("Backing up your world", -1);
                try {
                    Path zip = Setup.backupWorlds("worlds");
                    if (zip != null) event(Kind.SYSTEM, "World backed up.");
                } catch (IOException e) {
                    event(Kind.WARN, "Backup skipped: " + Kit.describe(e));
                }
            }

            beforeStart.run();

            // 5. launch
            launch();
        } catch (Exception e) {
            fail("Couldn't start the server: " + Kit.describe(e));
        }
    }

    private void fail(String message) {
        event(Kind.ERROR, message);
        setState(State.CRASHED, message);
    }

    private void launch() throws IOException {
        String ram = Kit.ram();
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>(List.of(java,
                "-Xms" + ram, "-Xmx" + ram,
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
                "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC",
                "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
                "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
                "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dterminal.jline=false", "-Dterminal.ansi=false",
                "-jar", Kit.PAPER_JAR.toString(), "--nogui"));

        online.clear();
        synchronized (recentRaw) { recentRaw.clear(); }
        startedAt = System.currentTimeMillis();
        readyAt = 0;
        lastLagWarning = 0;
        setState(State.STARTING, "Starting");
        event(Kind.SYSTEM, "Starting the server with " + ram.replace("G", " GB") + " of memory...");

        Process p = new ProcessBuilder(cmd)
                .directory(Kit.DIR.toFile())
                .redirectErrorStream(true)
                .start();
        proc = p;
        stdin = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);

        Thread reader = new Thread(() -> readOutput(p), "server-output");
        reader.setDaemon(true);
        reader.start();
    }

    private void readOutput(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String clean = ANSI.matcher(line).replaceAll("");
                raw(clean);
                try { handleLine(clean); } catch (RuntimeException ignored) { }
            }
        } catch (IOException ignored) { }

        int code;
        try { code = p.waitFor(); } catch (InterruptedException e) { code = -1; }
        stdin = null;
        proc = null;
        online.clear();
        onStopped.run();

        if (stopRequested || (code == 0 && readyAt > 0)) {
            event(Kind.SYSTEM, "Server stopped. Your world is saved.");
            setState(State.STOPPED, "");
        } else {
            event(Kind.ERROR, "The server stopped unexpectedly (code " + code + "). Last messages:");
            List<String> last;
            synchronized (recentRaw) { last = new ArrayList<>(recentRaw); }
            for (String l : last.subList(Math.max(0, last.size() - 12), last.size()))
                if (!l.isBlank()) event(Kind.ERROR, "    " + l);
            event(Kind.ERROR, "Full details: logs/latest.log in the server folder.");
            setState(State.CRASHED, "Stopped unexpectedly");
        }
        if (restartAfter) {
            restartAfter = false;
            start();
        }
    }

    // ------------------------------------------------------------------ log parsing

    static final Pattern LOG = Pattern.compile("^\\[(\\d{1,2}:\\d{2}:\\d{2})(?: [\\w-]+)? ?(\\w+)\\]: (.*)$");
    static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    static final Pattern DONE = Pattern.compile("^Done \\(([\\d.,]+)s\\)!");
    static final Pattern JOIN = Pattern.compile("^(\\S+) joined the game");
    static final Pattern LEAVE = Pattern.compile("^(\\S+) left the game");
    static final Pattern LIST = Pattern.compile("^There are (\\d+) of a max of (\\d+) players online:?\\s*(.*)$");
    static final Pattern NOT_WHITELISTED = Pattern.compile(
            "^(?:Disconnecting )?(\\S+?)(?: \\(/[^)]*\\))?(?: \\[[^\\]]*\\])? lost connection: .*(?:not white-?listed|whitelist)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern NOISE = Pattern.compile(
            "UUID of player|logged in with entity id|lost connection|^Disconnecting|issued server command"
            + "|moved too quickly|moved wrongly"
            + "|^\\[(?:Geyser-Spigot|floodgate|Geyser)\\] .*(?:connect|logged in|joined|ping|Started Geyser)"
            + "|ThreadedAnvilChunkStorage|^Saving chunks|^Saving players|^Saving worlds|^Flushing|^Closing"
            + "|^Stopping the server|^ThreadedChunkStorage|^Waiting for|^Shutting down|^\\[ChunkHolderManager\\]"
            + "|^\\[ChunkTaskScheduler\\]|^\\[MoonriseCommon\\]|^Flushed|^Saved (?:chunk|entity|POI)|^All dimensions are saved"
            + "|^Stopping server|^Disabling|^Unloading|^\\[spark\\]|^Automatic saving is now|^Saving the game"
            + "|StunObserver|STUN discovery|build(?:s)? behind|download the latest build|^\\*+$|Downloading Minecraft JAR"
            + "|Minecraft JAR has been|^Running delayed init|first time you're starting|Getting Started|next-steps"
            + "|from an external source|^\\[Geyser-Spigot\\] \\[mcxboxbroadcast\\]|^Halted|^Waiting \\d+s|RegionFile I/O");

    private boolean bindError;

    static final Pattern XBOX_CODE = Pattern.compile("microsoft\\.com/link and enter the code (\\S+)");
    static final Pattern XBOX_AUTHED = Pattern.compile("\\[mcxboxbroadcast\\].*Successfully authenticated as (.+?) \\(\\d+\\)");
    static final Pattern XBOX_RELAY = Pattern.compile("\\[mcxboxbroadcast\\].*Relayed bedrock client (\\S+) \\((\\d+)\\)");
    static final Pattern GEYSER_REJECTED = Pattern.compile(
            "^\\[Geyser-Spigot\\] (\\S+) has disconnected from the Java server because of .*(?:not white-?listed|whitelist)",
            Pattern.CASE_INSENSITIVE);

    /** Floodgate's player ID for a Bedrock (Xbox) account number. */
    static String floodgateUuid(String xuid) {
        String h = String.format("%032x", new java.math.BigInteger(xuid));
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20);
    }

    /** Remembers which Bedrock gamertag belongs to which Floodgate ID (the server can't always look it up). */
    static void rememberBedrock(String name, String uuid) {
        Kit.set("bedrock.uuid." + name.toLowerCase(Locale.ROOT), uuid);
        Kit.set("bedrock.name." + uuid, name);
    }

    static String bedrockUuidFor(String name) {
        return Kit.get("bedrock.uuid." + name.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT), null);
    }

    static String bedrockNameFor(String uuid) { return Kit.get("bedrock.name." + uuid, null); }

    static final Pattern XBOX_FRIEND = Pattern.compile("\\[mcxboxbroadcast\\].*Added (.+?) \\(\\d+\\) as a friend");

    /** Xbox sign-in code waiting to be entered at microsoft.com/link (null when none). */
    volatile String xboxCode;
    /** Called when a new Xbox sign-in code appears. */
    volatile java.util.function.Consumer<String> onXboxCode = c -> { };

    /** Xbox add-on lines matter at any time, even during startup. Returns true if handled. */
    private boolean handleXbox(String msg) {
        Matcher c = XBOX_CODE.matcher(msg);
        if (c.find()) {
            xboxCode = c.group(1);
            event(Kind.WARN, "Xbox sign-in needed: go to microsoft.com/link and enter the code " + xboxCode);
            onXboxCode.accept(xboxCode);
            fireChange();
            return true;
        }
        Matcher a = XBOX_AUTHED.matcher(msg);
        if (a.find()) {
            xboxCode = null;
            Kit.set("xbox.gamertag", a.group(1).trim());
            event(Kind.SYSTEM, "Xbox sign-in done. Console friends: add " + a.group(1).trim() + " as a friend on Xbox.");
            fireChange();
            return true;
        }
        if (msg.contains("[mcxboxbroadcast]") && msg.contains("Creation of Xbox LIVE session was successful")) {
            event(Kind.SYSTEM, "Your server now shows in the Friends tab for Xbox friends of " + Kit.get("xbox.gamertag", "the server account") + ".");
            return true;
        }
        Matcher r = XBOX_RELAY.matcher(msg);
        if (r.find()) {
            rememberBedrock(r.group(1), floodgateUuid(r.group(2)));
            return true;
        }
        Matcher f = XBOX_FRIEND.matcher(msg);
        if (f.find()) {
            event(Kind.INFO, f.group(1) + " is now friends with the server's Xbox account.");
            return true;
        }
        if (msg.contains("[mcxboxbroadcast]") && msg.contains("age verification")) {
            event(Kind.ERROR, "Xbox sign-in failed: that Microsoft account needs age verification at xbox.com first.");
            return true;
        }
        return false;
    }

    void handleLine(String line) {
        if (line.isBlank()) return;
        Matcher m = LOG.matcher(line);
        boolean structured = m.matches();
        String level = structured ? m.group(2).toUpperCase(Locale.ROOT) : "";
        String msg = structured ? m.group(3).trim() : line.trim();
        if (msg.startsWith("System chat: ")) msg = msg.substring("System chat: ".length()); // newer Paper versions
        if (handleXbox(msg)) return;

        if (msg.contains("FAILED TO BIND TO PORT") || msg.contains("Address already in use")) {
            if (!bindError) {
                bindError = true;
                event(Kind.ERROR, "The server's port is already in use. Is another Minecraft server already running on this PC?");
            }
            return;
        }
        if (msg.contains("Can't keep up!")) {
            long now = System.currentTimeMillis();
            if (now - lastLagWarning > 5 * 60_000) {
                lastLagWarning = now;
                event(Kind.WARN, "The server is lagging. This PC is struggling to keep up.");
            }
            return;
        }

        if (state == State.STARTING) {
            if (DONE.matcher(msg).find()) {
                readyAt = System.currentTimeMillis();
                bindError = false;
                setState(State.RUNNING, "");
                long secs = (readyAt - startedAt) / 1000;
                event(Kind.SYSTEM, "Server is online (started in " + secs + "s). Waiting for players...");
                onStarted.run();
                return;
            }
            if (level.equals("ERROR") && !msg.startsWith("[")) event(Kind.ERROR, msg);
            return; // hide startup chatter
        }

        if (!structured) return; // stack traces etc. stay in the full log

        Matcher j = JOIN.matcher(msg);
        if (j.find()) {
            online.add(j.group(1));
            event(Kind.JOIN, nice(j.group(1)) + " joined");
            fireChange();
            return;
        }
        Matcher l = LEAVE.matcher(msg);
        if (l.find()) {
            online.remove(l.group(1));
            event(Kind.LEAVE, nice(l.group(1)) + " left");
            fireChange();
            return;
        }
        Matcher li = LIST.matcher(msg);
        if (li.find()) {
            online.clear();
            for (String n : li.group(3).split(",\\s*")) if (!n.isBlank()) online.add(n.trim());
            fireChange();
            List<String> names = new ArrayList<>();
            for (String n : online) names.add(nice(n));
            event(Kind.INFO, online.isEmpty() ? "Nobody is online right now." : "Online now: " + String.join(", ", names));
            return;
        }
        Matcher gr = GEYSER_REJECTED.matcher(msg);
        if (gr.find()) {
            String name = gr.group(1);
            event(Kind.WARN, name + " (Bedrock) tried to join but isn't on the whitelist. Add them on the Players page.");
            lastRejected = name;
            lastRejectedBedrock = true;
            fireChange();
            return;
        }
        if (msg.contains("unable to find any existing account with the given username")) {
            event(Kind.WARN, "The server couldn't look up that Bedrock player yet. Ask them to try joining once; "
                    + "then an Add button for them appears on the Players page.");
            return;
        }
        Matcher w = NOT_WHITELISTED.matcher(msg);
        if (w.find()) {
            String name = w.group(1);
            if (name.startsWith("/")) return; // console players show up as an address here; the Geyser line names them
            boolean bedrock = name.startsWith(".");
            event(Kind.WARN, (bedrock ? name.substring(1) + " (Bedrock)" : name)
                    + " tried to join but isn't on the whitelist. Add them on the Players page.");
            lastRejected = bedrock ? name.substring(1) : name;
            lastRejectedBedrock = bedrock;
            fireChange();
            return;
        }
        if (NOISE.matcher(msg).find()) return;
        if (msg.startsWith("[Not Secure] ")) msg = msg.substring("[Not Secure] ".length());
        msg = msg.replaceAll("^<\\.([^>]+)>", "<$1>"); // Bedrock names carry a "." prefix

        Kind k;
        if (level.equals("ERROR") || level.equals("FATAL")) k = Kind.ERROR;
        else if (level.equals("WARN")) { if (msg.startsWith("[")) return; k = Kind.WARN; }
        else if (msg.startsWith("<") || msg.startsWith("[Server]") || msg.startsWith("* ")) k = Kind.CHAT;
        else k = Kind.INFO;
        event(k, msg);
    }

    /** Bedrock players get a "." in front of their name; show them nicely. */
    static String nice(String name) {
        if (name.startsWith(".")) return name.substring(1) + " (Bedrock)";
        return bedrockUuidFor(name) != null ? name + " (Bedrock)" : name;
    }

    /** The last player who was turned away by the whitelist (offered as a one-click add). */
    volatile String lastRejected;
    volatile boolean lastRejectedBedrock;
}
