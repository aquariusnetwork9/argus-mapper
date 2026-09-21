# ARGUS Mapper

A Fabric client mod from Aquarius Networks for the ARGUS spawn-region mapping
project. It takes the map you've already explored in Xaero's World Map and
uploads it to ARGUS, with tools to keep your private places private.

Works on **Minecraft 1.21.11 and 26.2** - the two versions 6b6t lets you join
(see [Supported versions](#supported-versions)).

## Highlights

- **Upload in a few clicks.** Drag-select an area on Xaero's world map,
  right-click, confirm. Or use the in-game GUI. No commands needed.
- **Live upload (opt-in).** Switch it on and the mod uploads the regions you
  explore automatically, on a random 45-80 minute delay - so ARGUS gets your map
  without ever seeing where you are right now.
- **Teleport-aware.** Teleport somewhere far outside the upload area (`/home`
  to a distant base, say) and live upload pauses, blackzones a 25-region radius
  around where you landed in the Overworld and Nether, and asks you to keep,
  cancel or edit it on Xaero's map before it resumes.
- **Whole-map upload (opt-in).** Lifts the default distance limit for one
  session, so regions anywhere on the map can be uploaded.
- **Blackzones.** Mark areas that are never uploaded - your base, a stash.
  Local only, and removing one always asks first.
- **Safe by default.** Nothing uploads unless you start it. Live and whole-map
  upload switch themselves off on every restart, crash or disconnect. Uploads
  stop at 200 regions (about 100k blocks) from the origin unless you lift that,
  nether uploads are limited to public highways, and every risky switch has a
  confirm popup.
- **See what's happening.** Xaero's map outlines blackzones in red and tints
  regions amber while uploading and green once uploaded.
- **Re-upload changed regions (opt-in).** Keep ARGUS current when you've
  explored more of a region you already sent.
- **GUI and pause-menu button.** A tabbed screen for every setting, one click
  away from the pause menu.
- **Extras.** Multi-server support with auto-detect, Discord stats, and the
  Aquarius Road Department (ARD) nether-highway tools bundled in the same jar.

## Quick start

1. **Install** [Fabric](https://fabricmc.net/), Fabric API, and (recommended)
   Xaero's World Map, then drop the ARGUS Mapper jar in your `mods` folder.
2. **Get a partner token** from ARGUS.
3. **In game**, open the GUI with `/argus gui` (or the **ARGUS Menu** button on
   the pause screen) and paste the token on the **Token** tab. Joining a known
   server sets the upload layer for you (6b6t uses `shallowplague`); otherwise
   use `/argus setlayer <layer>`.
4. **Upload.** Open Xaero's fullscreen map, drag a selection, right-click and
   choose **Upload This Area to ARGUS** - or run `/argus scan` to preview and
   `/argus upload` to send everything eligible.

## Using the mod

### GUI and pause-menu button

Open it with `/argus gui`, the **ARGUS Menu** button on the pause screen, or a
key you bind in Controls (unbound by default). The tabs are **General, Token,
Servers, Blackzones, Stats, Uploads, Auto-map, Road Dept.** and **API**;
everything in them does exactly what the matching chat command does. (26.2 has no
Road Dept. tab because ARD isn't ported there yet, and no Auto-map tab yet.)

The **Uploads** tab shows the current run live - progress bar, queued / done /
failed counts, and how many regions a blackzone kept out - and holds the
[live and whole-map switches](#automatic-uploads-live-and-whole-map).

The pause-menu button lines itself up under the vanilla buttons and steps around
other mods' pause-screen widgets instead of covering them.

### Xaero's World Map integration

Optional - the mod works the same with or without Xaero's World Map. With it,
drag a selection on the fullscreen map and right-click:

- **Upload This Area to ARGUS** - uploads exactly that selection. A popup shows
  how many regions and roughly how many MB before anything is sent, and the run
  shows up in the Uploads tab like any other.
- **Mark ARGUS Blackzone** - saves the selection as a blackzone.
- **Remove ARGUS Blackzone** - only shown when the selection overlaps an
  existing blackzone; asks for confirmation first.

The map also colours regions as you look at it: a **red outline** for a
blackzone, **amber** while uploading, **green** once uploaded.

### Automatic uploads: live and whole-map

Two opt-in switches on the GUI's **Uploads** tab, each behind its own confirm
popup. Both are **per session**: never saved to the config, off after any
restart or crash, and switched off when you disconnect. There is no setting that
turns them on for you.

- **Live upload** (`/argus live on|off|resume`) - while on, the mod uploads
  regions you explore on a random delay of 45-80 minutes. Each cycle sends the
  regions Xaero has saved since you turned it on that are new or changed since
  their last upload; a changed region replaces its older copy. A region Xaero
  is still writing to (you're mapping it right now) is held back until its file
  has been untouched for 10 minutes and is checked again just before it is
  sent, so half-finished regions don't go up and one region is never re-sent
  more than once per cycle. You don't need a dedicated mapping session: any
  region that changes while you play is picked up once you've moved on. The
  delay is a compromise between streaming your path as it happens and uploading
  by hand, so upload timing never reveals where you are right now. Blackzones,
  the nether gate and the size limit all still apply.
- **Whole-map upload** (`/argus wholemap on|off`) - lifts the
  [distance limit](#distance-limit) for the session, by hand or with live
  upload. Blackzones still apply.

**Live upload pauses when you teleport out of the upload area.** A teleport is a
move of more than 128 blocks in one step or a dimension change (a portal trip,
respawning far from where you died). If you land inside the default
[distance limit](#distance-limit) - about 102,400 blocks each way, whether or not
whole-map upload is on - nothing happens. If you land outside it, and not
already inside one of your blackzones:

1. Live upload pauses at once and cancels anything in flight.
2. ARGUS blackzones 25 regions (about 12,800 blocks) in every direction from
   where you landed, in the dimension you arrived in and its Overworld/Nether
   counterpart (coordinates scale 8:1). The End has no counterpart.
3. Once you've settled (5 seconds with no further jump) and no other screen is
   open, a popup asks about that blackzone: **OK** keeps it, **Cancel** removes
   it, **Modify** opens Xaero's World Map so you can drag-select and mark your
   own (right-click a blackzone there to remove the automatic one).
4. Then it asks whether to resume. Nothing resumes until you confirm, and
   resuming starts a fresh random delay.

The blackzone exists from the moment you land, so it protects you even if you
disconnect before answering. Your position is compared tick to tick to spot a
jump and is never stored or sent anywhere.

### Re-uploading regions that changed

By default a region is uploaded once and never again, even if you later explore
more of it. Turn on **Re-upload regions that changed** (General tab, or
`reuploadChangedRegions=true`) and each run also includes regions whose Xaero
file is newer than when it was last uploaded. Live upload always does this for
regions saved during the session.

- The ARGUS API keeps the newest copy of each region, so a re-upload replaces
  the old one rather than duplicating it.
- Blackzones, the distance limit and the size limit apply exactly as they do to
  a first upload.
- "Changed" means the file's modified time is newer than at upload. Regions
  uploaded by older versions have no recorded time, so the first run after
  updating records their current time as a baseline; only later changes count.
- "Regions contributed" still counts distinct regions.

### Auto-map (experimental)

Drag-select a box on Xaero's World Map, right-click and pick **Auto-Map This Area
(ARGUS)** (or use the GUI's **Auto-map** tab, or `/argus automap start <minRegionX>
<minRegionZ> <maxRegionX> <maxRegionZ>`), confirm, and the mod flies the box for you
so Xaero records it. The **Auto-map** tab has the area fields (with **Use my
region**), Start and Stop, the calibration flight, live status - including what's
missing if it can't start - and the speed, altitude and lane-width settings. It needs
[Meteor Client](https://meteorclient.com)'s **Elytra Fly** switched on in
**Vanilla** mode and you already gliding; Meteor does the flying and this steers
it. Overworld and End only. Nothing about it is saved, and it always asks first.

- **Path.** Back-and-forth lanes along the longer side of the box, starting at the
  corner nearest you, at `autoMapCruiseY` (default 475, above build height) and higher over tall
  terrain. Xaero maps a narrower strip the faster you fly - measured on 6b6t: about
  14 chunks across at 25 blocks/s, 6 at 40, 4 at 60, 2 at 92 - so faster means
  more, closer lanes. From that table (`autoMapWidthTable`, editable) it picks the
  speed that covers the most ground per second, which comes out around 25 blocks/s
  rather than the top speed, and spaces the lanes to match. `autoMapSpeed` pins the
  speed and `autoMapHalfWidthChunks` the spacing.
- **Paced to the map.** Every second it checks how many chunks either side of the
  lane Xaero has actually recorded (or that are loaded, if Xaero can't be read)
  and slows Meteor's horizontal speed until the lane is fully covered, then creeps
  back up. Each server rubberband lowers its speed ceiling for the rest of the run.
  It stays between `autoMapMinSpeed` and the speed it picked, and your Meteor
  speed is put back afterwards.
- **Gaps.** When the lanes are done it flies to any chunk Xaero still lacks and
  hovers until it is recorded, for up to two rounds, then reports what's left.
- **Nothing in the area uploads while it runs.** From the moment a flight (or
  calibration) starts until it ends - finished, stopped or aborted - the regions it
  is writing to, plus one region around them, are held back from every upload:
  live, manual, from the map, and again in the network call itself. A half-mapped
  region would otherwise replace a fuller copy on the server. It's session-only and
  cleared when the run ends; live upload then sends them after its usual
  10-minute quiet period. Starting a flight also cancels a live upload run that is
  in flight.
- **Stops by itself** if you teleport, change dimension, take damage, stop
  gliding, run low on elytra durability, switch Elytra Fly off or disconnect.
  `/argus automap stop` ends it, and `/argus automap` shows progress.

Xaero's own recording is usually the slow part at high speed; XaeroPlus's
**Fast Mapping** option may help.

**Calibration flight.** `/argus automap calibrate [speeds]` flies one straight line
at a series of speeds (blocks per tick; default 1.0 up to 5.99, about 4.5 minutes)
and writes what it saw to `argus-mapper-calibration/<time>-fastmapping-<on|off>/`
in the game folder: `summary.txt` (a table of how many chunks across were loaded and
how many Xaero mapped at each speed, plus a suggested `autoMapWidthTable`),
`rows.csv` (how often each row either side of the line was loaded or mapped) and
`samples.csv` (a reading every quarter second: speed, loaded and mapped widths,
fps, ping, the renderer's own chunk count). It heads whichever way has the fewest
existing map files, needs the same setup as auto-map, and stops the same ways.
Run it once with XaeroPlus Fast Mapping off and once on to compare.

### Bounty regions

The ARGUS bounty program works out which map cells (2 x 2 regions, 1024 x 1024
blocks) it wants filled in next, nearest to spawn first, plus today's double-token
cell. Turn on **Bounty** (the GUI's Bounty tab, or `/argus bounty on`) and the mod
marks them on Xaero's World Map as temporary waypoints named **ARGUS Bounty Region**,
at the middle of each cell. Today's double-token cell is gold and named **ARGUS
Bounty Region (2x)**.

- **Off by default.** Nothing is fetched until you turn it on.
- **What it sends.** While it's on, in the overworld on a server in your server list,
  the mod asks `bountyUrl` (default `https://map.argus.tools/api/needed-regions`) for
  the list about every 2 minutes, with `?limit=<bountyLimit>` (default 50). It is a
  plain public GET: no token, no player name or position, no other data. It asks
  nothing while you're in the nether or the end, on another server, or in
  single-player, and after failures it backs off up to 10 minutes.
- **The markers.** They're temporary (Xaero never saves them), shown on the World Map
  only (not the minimap or in the world), and replaced on each refresh. They go into
  the waypoint set you have open. Turning bounty off removes them.
- **The boxes.** Each cell is also drawn on the World Map as a box: aqua for a wanted
  cell, gold for today's 2x cell.
- **Flying there.** `/argus bounty go` (or `go 2x` / `go nearest`), the GUI's **Map the
  2x cell** / **Map the nearest** buttons, or right-clicking a selection on a bounty
  box in the World Map and choosing **Fly to Bounty Region and Auto-Map**, asks
  "Fly to this bounty region and map it?". On **Yes** it flies to the box's nearest
  corner at Meteor's top speed (`autoMapMaxSpeed`, 5.99 blocks/tick by default),
  then within about two seconds of reaching it slows to the mapping speed (about
  25 blocks/s) and maps the whole box in lanes, like an auto-map. It needs what
  auto-map needs: Elytra Fly on in Vanilla mode and you already gliding (take off
  yourself). It stops the same ways, `/argus automap stop` cancels it, and uploads
  of the box are held until it finishes. Bounty boxes are in the overworld.
- **Commands.** `/argus bounty` shows the state, `on` / `off` switch it, `refresh`
  asks again now (at most every 15 seconds), `clear` removes the markers and boxes
  until the next refresh, `go` starts the flight above.

Needs Xaero's Minimap and World Map. Not in the 26.2 build yet.

## Privacy and safety

**What gets sent.** An upload contains the region's Xaero zip, its file name and
dimension, the layer, the file's modified time, and your token. Nothing about
where you are or what you were doing. Nothing is sent in the background unless
you've switched live upload on.

### Blackzones

A blackzone is an area (dimension plus region bounds) that is **never**
uploaded until you remove it. It's local only - never sent anywhere, and nothing
in an upload hints that one exists. It's per server (layer), so two servers can
reuse the same coordinates without clashing.

Add one from the map (right-click), automatically when live upload catches a far teleport, or
`/argus blackzone add <id> <dimension> <minX> <minZ> <maxX> <maxZ> [label]`
(region numbers - the numbers in a Xaero filename like `3_-1.zip`). The GUI's
**Blackzones** tab lists them, and `/argus blackzone list` does too.

Removing one is never a single click: the Blackzones tab's **Remove** button, the
map's **Remove ARGUS Blackzone** option and `/argus blackzone remove <id>` all
open the same confirm popup naming what will stop being excluded. Removal only
makes the area eligible for the *next* upload; it uploads nothing by itself.
They're stored in `<config dir>/argus-mapper-blackzones.properties`.

### Distance limit

The mod never scans or uploads a region more than 200 regions from the origin on
either axis. A region is 512x512 blocks, so that's roughly +/-102,400 blocks in
every dimension. `/argus scan` reports how many regions it skipped for this.
[Whole-map upload](#automatic-uploads-live-and-whole-map) lifts the limit for a
session. (Earlier versions applied a 99,999 cap to the region number as if it
were a block coordinate, which is over 51 million blocks and limited nothing.)

### Nether highway privacy gate

One nether block equals eight overworld blocks, so off-highway nether terrain
gives away far more than the same overworld region. By default
(`restrictNetherToHighways=true`) a nether region is only uploaded if it comes
near a road on ARD's public highway network for the current server. This is a
coarse, region-level gate, not redaction inside a region - a region a highway
merely clips still uploads in full.

It **fails closed**: until ARD's road data has loaded for the server, every
nether region is excluded, and `/argus scan` tells you when that's the reason.
Overworld and end uploads are unaffected. On 26.2, where ARD isn't bundled, the
setting excludes *all* nether regions while it's on. Set
`restrictNetherToHighways=false` to disable it (not recommended).

### Your token

**Never put the token in source code, `fabric.mod.json` or anything committed to
git.** The mod reads it from `<minecraft config dir>/argus-mapper.properties`,
ships without one, and won't attempt an upload unless both a token and a layer
are set. Supply it with the GUI's **Token** tab, `/argus settoken <token>` (it's
typed into chat, so consider clearing `logs/latest.log` afterwards), or by
copying `config.example.properties` and filling it in by hand. `.gitignore`
excludes every `*.properties` file except that example. If a token has been
pasted anywhere outside your machine, treat it as exposed and ask ARGUS to
rotate it.

### Add-on API

Other Fabric mods can react to ARGUS Mapper through
`tools.argus.uploader.fabric.api.ArgusMapperEvents`:

```java
ArgusMapperEvents.UPLOAD_COMPLETED.register(summary ->
    log.info(summary.succeeded() + " regions uploaded"));
ArgusMapperEvents.STATS_CHANGED.register(stats ->
    hud.update(stats.distanceTraveledBlocks()));
```

Both fire on the client thread: `UPLOAD_COMPLETED` after each finished run, and
`STATS_CHANGED` after that and once at startup. They only carry upload counts and
aggregate stats - never a position.

It's on by default; turn it off with `enableAddonApi=false` or the GUI's API tab
and neither event ever fires. That is a switch on ARGUS Mapper's own two events,
not a sandbox: any mod in the same game can already read your position through
vanilla Minecraft, and this toggle can't change that.

## Reference

### Commands

| Command | What it does |
|---|---|
| `/argus gui` | Open the GUI. |
| `/argus scan [dimension]` | Dry run: what was found, what's pending, what was skipped and why. Run this before uploading. |
| `/argus upload [dimension]` | Start a rate-limited upload in the background. |
| `/argus status` / `/argus cancel` | Check or stop the current run. |
| `/argus live [on\|off\|resume]` | Live upload switch; no argument shows status. `on` asks first. |
| `/argus wholemap [on\|off]` | Whole-map switch; no argument shows status. `on` asks first. |
| `/argus blackzone add\|list\|remove` | Manage blackzones (`remove` asks first). |
| `/argus automap [start <minRX> <minRZ> <maxRX> <maxRZ>\|calibrate [speeds]\|stop\|status]` | Fly a box of the map for you, or a speed test flight (experimental). `start` and `calibrate` ask first. |
| `/argus bounty [on\|off\|refresh\|clear\|status\|go [2x\|nearest]]` | Mark the map's wanted regions on Xaero's World Map (off by default; fetches a public list while on); `go` flies to one and maps it, after asking. |
| `/argus settoken <token>` / `/argus setlayer <layer>` | Set those from in-game. |
| `/argus server` / `list` / `use <id>` / `add <id> <layer> <matches>` | Server auto-detect and the server list. |
| `/argus discord sethook <url>` / `test` / `report` | Discord webhook setup and stats. |
| `/argus config` / `/argus reload` | Show non-secret settings / re-read the config file. |

### Config file

`<minecraft config dir>/argus-mapper.properties`; see
`config.example.properties` for every field. The notable ones beyond
`apiBaseUrl`, `token` and `layer`:

- `xaeroRootOverride` - point at the exact Xaero folder if auto-detect picks the
  wrong one (see [How region files are found](#how-region-files-are-found)).
- `reuploadChangedRegions` - see above; off by default.
- `restrictNetherToHighways` - see above; on by default.
- `enableAddonApi` - see above; on by default.
- `discordWebhookUrl` / `autoReportToDiscord` - see [Discord](#discord).

Live and whole-map upload are deliberately **not** config options.

### Servers and layers

ARGUS takes a `layer`, not a server name, so a small registry
(`argus-mapper-servers.properties`) maps servers to layers. It ships with
`6b6t -> shallowplague`, matched by any address containing `6b6t`. On every join
the mod matches the address you connected with, and only speaks up for a real or
ambiguous match. Add a server with
`/argus server add <id> <layer> <matchSubstring1,matchSubstring2,...>`, or apply
one by hand with `/argus server use <id>`.

### Discord

`/argus discord sethook <url>` takes a webhook URL from a Discord channel's
Integrations settings - no bot, one HTTP POST per report. `/argus discord report`
posts now; `autoReportToDiscord=true` posts after every upload run with at least
one success. The embed shows **regions contributed**, **~chunks contributed**
(regions x 1024, approximate) and **distance traveled** (jumps over 50 blocks
are ignored so teleports don't inflate it; not available on 26.2 yet).

Discord Rich Presence isn't implemented: it needs a native IPC library and a
Discord Application ID. `enableRichPresence` and `discordApplicationId` are
reserved in the config so adding it later won't need a migration.

### Aquarius Road Department (ARD)

[Aquarius Road Department](https://github.com/aquariusnetwork9/Aquarius-Road-Department)
is a separate, privacy-first project for crowdsourced nether-highway conditions
(holes, lava, obstructions, campers). Its wire protocol has no `(x, z)` field at
all - only a position along a public road - so an off-highway coordinate can't be
expressed. Its Fabric client is bundled here as an independent feature set with
its own config (`ard.json`) and `/ard` commands. The only place it touches
ARGUS Mapper is the nether gate above, which reads ARD's public road data.

- **Hazard-ahead HUD** - on by default; a read-only lookup of public data.
- **Local hazard alert** - detects an obstruction in front of you with no
  network use, and fires a Fabric event other mods can hook.
- **Baritone auto-avoid** - opt-in (`/ard baritone on|off`); no hard dependency.
- **Report submission** - opt-in (`/ard reporting on|off`); contributes your own
  on-highway observations.
- **Account linking** - `/ard link`, then `/ard token <value>`.

Not in the 26.2 build: ARD has no 26.2 port yet, so there are no `/ard` commands
or HUD there. See ARD's own
[client README](https://github.com/aquariusnetwork9/Aquarius-Road-Department/blob/main/client-fabric/README.md)
for detail.

### How region files are found

Xaero's World Map saves each 512x512-block region as `<regionX>_<regionZ>.zip`
under a per-world folder (`xaero/world-map/<world>/...`). Nether is `DIM-1` and
the end is `DIM1`; anything else counts as the overworld. A `caves/` branch is
skipped unless `includeCaves=true`. Only these real `.zip` saves are uploaded -
Xaero's `.xwmc` render cache is ignored.

Xaero's folder naming isn't public and varies by version, so the mod does a
loose, case-insensitive match against your current server or world.
`/argus scan` always shows which folder it found; if it's wrong, set
`xaeroRootOverride` to the exact path.

### Upload rate limits

The server throttles what it receives, so the mod doesn't pace itself on a timer.
It keeps up to `uploadConcurrency` files (default 4, at most 8) going out at once
and starts the next region as soon as a file has been sent, without waiting for
the server to reply, with at most four times that many replies outstanding. As of
this writing the server allows 800 regions per user and 1500 overall, each per 10
minutes. The server pushes back in two ways:

- **"Server busy"** (the overall rate): the number of open requests is halved, new
  ones wait a moment, and the region is retried without using up its retries. The
  number climbs back as uploads succeed. You get one chat line the first time.
- **The per-user limit** (any other `429`): the run pauses and heals itself. Nothing
  is marked failed; every region goes back in the queue and you get a chat line
  saying it will resume. After 30 seconds it sends a single probe request, and
  then one every minute, until one gets through - then the run carries on at
  full speed. `/argus cancel` ends the wait at once. If the limit hasn't cleared
  after 30 minutes the run ends and the unsent regions wait for a later run. Only
  requests that belong to a run you started are ever sent while waiting.

A new `batchId` starts every `maxPerBatch` regions (default 200).
Already-uploaded regions are tracked in `<config dir>/argus-mapper-manifest.txt`,
so a big first upload can be split across sessions.

Each run also writes a log to `argus-mapper-upload-log/` in the game folder (the
newest 30 are kept): when every request started, how many were open at once, how
long the file took to send and the server to reply, the status and reply body,
rate-limit-looking response headers (all of them for the first few replies and for
any error), each pause and retry, and a summary with regions per minute and reply
time percentiles. The token and request headers are never written.

Every upload also carries `X-Region-Modified: <file modified time, UTC epoch
ms>`, which the API uses to keep the newest copy (the zip's own timestamp has no
time zone). The `batchId` prefix tells the modes apart: `run-`, `map-run-` (map
selection) and `live-run-`.

## For developers

### How the code is organised

Almost nothing here touches Minecraft internals - it reads zip files and makes
HTTP requests - so the logic is separated from the game glue:

- **`core/`** - plain Java with no Minecraft or Fabric dependency: scanning,
  the distance limit, blackzones, the upload client and runner, config, the
  server registry, the live-upload state machine, teleport detection, and the
  Discord client. Identical on every version and covered by the unit tests.
- **`fabric-common/`** - the shared Fabric adapter (mod init, the `/argus`
  command, the GUI, popups), compiled as source into the 1.21.x builds.
- **`1.21.4/`, `1.21.8/`, `1.21.11/`** - independent Fabric Loom subprojects
  that add `core/` and `fabric-common/` as source directories (no jar-in-jar).
  Each has small per-version forks where Minecraft's API differs (`gui-src/`,
  `ard-src/`).
- **`26.2/`** - a full port to 26.x's Mojang mappings. It has everything except
  ARD, a real nether-highway check, and the distance stat; see
  [26.2/NOTES.md](26.2/NOTES.md).

### Supported versions

6b6t kicks any client below 1.21.11, so **1.21.11 and 26.2** are the versions
that matter: CI builds them and a tagged release ships them. 1.21.4 and 1.21.8
stay in the repo and keep getting updates for other servers, but you build them
yourself (`gradle :1.21.4:build`) and they aren't released.

### Testing

`core/` has a JUnit 5 suite (`core/src/test/java`) covering the distance limit,
the scanner, config, manifest, blackzones, the live-upload rules, teleport
detection and the request headers - including that the upload client refuses an
out-of-range or blackzoned region *without making a network call*. The Fabric
layers are thin glue and are tested by hand in a running client; the checklist
is [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md).

### CI

`.github/workflows/build.yml` runs on every push and PR: the core unit tests,
a **1.21.11** build, and a **26.2** build (`continue-on-error` so it can't block
the 1.21.11 release). No Gradle wrapper is committed; CI installs Gradle
directly, and the foojay toolchain resolver in `settings.gradle` fetches
whichever JDK each module needs (17, 21 or 25). `fabric-loom` is only on
Fabric's own Maven, which `settings.gradle` declares under `pluginManagement`.

### Releasing

Pushing a `v*` tag (e.g. `git tag v0.2.0 && git push origin v0.2.0`) runs the
full build and test matrix and, if green, rebuilds the 1.21.11 and 26.2 jars
with the version taken from the tag and publishes a GitHub Release with
`gh release create`. A push to `main` only builds and tests. The `version` in
`gradle.properties` is just the fallback for local builds.

### Building locally

Each version folder is a standard Fabric Loom project. Open the repo in an IDE
with Gradle support, or install Gradle and run e.g. `gradle :1.21.11:build` from
the repo root. You need the JDK each `build.gradle` specifies (21 for the 1.21.x
line, 25 for 26.2), or let the foojay resolver download it.
