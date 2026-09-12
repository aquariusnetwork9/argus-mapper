# ARGUS Mapper

A Fabric client mod, built by Aquarius Networks for the ARGUS spawn region
mapping project. It scans your Xaero's World Map region files for the
current world/server and uploads them to ARGUS's partner API
(`https://map.argus.tools/api/partner/upload`), respecting the API's rate
limits and batch size, and can report contribution stats (regions/chunks
mapped, distance traveled) to a Discord webhook.

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

## How region files are found

Xaero's World Map stores each 512x512-block region as `<regionX>_<regionZ>.zip`
under a per-world folder - `xaero/world-map/<world>/...` on current versions,
`XaeroWorldMap/<world>_<dim>/...` on older ones. Dimension subfolders are
named `DIM-1` (nether) and `DIM1` (end); anything else defaults to overworld.
A `caves/` branch holds cave-mode regions and is skipped unless
`includeCaves=true`.

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
(they're thin Minecraft-API glue); if you want coverage there it'd have to
be an in-game manual pass, since it needs a running client.

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

## Building locally

Each version subdirectory is a standard Fabric Loom project. Without a
committed wrapper, either open the repo root in an IDE with Gradle support
(it'll offer to set one up), or install Gradle yourself and run e.g.
`gradle :1.21.11:build` from the repo root. You'll need the JDK each
module's `build.gradle` specifies (21 for the 1.21.x line, 25 for 26.1) -
or let the foojay resolver plugin fetch it automatically.
