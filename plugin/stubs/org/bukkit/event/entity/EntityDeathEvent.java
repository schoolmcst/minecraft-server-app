// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.entity;
public class EntityDeathEvent extends EntityEvent { private final java.util.List<org.bukkit.inventory.ItemStack> drops; private int xp; public EntityDeathEvent(java.util.List<org.bukkit.inventory.ItemStack> d, int x) { drops = d; xp = x; } public java.util.List<org.bukkit.inventory.ItemStack> getDrops() { return drops; } public int getDroppedExp() { return xp; } public void setDroppedExp(int x) { xp = x; } }
