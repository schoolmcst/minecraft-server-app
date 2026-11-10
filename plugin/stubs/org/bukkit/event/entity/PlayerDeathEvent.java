// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.entity;
public class PlayerDeathEvent extends EntityDeathEvent { private final org.bukkit.entity.Player p; private boolean keep; public PlayerDeathEvent(org.bukkit.entity.Player p, java.util.List<org.bukkit.inventory.ItemStack> d, int x) { super(d, x); this.p = p; } public org.bukkit.entity.Player getPlayer() { return p; } public boolean getKeepInventory() { return keep; } public void setKeepInventory(boolean k) { keep = k; } }
