// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit;
public interface Chunk { int getX(); int getZ(); World getWorld(); ChunkSnapshot getChunkSnapshot(boolean maxY, boolean biome, boolean temp); }
