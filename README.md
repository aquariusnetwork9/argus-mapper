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
- **Teleport-aware.** Live upload pauses the moment you teleport (`/home`, a
  portal, respawning far away), cancels anything in flight, and once you've
  settled asks whether to blackzone where you landed before it resumes.
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
Servers, Blackzones, Stats, Uploads, Road Dept.** and **API**; everything in
them does exactly what the matching chat command does. (26.2 has no Road Dept.
tab because ARD isn't ported there yet.)

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
  their last upload; a changed region replaces its older copy. The delay is a
  compromise between streaming your path as it happens and uploading by hand, so
  upload timing never reveals where you are right now. Blackzones, the nether
  gate and the size limit all still apply.
- **Whole-map upload** (`/argus wholemap on|off`) - lifts the
  [distance limit](#distance-limit) for the session, by hand or with live
  upload. Blackzones still apply.

**Live upload pauses at once when you teleport** - the server's "Teleporting to
X in N seconds" message, a move of more than 128 blocks in one step, or a
dimension change (respawning far from where you died counts). Anything in flight
is cancelled. After you've arrived and settled (5 seconds with no further jump),
two popups follow: blackzone the area around you (3x3 regions)? then resume?
Nothing resumes until you confirm, and resuming starts a fresh random delay. The
popups only open when no other screen is showing. Your position is compared
tick to tick to spot a jump and is never stored or sent anywhere.

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

Add one from the map (right-click), the teleport prompt, or
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

The API allows 200 requests per 10 minutes, about one region every 3 seconds,
and 200 regions per `batchId`. The mod sends one request at a time, waiting
`paceMillis` (default 3000 ms) between them, which keeps you inside both limits,
and starts a new `batchId` every `maxPerBatch` regions. Already-uploaded regions
are tracked in `<config dir>/argus-mapper-manifest.txt`, so a big first upload
can be split across sessions.

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
