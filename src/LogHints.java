// Plain-words explanations for common things in server logs and crash reports.

import java.util.*;
import java.util.regex.*;

final class LogHints {
    private LogHints() { }

    record Hint(Pattern pattern, String title, String help) { }

    static final List<Hint> HINTS = List.of(
            h("OutOfMemoryError|Java heap space|GC overhead limit", "The server ran out of memory",
                    "Give it more memory in Settings > Memory, or have fewer things loaded (huge farms, lots of mods, players far apart)."),
            h("FAILED TO BIND TO PORT|Address already in use|BindException", "Another server is already using the port",
                    "Another Minecraft server (or an old copy of this one) is still running. Close it, or restart the PC, then start again."),
            h("Unsupported class file major version|UnsupportedClassVersionError|requires (?:running the server with )?Java \\d+|class file version \\d+",
                    "Wrong version of Java", "Something needs a newer Java. Close the app, delete the 'runtime' folder in the app's folder, and open the app again so it gets the right Java."),
            h("Incompatible mods? found|Mod resolution failed|requires (?:any version of |version .* of )?\\S+, which is missing|Missing mod|depends on .* which is missing",
                    "A mod is missing something it needs", "One of the mods needs another mod (or a different Minecraft version). The lines around this message name it. Add the missing mod on the Mods page, or remove the one that needs it."),
            h("Could not load 'plugins[/\\\\][^']+'|Error occurred while enabling|is not compatible with|Unsupported API version",
                    "A plugin didn't load", "A plugin is broken or made for a different Minecraft version. Update it or remove it from the plugins folder."),
            h("Exception in server tick loop|Encountered an unexpected exception", "The server crashed while running",
                    "Something in the world or a plugin/mod caused it. If it keeps happening at the same place, the lines below the error name the cause. Restoring a backup from Settings fixes a broken world."),
            h("Ticking entity|Entity being ticked", "A mob or item broke the server",
                    "A single mob, item or minecart caused the crash. The report says its position; an admin can remove it there with /kill."),
            h("Ticking block entity|Block entity being ticked", "A block broke the server",
                    "A machine-like block (chest, hopper, modded machine...) caused the crash. The report says where it is."),
            h("Can't keep up! Is the server overloaded", "The server was lagging",
                    "The PC couldn't keep up for a while. Lots of mobs, big redstone builds, flying around new land, or not enough memory are the usual causes. The Health page graph shows when."),
            h("Failed to load level|Exception reading .*\\.mca|Region file .* is truncated|Corrupted chunk|ChunkSerializer", "Part of the world is damaged",
                    "Some of the world couldn't be read, usually after the PC lost power. Restore the last backup in Settings > Backups."),
            h("You need to agree to the EULA", "The EULA isn't accepted", "Start the server from the app and press 'I accept' when it asks."),
            h("moved too quickly|moved wrongly", "A player moved oddly",
                    "Usually just lag. If it's always the same player, they may be using a cheat (see the anti-cheat on the Rules page)."),
            h("Failed to verify username|Invalid session|disconnected with: .*authentication", "Someone couldn't sign in",
                    "Their Minecraft account didn't check out with Microsoft. Asking them to restart their game and launcher usually fixes it."),
            h("Watchdog|A single server tick took|server has stopped responding", "The server froze",
                    "The server stopped responding for a long time and was shut down. A huge redstone machine, an enormous explosion, or a broken plugin/mod is the usual cause."));

    private static Hint h(String re, String title, String help) { return new Hint(Pattern.compile(re, Pattern.CASE_INSENSITIVE), title, help); }

    /** The hints that apply to this text, most serious first (title, help). */
    static List<String[]> find(String text) {
        List<String[]> out = new ArrayList<>();
        for (Hint h : HINTS) if (h.pattern().matcher(text).find()) out.add(new String[]{h.title(), h.help()});
        return out;
    }
}
