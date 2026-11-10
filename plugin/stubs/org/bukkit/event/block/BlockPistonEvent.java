// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.block;
public abstract class BlockPistonEvent extends BlockEvent implements org.bukkit.event.Cancellable { private boolean cancel; public BlockPistonEvent(org.bukkit.block.Block b) { super(b); } public boolean isCancelled() { return cancel; } public void setCancelled(boolean c) { cancel = c; } }
