// Where mods come from: Modrinth (no setup) or CurseForge (needs a free API key from console.curseforge.com).
// Both give the Mods page the same things: categories, a searchable list, a details page and an install.

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

interface ModSource {
    String name();

    /** Category id to show name, in display order. */
    LinkedHashMap<String, String> categories() throws IOException, InterruptedException;

    /** Sort options shown to the user, first is the default. */
    List<String> sorts();

    Page search(String query, String category, String sort, String mc, int offset) throws IOException, InterruptedException;

    Detail details(Hit h) throws IOException, InterruptedException;

    /** Installs the mod (and the mods it needs); returns the names it added. */
    List<String> install(Hit h, String mc, Kit.Progress p) throws IOException, InterruptedException;

    /** The value saved as mod.&lt;file&gt; for mods this source installed, so the page can tell what's installed. */
    String installedKey(Hit h);

    record Hit(String id, String slug, String title, String author, String description, long downloads,
               String iconUrl, List<String> categories, String clientSide, String pageUrl) {
        boolean playersNeedIt() { return "required".equals(clientSide); }
    }

    record Page(List<Hit> hits, int total) { }

    record Shot(String thumb, String full, String title) { }

    /** Description as simple HTML, screenshots, and a reason the app can't install it (or null). */
    record Detail(String html, List<Shot> gallery, String blocked) { }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String pretty(String slug) {
        String s = slug.replace('-', ' ').replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Adds pictures from the description to the gallery (a lot of mods only have pictures there). */
    static void addPictures(List<Shot> g, String text) {
        Set<String> have = new HashSet<>();
        for (Shot s : g) { have.add(s.full()); have.add(s.thumb()); }
        for (String u : Markdown.images(text)) {
            if (g.size() >= 16) break;
            if (have.add(u)) g.add(new Shot(u, u, null));
        }
    }

    // ==================================================================== Modrinth

    final class Modrinth implements ModSource {
        private LinkedHashMap<String, String> cats;

        public String name() { return "Modrinth"; }

        public LinkedHashMap<String, String> categories() throws IOException, InterruptedException {
            if (cats != null) return cats;
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            Set<String> skip = Set.of("library", "cursed");
            List<String> names = new ArrayList<>();
            for (Object c : (List<?>) Kit.Json.parse(Kit.getString(Mods.API + "/tag/category"))) {
                if (!"mod".equals(Kit.Json.str(c, "project_type")) || !"categories".equals(Kit.Json.str(c, "header"))) continue;
                String n = Kit.Json.str(c, "name");
                if (n != null && !skip.contains(n)) names.add(n);
            }
            Collections.sort(names);
            for (String n : names) out.put(n, n.equals("worldgen") ? "World generation" : n.equals("game-mechanics") ? "Game mechanics" : pretty(n));
            return cats = out;
        }

        public List<String> sorts() { return List.of("Most downloaded", "Best match", "Most followed", "Newest", "Recently updated"); }

        public Page search(String query, String category, String sort, String mc, int offset) throws IOException, InterruptedException {
            StringBuilder f = new StringBuilder("[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:" + mc + "\"],"
                    + "[\"server_side:required\",\"server_side:optional\"]");
            if (category != null) f.append(",[\"categories:").append(category).append("\"]");
            else if (query.isBlank()) f.append(",[\"categories!=library\"]"); // libraries get added by themselves when a mod needs them
            f.append("]");
            String index = switch (sort) {
                case "Best match" -> "relevance";
                case "Most followed" -> "follows";
                case "Newest" -> "newest";
                case "Recently updated" -> "updated";
                default -> query.isBlank() ? "downloads" : "relevance";
            };
            String url = Mods.API + "/search?limit=24&offset=" + offset + "&index=" + index + "&query=" + enc(query) + "&facets=" + enc(f.toString());
            Object r = Kit.Json.parse(Kit.getString(url));
            List<Hit> out = new ArrayList<>();
            Set<String> loaders = Set.of("fabric", "forge", "neoforge", "quilt", "liteloader", "modloader", "rift");
            for (Object h : Kit.Json.list(r, "hits")) {
                Object d = Kit.Json.get(h, "downloads");
                List<String> c = new ArrayList<>();
                for (Object o : Kit.Json.list(h, "display_categories")) if (o instanceof String s && !loaders.contains(s)) c.add(pretty(s));
                String slug = Kit.Json.str(h, "slug");
                out.add(new Hit(Kit.Json.str(h, "project_id"), slug, Kit.Json.str(h, "title"), Kit.Json.str(h, "author"),
                        Kit.Json.str(h, "description"), d instanceof Number n ? n.longValue() : 0, Kit.Json.str(h, "icon_url"), c,
                        Kit.Json.str(h, "client_side"), "https://modrinth.com/mod/" + slug));
            }
            Object total = Kit.Json.get(r, "total_hits");
            return new Page(out, total instanceof Number n ? n.intValue() : out.size());
        }

