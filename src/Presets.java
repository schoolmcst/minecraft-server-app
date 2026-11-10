// One-click setups ("presets") that switch several rules at once and switch them back afterwards.
// While one is on, the values it replaced are saved (preset.saved) so they can be put back when it ends, even if the
// app or server restarted in between. Presets never change the owner's saved choices on the Rules page.

import java.time.*;
import java.util.*;

final class Presets {
    private Presets() { }

    record Preset(String id, String name, String blurb, Map<String, String> rules, String difficulty, Boolean graves,
                  String time, String weather, String announce, String defaultLength) { }

    static final List<String> LENGTHS = List.of("For 1 hour", "For 2 hours", "For 3 hours", "Until tomorrow morning", "Until Monday morning", "Until I stop it");

    static final List<Preset> ALL = List.of(
            new Preset("hardcore", "Hardcore weekend",
                    "Hard mode, and dying really costs you: no keeping items, no graves, everyone has to sleep to skip the night.",
                    rules("keep_inventory", "false", "immediate_respawn", "false", "players_sleeping_percentage", "100", "show_death_messages", "true"),
                    "hard", false, null, null, "Hardcore weekend has started! Hard mode, no graves, no keeping items. Good luck.", "Until Monday morning"),
            new Preset("build", "Peaceful build day",
                    "No monsters, always daytime, clear skies, no fall damage, nothing burns or blows up, and you keep your items.",
                    rules("keep_inventory", "true", "mob_griefing", "false", "fire_spread_radius_around_player", "0", "tnt_explodes", "false",
                            "spawn_phantoms", "false", "fall_damage", "false", "advance_time", "false", "advance_weather", "false"),
                    "peaceful", null, "noon", "clear", "Peaceful build day! No monsters, always sunny. Build away.", "Until tomorrow morning"),
            new Preset("pvp", "PvP event",
                    "Fighting on, keep your items when you die, respawn instantly, no monsters getting in the way, always daytime.",
                    rules("pvp", "true", "keep_inventory", "true", "immediate_respawn", "true", "spawn_monsters", "false",
                            "show_death_messages", "true", "advance_time", "false"),
                    "normal", null, "noon", "clear", "PvP event has started! You keep your items when you die. Fight!", "For 1 hour"));

