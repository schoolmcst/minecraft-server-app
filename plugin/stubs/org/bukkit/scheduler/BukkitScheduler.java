// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.scheduler;
public interface BukkitScheduler { BukkitTask runTaskTimer(org.bukkit.plugin.Plugin p, Runnable r, long delay, long period); BukkitTask runTask(org.bukkit.plugin.Plugin p, Runnable r); BukkitTask runTaskLater(org.bukkit.plugin.Plugin p, Runnable r, long delay); }
