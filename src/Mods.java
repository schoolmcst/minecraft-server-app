// Mods for Fabric servers, from Modrinth (api.modrinth.com). Installing a mod also installs the mods it requires.

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.ZipFile;

final class Mods {
    private Mods() { }

    static final String API = System.getProperty("kit.modrinth", "https://api.modrinth.com/v2");

    record Hit(String slug, String title, String description, long downloads, String clientSide, String serverSide) {
        /** Players need the mod too when it changes things on their screen. */
        boolean playersNeedIt() { return "required".equals(clientSide); }
    }

    record Installed(Path file, String name, String version, String id) { }

    // ------------------------------------------------------------------ what's installed

    static List<Installed> installed() {
        if (!Files.isDirectory(Kit.mods())) return List.of();
        try (Stream<Path> s = Files.list(Kit.mods())) {
            List<Installed> out = new ArrayList<>();
            for (Path f : s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().collect(Collectors.toList()))
                out.add(describe(f));
            out.sort(Comparator.comparing(i -> i.name().toLowerCase(Locale.ROOT)));
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Reads the mod's own name from its fabric.mod.json, falling back to the file name. */
    static Installed describe(Path jar) {
        String name = jar.getFileName().toString().replaceFirst("\\.jar$", ""), version = "", id = "";
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var e = z.getEntry("fabric.mod.json");
            if (e != null) {
                String json = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                Object o = Kit.Json.parse(json.trim());
                if (Kit.Json.str(o, "name") != null) name = Kit.Json.str(o, "name");
                if (Kit.Json.str(o, "version") != null) version = Kit.Json.str(o, "version");
                if (Kit.Json.str(o, "id") != null) id = Kit.Json.str(o, "id");
            }
        } catch (Exception ignored) { }
        return new Installed(jar, name, version, id);
    }

    static boolean hasFabricApi() {
        for (Installed i : installed()) if (i.id().equals("fabric-api") || i.file().getFileName().toString().startsWith("fabric-api")) return true;
        return false;
    }

    // ------------------------------------------------------------------ Modrinth

    static List<Hit> search(String query, String mc) throws IOException, InterruptedException {
        String facets = "[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:" + mc + "\"],"
                + "[\"server_side:required\",\"server_side:optional\"]]";
        String url = API + "/search?limit=20&index=relevance&query=" + enc(query) + "&facets=" + enc(facets);
        List<Hit> out = new ArrayList<>();
        for (Object h : Kit.Json.list(Kit.Json.parse(Kit.getString(url)), "hits")) {
            Object d = Kit.Json.get(h, "downloads");
            out.add(new Hit(Kit.Json.str(h, "slug"), Kit.Json.str(h, "title"), Kit.Json.str(h, "description"),
                    d instanceof Number n ? n.longValue() : 0, Kit.Json.str(h, "client_side"), Kit.Json.str(h, "server_side")));
        }
        return out;
    }

    /** Installs the newest version of a mod for this Minecraft version, plus its required mods. Returns what it added. */
    static List<String> install(String slugOrId, String mc, Kit.Progress p) throws IOException, InterruptedException {
        List<String> added = new ArrayList<>();
        install(slugOrId, mc, p, new HashSet<>(), added);
        return added;
    }

    private static void install(String slugOrId, String mc, Kit.Progress p, Set<String> seen, List<String> added)
            throws IOException, InterruptedException {
        if (!seen.add(slugOrId)) return;
        String url = API + "/project/" + enc(slugOrId) + "/version?loaders=" + enc("[\"fabric\"]")
                + "&game_versions=" + enc("[\"" + mc + "\"]");
        List<?> versions = (List<?>) Kit.Json.parse(Kit.getString(url));
        if (versions.isEmpty()) throw new IOException("That mod has no version for Minecraft " + mc + " yet");
        Object v = versions.get(0);
        for (Object o : versions) if ("release".equals(Kit.Json.str(o, "version_type"))) { v = o; break; }

        Object file = null;
        for (Object f : Kit.Json.list(v, "files")) if (Boolean.TRUE.equals(Kit.Json.get(f, "primary"))) file = f;
        if (file == null && !Kit.Json.list(v, "files").isEmpty()) file = Kit.Json.list(v, "files").get(0);
        if (file == null) throw new IOException("That mod has no file to download");
        String name = Kit.Json.str(file, "filename");
        Path target = Kit.mods().resolve(name);
        Files.createDirectories(Kit.mods());

        String projectId = Kit.Json.str(v, "project_id");
        removeOtherVersions(projectId, target);
        if (!Files.exists(target)) {
            Path tmp = Kit.download(Kit.Json.str(file, "url"), target, "Downloading " + name, p);
            String sha1 = Kit.Json.str(file, "hashes", "sha1");
            if (sha1 != null && !sha1.equalsIgnoreCase(sha1(tmp))) {
                Files.deleteIfExists(tmp);
                throw new IOException(name + " was damaged on the way. Please try again.");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            Kit.set("mod." + name, projectId);
            added.add(describe(target).name());
        }
        for (Object d : Kit.Json.list(v, "dependencies")) {
            if (!"required".equals(Kit.Json.str(d, "dependency_type"))) continue;
            String dep = Kit.Json.str(d, "project_id");
            if (dep != null && !alreadyHave(dep)) install(dep, mc, p, seen, added);
        }
    }

    private static boolean alreadyHave(String projectId) {
        for (Installed i : installed()) if (projectId.equals(Kit.get("mod." + i.file().getFileName(), ""))) return true;
        return false;
    }

    private static void removeOtherVersions(String projectId, Path keep) throws IOException {
        if (projectId == null) return;
        for (Installed i : installed()) {
            if (i.file().equals(keep)) continue;
            if (projectId.equals(Kit.get("mod." + i.file().getFileName(), ""))) {
                Files.deleteIfExists(i.file());
                Kit.set("mod." + i.file().getFileName(), null);
            }
        }
    }

    static void remove(Installed i) throws IOException {
        Files.deleteIfExists(i.file());
        Kit.set("mod." + i.file().getFileName(), null);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String sha1(Path p) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(Files.readAllBytes(p));
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
