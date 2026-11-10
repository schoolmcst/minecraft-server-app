// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit;
public class Location { private final World w; private final double x, y, z; public Location(World w, double x, double y, double z) { this.w = w; this.x = x; this.y = y; this.z = z; } public World getWorld() { return w; } public int getBlockX() { return (int) Math.floor(x); } public int getBlockY() { return (int) Math.floor(y); } public int getBlockZ() { return (int) Math.floor(z); } }
