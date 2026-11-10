// Game rules shown as on/off switches on the Rules page. Names are Minecraft 26.x snake_case rules.
// What the owner picks is saved per server as rule.<name>=<value> and sent again every time the server starts
// (Minecraft keeps them in the world too, so this just makes sure the app and the world agree).

import java.util.*;
import java.util.regex.*;

final class Rules {
    private Rules() { }

    /** One switch: the game rule, what "on" and "off" mean for it, and whether it's on by default in Minecraft. */
    record Rule(String name, String label, String help, String onValue, String offValue, boolean defaultOn) {
        boolean isOn(String value) {
            if (value == null) return defaultOn;
            if (onValue.equalsIgnoreCase(value)) return true;
            if (offValue.equalsIgnoreCase(value)) return false;
            try { // a number someone set by hand: whichever end it's closer to (fire radius 64 = on, sleeping 50% = off)
                double v = Double.parseDouble(value), on = Double.parseDouble(onValue), off = Double.parseDouble(offValue);
                if (off == 0) return v != 0;
                return Math.abs(v - on) < Math.abs(v - off);
            } catch (NumberFormatException e) {
                return defaultOn;
            }
        }
    }

    static final List<Rule> GAME = List.of(
            new Rule("keep_inventory", "Keep items when you die", "Players keep their inventory and XP after dying.", "true", "false", false),
            new Rule("reduced_debug_info", "Show coordinates", "Java players see their coordinates on the F3 screen.", "false", "true", true),
            new Rule("locator_bar", "Locator bar", "Shows which way your friends are, above the hotbar.", "true", "false", true),
            new Rule("players_sleeping_percentage", "One player can skip the night", "Only one person has to sleep to make it morning.", "1", "100", false),
            new Rule("immediate_respawn", "Respawn instantly", "Skips the death screen.", "true", "false", false),
            new Rule("mob_griefing", "Mobs can break blocks", "Creeper holes, endermen picking up blocks, ghast fireballs. Off keeps builds safe.", "true", "false", true),
            new Rule("fire_spread_radius_around_player", "Fire spreads", "Off stops fire from spreading and burning down builds.", "128", "0", true),
            new Rule("tnt_explodes", "TNT explodes", "Off makes TNT do nothing.", "true", "false", true),
            new Rule("spawn_monsters", "Monsters spawn", "Zombies, skeletons, creepers and the rest. Off is like peaceful, but you still get hungry.", "true", "false", true),
            new Rule("spawn_phantoms", "Phantoms", "The flying mobs that come when you haven't slept for a few nights.", "true", "false", true),
            new Rule("raids", "Raids", "Pillager raids on villages.", "true", "false", true),
            new Rule("fall_damage", "Fall damage", "Off means falling never hurts.", "true", "false", true),
            new Rule("advance_time", "Day and night", "Off freezes the time of day.", "true", "false", true),
            new Rule("advance_weather", "Weather changes", "Off stops rain and storms from coming and going.", "true", "false", true),
            new Rule("show_death_messages", "Death messages in chat", "\"Steve was blown up by Creeper\".", "true", "false", true));

    /** What the running server says each rule is, filled in from its replies. */
    static final Map<String, String> live = Collections.synchronizedMap(new HashMap<>());
    static volatile Runnable onChange = () -> { };

    static String saved(Rule r) { return Kit.get("rule." + r.name(), null); }

    /** The value to show: what the server said, else what the owner picked, else Minecraft's default. */
    static boolean shownOn(Rule r, boolean running) {
        String v = running ? live.get(r.name()) : null;
        if (v == null) v = saved(r);
        return r.isOn(v);
    }

    /** Saves the choice and, if the server is running, changes it right away. */
    static void set(Server s, Rule r, boolean on) {
        String v = on ? r.onValue() : r.offValue();
        Kit.set("rule." + r.name(), v);
        if (s.isRunning()) s.send("gamerule " + r.name() + " " + v);
    }

    /** Sent once the server is up: the owner's saved choices, then a question for every rule so the page is accurate. */
    static void applyAndAsk(Server s) {
        live.clear();
        for (Rule r : GAME) {
            String v = saved(r);
            if (v != null) s.send("gamerule " + r.name() + " " + v);
        }
        ask(s);
    }

    static void ask(Server s) {
        if (!s.isRunning()) return;
        for (Rule r : GAME) s.send("gamerule " + r.name());
    }

    static final Pattern REPLY = Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)?(?:System chat:\\s*)?Game ?rule (?:minecraft:)?([a-z0-9_]+) is (?:currently|now) set to:? (\\S+)");
    static final Pattern UNKNOWN = Pattern.compile("(?i)(unknown|incorrect argument).*game ?rule|game ?rule.*<--\\[HERE\\]");

    /** Reads the server's reply to a gamerule command. Returns true when the line was one (so the feed can skip it). */
    static boolean read(String msg) {
        Matcher m = REPLY.matcher(msg);
        if (m.find()) {
            live.put(m.group(1), m.group(2));
            onChange.run();
            return true;
        }
        return UNKNOWN.matcher(msg).find();
    }
}
