// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.entity;
public interface Player extends org.bukkit.command.CommandSender { java.util.UUID getUniqueId(); org.bukkit.GameMode getGameMode(); String getClientBrandName(); org.bukkit.Location getLocation(); void giveExp(int amount); org.bukkit.inventory.PlayerInventory getInventory(); }
