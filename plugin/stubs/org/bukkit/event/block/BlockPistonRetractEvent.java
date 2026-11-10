// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit.event.block;
public class BlockPistonRetractEvent extends BlockPistonEvent { private final java.util.List<org.bukkit.block.Block> list; public BlockPistonRetractEvent(org.bukkit.block.Block b, java.util.List<org.bukkit.block.Block> l) { super(b); list = l; } public java.util.List<org.bukkit.block.Block> getBlocks() { return list; } }
