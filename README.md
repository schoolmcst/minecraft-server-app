# Your Minecraft Server

A survival server that runs on your own computer. Java Edition **and** Bedrock Edition players
(phones, tablets, Windows Bedrock) can play together in the same world.

It runs **Paper** (a faster drop-in replacement for the official server — gameplay stays vanilla)
plus **Geyser** and **Floodgate**, which let Bedrock players join without owning Java Edition.

---

## 1. Start the server

Put this folder somewhere permanent (e.g. Documents), then:

- **Windows:** double-click **`Start Server.bat`**
- **Mac:** right-click **`start-server.command`** → **Open** → **Open** (only needed the first time,
  because macOS doesn't know the file yet). After that you can double-click it.
- **Linux:** run `./start-server.command` in a terminal.

That's it — no separate Java install. The first start sets everything up by itself (about 150 MB:
its own copy of Java in the `runtime` folder, plus the server), asks you to accept the
[Minecraft EULA](https://aka.ms/MinecraftEULA) — type `yes` — and builds the world.
When the window says **SERVER IS ONLINE**, you're ready.

**Windows:** a firewall pop-up for Java will appear. Tick **Private networks** and click **Allow**.

### The server window

Instead of the raw server log, the window shows a short "how to join" panel, then one line per event:
players joining and leaving, chat, deaths, replies to your commands, and real problems
(lag, someone who isn't on the whitelist, errors). Everything else still goes to `logs\latest.log`.
To see the full raw log in the window instead, start it with `--full-log`
(e.g. make a shortcut to `Start Server.bat --full-log`).

## 2. Make yourself admin and add your friends

Type these in the server window (no `/`), replacing the names:

| What | Command |
|---|---|
| Make yourself admin | `op YourJavaName` |
| Let a **Java** friend in | `whitelist add TheirJavaName` |
| Let a **Bedrock** friend in | `fwhitelist add TheirXboxGamertag` |
| Remove someone | `whitelist remove Name` |
| Shut down safely | `stop` |

The whitelist is **on**, so only people you add can join. That matters because the server runs on
your computer. Bedrock players show up in-game with a `.` in front of their name — that's normal.

## 3. Joining

The server window shows the right addresses every time it starts.

| Who | Java Edition | Bedrock Edition |
|---|---|---|
| You, on this computer | Multiplayer → Add Server → `localhost` | Servers → Add Server → `127.0.0.1`, port `19132` |
| Friends on your Wi-Fi | your computer's local IP (shown in the window, e.g. `192.168.1.23`) | same IP, port `19132` |
| Friends anywhere else | see section 4 | see section 4 |

## 4. Playing with friends over the internet

Pick **one** of these.

### Option A — playit.gg (easiest, free, no router settings)

1. Download the playit program from <https://playit.gg> and run it. Sign in when it asks.
2. On the playit website, add a tunnel: type **Minecraft Java**, local port `25565`.
3. Add a second tunnel: type **Minecraft Bedrock**, local IP `127.0.0.1`, local port `19132`,
   Proxy Protocol **None**.
4. Give friends the addresses playit shows:
   - Java friends: the Java tunnel address (e.g. `something.joinmc.link`)
   - Bedrock friends: the Bedrock tunnel address **and its port** (e.g. `something.ply.gg`, port `6695`)
5. Stop the server, open `plugins/Geyser-Spigot/config.yml`, set `broadcast-port` to the Bedrock
   tunnel's port (e.g. `6695`), save, and start the server again.

Keep the playit program running while you play — closing it closes the tunnels.

### Option B — port forwarding on your router

In your router's settings, forward these to your computer's local IP:
- **TCP 25565** (Java)
- **UDP 19132** (Bedrock)

Friends then join with your public IP (search "what is my IP"). Your public IP can change,
and this exposes your computer directly, so Option A is usually the better choice.

## Everyday stuff

- **Backups:** every start saves a copy of the world to `backups/` (the newest 5 are kept). To restore
  one: stop the server, delete the world folders, and unzip the backup into this folder.
- **Updating:** when a new Minecraft version comes out, copy this whole folder somewhere safe, then
  run **`Update Server.bat`** (Mac/Linux: `./start-server.command --update`). Bedrock players
  usually need the latest Geyser, so update when they say they can't connect after a Bedrock update.
- **Memory:** the server uses 4 GB. If your computer has only 8 GB, open the start script in a text
  editor and change `RAM=4G` to `RAM=3G`.
- **Settings:** `server.properties` holds difficulty, game mode, the welcome message (`motd`),
  view distance and more. Stop the server before editing it.
- **Your computer must be on** with the server window open for anyone to play.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Couldn't set up Java automatically" | Check your internet and start again. If it keeps failing, see the manual steps it prints. |
| Window closes or shows "stopped unexpectedly" | It lists the last messages; the full story is in `logs\latest.log`. |
| "You are not whitelisted" | Add them with `whitelist add` (Java) or `fwhitelist add` (Bedrock). |
| Bedrock friends are asked to sign in to a Java account | In `plugins/Geyser-Spigot/config.yml` set `auth-type: floodgate`, then restart. |
| Bedrock says "Unable to connect to world" | Check the port is `19132` (or the playit port), and allow Java through the firewall. |
| Friends on Wi-Fi can't connect | Allow Java through Windows Firewall (search "Allow an app through Windows Firewall"). |
| Xbox / PlayStation / Switch players | Consoles can't type in server addresses. See <https://geysermc.org/wiki/geyser/using-geyser-with-consoles/> |
| Download fails | Get Paper from <https://papermc.io/downloads/paper>, rename it `paper.jar`, put it in this folder; put Geyser + Floodgate (Spigot versions from <https://geysermc.org/download>) in `plugins/`. |
