// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.player;
public abstract class PlayerEvent extends org.bukkit.event.Event {
  protected org.bukkit.entity.Player player;
  public PlayerEvent(org.bukkit.entity.Player p) { player = p; }
  public final org.bukkit.entity.Player getPlayer() { return player; }
}
