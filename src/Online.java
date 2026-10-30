// Online play through playit.gg: link an account once, then the app creates a Java and a
// Bedrock tunnel and runs the playit agent quietly in the background while the server is on.

import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

final class Online {

    enum State { UNSUPPORTED, NOT_LINKED, LINKING, WORKING, READY, MANUAL, ERROR }

    static final String API = System.getProperty("kit.playit", "https://api.playit.gg");
    static final String RELEASES = System.getProperty("kit.playitdl", "https://github.com/playit-cloud/playit-agent/releases");
    static final Path SECRET = Kit.ONLINE.resolve("playit.toml");
    static final Path AGENT_LOG = Kit.ONLINE.resolve("playit.log");

    private volatile State state;
    private volatile String message = "";
    private volatile String claimUrl;
    private volatile Process agent;
    private volatile boolean cancel;
    private volatile Thread worker;
    private volatile String bedrockProblem;

    final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Gets told about things worth showing in the activity feed. */
    volatile java.util.function.BiConsumer<Server.Kind, String> log = (k, s) -> { };
    /** True while the Minecraft server is running (the tunnel only stays up then). */
    volatile java.util.function.BooleanSupplier serverRunning = () -> false;

    Online() {
        if (agentAsset() == null) state = State.UNSUPPORTED;
        else if (!Files.exists(SECRET)) state = State.NOT_LINKED;
        else if (javaAddress() != null) state = State.READY;
        else state = State.WORKING; // linked, tunnels still to be made (finished on next connect)
    }

    State state() { return state; }
    String message() { return message; }
    String claimUrl() { return claimUrl; }
    String bedrockProblem() { return bedrockProblem; }
    boolean linked() { return Files.exists(SECRET); }
    boolean live() { Process a = agent; return a != null && a.isAlive() && state == State.READY; }
    boolean autoShare() { return Kit.getBool("online.auto", true); }
    void setAutoShare(boolean on) { Kit.set("online.auto", on); fire(); }

    static String javaAddress() { return Kit.get("online.java", null); }
    static String bedrockHost() { return Kit.get("online.bedrock.host", null); }
    static int bedrockPort() {
        try { return Integer.parseInt(Kit.get("online.bedrock.port", "0")); } catch (NumberFormatException e) { return 0; }
    }

    private void set(State s, String msg) {
        if (cancel && (s == State.WORKING || s == State.LINKING)) return;
        state = s;
        message = msg == null ? "" : msg;
        fire();
    }

    private void fire() { for (Runnable r : listeners) r.run(); }

    // ------------------------------------------------------------------ public actions

    /** First-time setup: link playit in the browser, then make the tunnels. */
    void setUp() {
        if (state == State.UNSUPPORTED || isWorking()) return;
        run(this::fullSetup);
    }

    private void fullSetup() throws Exception {
        set(State.WORKING, "Getting playit ready");
        ensureAgent();
        if (!Files.exists(SECRET)) claim();
        if (cancel) return;
        connect(true);
    }

    /** Called when the server starts: bring the tunnel up (and finish setup if needed). */
    void goLive() {
        if (!linked() || !autoShare() || state == State.UNSUPPORTED || isWorking()) return;
        run(() -> connect(false));
    }

    /** Re-checks the tunnels (used by the "check again" button). */
    void retry() {
        if (isWorking()) return;
        run(() -> {
            if (!Files.exists(SECRET)) fullSetup();
            else connect(true);
        });
    }

    void cancel() {
        cancel = true;
        Thread w = worker;
        if (w != null) w.interrupt();
        if (!linked()) set(State.NOT_LINKED, "");
        else set(javaAddress() != null ? State.READY : State.MANUAL, "");
        if (!serverRunning.getAsBoolean()) stopAgent();
    }

