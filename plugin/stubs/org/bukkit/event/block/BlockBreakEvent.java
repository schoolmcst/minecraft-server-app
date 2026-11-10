// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.block;
public class BlockBreakEvent extends BlockEvent implements org.bukkit.event.Cancellable { private final org.bukkit.entity.Player player; private boolean cancel; public BlockBreakEvent(org.bukkit.block.Block b, org.bukkit.entity.Player p) { super(b); player = p; } public org.bukkit.entity.Player getPlayer() { return player; } public boolean isCancelled() { return cancel; } public void setCancelled(boolean c) { cancel = c; } }
