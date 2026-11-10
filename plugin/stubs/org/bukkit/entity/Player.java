// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.entity;
public interface Player extends org.bukkit.command.CommandSender { java.util.UUID getUniqueId(); org.bukkit.GameMode getGameMode(); String getClientBrandName(); org.bukkit.Location getLocation(); void giveExp(int amount); org.bukkit.inventory.PlayerInventory getInventory();
 default double getHealth() { throw new UnsupportedOperationException(); }
 @Deprecated default double getMaxHealth() { throw new UnsupportedOperationException(); }
 default int getFoodLevel() { throw new UnsupportedOperationException(); }
 default int getLevel() { throw new UnsupportedOperationException(); }
 default int getPing() { throw new UnsupportedOperationException(); }
 default org.bukkit.inventory.Inventory getEnderChest() { throw new UnsupportedOperationException(); }
 default boolean teleport(org.bukkit.Location l) { throw new UnsupportedOperationException(); }
 default boolean isOnline() { return true; } }
