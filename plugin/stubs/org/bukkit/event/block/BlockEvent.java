// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.block;
public abstract class BlockEvent extends org.bukkit.event.Event {
  protected org.bukkit.block.Block block;
  public BlockEvent(org.bukkit.block.Block b) { block = b; }
  public final org.bukkit.block.Block getBlock() { return block; }
}
