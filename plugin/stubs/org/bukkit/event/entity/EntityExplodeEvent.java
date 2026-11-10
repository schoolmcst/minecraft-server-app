// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.entity;
public class EntityExplodeEvent extends EntityEvent { private final java.util.List<org.bukkit.block.Block> list; public EntityExplodeEvent(java.util.List<org.bukkit.block.Block> l) { list = l; } public java.util.List<org.bukkit.block.Block> blockList() { return list; } }