    void unlink() {
        cancel();
        stopAgent();
        try { Files.deleteIfExists(SECRET); } catch (IOException ignored) { }
        Kit.set("online.java", null);
        Kit.set("online.bedrock.host", null);
        Kit.set("online.bedrock.port", null);
        bedrockProblem = null;
        set(State.NOT_LINKED, "");
    }

    void stopAgent() {
        Process a = agent;
        agent = null;
        if (a != null && a.isAlive()) {
            a.destroy();
            try { if (!a.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) a.destroyForcibly(); }
            catch (InterruptedException ignored) { a.destroyForcibly(); }
        }
        fire();
    }

    boolean isWorking() {
        Thread w = worker;
        return w != null && w.isAlive();
    }

    private void run(ThrowingRunnable job) {
        cancel = false;
        Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (InterruptedException e) {
                // cancelled
            } catch (ManualSetup e) {
                set(State.MANUAL, e.getMessage());
            } catch (Exception e) {
                if (!cancel) {
                    set(State.ERROR, friendly(e));
                    log.accept(Server.Kind.WARN, "Online play: " + friendly(e));
                }
            } finally {
                worker = null;
                fire();
            }
        }, "online");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static final class ManualSetup extends Exception {
        ManualSetup(String m) { super(m); }
    }

    static final class ApiFail extends IOException {
        final String status, data;
        ApiFail(String status, String data, String raw) {
            super("playit replied: " + status + " " + data + (raw == null ? "" : " " + raw.substring(0, Math.min(raw.length(), 300))));
            this.status = status;
            this.data = data;
        }
        boolean is(String... values) {
            for (String v : values) if (data != null && data.contains(v)) return true;
            return false;
        }
    }

    private String friendly(Exception e) {
        if (e instanceof ApiFail f) {
            if (f.is("RequiresVerifiedAccount")) return "playit needs you to verify your email first. Check your inbox, then click Try again.";
            if (f.is("UserRejected")) return "The link was declined in the browser. Click Try again to start over.";
            if (f.is("CodeExpired", "CodeNotFound")) return "The setup link expired. Click Try again for a new one.";
            if (f.is("TooManyRequests")) return "playit is busy right now. Wait a minute and try again.";
            if ("error".equals(f.status) && f.is("auth", "Auth")) return "This PC's playit link stopped working. Click Unlink, then set it up again.";
        }
        return Kit.describe(e);
    }

    // ------------------------------------------------------------------ the playit agent program

    /** The playitd release file for this computer, or null if there isn't one (e.g. macOS). */
    static String agentAsset() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (Kit.WINDOWS) return arch.contains("86") && !arch.contains("64") ? "playitd-windows-x86.exe" : "playitd-windows-x86_64.exe";
        if (Kit.MAC) return null;
        return arm ? "playit-linux-aarch64" : "playit-linux-amd64";
    }

    static Path agentPath() { return Kit.ONLINE.resolve(Kit.WINDOWS ? "playitd.exe" : "playitd"); }

    private void ensureAgent() throws IOException, InterruptedException {
        Path exe = agentPath();
        if (Files.exists(exe) && Files.size(exe) > 1_000_000) return;
        Files.createDirectories(Kit.ONLINE);
        String asset = agentAsset();
        IOException last = null;
        for (String url : List.of(RELEASES + "/latest/download/" + asset,
                                  RELEASES + "/download/v1.2.0/" + asset,
                                  RELEASES + "/download/v1.1.0/" + asset)) {
            try {
                Path tmp = Kit.download(url, exe, "Downloading playit", (t, f) -> set(State.WORKING, t));
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
                exe.toFile().setExecutable(true);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("Couldn't download playit: " + Kit.describe(last));
    }

    private void startAgent() throws IOException {
        Process a = agent;
        if (a != null && a.isAlive()) return;
        ensureAgentSilently();
        String socket = Kit.WINDOWS ? "\\\\.\\pipe\\playit-minecraft-server-kit"
                : Paths.get(System.getProperty("java.io.tmpdir"), "playit-mckit-" + Math.abs(Kit.DIR.hashCode()) + ".sock").toString();
        List<String> cmd = List.of(agentPath().toString(),
                "--secret-path", SECRET.toString(),
                "--log-path", AGENT_LOG.toString(),
                "--socket-path", socket);
        Process p = new ProcessBuilder(cmd)
                .directory(Kit.ONLINE.toFile())
                .redirectErrorStream(true)
                .redirectOutput(Kit.ONLINE.resolve("playit-output.txt").toFile())
                .start();
        agent = p;
        p.onExit().thenRun(() -> {
            if (agent == p) {
                agent = null;
                if (serverRunning.getAsBoolean() && state == State.READY)
                    log.accept(Server.Kind.WARN, "playit stopped, so friends online can't join right now. Open Play online to reconnect.");
                fire();
            }
        });
        fire();
    }

    private void ensureAgentSilently() throws IOException {
        try { ensureAgent(); } catch (InterruptedException e) { throw new IOException("cancelled"); }
    }

    // ------------------------------------------------------------------ linking (claim)

    private void claim() throws Exception {
        String code = randomHex(8);
        String setupBody = "{\"code\":" + Kit.Json.q(code) + ",\"agent_type\":\"self-managed\",\"version\":"
                + Kit.Json.q("Minecraft Server app " + Kit.VERSION) + "}";
        for (int i = 0; ; i++) {
            try { api("/claim/setup", setupBody, null); break; }
            catch (ApiFail f) { throw f; }
            catch (IOException e) { if (i >= 4) throw e; Thread.sleep(2000); }
        }
        claimUrl = "https://playit.gg/claim/" + code;
        set(State.LINKING, "");
        Kit.browse(claimUrl);

        while (!cancel) {
            Object r;
            try {
                r = api("/claim/setup", setupBody, null);
            } catch (ApiFail f) {
                throw f;
            } catch (IOException e) {
                Thread.sleep(3000);
                continue;
            }
            String s = String.valueOf(r);
            if (s.contains("UserAccepted")) break;
            if (s.contains("UserRejected")) throw new ApiFail("fail", "UserRejected", null);
            Thread.sleep(2000);
        }
        if (cancel) return;

        set(State.WORKING, "Linked! Finishing setup");
        String secret = null;
        for (int i = 0; i < 15 && secret == null; i++) {
            try {
                secret = Kit.Json.str(api("/claim/exchange", "{\"code\":" + Kit.Json.q(code) + "}", null), "secret_key");
            } catch (ApiFail f) {
                if (!f.is("NotAccepted", "NotSetup")) throw f;
                Thread.sleep(2000);
            } catch (IOException e) {
                Thread.sleep(2000);
            }
        }
        if (secret == null || secret.isBlank()) throw new IOException("playit didn't send this PC its key. Click Try again.");
        Files.createDirectories(Kit.ONLINE);
        Files.writeString(SECRET, "secret_key = " + Kit.Json.q(secret.trim()) + System.lineSeparator());
        claimUrl = null;
        log.accept(Server.Kind.SYSTEM, "Linked this PC to playit.gg.");
    }

    static String readSecret() throws IOException {
        String t = Files.readString(SECRET);
        int a = t.indexOf('"'), b = t.lastIndexOf('"');
        return a >= 0 && b > a ? t.substring(a + 1, b).trim() : t.trim();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    // ------------------------------------------------------------------ tunnels

    /** Starts the agent and makes sure both tunnels exist, then records their addresses. */
    private void connect(boolean interactive) throws Exception {
        set(State.WORKING, "Connecting to playit");
        startAgent();
        String secret = readSecret();

        long deadline = System.currentTimeMillis() + 120_000;
        boolean triedJava = false, triedBedrock = false;
        bedrockProblem = null;
        String javaAddr = null, bedAddr = null;

        while (!cancel && System.currentTimeMillis() < deadline) {
            Object run;
            try {
                run = api("/v1/agents/rundata", "{}", secret);
            } catch (ApiFail f) {
                if ("error".equals(f.status)) throw f;
                Thread.sleep(3000);
                continue;
            } catch (IOException e) {
                Thread.sleep(3000); // network hiccup; keep trying until the deadline
                continue;
            }
            String agentId = Kit.Json.str(run, "agent_id");
            Map<?, ?> java = findTunnel(run, "minecraft-java", "java");
            Map<?, ?> bedrock = findTunnel(run, "minecraft-bedrock", "bedrock");

            if (java == null && !triedJava && agentId != null) {
                set(State.WORKING, "Creating your Java address");
                triedJava = createTunnel(secret, agentId, "minecraft-java", "Minecraft Java", "tcp", 25565);
            }
            if (bedrock == null && !triedBedrock && agentId != null && bedrockProblem == null) {
                set(State.WORKING, "Creating your Bedrock address");
                triedBedrock = createTunnel(secret, agentId, "minecraft-bedrock", "Minecraft Bedrock", "udp", 19132);
            }

            if (java != null) javaAddr = Kit.Json.str(java, "display_address");
            if (bedrock != null) bedAddr = Kit.Json.str(bedrock, "display_address");
            boolean bedrockDone = bedAddr != null || bedrockProblem != null;
            if (javaAddr != null && bedrockDone) break;
            set(State.WORKING, "Waiting for playit to give you an address");
            Thread.sleep(3000);
        }
        if (cancel) return;

        if (javaAddr == null) {
            throw new ManualSetup("playit linked fine, but didn't hand out an address automatically.");
        }
        Kit.set("online.java", javaAddr);
        if (bedAddr != null) {
            int colon = bedAddr.lastIndexOf(':');
            String host = colon > 0 ? bedAddr.substring(0, colon) : bedAddr;
            String port = colon > 0 ? bedAddr.substring(colon + 1) : "19132";
            Kit.set("online.bedrock.host", host);
            Kit.set("online.bedrock.port", port);
            if (!serverRunning.getAsBoolean()) applyGeyserPort();
        } else if (bedrockProblem == null) {
            bedrockProblem = "playit hasn't finished the Bedrock address yet. It should appear soon; click Check again.";
        }
        set(State.READY, "");
        if (bedAddr != null && serverRunning.getAsBoolean() && Setup.geyserBroadcastPort() != bedrockPort())
            log.accept(Server.Kind.SYSTEM, "Restart the server once so Bedrock players can join from the internet.");
        if (interactive) log.accept(Server.Kind.SYSTEM, "Online play is ready. Friends can join at " + javaAddr);
        if (!serverRunning.getAsBoolean()) stopAgent();
    }

    /** Points Geyser at the public Bedrock port. Only call while the server is stopped. */
    static void applyGeyserPort() {
        int port = bedrockPort();
        if (port > 0 && Files.exists(SECRET) && Kit.getBool("online.auto", true)) Setup.setGeyserBroadcastPort(port);
        else Setup.setGeyserBroadcastPort(19132);
    }

    private static Map<?, ?> findTunnel(Object run, String type, String word) {
        for (String section : List.of("tunnels", "pending")) {
            for (Object o : Kit.Json.list(run, section)) {
                if (!(o instanceof Map<?, ?> t)) continue;
                String tt = String.valueOf(t.get("tunnel_type"));
                String disp = String.valueOf(t.get("tunnel_type_display")).toLowerCase(Locale.ROOT);
                if (type.equals(tt) || (disp.contains("minecraft") && disp.contains(word))) {
                    if (section.equals("tunnels")) return t;
                    return Map.of("pending", true); // exists, no address yet
                }
            }
        }
        return null;
    }

    /** Creates a tunnel. Returns false if playit isn't ready for it yet (try again shortly). */
    private boolean createTunnel(String secret, String agentId, String type, String name, String proto, int localPort)
            throws Exception {
        String fields = "{\"fields\":[{\"name\":\"local_ip\",\"value\":\"127.0.0.1\"},{\"name\":\"local_port\",\"value\":\""
                + localPort + "\"}]}";
        String origin = "{\"type\":\"agent\",\"data\":{\"agent_id\":" + Kit.Json.q(agentId) + ",\"config\":" + fields + "}}";
        String region = "{\"type\":\"region\",\"details\":{\"region\":\"global\",\"port\":null}}";

        // playit has used a few request formats over time; try the current one first.
        List<String[]> attempts = List.of(
                new String[]{"/v1/tunnels/create", "{\"name\":" + Kit.Json.q(name)
                        + ",\"protocol\":{\"type\":\"tunnel-type\",\"details\":" + Kit.Json.q(type) + "}"
                        + ",\"origin\":" + origin + ",\"endpoint\":" + region + ",\"enabled\":true}"},
                new String[]{"/v1/tunnels/create", "{\"ports\":{\"type\":\"tunnel-type\",\"details\":" + Kit.Json.q(type) + "}"
                        + ",\"origin\":" + origin + ",\"enabled\":true,\"alloc\":" + region
                        + ",\"name\":" + Kit.Json.q(name) + ",\"firewall_id\":null}"},
                new String[]{"/tunnels/create", "{\"name\":" + Kit.Json.q(name) + ",\"tunnel_type\":" + Kit.Json.q(type)
                        + ",\"port_type\":" + Kit.Json.q(proto) + ",\"port_count\":1"
                        + ",\"origin\":{\"type\":\"agent\",\"data\":{\"agent_id\":" + Kit.Json.q(agentId)
                        + ",\"local_ip\":\"127.0.0.1\",\"local_port\":" + localPort + "}}"
                        + ",\"enabled\":true,\"alloc\":{\"type\":\"region\",\"details\":{\"region\":\"global\"}}"
                        + ",\"firewall_id\":null,\"proxy_protocol\":null}"});

        for (String[] a : attempts) {
            try {
                api(a[0], a[1], secret);
                return true;
            } catch (ApiFail f) {
                if (f.status.startsWith("http") && !f.data.startsWith("HTTP 4")) return false; // server trouble; retry later
                if (f.is("AgentNotFound", "AgentVersionTooOld", "InvalidAgentId")) return false; // agent still registering
                if (f.is("RequiresVerifiedAccount")) throw f;
                if (f.is("Premium", "OverPortLimit", "PortAlloc")) {
                    if (type.equals("minecraft-bedrock")) {
                        bedrockProblem = "playit wouldn't make a free Bedrock address on your account, so Bedrock friends "
                                + "can only join on your Wi-Fi. Java friends can still join from anywhere.";
                        return true;
                    }
                    throw new ManualSetup("playit wouldn't make the tunnel automatically on your account.");
                }
                // any other refusal: try the next request format
            } catch (IOException e) {
                return false; // network hiccup; the caller tries again shortly
            }
        }
        throw new ManualSetup("playit linked fine, but didn't accept the request to make a tunnel automatically.");
    }

    /** POSTs to the playit API and returns the "data" of a success reply. */
    private static Object api(String path, String body, String secret) throws IOException, InterruptedException {
        HttpRequest.Builder b = Kit.request(API + path)
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (secret != null) b.header("Authorization", "Agent-Key " + secret);
        HttpResponse<String> r = Kit.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 429) throw new ApiFail("fail", "TooManyRequests", null);
        Object json;
        try {
            json = Kit.Json.parse(r.body());
        } catch (IOException e) {
            throw new ApiFail("http", "HTTP " + r.statusCode(), r.body());
        }
        String status = Kit.Json.str(json, "status");
        Object data = Kit.Json.get(json, "data");
        if ("success".equals(status)) return data;
        throw new ApiFail(status == null ? "http " + r.statusCode() : status, String.valueOf(data), r.body());
    }
}