        public Detail details(Hit h) throws IOException, InterruptedException {
            Object p = Kit.Json.parse(Kit.getString(Mods.API + "/project/" + enc(h.id() != null ? h.id() : h.slug())));
            List<Shot> g = new ArrayList<>();
            List<?> gal = Kit.Json.list(p, "gallery");
            List<Object> sorted = new ArrayList<>(gal);
            sorted.sort(Comparator.comparing(o -> Boolean.TRUE.equals(Kit.Json.get(o, "featured")) ? 0 : 1));
            for (Object o : sorted) {
                String full = Kit.Json.str(o, "raw_url");
                g.add(new Shot(Kit.Json.str(o, "url"), full != null ? full : Kit.Json.str(o, "url"), Kit.Json.str(o, "title")));
            }
            String body = Kit.Json.str(p, "body");
            addPictures(g, body);
            return new Detail(Markdown.toHtml(body == null ? "" : body), g, null);
        }

        public List<String> install(Hit h, String mc, Kit.Progress p) throws IOException, InterruptedException {
            return Mods.install(h.id() != null ? h.id() : h.slug(), mc, p);
        }

        public String installedKey(Hit h) { return h.id(); }
    }

    // ==================================================================== CurseForge

    final class CurseForge implements ModSource {
        static final String API = System.getProperty("kit.curseforge", "https://api.curseforge.com");
        static final int MINECRAFT = 432, MODS = 6, FABRIC = 4;
        private LinkedHashMap<String, String> cats;

        static String key() { return clean(Kit.get("app.curseforge.key", "")); }

        /** Removes spaces, line breaks and invisible characters that sneak in when copying from a web page. */
        static String clean(String k) { return k == null ? "" : k.replaceAll("[\\s\\p{Cf}\\u00A0\"'`]", ""); }

        static boolean ready() { return !key().isEmpty(); }

        private static String get(String path) throws IOException, InterruptedException {
            if (!ready()) throw new IOException("add your CurseForge key first");
            return Kit.getString(API + path, "x-api-key", key(), "Accept", "application/json");
        }

        /** Checks a key by asking for the Minecraft categories with it. */
        static void test(String key) throws IOException, InterruptedException {
            try {
                Kit.getString(API + "/v1/games/" + MINECRAFT, "x-api-key", clean(key), "Accept", "application/json");
            } catch (IOException e) {
                if (String.valueOf(e.getMessage()).contains("403") || String.valueOf(e.getMessage()).contains("401"))
                    throw new IOException("CurseForge said no to this key. Check you copied all of it (it starts with $2a$10$). "
                            + "A brand-new key can take a while to start working; if it still fails later, make a new key in the console");
                throw e;
            }
        }

        public String name() { return "CurseForge"; }

        public LinkedHashMap<String, String> categories() throws IOException, InterruptedException {
            if (cats != null) return cats;
            List<String[]> list = new ArrayList<>();
            for (Object c : Kit.Json.list(Kit.Json.parse(get("/v1/categories?gameId=" + MINECRAFT + "&classId=" + MODS)), "data")) {
                Object parent = Kit.Json.get(c, "parentCategoryId");
                if (!(parent instanceof Number n) || n.intValue() != MODS) continue;
                String name = Kit.Json.str(c, "name");
                Object id = Kit.Json.get(c, "id");
                if (name == null || !(id instanceof Number i)) continue;
                if (name.equalsIgnoreCase("API and Library") || name.equalsIgnoreCase("MCreator")) continue;
                list.add(new String[]{String.valueOf(i.longValue()), name});
            }
            list.sort(Comparator.comparing(a -> a[1].toLowerCase(Locale.ROOT)));
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            for (String[] a : list) out.put(a[0], a[1]);
            return cats = out;
        }

        public List<String> sorts() { return List.of("Most downloaded", "Popular", "Recently updated", "Newest", "Name"); }

        public Page search(String query, String category, String sort, String mc, int offset) throws IOException, InterruptedException {
            int field = switch (sort) {
                case "Popular" -> 2;
                case "Recently updated" -> 3;
                case "Name" -> 4;
                case "Newest" -> 11;
                default -> 6;
            };
            String url = "/v1/mods/search?gameId=" + MINECRAFT + "&classId=" + MODS + "&modLoaderType=" + FABRIC
                    + "&gameVersion=" + enc(mc) + "&pageSize=24&index=" + offset + "&sortField=" + field
                    + "&sortOrder=" + (field == 4 ? "asc" : "desc")
                    + (query.isBlank() ? "" : "&searchFilter=" + enc(query))
                    + (category == null ? "" : "&categoryId=" + category);
            Object r = Kit.Json.parse(get(url));
            List<Hit> out = new ArrayList<>();
            for (Object m : Kit.Json.list(r, "data")) out.add(hit(m));
            Object total = Kit.Json.get(Kit.Json.get(r, "pagination"), "totalCount");
            int t = total instanceof Number n ? n.intValue() : out.size();
            return new Page(out, Math.min(t, 10000));
        }

