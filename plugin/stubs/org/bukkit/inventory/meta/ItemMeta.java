// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.inventory.meta;
public interface ItemMeta { boolean hasDisplayName(); @Deprecated String getDisplayName(); java.util.Map<org.bukkit.enchantments.Enchantment, Integer> getEnchants(); }
