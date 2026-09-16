# RS3OS

A private game server for the RuneScape 3 **NXT** client, build **950**.

You run it on your own machine, point the game client at it, log in and play —
no Jagex servers involved after the initial client and cache download.

> **This is a hobby and research project.** It is not affiliated with, endorsed
> by, or connected to Jagex Ltd. RuneScape is a trademark of Jagex. No game
> assets are distributed here — the client and the cache are downloaded from
> Jagex by tools you run yourself. See [Legal](#legal).


Thank you to the small team of people from ONXT and other various RS3 projects. This is a amalgamation of all of them, wrapped in an easy to digest package(hopefully)

---

## What you need

| | |
|---|---|
| **Windows** | the NXT client is a Windows program |
| **Java 21** | [Temurin 21](https://adoptium.net/temurin/releases/?version=21), and it must be the `java` on your PATH. Newer versions **do not work** — the Kotlin compiler fails on them with an error that does not say why. 21 can sit alongside a newer Java you already have |
| **Python 3** | [python.org](https://www.python.org/downloads/) — used during setup and to launch the client |
| **Disk space** | around 30 GB — the game cache is most of it |

## Getting it running

Clone the repository, then run one command from its folder:

```powershell
.\play.ps1
```

The first time, that does everything in order: builds the server, generates your
own RSA key pair, downloads the build 950 client and patches it to trust that
key, downloads the game cache, builds the definition database the server reads,
decodes the world map out of it and extracts NPC combat data. Then it starts the
server and launches the client.

The cache download is tens of gigabytes and takes a long time. It resumes where
it left off, so stopping it is safe — just run the same command again. Every
later `.\play.ps1` skips straight to starting the game.

When the client opens, log in with any username and password. An account is
created the first time you use a name. Closing the client stops the server.

If PowerShell refuses to run the script, allow local scripts for your user once:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

### The pieces, separately

`play.ps1` is just `setup.ps1` followed by `run.ps1`.

```powershell
.\setup.ps1                 # set up, skipping anything already done
.\run.ps1                   # start the server, then the client
.\run.ps1 -ServerOnly       # just the server
.\run.ps1 -ClientOnly       # another client against a running server
```

Already have a build 950 cache that `cache-downloader` produced? Skip the
download:

```powershell
.\play.ps1 -Cache 'D:\rs3-950-cache'
```

To give yourself in-game admin powers, put your account name in
`data\config\mods.json`.

### If something goes wrong

Every setup step can be run on its own, which is the quickest way to retry one
thing:

```powershell
.\setup.ps1 -Step cache        # just resume the download
.\setup.ps1 -Step database     # just rebuild the definition database
.\setup.ps1 -Step world        # just re-decode the world map
.\setup.ps1 -Step patch -Force # re-patch the client from scratch
```

The server writes its log to `logs\`. A few things worth knowing:

* **The downloaders always fetch whatever Jagex is serving right now.** Today
  that is build 950. Once the live game moves on, `client-downloader` and
  `cache-downloader` will fetch a newer build, and setup stops with an
  explanation rather than handing you something this server cannot speak.
* **The cache must be one `cache-downloader` produced — not a copy of the folder
  your game client keeps.** The client re-encodes each file when it stores it,
  so a checksum computed over its copy never matches the one the server has to
  advertise, and the client stalls while loading.
* **If you regenerate `rsa.toml`, re-patch the client** (`.\setup.ps1 -Step patch
  -Force`). A client patched with a different key stops at around 26% while
  loading, with nothing in the server log.
* **The client keeps its own files in `data\client\`**, separate from any real
  game install on the same machine. The launcher refuses to point it at a real
  install's folders.
* **The first start of the server takes a few minutes** while it indexes the
  cache. `run.ps1` waits for it before launching the client.

---

## What's in the box

### Working

**Getting in.** Account creation, the lobby, the world login handshake, and
character saves that survive a restart.

**The game frame.** The HUD, side panels, ribbon, options menu, world map, tool
belt, loot window, the interface editor and action-bar locking.

**The world itself.** Terrain, collision and every object placement decoded from
your cache. Over 16,000 NPC placements, activated around players as they move,
including standing bosses that can be fought.

**Moving around.** Walking and running, run energy, pathfinding, doors and gates
that swing, ladders, stairs and lodestone teleports.

**Fighting.** All four combat styles with abilities on the action bar and
Revolution, adrenaline, statuses and buffs, channelled and area abilities,
ammunition, combat spells and auto-casting, necromancy conjures, projectiles,
death animations, drop tables for around 1,500 monsters and combat experience
split across the skills you trained.

**Skills.** Woodcutting, Mining, Fishing, Cooking, Firemaking, Smithing,
Fletching, Thieving and Prayer run as real gathering and production loops with
tool checks, level requirements, success rolls and resource depletion. Every
other skill has its basic actions wired from the cache's recipe data.

**Things to do with items.** Backpack and equipment, the bank with tabs and the
metal bank, shops, ground items and pickup, the money pouch, and item actions
like eating and burying.

**Talking to people.** NPC dialogue for thousands of NPCs, following the same
page-by-page sequence the real client uses.

**Odds and ends.** Emotes, cosmetic overrides, and a set of admin commands gated
behind `mods.json`.

### Being worked on

**Quests.** No quest engine.

**Instances and boss mechanics.** Bosses stand in the shared world; there is no
instancing, and phase mechanics are partial.

**Grand Exchange, trading and player versus player.** Not implemented.

**Hit splat numbers and the target box.** Damage is dealt and lifepoints change,
but the numbers over a target and the target information box do not show yet.

**Music.** The server sends no music; the client falls back to its own player.

**Members areas.** There is no membership model. Every character is treated as a
member.

**Some interface pages** open without their content, and some protocol messages
are not decoded yet; the server log lists them.

---

## How the project is laid out

```
src/main/kotlin/com/opennxt/
  net/            the protocol: packet codecs, login, the JS5 file server
  model/          the world — players, NPCs, movement, combat, the tick loop
  content/        gameplay: skills, abilities, banks, shops, dialogue, doors
  resources/      reading the game cache and the definition database
  tools/          the command line tools

data/
  config/         your server settings and keys (examples are committed)
  prot/950/       the protocol tables — packet names, sizes and field layouts
  seed/           game data used to drive content
  cache/          the game cache, once you have downloaded it

tools/
  launch_client.py    starts the patched client and acts as its launcher
  seed_from_cache.py  extracts NPC combat data from your database

setup.ps1   run.ps1   play.ps1
```

### The command line

Everything runs through one program, built to `build\install\rs3os\bin\`.

```
rs3os run-server                    start the server
rs3os run-tool --help               list every bundled tool
rs3os run-tool rsa-key-generator    generate your key pair
rs3os run-tool client-downloader    download the client from Jagex
rs3os run-tool client-patcher       patch the client to trust your key
rs3os run-tool cache-downloader     download the game cache
rs3os run-tool db-builder           build the definition database from the cache
rs3os run-tool map-builder          decode the world map into that database
```

When running these by hand, set `RS3OS_OPTS=-Dopennxt.prot.experimentalBuild=950`
first; the scripts do this for you.

### Feature switches

Most gameplay sits behind a `-Dopennxt.experiment.*` switch so unfinished work
can be turned off without editing code. `run.ps1` turns on everything that works
and documents each switch inline. To override one:

```powershell
.\run.ps1 -Flags '-Dopennxt.experiment.npcs.aggro=off'
```

---

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).

---

## Legal

This project exists to study how the RuneScape 3 client works and to run a
private server for personal use. It ships no Jagex code, no Jagex assets and no
game cache. The client and the cache are fetched from Jagex's public content
servers by tools you choose to run. Anything derived from the cache — the
definition database and `data/seed/npc_cache.json` — is built on your machine
during setup, which is why neither is committed.

It is not affiliated with or endorsed by Jagex Ltd. Running a private server may
conflict with Jagex's terms of service — that is between you and them. Don't run
this as a public service, and don't use it to take anything away from the real
game.

---

## Contributing

Bug reports and pull requests are welcome. A few things worth knowing before you
start are in [CONTRIBUTING.md](CONTRIBUTING.md).
