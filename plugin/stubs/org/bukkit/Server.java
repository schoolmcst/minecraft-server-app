// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit;
public interface Server { org.bukkit.plugin.PluginManager getPluginManager(); java.util.Collection<? extends org.bukkit.entity.Player> getOnlinePlayers(); org.bukkit.entity.Player getPlayer(java.util.UUID id); World getWorld(String name);
 default org.bukkit.scheduler.BukkitScheduler getScheduler() { return new org.bukkit.scheduler.BukkitScheduler() { // tests: timers never fire, one-off tasks run now
   public org.bukkit.scheduler.BukkitTask runTaskTimer(org.bukkit.plugin.Plugin p, Runnable r, long d, long q) { return null; }
   public org.bukkit.scheduler.BukkitTask runTask(org.bukkit.plugin.Plugin p, Runnable r) { r.run(); return null; }
   public org.bukkit.scheduler.BukkitTask runTaskLater(org.bukkit.plugin.Plugin p, Runnable r, long d) { r.run(); return null; } }; }
 default double[] getTPS() { throw new UnsupportedOperationException(); }
 default double getAverageTickTime() { throw new UnsupportedOperationException(); }
 default java.util.List<World> getWorlds() { return java.util.List.of(); }
 default org.bukkit.entity.Player getPlayerExact(String n) { throw new UnsupportedOperationException(); }
 default boolean isPrimaryThread() { return true; } }