        private static Hit hit(Object m) {
            List<String> c = new ArrayList<>();
            for (Object o : Kit.Json.list(m, "categories")) if (Kit.Json.str(o, "name") != null) c.add(Kit.Json.str(o, "name"));
            List<?> authors = Kit.Json.list(m, "authors");
            Object d = Kit.Json.get(m, "downloadCount");
            Object id = Kit.Json.get(m, "id");
            String website = Kit.Json.str(m, "links", "websiteUrl");
            return new Hit(id instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(id), Kit.Json.str(m, "slug"),
                    Kit.Json.str(m, "name"), authors.isEmpty() ? null : Kit.Json.str(authors.get(0), "name"), Kit.Json.str(m, "summary"),
                    d instanceof Number n ? n.longValue() : 0, Kit.Json.str(m, "logo", "thumbnailUrl"), c, null,
                    website != null ? website : "https://www.curseforge.com/minecraft/mc-mods/" + Kit.Json.str(m, "slug"));
        }

        public Detail details(Hit h) throws IOException, InterruptedException {
            Object m = Kit.Json.get(Kit.Json.parse(get("/v1/mods/" + h.id())), "data");
            String html = "";
            try { html = Kit.Json.str(Kit.Json.parse(get("/v1/mods/" + h.id() + "/description")), "data"); } catch (IOException ignored) { }
            List<Shot> g = new ArrayList<>();
            for (Object s : Kit.Json.list(m, "screenshots"))
                g.add(new Shot(Kit.Json.str(s, "thumbnailUrl"), Kit.Json.str(s, "url"), Kit.Json.str(s, "title")));
            addPictures(g, html);
            String blocked = Boolean.FALSE.equals(Kit.Json.get(m, "allowModDistribution"))
                    ? "The person who made this mod only allows downloading it from the CurseForge website." : null;
            return new Detail(Markdown.cleanHtml(html == null ? "" : html), g, blocked);
        }

        public List<String> install(Hit h, String mc, Kit.Progress p) throws IOException, InterruptedException {
            List<String> added = new ArrayList<>();
            install(h.id(), h.title(), mc, p, new HashSet<>(), added);
            return added;
        }

        public String installedKey(Hit h) { return "cf:" + h.id(); }

        private void install(String modId, String title, String mc, Kit.Progress p, Set<String> seen, List<String> added)
                throws IOException, InterruptedException {
            if (!seen.add(modId)) return;
            Object r = Kit.Json.parse(get("/v1/mods/" + modId + "/files?gameVersion=" + enc(mc) + "&modLoaderType=" + FABRIC + "&pageSize=50"));
            List<Object> files = new ArrayList<>(Kit.Json.list(r, "data"));
            files.removeIf(f -> Kit.Json.list(f, "gameVersions").stream().noneMatch(v -> "Fabric".equalsIgnoreCase(String.valueOf(v))));
            if (files.isEmpty()) throw new IOException((title != null ? title : "That mod") + " has no Fabric version for Minecraft " + mc + " yet");
            files.sort(Comparator.comparing((Object f) -> String.valueOf(Kit.Json.str(f, "fileDate"))).reversed());
            Object file = files.get(0);
            for (Object f : files) if (Kit.Json.get(f, "releaseType") instanceof Number n && n.intValue() == 1) { file = f; break; }

            String name = Kit.Json.str(file, "fileName");
            String url = Kit.Json.str(file, "downloadUrl");
            if (url == null || url.isBlank())
                throw new IOException("the person who made " + (title != null ? title : "it") + " only allows downloading it from the CurseForge website");
            if (name == null || name.contains("/") || name.contains("\\")) throw new IOException("that mod's file has a strange name");
            Path target = Kit.mods().resolve(name);
            Files.createDirectories(Kit.mods());
            String key = "cf:" + modId;
            Mods.removeOtherVersions(key, target);
            if (!Files.exists(target)) {
                Path tmp = Kit.download(url, target, "Downloading " + name, p);
                String sha1 = null;
                for (Object hsh : Kit.Json.list(file, "hashes"))
                    if (Kit.Json.get(hsh, "algo") instanceof Number n && n.intValue() == 1) sha1 = Kit.Json.str(hsh, "value");
                if (sha1 != null && !sha1.equalsIgnoreCase(Images.sha1(Files.readAllBytes(tmp)))) {
                    Files.deleteIfExists(tmp);
                    throw new IOException(name + " was damaged on the way. Please try again.");
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                Mods.rejectClientOnly(target);
                Kit.set("mod." + name, key);
                added.add(Mods.describe(target).name());
            }
            for (Object d : Kit.Json.list(file, "dependencies")) {
                if (!(Kit.Json.get(d, "relationType") instanceof Number n) || n.intValue() != 3) continue;
                Object dep = Kit.Json.get(d, "modId");
                if (!(dep instanceof Number dn)) continue;
                String depId = String.valueOf(dn.longValue());
                if (!Mods.alreadyHave("cf:" + depId) && !(depId.equals("306612") && Mods.hasFabricApi())) // 306612 = Fabric API
                    install(depId, null, mc, p, seen, added);
            }
        }
    }
}
