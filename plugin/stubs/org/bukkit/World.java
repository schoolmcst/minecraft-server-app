// Stand-in for the Paper/Bukkit API, only to compile XrayWatch. Not shipped.
package org.bukkit;
public interface World { String getName(); Environment getEnvironment(); enum Environment { NORMAL, NETHER, THE_END, CUSTOM } }
