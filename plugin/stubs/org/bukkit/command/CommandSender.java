// Stand-in for the Paper/Bukkit API, only to compile XrayWatch. Not shipped.
package org.bukkit.command;
public interface CommandSender { void sendMessage(String m); boolean isOp(); String getName(); }
