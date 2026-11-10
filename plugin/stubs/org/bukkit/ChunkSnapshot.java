// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit;
public interface ChunkSnapshot { int getHighestBlockYAt(int x, int z); Material getBlockType(int x, int y, int z); org.bukkit.block.data.BlockData getBlockData(int x, int y, int z); }
