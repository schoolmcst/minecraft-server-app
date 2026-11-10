// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit;
public interface Server { org.bukkit.plugin.PluginManager getPluginManager(); java.util.Collection<? extends org.bukkit.entity.Player> getOnlinePlayers(); org.bukkit.entity.Player getPlayer(java.util.UUID id); World getWorld(String name); }
