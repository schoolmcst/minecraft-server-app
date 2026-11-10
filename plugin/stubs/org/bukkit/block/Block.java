// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.block;
public interface Block { org.bukkit.Material getType(); int getX(); int getY(); int getZ(); org.bukkit.World getWorld(); Block getRelative(int x, int y, int z); void setType(org.bukkit.Material m); org.bukkit.Location getLocation(); boolean isEmpty(); boolean isLiquid(); boolean isReplaceable(); }
