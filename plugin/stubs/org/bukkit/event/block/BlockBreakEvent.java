// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.block;
public class BlockBreakEvent extends BlockEvent {
  private final org.bukkit.entity.Player player;
  public BlockBreakEvent(org.bukkit.block.Block b, org.bukkit.entity.Player p) { super(b); player = p; }
  public org.bukkit.entity.Player getPlayer() { return player; }
}
