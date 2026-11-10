// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.inventory;
public interface Inventory { java.util.HashMap<Integer, ItemStack> addItem(ItemStack... items) throws IllegalArgumentException; default ItemStack[] getContents() { throw new UnsupportedOperationException(); } }
