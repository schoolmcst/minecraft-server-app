// The list of servers the app knows about (HOME/servers.json). Servers are just folders; the app runs one at a time,
// and they all share the same ports, online-play addresses and Xbox setup.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

final class Servers {
    private Servers() { }

    record Entry(Path path, String name) {
        boolean exists() { return Files.isDirectory(path); }
    }

    static Path file() { return Kit.HOME.resolve("servers.json"); }

    static synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            if (Files.exists(file())) {
                for (Object o : Kit.Json.list(Kit.Json.parse(Files.readString(file(), StandardCharsets.UTF_8)), "servers")) {
                    String p = Kit.Json.str(o, "path"), n = Kit.Json.str(o, "name");
                    if (p != null) out.add(new Entry(Paths.get(p), n == null ? Paths.get(p).getFileName().toString() : n));
                }
            }
        } catch (IOException ignored) { }
        if (out.isEmpty() && Kit.homeIsServer()) out.add(new Entry(Kit.HOME, defaultName(Kit.HOME)));
        return out;
    }

    private static void save(List<Entry> entries) {
        StringBuilder b = new StringBuilder("{\n  \"servers\": [");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"name\": ").append(Kit.Json.q(e.name()))
                    .append(", \"path\": ").append(Kit.Json.q(e.path().toString())).append("}");
        }
        b.append("\n  ]\n}\n");
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), b.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    static String defaultName(Path p) {
        String n = p.getFileName() == null ? "My server" : p.getFileName().toString();
        return n.equalsIgnoreCase("MinecraftServer") ? "My server" : n;
    }

    /** The server to open on start: the last one used, if it's still there. */
    static Path initial() {
        String last = Kit.get("app.current", null);
        List<Entry> l = list();
        if (last != null) for (Entry e : l) if (e.path().toString().equals(last) && e.exists()) return e.path();
        for (Entry e : l) if (e.exists()) return e.path();
        return null;
    }

    static synchronized void add(Path p, String name) {
        List<Entry> l = new ArrayList<>(list());
        Path abs = p.toAbsolutePath().normalize();
        l.removeIf(e -> e.path().toAbsolutePath().normalize().equals(abs));
        l.add(new Entry(abs, name));
        save(l);
        adoptOnlineSetup(abs);
    }

    static synchronized void remove(Path p) {
        List<Entry> l = new ArrayList<>(list());
        l.removeIf(e -> e.path().equals(p));
        save(l);
    }

    static synchronized void rename(Path p, String name) {
        List<Entry> l = new ArrayList<>();
        for (Entry e : list()) l.add(e.path().equals(p) ? new Entry(e.path(), name) : e);
        save(l);
    }

    static String nameOf(Path p) {
        for (Entry e : list()) if (e.path().equals(p)) return e.name();
        return p == null ? "" : defaultName(p);
    }

    /** A folder name that isn't taken yet, inside the default servers folder. */
    static Path suggestFolder(String name) {
        Path base = defaultParent();
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (clean.isEmpty()) clean = "My server";
        Path p = base.resolve(clean);
        for (int i = 2; Files.exists(p); i++) p = base.resolve(clean + " " + i);
        return p;
    }

    static Path defaultParent() {
        return Paths.get(System.getProperty("user.home"), "Documents", "Minecraft Servers");
    }

    /** Servers made before the Servers page kept the playit link in their own folder; share it with the whole app. */
    static void adoptOnlineSetup(Path server) {
        try {
            Path old = server.resolve("online");
            if (Kit.ONLINE.equals(old) || !Files.exists(old.resolve("playit.toml")) || Files.exists(Kit.ONLINE.resolve("playit.toml"))) return;
            Files.createDirectories(Kit.ONLINE);
            try (Stream<Path> s = Files.list(old)) {
                for (Path f : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    String n = f.getFileName().toString();
                    if (n.endsWith(".log") || n.endsWith(".txt")) continue;
                    Files.copy(f, Kit.ONLINE.resolve(n), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ignored) { }
    }
}
