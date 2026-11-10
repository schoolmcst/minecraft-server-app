# Minecraft Server app

Run Minecraft servers on your own computer from one app. Survival servers work for Java, Bedrock and Xbox
players together; modded servers run Fabric mods from Modrinth. Friends can join from anywhere with free online play.

Under the hood: **Paper** (survival; gameplay stays vanilla), **Fabric** (modded), **Geyser + Floodgate**
(Bedrock players), **MCXboxBroadcast** (Xbox/console players), and **playit.gg** (online play, no router settings).

---

## Opening the app

Search **Minecraft Server** in the Start menu, or use the icon on your desktop. The app is installed in
`AppData\Local\Programs\Minecraft Server`; your servers live in their own folders (new ones go in
`Documents\Minecraft Servers`). Moving a server folder is fine: use **Servers > Add existing folder** to point the
app at its new place.

## Servers

The **Servers** page lists your servers. Click the server name at the top of the sidebar to get there.

- **New server**: pick **Survival** (Java + Bedrock + Xbox) or **Modded** (Fabric), a Minecraft version, game mode
  and difficulty. You can copy the whitelist and admins from the server you have open. The first **Start server**
  downloads what it needs and builds a new world.
- **Switch** opens another server. One server runs at a time, and they all share your online addresses and Xbox
  account, so friends always join the same way.
- **Remove** only takes a server off the list. Its folder and world are never deleted by the app.

## Modded servers

Open **Mods** in the sidebar. **Discover** shows mods by category (Adventure, Mobs, World generation, ...), or search
by name. Click a mod to see its pictures and description, then **Install**. Mods they need are added automatically.
Mods come from **Modrinth** by default; switch to **CurseForge** at the top right (it asks for a free key from
console.curseforge.com once). Some CurseForge mods can only be downloaded on the CurseForge website.
Mods tagged **Players need it too** must also be installed by each Java player (same version). Bedrock and Xbox
players are off by default on modded servers because they can't see most modded things.

## Pages

| Page | What it's for |
|---|---|
| **Dashboard** | Status, who's online, the addresses friends type in, recent activity. |
| **Players** | Whitelist (Java name or Xbox gamertag), admins, kick, remove. |
| **Mods** | Modded servers only: add and remove mods. |
| **Rules** | Switches for anti-cheat and game rules (keep inventory, coordinates, mob griefing, fire spread...). |
| **Console** | A tidy feed of what's happening and a box for commands. |
| **Play online** | Friends join from anywhere (playit.gg), plus Xbox/console setup. |
| **Settings** | Server name, rules, memory, backups, updates, desktop shortcut. |

## Anti-cheat

Survival servers come with **Grim Anticheat** (stops flying, speed, reach, kill aura and auto-clickers) and X-ray
protection (ores stay hidden until a player is right next to them). Bedrock and Xbox players are skipped so they never
get kicked by mistake. Flagged players show up in Recent activity. Each part can be switched on or off on the **Rules** page.

**X-ray detection**: the server watches how players mine. X-rayers give themselves away by turning toward ores they
can't see and by finding far more hidden diamonds and netherite than luck allows. The **X-ray report** on the Rules page
shows everyone's score (0-100) and recent hidden finds with a **Copy teleport** button so you can go and look at their
tunnels. Admins can also type `/xw` or `/xw <player>` in the game. Ores in newly explored areas are shuffled so seed-based
ore finders point to the wrong places.

## Updates

- **The app** checks for a new version when it opens and every few hours, and installs it by itself.
- **Server software** (server fixes, Bedrock support, console support) updates every time a server starts. New
  Minecraft versions for survival servers are offered in Settings, because Java players must update to match.
- **Backups**: every start backs up the world (newest 5 kept). Restore from Settings > Backups.

## Troubleshooting

| Problem | Fix |
|---|---|
| Desktop icon says it can't find the app | Open the app from the Start menu, then Settings > Add desktop shortcut. |
| A server shows "Folder missing" | You moved or renamed it. Remove it from the list, then Add existing folder. |
| "The server's port is already in use" | Another Minecraft server is running on this PC. Close it first. |
| Bedrock friends can't connect online | Restart the server once after setting up online play; check they typed the port. |
| A modded server won't start | Console shows the last messages. Remove the newest mod (Mods page) and try again. |
