// Stand-in for the Paper/Bukkit API, only to compile and test XrayWatch. Not shipped.
package org.bukkit;
public enum Material { AIR, CAVE_AIR, VOID_AIR, STONE, DEEPSLATE, DEEPSLATE_DIAMOND_ORE, DIAMOND_ORE, ANCIENT_DEBRIS, NETHERRACK, WATER, LAVA, PLAYER_HEAD, DIAMOND_SWORD, DIAMOND_HELMET, DIRT; public boolean isAir() { return this == AIR || this == CAVE_AIR || this == VOID_AIR; } public boolean isSolid() { return !isAir() && this != WATER && this != LAVA; } }
