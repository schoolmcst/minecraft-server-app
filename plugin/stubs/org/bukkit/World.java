// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit;
public interface World { String getName(); Environment getEnvironment(); org.bukkit.block.Block getBlockAt(int x, int y, int z); org.bukkit.entity.Item dropItemNaturally(Location l, org.bukkit.inventory.ItemStack i); Location getSpawnLocation(); int getMinHeight(); int getMaxHeight(); enum Environment { NORMAL, NETHER, THE_END, CUSTOM } }
