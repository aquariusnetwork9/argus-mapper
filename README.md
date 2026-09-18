# ARGUS Mapper

A Fabric client mod, built by Aquarius Networks for the ARGUS spawn region
mapping project. It scans your Xaero's World Map region files for the
current world/server and uploads them to ARGUS's partner API
(`https://map.argus.tools/api/partner/upload`), respecting the API's rate
limits and batch size, and can report contribution stats (regions/chunks
mapped, distance traveled) to a Discord webhook.

**The easiest way to use it is the [GUI](#gui) (`/argus gui`, or bind a
key to it in Controls) plus the [Xaero's World Map integration](#xaeros-world-map-integration)**
(drag-select an area on the map and right-click it) - between the two,
every setting and every day-to-day action (setting your token, marking a
[blackzone](#blackzones), scoping an upload to exactly the area you want)
is a click away, with no command syntax to remember. The `/argus` chat
commands documented under [Commands](#commands) do the exact same things
underneath and are always available as a scriptable/always-on fallback
(and the only option before you've set a token), but for normal play the
GUI and the map are the more comfortable way in.

It also bundles
[Aquarius Road Department](https://github.com/aquariusnetwork9/Aquarius-Road-Department)
(ARD) - crowdsourced nether-highway condition reporting and a hazard-ahead
HUD - as a second, independent feature set in the same jar (see "Aquarius
Road Department" below).

## Design

Almost none of this mod touches Minecraft internals - it reads `.zip` files
off disk and makes HTTP requests. So it's split into:

- **`core/`** - plain Java, zero Minecraft/Fabric dependencies. Region
  scanning, dimension classification, the coordinate cap, the upload
  client, the rate-limited batch runner, config, the server registry, the
  Discord webhook client, and the "already uploaded" manifest all live
  here. This is the part that matters and it's identical on every
  Minecraft version - and the part covered by the test suite in
  `core/src/test/java` (see "Testing" below).
- **`fabric-common/`** - a thin Fabric client adapter (mod initializer,
  the `/argus` command, player-distance tracking) shared, as source,
  across the three 1.21.x builds.
- **`1.21.4/`, `1.21.8/`, `1.21.11/`** - each is a real, independently
  buildable Fabric Loom subproject. They don't depend on `core` as a
  compiled jar; they just add `core/src/main/java` and
  `fabric-common/src/main/java` as extra source directories, so there's
  only one copy of the logic to maintain and no jar-in-jar complexity.
- **`26.1/`** - **best-effort scaffold, not verified.** See
  [26.1/NOTES.md](26.1/NOTES.md) before relying on it. 26.1 is Fabric's
  first Mojang-mappings, non-obfuscated release (shipped 2026-03-14) and
  every existing mod needs at least a recompile against a completely
  different mapping set. This module has its own copy of the adapter code
  with the handful of vanilla-touching bits (auto-detecting the current
  server, distance tracking) stubbed out rather than guessed at.

## GUI

The recommended way to use this mod day-to-day. Open it with `/argus gui`,
or bind a key to it in Controls (unbound by default, listed under "ARGUS
Mapper") - either way it opens reliably and resizes itself to fit your
window on every 1.21.x version this mod supports. Seven tabs - General,
Token, Servers, Stats, Uploads, Road Dept. (ARD), and Add-on API - read and
write the exact same config objects the chat commands do, so there's no
separate GUI-only state to fall out of sync and nothing you can only do
from one side or the other. The **Uploads** tab is a torrent-style live
view of the current run (aggregate progress bar, queued/done/failed
counts, and how many were excluded by a blackzone), backed by a tracker
that lives on the background upload run itself - it keeps updating even if
you close and reopen the GUI mid-run, and survives a server
disconnect/switch, since the run doesn't stop until the game does.

Not available on [26.1](26.1/NOTES.md) (it shares that build's other
omissions - see that file). Available on **1.21.4**, **1.21.8**, and
**1.21.11** - 1.21.11 needed its own port of the GUI's widget and keybinding
code (`1.21.11/gui-src/`) since that version's `PressableWidget` changed
`onPress`/`renderWidget`/`drawIcon` from what 1.21.4/1.21.8 use, and its
`KeyBinding` category parameter became a `KeyBinding.Category` object
instead of a plain `String`. `/argus` and `/ard` chat commands are
unaffected on every version regardless of GUI availability.

Picked from three visual directions pitched up front (a vanilla-menu skin,
a floating utility-client skin, and an original watchtower-console skin) -
shipped as **Nightwire**, the utility-client one: dark panel, violet
accent, pill-style toggles. Minecraft can't load a custom font without
shipping a resource pack (not done for v1), so in-game text uses the
default Minecraft font rather than the concept mockup's; sliders also keep
vanilla's own groove/handle rendering rather than a fully custom one.

### Add-on API

Other Fabric mods can react to ARGUS Mapper without touching its
internals, via `tools.argus.uploader.fabric.api.ArgusMapperEvents` - the
same pattern ARD's own `LocalHazardEvents` uses:

```java
ArgusMapperEvents.UPLOAD_COMPLETED.register(summary ->
    log.info(summary.succeeded() + " regions uploaded"));
ArgusMapperEvents.STATS_CHANGED.register(stats ->
    hud.update(stats.distanceTraveledBlocks()));
```

Both fire on the client thread. `UPLOAD_COMPLETED` fires once per
`/argus upload` run; `STATS_CHANGED` fires after that and once at startup
with whatever was already on disk, so a fresh listener doesn't have to
wait for an upload to get an initial value.

## Xaero's World Map integration

The other easy way in: **1.21.11 only for now** (1.21.4/1.21.8 don't have
this yet - they still get everything else in this README, including the
full GUI above). Optional: the mod loads and works identically whether or
not Xaero's World Map is installed at all - a Mixin config plugin gates
the integration on `FabricLoader.isModLoaded("xaeroworldmap")`, so nothing
about the rest of the mod depends on it.

With Xaero's World Map installed, drag a selection on the fullscreen map
(Xaero's own native rectangle-select) and right-click inside it for two
extra options:

- **Mark ARGUS Blackzone** - saves the selection as a blackzone (see
  [Blackzones](#blackzones) below) covering the current dimension,
  immediately and without leaving the map.
- **Upload This Area to ARGUS** - scopes an upload to exactly the
  selection: a confirm popup shows the region count and approximate size
  before anything is sent, and only on confirmation does it start the
  same background run `/argus upload` does - so it shows up in the
  Uploads tab, gets the same Discord auto-report, and gets the same
  blackzone/coordinate-cap enforcement. Answering either way returns you
  to the map. Refuses (with a chat message, no popup) if a run is already
  in progress or the selection has nothing eligible to upload.

The map also colors each visible region tile so you can see this at a
glance instead of checking chat or the Uploads tab: a red outline for a
blackzone, translucent amber while it's uploading, translucent green once
it's uploaded (this run or a past one) - the same idea as mods like
NewerNewChunks coloring chunks by render version. Tracks pan and zoom, and
never blocks map interaction if something goes wrong internally - it just
stops drawing for that session rather than risking the map screen.

## Security: the API token

**Never put the bearer token in source code, `fabric.mod.json`, or anything
committed to git.** The mod reads it at runtime from a local properties file:

```
<minecraft config dir>/argus-mapper.properties
```

The mod never ships with a key baked in, and there is no way to make it
attempt an upload without one - `isUsable()` gates every network call on
both `token` and `layer` being non-blank. You can supply the token two ways:

- **In-game:** `/argus settoken <token>` and `/argus setlayer <layer>` (or
  `/argus server use <id>` to set the layer from a known server). This
  writes straight to the config file and is never echoed back in chat -
  but it was typed into local chat, so consider clearing
  `logs/latest.log` / your chat history afterwards.
- **By hand:** copy `config.example.properties` to the path above and fill
  it in yourself, on your own machine.

`.gitignore` excludes every `*.properties` file except the checked-in
`config.example.properties` template, so a `git add -A` won't accidentally
stage your real token.

If this token has already been pasted anywhere outside your own machine
(chat, ticket, etc.), treat it as exposed and ask ARGUS to rotate it -
scoping it out of a private conversation after the fact doesn't undo that.

## Coordinate cap

The mod can never scan or upload a region whose X or Z coordinate is 6
digits or more - magnitude is capped at 99,999 (`CoordLimits`), bounding
the whole mod to roughly a 200k x 200k block area centered on origin, in
every dimension. This is enforced twice, independently:

1. `XaeroScanner` never returns an out-of-range file in the first place -
   it isn't in the list `/argus scan` or `/argus upload` ever sees.
2. `ArgusUploadClient.upload()` - the only method that actually talks to
   the network - refuses an out-of-range region on its own, even if
   something else were to hand it one directly.

`/argus scan` reports how many region files were skipped for this reason.

## Nether highway privacy gate

Nether coordinates are worth more than overworld ones - 1 nether block equals
8 overworld blocks, so off-highway nether terrain hints at a proportionally
larger overworld area than the equivalent overworld region would. On top of
the coordinate cap above, `restrictNetherToHighways` (default **on**) adds a
second, nether-specific restriction: a nether region can only be uploaded if
it comes within [ARD](#aquarius-road-department-ard)'s reporting tolerance of
a road ARD's own protocol already treats as public, contested infrastructure
(PROTOCOL.md §3) - never an arbitrary off-highway area.

This is a coarse, **region-level** gate, not pixel-level redaction of a
region's interior - a 512-block Xaero region that a highway just clips at one
edge still uploads in full. Redacting the interior of Xaero's own tile format
would need actually decoding and re-encoding it, a much bigger undertaking;
this is the privacy-conservative middle ground between "upload everything"
and that.

How it decides, in `core/HighwayProximity.java` (fully unit tested) plus the
`fabric-common` glue that feeds it live geometry:

1. Convert the region's Xaero index to its real nether block bounding box
   (`regionX*512 .. regionX*512+511`, same for Z).
2. Fetch ARD's own live road table for the current server from its fully
   public `GET /geometry/<server>` route (PROTOCOL.md §7 - no token, and this
   mod maintains its own fetch independent of ARD's reporter/HUD modules, so
   it stays correct even if you have ARD's own reporting disabled).
3. Apply ARD's road-set policy (PROTOCOL.md §3) per road: an `axis` road is
   allowed everywhere; a ring/diamond/grid road only within ARD's
   near-spawn radius. Applied conservatively - a road that's ambiguous at
   this coarse per-road check is treated as *not* eligible, never the
   reverse.
4. Test whether the region's box comes within ARD's own reporting tolerance
   of any eligible road segment (exact line-vs-box geometry, not sampling).

**Fails closed:** if ARD's geometry hasn't loaded yet for the current server
(not yet fetched, server unrecognized, network hiccup), *every* nether region
is excluded until it has - never "allow through because we can't check yet."
`/argus scan` reports both how many regions this excluded and whether
geometry simply hasn't loaded. Overworld and end uploads are completely
unaffected either way. Set `restrictNetherToHighways=false` in the config to
disable this (not recommended).

The [26.1](26.1/NOTES.md) build does not bundle ARD (see below), so it has no
verified way to check highway-adjacency at all - there, this setting instead
excludes *all* nether regions outright while it's on, rather than silently
skipping the check.

## Blackzones

A blackzone is a user-declared rectangle (dimension + region-file bounds)
that is **never** uploaded, permanently, until you remove it yourself -
independent of the coordinate cap and the nether highway gate above, for
the areas you specifically don't want ARGUS to ever see rather than areas
excluded by a general policy. Local-only: a blackzone is never sent
anywhere, and nothing about *what* you've blackzoned - not even that a
blackzone exists - is visible in the uploaded data itself, since the
uploader simply never attempts those region files in the first place.

Enforced twice, independently, same pattern as the coordinate cap:

1. `UploadRunner`'s own filter excludes a blackzoned region before it's
   ever queued - it never shows up in the "to upload" count.
2. `ArgusUploadClient.upload()` - the only method that actually talks to
   the network - refuses a blackzoned region on its own, even if
   something else were to hand it one directly.

`/argus scan` and `/argus upload` both report how many regions were
excluded this way. Declare one two ways:

- **Chat:** `/argus blackzone add <id> <dimension> <minX> <minZ> <maxX>
  <maxZ> [label]` (region-file coordinates - the numbers in a Xaero
  filename like `3_-1.zip`, not block or chunk coordinates).
  `/argus blackzone list` / `/argus blackzone remove <id>` manage them.
- **On the map** (1.21.11 only for now): see
  [Xaero's World Map integration](#xaeros-world-map-integration) above.

A blackzone is scoped to the layer active when it was created (multiple
servers can reuse the same region coordinates without colliding), and
stored locally at `<config dir>/argus-mapper-blackzones.properties` -
excluded from `.gitignore`'s properties-file rule the same way the main
config is, so it's never accidentally committed.

## Config file (`argus-mapper.properties`)

See `config.example.properties` for the full set of fields with comments.
Beyond the API basics (`apiBaseUrl`, `token`, `layer`), notable ones:

- `xaeroRootOverride` - see "How region files are found" below.
- `discordWebhookUrl` / `autoReportToDiscord` - see "Discord" below.
- `discordApplicationId` / `enableRichPresence` - reserved, **not
  implemented yet**, see "Discord Rich Presence" below.

## Commands

- `/argus scan [dimension]` - dry run. Reports which Xaero root folder(s) it
  found, how many region files are pending per dimension, how many are
  already marked uploaded, how many are over the size limit, and how many
  were skipped by the coordinate cap. **Always run this before
  `/argus upload`.**
- `/argus upload [dimension]` - starts the rate-limited upload run in the
  background (does not block the game). Progress posts to chat periodically.
- `/argus status` / `/argus cancel` - check or stop an active run.
- `/argus reload` - re-reads the config file from disk.
- `/argus config` - prints the config file path and non-secret settings.
  Never prints the token.
- `/argus settoken <token>` / `/argus setlayer <layer>` - set those two
  fields from in-game (see "Security" above).
- `/argus server` - auto-detects the current server from its address and
  applies the matching profile's layer, if exactly one is known.
- `/argus server list` - lists registered server profiles.
- `/argus server use <id>` - manually apply a known profile's layer.
- `/argus server add <id> <layer> <matchSubstring1,matchSubstring2,...>` -
  register a new server (e.g. when ARGUS expands to another anarchy
  server), so future joins auto-detect it too.
- `/argus discord sethook <url>` - set the Discord webhook URL.
- `/argus discord test` - send a test message to confirm the webhook works.
- `/argus discord report` - manually post current stats (regions
  contributed, approximate chunks, distance traveled) to Discord.
- `/argus blackzone add <id> <dimension> <minX> <minZ> <maxX> <maxZ>
  [label]` / `/argus blackzone list` / `/argus blackzone remove <id>` -
  manage blackzones (see "Blackzones" below).
- `/argus gui` - opens the [GUI](#gui) (see above) - everything above also
  works as a screen, not just chat commands.

## How region files are found

Xaero's World Map stores each 512x512-block region as `<regionX>_<regionZ>.zip`
under a per-world folder - `xaero/world-map/<world>/...` on current versions,
`XaeroWorldMap/<world>_<dim>/...` on older ones. Dimension subfolders are
named `DIM-1` (nether) and `DIM1` (end); anything else defaults to overworld.
A `caves/` branch holds cave-mode regions and is skipped unless
`includeCaves=true`.

Xaero also keeps its own multi-zoom-level render cache alongside the real
`.zip` saves, as `.xwmc` (and `.xwmc.outdated` for a superseded entry) under
numbered `cache`/`cache_1`/`cache_2`/... subfolders - confirmed live against
a real 6b6t session, not guessed. That's a disposable rendering artifact,
never real region data (it doesn't get "promoted" into a `.zip`), and
`XaeroScanner` never treats it as such - a version of this mod briefly did,
on the mistaken assumption that Xaero had switched formats entirely, and
that caused a real upload to fail against ARGUS's own backend (HTTP 400
`render_failed`, since a `.xwmc` filename isn't a real region save at all).

**Caveat:** Xaero sanitizes server addresses / world names when building
these folder names, and the exact rule isn't public and varies by version.
Rather than guess it exactly, the scanner does a fuzzy (case-insensitive
"contains") match against your current world/server identifier, and
`/argus scan` always shows you what it found so you can catch a wrong match
and set `xaeroRootOverride` to the exact absolute path instead.

## Servers and layers (multi-server support)

The ARGUS API takes a `layer` parameter, not a server name, so this mod
maps "which anarchy server am I on" to "which layer do I upload to" via a
small registry (`argus-mapper-servers.properties`, alongside the main
config), seeded with one entry from the API example this mod was built
against: `6b6t -> shallowplague`, matched by any address containing
`6b6t`.

Auto-detection matches the address the client actually connected with -
for an anarchy server like 6b6t that's reached through one public hostname
(e.g. `play.6b6t.org`) routing to any of several backend addresses the
client never sees, that's fine, because Minecraft's client only ever
records the address as the player typed it, not whatever it got routed to
- so matching against known hostnames/domains works regardless of backend
routing. Auto-detection runs on every server join and only speaks up in
chat for a real match or an ambiguous one; joining an unrelated server
stays silent.

To add a second server once ARGUS expands: `/argus server add 2b2t
<layer-name> 2b2t,2builders2tools` (comma-separated match substrings).

## Aquarius Road Department (ARD)

[Aquarius Road Department](https://github.com/aquariusnetwork9/Aquarius-Road-Department)
is a separate, privacy-engineered project: crowdsourced nether-highway
condition reporting (holes, lava, obstructions, campers) whose entire wire
protocol is built so that **an off-highway coordinate is unrepresentable** -
there's no `(x, z)` field anywhere on the wire, only a 1-D
`(road, seg, along)` position re-derived from a public road table. See
[its PROTOCOL.md](https://github.com/aquariusnetwork9/Aquarius-Road-Department/blob/main/PROTOCOL.md)
for the full contract.

Its Fabric client (`client-fabric`) is bundled into this mod as a second,
fully independent feature set - own package
(`com.aquariusnetwork.highwayconditions`), own config file (`ard.json`), own
mod initializer, registered alongside `ArgusUploaderClientMod` in the same
`fabric.mod.json`. Neither module reaches into the other's internals; the
only place they touch at all is the nether privacy gate above, which uses
ARD's *public, unauthenticated* geometry read, not its reporter/HUD modules.
Ported as close to verbatim as possible from ARD's own repository - the
privacy-critical gate logic (`net/Geo.java`, `net/Report.java`, the
obstruction detector) is copied unchanged rather than reimplemented, exactly
as ARD's own README states it does between its own producers ("byte-for-byte
the same files").

**What it adds**, all commands under `/ard` (see ARD's own
[client-fabric/README.md](https://github.com/aquariusnetwork9/Aquarius-Road-Department/blob/main/client-fabric/README.md)
for full detail):

- **Hazard-ahead HUD** - on by default, a pure read against ARD's public
  `/conditions/<server>` route, zero privacy cost, independent of whether
  you report anything yourself.
- **Local hazard alert** - an always-on, zero-network detection of an
  obstruction right in front of you, and a public Fabric event
  (`api.LocalHazardEvents`) other mods (a Meteor addon, a Baritone add-on)
  can hook into directly.
- **Optional Baritone auto-avoid** - opt-in (`/ard baritone on|off`, off by
  default), zero compile/runtime dependency on Baritone (reflection-probed,
  degrades to "disabled" if Baritone isn't present or its API doesn't match).
- **Report submission** - opt-in (`/ard reporting on|off`, off by default):
  contributes your own on-highway observations back to ARD's network.
- **Account linking** (`/ard link` + `/ard token <value>`) - reach Tier B
  (PROTOCOL.md §6) via a device-code-style Discord link flow.

**Per-Minecraft-version differences**, matched to ARD's own documented API
research rather than guessed (see each fork's javadoc): the HUD registration
call (`HudRenderCallback` on 1.21.4 vs `HudElementRegistry` on 1.21.8/1.21.11),
the account-linking session-service accessor (`getSessionService()` on
1.21.4/1.21.8 vs `getApiServices().sessionService()` on 1.21.11), and the
`ClickEvent` construction shape (old `ClickEvent(Action, String)` on 1.21.4
vs the newer sealed `ClickEvent.OpenUrl(URI)` on 1.21.8/1.21.11). These three
files (`HighwayConditionsFabricClient.java` and
`command/HighwayConditionsCommand.java`) live per-version in each MC folder's
own `ard-src/`; everything else is one shared copy in `ard-common/`.

**Not included in the 26.1 build**: ARD itself has no 26.1 port, and porting
its considerably larger vanilla-API surface (HUD registration, the Mojang
session service, Baritone reflection) to that unverified toolchain would
compound 26.1's existing risk far more than this project's own thin adapter
layer already does (see [26.1/NOTES.md](26.1/NOTES.md)). 26.1 gets none of
the `/ard` commands or HUD, and its nether privacy gate falls back to
excluding all nether regions outright (see above) rather than checking
highway-adjacency it has no verified way to check.

## Discord

### Webhook reporting (implemented)

`/argus discord sethook <url>` takes a webhook URL from a Discord channel's
Integrations settings - no bot, no gateway connection, just one HTTP POST
per report. `/argus discord report` sends one immediately; setting
`autoReportToDiscord=true` sends one automatically after every upload run
that had at least one success. The embed includes:

- **Regions contributed** - exact, from the upload manifest.
- **~Chunks contributed** - `regions x 1024` (a 512x512 region is 32x32
  chunks); labeled "~" because this is a count of chunks *covered*, not
  chunks whose data actually changed, since that would mean unzipping and
  diffing every region.
- **Distance traveled** - accumulated every tick while the mod is loaded
  (any world, not just ARGUS-registered servers), with single-tick jumps
  over 50 blocks discarded so `/home`, `/tp`, etc. can't inflate it. Not
  reset between sessions.

### Discord Rich Presence (not yet implemented)

This needs two things I didn't want to build blind:

1. **A real IPC library.** Discord Rich Presence talks to the local
   Discord client over a native IPC socket, not HTTP - it isn't something
   to hand-roll safely alongside a "harden and test before deployment"
   pass. The usual choice in the Minecraft modding space is
   [`jagrosh/DiscordIPC`](https://github.com/jagrosh/DiscordIPC), but it's
   distributed via JitPack, not Maven Central, and I did not verify its
   current coordinates/version live - pin it yourself, or say the word and
   I'll verify and wire it in as a follow-up.
2. **A Discord Application ID.** Rich Presence is tied to a Discord
   "Application" - create one for ARGUS at
   https://discord.com/developers/applications, copy its Application ID,
   and put it in `discordApplicationId`.

`enableRichPresence` and `discordApplicationId` already exist in the config
so adding this later doesn't need another config migration.

## Rate limiting

The API allows 200 requests / 10 min and asks for ~1 region every 3s, and
200 regions per `batchId`. The runner sends strictly one request at a time,
waiting `paceMillis` (default 3000ms, measured from the end of one response
to the start of the next) before the next send - 200 requests at a 3s
cadence is exactly 600s, so normal pacing alone keeps you inside both limits.
A fresh `batchId` is generated automatically every `maxPerBatch` regions.

Already-uploaded regions (tracked per dimension+filename in
`<config dir>/argus-mapper-manifest.txt`) are skipped on subsequent runs,
so a big first export can safely be split across multiple `/argus upload`
invocations/sessions.

## Testing

`core/` has a real JUnit 5 test suite (`core/src/test/java`) covering the
coordinate cap, dimension classification, the region scanner (including
the caves and out-of-range-coordinate exclusions), config round-tripping,
the upload manifest, the server registry and its address matching, the
Discord embed JSON builder, and - importantly - that
`ArgusUploadClient.upload()` refuses an out-of-range region *without
making a network call*, not just that the scanner filters it out
upstream. `fabric-common` and the version modules aren't unit tested
(they're thin Minecraft-API glue); coverage there is a manual, in-game
pass instead, since it needs a running client - see
[MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md) for the checklist covering
blackzones, the Uploads tab, and the Xaero map integration.

## CI

`.github/workflows/build.yml` runs on every push/PR:

- **Core unit tests** - actually executes the test suite above and
  uploads the report as a build artifact.
- **Build 1.21.4 / 1.21.8 / 1.21.11** - real builds, each uploading its mod
  jar as an artifact.
- **Build 26.1** - best-effort, `continue-on-error: true` so a failure
  here (expected until someone verifies the toolchain - see
  [26.1/NOTES.md](26.1/NOTES.md)) never blocks the real builds above.

No Gradle wrapper is committed - CI installs Gradle directly via
`gradle/actions/setup-gradle`'s `gradle-version` input, and
`org.gradle.toolchains.foojay-resolver-convention` (in `settings.gradle`)
lets Gradle auto-download whatever JDK each module's toolchain asks for
(17/21/25) instead of requiring them all pre-installed.

`fabric-loom` itself is published only on Fabric's own Maven
(`https://maven.fabricmc.net/`), not on the Gradle Plugin Portal - it needs
a `pluginManagement { repositories { ... } }` block in `settings.gradle`
naming that repository, or every build fails at configuration time with
"Plugin [id: 'fabric-loom' ...] was not found in any of the following
sources" no matter how correct the version number is. (This is what broke
CI outright from the first commit through 2026-09-14 - both prior pushes
show as failed runs in the Actions tab; nothing after that root cause was
ever actually verified against a real build until it was fixed.)

## Releasing

Pushing a `v*` tag (e.g. `git tag v0.2.0 && git push origin v0.2.0`) runs
the full build/test matrix against that commit and, if it's green, a
`release` job rebuilds the three real 1.21.x jars (and 26.1's, best-effort)
with `-Pversion=<tag without the leading v>` so each jar's own
`fabric.mod.json` reports the version it actually shipped under, then
publishes a GitHub Release with all of them attached via `gh release
create`. A plain push to `main` still only builds and tests - it never
publishes anything. `gradle.properties`' `version=0.1.0` is just the
fallback for a local/branch build; a release always overrides it from the
tag.

## Building locally

Each version subdirectory is a standard Fabric Loom project. Without a
committed wrapper, either open the repo root in an IDE with Gradle support
(it'll offer to set one up), or install Gradle yourself and run e.g.
`gradle :1.21.11:build` from the repo root. You'll need the JDK each
module's `build.gradle` specifies (21 for the 1.21.x line, 25 for 26.1) -
or let the foojay resolver plugin fetch it automatically.
