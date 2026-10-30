# Minecraft Server app

A survival server that runs on your own computer, in a proper app window. Java Edition and
Bedrock Edition players (phones, tablets, Windows) play together in the same world, and with
one-time setup your friends can join from anywhere.

Under the hood: **Paper** (a faster drop-in for the official server; gameplay stays vanilla),
**Geyser + Floodgate** (Bedrock support), and **playit.gg** (free online play, no router settings).

---

## Start it

Put this folder somewhere permanent (e.g. Documents), then:

- **Windows:** double-click **`Start Server.bat`**. The first time, a black window downloads Java for
  the app (about 50 MB, once). After that the app opens directly. In the app, use
  **Settings > Add desktop shortcut** for a one-click icon.
- **Mac:** right-click **`start-server.command`** > **Open** > **Open** the first time, then double-click.
- **Linux:** run `./start-server.command`.

In the app, click **Start server**. The first start downloads the server (about 100 MB) and asks
you to accept Minecraft's EULA. **Windows:** if a firewall pop-up for Java appears, tick
**Private networks** and click **Allow**.

## The app

| Page | What it's for |
|---|---|
| **Dashboard** | Status, who's online, the addresses friends type in (with Copy buttons), recent activity. |
| **Players** | Add people to the whitelist (Java name or Bedrock/Xbox gamertag), make admins, kick, remove. If someone not on the list tries to join, a one-click **Add** appears. |
| **Console** | A tidy feed of what's happening, a command box, and quick buttons (make it day, clear weather...). Flip **Full server log** for the raw log. |
| **Play online** | Lets friends join from anywhere. See below. |
| **Settings** | Server name, game mode, difficulty, max players, PvP, whitelist, memory, backups (restore too), updates, desktop shortcut. |

Closing the window while the server runs asks first, then saves the world and shuts down cleanly.

## Playing with friends over the internet

Open **Play online** and click **Set up online play**:

1. Your browser opens playit.gg. Create a free account (or log in) and approve this PC.
2. The app creates two addresses, one for Java and one for Bedrock (with a port).
3. Share them. They stay the same, and go live whenever the server is running
   (the header shows **Online + internet**).

The playit program runs quietly in the background only while your server is on. Turn off
"Go online automatically" on that page to keep the server Wi-Fi only.
If playit ever refuses to make an address automatically, the page shows a short manual step.

Online play is automatic on Windows and Linux. On a Mac, install playit from
<https://playit.gg/download> and add a "Minecraft Java" tunnel (port 25565) and a
"Minecraft Bedrock" tunnel (port 19132).

## Good to know

- **Whitelist:** only people you add can join. That matters because the server runs on your computer.
- **Backups:** every start backs up the world (the newest 5 are kept). Restore from Settings > Backups;
  your current world is backed up first.
- **Updating:** Settings > Check for updates (server stopped). Bedrock players usually need the latest
  Geyser, so update when they suddenly can't connect.
- **Consoles** (Xbox, PlayStation, Switch) can't type in server addresses. See
  <https://geysermc.org/wiki/geyser/using-geyser-with-consoles/>.
- **Your PC must be on** with the app open for anyone to play.
- The `src` folder is the app's source code, included so you can see what it does. You never need it.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Couldn't set up Java automatically" | Check your internet and run `Start Server.bat` again, or follow the manual steps it prints. |
| "Already open" | The app is already running; check your taskbar. |
| Server says it stopped unexpectedly | Console shows the last messages; Settings > Open full log has everything. |
| "The server's port is already in use" | Another Minecraft server is running on this PC. Close it first. |
| Bedrock friends get "Unable to connect" online | Restart the server once after setting up online play (the app tells you when). Check they typed the port too. |
| Friends on your Wi-Fi can't connect | Allow Java through Windows Firewall (search "Allow an app through Windows Firewall"). |
| Download fails | Get Paper from <https://papermc.io/downloads/paper>, rename it `paper.jar`, put it in this folder; put Geyser + Floodgate (Spigot versions from <https://geysermc.org/download>) in `plugins`. |

## Updates

Everything keeps itself up to date:

- **The app** checks for a new version every time it opens (and every few hours while it's open) and installs it by
  itself. Turn this off in **Settings > This app**.
- **The server software** (Minecraft server fixes, Bedrock support, Floodgate, console support) updates every time
  the server starts, so Bedrock and Xbox friends can still join after their game updates. A brand-new Minecraft version
  is only offered (Settings > Server software), because Java players have to update their game to match.
