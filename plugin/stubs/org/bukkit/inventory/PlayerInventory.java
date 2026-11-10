// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.inventory;
public interface PlayerInventory extends Inventory { ItemStack getHelmet(); ItemStack getChestplate(); ItemStack getLeggings(); ItemStack getBoots(); void setHelmet(ItemStack i); void setChestplate(ItemStack i); void setLeggings(ItemStack i); void setBoots(ItemStack i); }
