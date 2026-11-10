// Stand-in for the Paper/Bukkit API, only to compile XrayWatch. Not shipped.
package org.bukkit.block;
public interface Block { org.bukkit.Material getType(); int getX(); int getY(); int getZ(); org.bukkit.World getWorld(); Block getRelative(int x, int y, int z); }
