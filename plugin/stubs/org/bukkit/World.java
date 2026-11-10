// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit;
public interface World { String getName(); Environment getEnvironment(); org.bukkit.block.Block getBlockAt(int x, int y, int z); org.bukkit.entity.Item dropItemNaturally(Location l, org.bukkit.inventory.ItemStack i); Location getSpawnLocation(); int getMinHeight(); int getMaxHeight(); enum Environment { NORMAL, NETHER, THE_END, CUSTOM }
 default int getEntityCount() { throw new UnsupportedOperationException(); }
 default int getChunkCount() { throw new UnsupportedOperationException(); }
 default long getTime() { throw new UnsupportedOperationException(); }
 default boolean hasStorm() { throw new UnsupportedOperationException(); }
 default boolean isThundering() { throw new UnsupportedOperationException(); }
 default boolean isChunkLoaded(int x, int z) { throw new UnsupportedOperationException(); }
 default Chunk getChunkAt(int x, int z) { throw new UnsupportedOperationException(); }
 default java.util.concurrent.CompletableFuture<Chunk> getChunkAtAsync(int x, int z, boolean gen) { throw new UnsupportedOperationException(); }
 default int getHighestBlockYAt(int x, int z) { throw new UnsupportedOperationException(); } }
