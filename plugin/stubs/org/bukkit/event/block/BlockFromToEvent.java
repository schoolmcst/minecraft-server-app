// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.block;
public class BlockFromToEvent extends BlockEvent implements org.bukkit.event.Cancellable { private final org.bukkit.block.Block to; private boolean cancel; public BlockFromToEvent(org.bukkit.block.Block b, org.bukkit.block.Block t) { super(b); to = t; } public org.bukkit.block.Block getToBlock() { return to; } public boolean isCancelled() { return cancel; } public void setCancelled(boolean c) { cancel = c; } }