    private static Map<String, String> rules(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static Preset byId(String id) { for (Preset p : ALL) if (p.id().equals(id)) return p; return null; }

    static Preset active() { return byId(Kit.get("preset.active", "")); }

    /** When the active preset ends (epoch ms), or 0 for "until I stop it". */
    static long until() {
        try { return Long.parseLong(Kit.get("preset.until", "0")); } catch (NumberFormatException e) { return 0; }
    }

    static long endFor(String length) {
        ZonedDateTime now = ZonedDateTime.now();
        return switch (length) {
            case "For 1 hour" -> now.plusHours(1).toInstant().toEpochMilli();
            case "For 2 hours" -> now.plusHours(2).toInstant().toEpochMilli();
            case "For 3 hours" -> now.plusHours(3).toInstant().toEpochMilli();
            case "Until tomorrow morning" -> now.toLocalDate().plusDays(1).atTime(7, 0).atZone(now.getZone()).toInstant().toEpochMilli();
            case "Until Monday morning" -> {
                LocalDate d = now.toLocalDate().plusDays(1);
                while (d.getDayOfWeek() != DayOfWeek.MONDAY) d = d.plusDays(1);
                yield d.atTime(7, 0).atZone(now.getZone()).toInstant().toEpochMilli();
            }
            default -> 0L;
        };
    }

    /** What a rule is right now (what the server said, else the owner's saved choice, else Minecraft's default). */
    static String current(String rule) {
        String v = Rules.live.get(rule);
        if (v != null) return v;
        for (Rules.Rule r : Rules.GAME) if (r.name().equals(rule)) { String s = Rules.saved(r); return s != null ? s : r.defaultOn() ? r.onValue() : r.offValue(); }
        return "true"; // pvp, natural_health_regeneration
    }

    static String currentDifficulty() {
        String d = Rules.live.get("difficulty");
        if (d != null) return d;
        try {
            Properties p = new Properties();
            try (var in = java.nio.file.Files.newInputStream(Kit.dir().resolve("server.properties"))) { p.load(in); }
            return p.getProperty("difficulty", "easy").trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "easy";
        }
    }

    /** Starts a preset (switching back any other one first). */
    static void start(Server s, Preset p, String length) {
        if (!s.isRunning()) return;
        if (active() != null) stop(s, false);
        StringBuilder saved = new StringBuilder();
        for (String r : p.rules().keySet()) saved.append(r).append('=').append(current(r)).append(';');
        if (p.difficulty() != null) saved.append("difficulty=").append(currentDifficulty()).append(';');
        if (p.graves() != null) saved.append("graves=").append(AntiCheat.graves()).append(';');
        Kit.set("preset.saved", saved.toString());
        Kit.set("preset.active", p.id());
        long end = endFor(length);
        Kit.set("preset.until", String.valueOf(end));
        apply(s, p, true);
        String when = end == 0 ? "until you stop it" : "until " + describe(end);
        s.event(Server.Kind.SYSTEM, p.name() + " is on " + when + ". Everything switches back afterwards.");
        if (p.announce() != null) s.send("say " + p.announce());
    }

    private static void apply(Server s, Preset p, boolean firstTime) {
        for (var e : p.rules().entrySet()) s.send("gamerule " + e.getKey() + " " + e.getValue());
        if (p.difficulty() != null) s.send("difficulty " + p.difficulty());
        if (p.graves() != null && AntiCheat.graves()) s.send("xw graves " + (p.graves() ? "on" : "off"));
        if (firstTime && p.time() != null) s.send("time set " + p.time());
        if (firstTime && p.weather() != null) s.send("weather " + p.weather());
        Rules.ask(s);
    }

    /** Ends the active preset and puts back what it changed. */
    static void stop(Server s, boolean say) {
        Preset p = active();
        String saved = Kit.get("preset.saved", "");
        Kit.set("preset.active", null);
        Kit.set("preset.until", null);
        Kit.set("preset.saved", null);
        if (p == null || !s.isRunning()) return;
        for (String kv : saved.split(";")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            if (k.equals("difficulty")) s.send("difficulty " + v);
            else if (k.equals("graves")) { if (AntiCheat.graves()) s.send("xw graves on"); }
            else s.send("gamerule " + k + " " + v);
        }
        Rules.ask(s);
        if (say) {
            s.event(Server.Kind.SYSTEM, p.name() + " is over. Everything is back to how it was.");
            s.send("say " + p.name() + " is over. Everything is back to normal.");
        }
    }

    /** After the server starts: re-apply the active preset, or switch it back if it ended while the server was off. */
    static void resume(Server s) {
        Preset p = active();
        if (p == null) return;
        long end = until();
        if (end > 0 && System.currentTimeMillis() >= end) {
            // ended while the server was off: the world still has the preset's rules, so put them back now
            Kit.set("preset.active", p.id());
            stop(s, true);
        } else {
            apply(s, p, false);
            s.event(Server.Kind.SYSTEM, p.name() + " is still on" + (end > 0 ? " until " + describe(end) : "") + ".");
        }
    }

    /** Called every second by the app. */
    static void tick(Server s) {
        Preset p = active();
        if (p == null || !s.isRunning()) return;
        long end = until();
        if (end > 0 && System.currentTimeMillis() >= end) stop(s, true);
    }

    static String describe(long epochMs) {
        ZonedDateTime t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        ZonedDateTime now = ZonedDateTime.now();
        String clock = App.CLOCK.format(t.toLocalTime());
        if (t.toLocalDate().equals(now.toLocalDate())) return clock;
        if (t.toLocalDate().equals(now.toLocalDate().plusDays(1))) return "tomorrow " + clock;
        return t.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + " " + clock;
    }
}
