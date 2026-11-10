// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.plugin.java;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin, org.bukkit.command.CommandExecutor {
 public static org.bukkit.Server testServer;
 public JavaPlugin() { }
 public org.bukkit.Server getServer() { return testServer; }
 public java.util.logging.Logger getLogger() { return java.util.logging.Logger.getLogger("XrayWatch"); }
 public java.io.File getDataFolder() { return new java.io.File(System.getProperty("xw.dir", "XrayWatch")); }
 public void onEnable() { }
 public void onDisable() { }
 public boolean onCommand(org.bukkit.command.CommandSender s, org.bukkit.command.Command c, String l, String[] a) { return false; }
}
