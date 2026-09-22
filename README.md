# ARGUS Mapper

A Fabric mod from Aquarius Networks for the ARGUS spawn-region mapping project.
It takes the map you've already explored in Xaero's World Map and shares it with
ARGUS - with plenty of tools to keep your own places private, and a few to help you
explore more of the map faster.

Works on **Minecraft 1.21.11 and 26.2**, the two versions 6b6t lets you join.

> ### Legacy versions: 1.21.4 and 1.21.8
>
> The 1.21.4 and 1.21.8 versions of this mod are **legacy, and completely out of
> band**. They are **not built, not released, not updated and not supported**. Nothing
> in 1.0 or any later release is made with them in mind, and there is no 1.0 build of
> them. Their folders remain in the repository only as a record; they are not kept
> working, and questions or bug reports about them will not be answered. If you play
> on 1.21.4 or 1.21.8, this release is not for you.

## What it does

### Upload your map in a few clicks
Open Xaero's fullscreen map, drag a box around what you want to share, right-click and
choose **Upload This Area to ARGUS**. A popup tells you how many regions and roughly how
many megabytes are about to go before anything is sent. Prefer the keyboard?
`/argus scan` shows exactly what would be uploaded and what would be skipped and why,
and `/argus upload` sends it. Everything is also available from the in-game screen (see
[The ARGUS screen](#the-argus-screen)).

Uploads are quick: several regions go out at once. If ARGUS is busy the mod eases off
by itself, and if you reach your personal limit (currently 800 regions per 10 minutes)
it waits, then carries on without you doing anything - nothing is lost or marked as
failed. You can stop a run at any time with `/argus cancel`.

### Live upload (opt-in)
Switch it on and the mod uploads what you explore while you play, in the background.
Every few minutes (a random 3 to 10) it sends the regions you've mapped since turning it
on. A region you're still exploring is held back until you've moved on for about three
minutes, so ARGUS gets finished regions rather than half-drawn ones. The random delay
means the timing of uploads never shows where you are right now.

Live upload is per session: it never saves itself as "on", turns off when you disconnect,
crash or restart, and always asks before starting.

### Teleport protection
Live upload watches for teleports (a jump of more than 128 blocks, or a change of
dimension - `/home` to a faraway base, a portal trip, respawning). Landing somewhere inside
the normal upload area changes nothing. Landing **outside** it - somewhere private,
probably - pauses live upload at once, cancels anything in progress, and drops a
protective blackzone 25 regions (about 12,800 blocks) in every direction around where you
landed, in the dimension you arrived in and its Overworld or Nether counterpart. Once you've settled, a popup lets you keep
it (**OK**), remove it (**Cancel**) or draw your own on Xaero's map (**Modify**), and then
asks whether to resume. The blackzone is there from the moment you land, even if you log
out before answering. Your position is only compared from moment to moment on your own
computer - it's never stored or sent anywhere.

### Blackzones
A blackzone is an area that is never uploaded, no matter what: your base, a stash, a
hideout. Blackzones stay on your computer - ARGUS never sees them and nothing in an upload
hints that one exists. Draw one on Xaero's map (drag, right-click, **Mark ARGUS
Blackzone**), let teleport protection add one, or use `/argus blackzone add`. Removing one
always asks first and names what will stop being excluded.

### Distance limit and whole-map upload
By default nothing more than 200 regions (about 100,000 blocks) from the world origin is
ever uploaded. **Whole-map upload** lifts that limit for one session - handy for
far-away bases you actually want to share. Blackzones still apply, and it switches
itself off when you disconnect.

### Nether privacy
One block in the Nether covers eight in the Overworld, so an unrestricted Nether map gives
away much more than the same Overworld region. By default the mod only uploads Nether
regions that lie along the public highway network (from Aquarius Road Department). If
the highway data hasn't loaded yet it plays safe and uploads no Nether regions, and
`/argus scan` tells you when that's why.

### Re-upload changed regions (opt-in)
Normally a region is uploaded once. Turn on **Re-upload regions that changed** and any
region you've explored more of since is sent again, replacing the older copy on ARGUS.
Blackzones, the distance limit and the size limit still apply. Live upload always does
this for regions saved during the session.

### Auto-map (experimental)
Let the mod fly the map for you. Drag a box on Xaero's map, right-click, choose
**Auto-Map This Area (ARGUS)**, and confirm. It flies back-and-forth lanes over the box
with Meteor Client's Elytra Fly, at a steady height well above build height (Y 475 by
default), so Xaero records every chunk.

- **Smart about speed.** Xaero draws a narrower strip the faster you fly, so the mod
  flies at the speed that maps the most ground per second - around 25 blocks per second
  on 6b6t - and spaces the lanes to match. If the map can't keep up it slows down; if the
  server pulls you back it remembers and stays slower.
- **Fills the gaps.** When the lanes are done it goes back for any chunk that was missed.
- **Safe.** Nothing in the area is uploaded until the flight ends, so a half-mapped region
  never replaces a fuller one. It stops by itself if you teleport, change dimension, take
  damage, stop gliding, run low on elytra, or switch Elytra Fly off. `/argus automap stop`
  ends it at any time, and your Meteor speed setting is put back.

You need Meteor Client's **Elytra Fly** switched on in **Vanilla** mode and to be
gliding already (take off yourself). Overworld and End only.

There's also a **calibration flight** (`/argus automap calibrate`) that flies one
straight line at a series of speeds and writes a report showing how wide a strip gets
mapped at each - useful for tuning. Run it with and without XaeroPlus's Fast Mapping to
compare.

### Bounty regions
The ARGUS bounty works out which parts of the map it wants filled in next, closest to
spawn first, plus a special double-reward cell each day. Turn on **Bounty** and the mod
shows them on Xaero's World Map:

- Each wanted cell is drawn as an **aqua box**, and today's double-reward cell as a **gold
  box**.
- A marker named **ARGUS Bounty Region** sits in the middle of each one (the gold one is
  named **ARGUS Bounty Region (2x)**). They're temporary - Xaero never saves them - and
  only show on the world map, not the minimap or in the world.
- **Fly there and map it.** Right-click a selection over a box and choose **Fly to
  Bounty Region and Auto-Map**, press a button in the Bounty tab, or type
  `/argus bounty go`. You're asked to confirm, then the mod flies to the box's nearest
  corner at top speed, slows to mapping speed within a couple of seconds of arriving,
  and maps the whole box like an auto-map.

Bounty is **off by default**. While it's on, in the Overworld on a known server, the mod
asks ARGUS for the current list about every two minutes. That's a plain download of a
public list: no token, no name, no position - nothing about you is sent. It stays quiet
in the Nether and the End, on other servers and in single-player. Needs Xaero's Minimap
and World Map.

### Waystone teleports
Waystones are approved teleport points around the map. The **Waystones** tab lists them,
nearest first, along with your balance, whether ARGUS's delivery bot is ready, and its
cooldown. Each teleport costs one **Waystone token**, which you earn by uploading regions
(the tab shows how many regions earn one).

Click **Teleport** next to a waystone and confirm. ARGUS's bot then travels to that
waystone; the tab (and chat) keep you posted - where you are in the line, when the bot is
on its way. ARGUS names the exact bot delivering your teleport, so when it's ready at the
waystone, **the mod sends the `/tpa` request to it for you**, and it auto-accepts. You never
have to type anything.

The tab also has a **bot name** box, normally greyed out with the name ARGUS already gave -
you don't need to touch it, and ARGUS's own answer always wins over whatever's typed there.
It's a fallback for the rare case ARGUS hasn't named a bot yet, and a **Reset** button next
to it clears anything you typed. With neither ARGUS's name nor yours, the mod tells you when
the bot is ready and leaves the `/tpa` to you.

You can also **see the waystones on Xaero's World Map**. Turn on **Show waystones on the
Xaero map** and each waystone in the dimension you're in gets a purple marker named **ARGUS
Waystone: <name>** (temporary, world map only). Right-click a marker and choose
**Teleport to this Waystone (ARGUS)** for the same confirmation and the same teleport.

Good to know: you need to be on the ARGUS server with your token set, and to have set your
Minecraft username at map.argus.tools/my-tokens. Only one teleport can be in progress at a
time, counted across the mod and the website, and your balance is the same in both. If the
bot is offline the tab says so. `/argus waystone cancel` stops the mod watching a teleport
(it stays queued with ARGUS).

Nothing is fetched until you open the tab or turn on the map markers. Opening the tab
downloads the public waystone list and the bot's status and asks ARGUS for your balance
using your token; the markers only download the public list. Choosing a teleport sends your
token and the waystone you picked. The only thing the mod ever types into chat for this is
`/tpa` followed by the bot's player name.

### See what's happening
Xaero's map colours regions as you look at it: a **red outline** for a blackzone,
**amber** while a region is uploading, **green** once it's uploaded, and the aqua and
gold boxes for the bounty.

### The ARGUS screen
Open it with `/argus gui`, the **ARGUS Menu** button on the pause screen, or a key you
bind in Controls. Tabs: **General, Token, Servers, Blackzones, Stats, Uploads, Auto-map,
Bounty, Waystones, Road Dept.** and **API**. Everything in them does the same as the matching chat
command. The Uploads tab shows the current run live - a progress bar, how many are
done, waiting or failed - and holds the live and whole-map switches.

### Servers, stats and extras
- **Servers and layers.** ARGUS asks for a "layer" rather than a server name, so the mod
  keeps a small list of servers (6b6t is built in) and picks the right layer when you
  join. Add others with `/argus server add`.
- **Discord stats.** Point the mod at a Discord webhook and it can post how many regions
  you've contributed, roughly how many chunks, and how far you've travelled - on demand
  or after every upload run.
- **Aquarius Road Department (ARD).** The nether-highway tools from the separate
  [Aquarius Road Department](https://github.com/aquariusnetwork9/Aquarius-Road-Department)
  project come bundled: a hazard-ahead display, hazard alerts, optional Baritone
  auto-avoid and optional report submission (`/ard`). Not in the 26.2 build.
- **For other mods.** A small add-on API lets other Fabric mods hear when an upload
  finishes (counts and totals only, never a position). On by default; switch it off
  under the API tab or with `enableAddonApi=false`.

## Getting started

1. **Install** [Fabric](https://fabricmc.net/), Fabric API and - recommended -
   Xaero's World Map, then put the ARGUS Mapper jar in your `mods` folder. For auto-map
   and bounty flights you'll also want [Meteor Client](https://meteorclient.com); the
   bounty needs Xaero's Minimap as well.
2. **Get a partner token** from ARGUS.
3. **In game**, open the screen with `/argus gui` and paste the token on the **Token**
   tab. Joining 6b6t sets the upload layer for you; on another server use
   `/argus setlayer <layer>`.
4. **Upload**: drag-select on Xaero's map and right-click, or `/argus scan` then
   `/argus upload`.

## Privacy and safety

**What gets sent.** An upload contains the region's map file, its name, the dimension, the
layer, when the file was last changed, and your token - nothing about where you are or what
you were doing. Nothing is sent to ARGUS unless you start an upload or switch on live
upload. The optional bounty only downloads a public list and sends nothing about you.

**Your token.** It's stored on your computer, sent only with uploads, and hidden in the
**Token** tab unless you choose to reveal it. Paste it there rather than using
`/argus settoken`, since chat commands can end up in the game log. If a token is ever
exposed, ask ARGUS to replace it.

**Every risky switch asks first.** Live upload, whole-map upload, removing a blackzone,
auto-map and the bounty flight each have their own confirmation, and the two upload
switches are never saved.

## Commands

| Command | What it does |
|---|---|
| `/argus gui` | Open the ARGUS screen. |
| `/argus scan [dimension]` | Preview what would upload, and what would be skipped and why. |
| `/argus upload [dimension]` | Upload everything eligible. |
| `/argus status` / `/argus cancel` | Check or stop the current run. |
| `/argus live [on\|off\|resume]` | Live upload; with no argument, shows its status. |
| `/argus wholemap [on\|off]` | Whole-map upload; with no argument, shows its status. |
| `/argus blackzone add\|list\|remove` | Manage blackzones. |
| `/argus automap [start <minRX> <minRZ> <maxRX> <maxRZ>\|calibrate\|stop\|status]` | Fly a box of the map for you. |
| `/argus bounty [on\|off\|refresh\|clear\|status\|go [2x\|nearest]]` | Show the bounty on the map; `go` flies to one and maps it. |
| `/argus waystone [status\|refresh\|cancel]` | Waystone teleport status; the teleports themselves are started from the Waystones tab or the World Map. |
| `/argus settoken` / `/argus setlayer` | Set your token and layer. |
| `/argus server [list\|use\|add]` | Server detection and the server list. |
| `/argus discord sethook\|test\|report` | Discord stats. |
| `/argus config` / `/argus reload` | Show your settings / re-read the settings file. |

## Settings

Everything is in the GUI; the same options live in
`<minecraft config folder>/argus-mapper.properties` (see `config.example.properties`
for the full list). The ones people change:

- `uploadConcurrency` - how many files upload at once (1-8, default 4).
- `reuploadChangedRegions` - re-send regions you've explored more of (off).
- `restrictNetherToHighways` - the Nether privacy gate (on).
- `includeCaves` - also upload cave layers (off).
- `xaeroRootOverride` - point at the exact Xaero folder if the mod picks the wrong one.
- `autoMapCruiseY`, `autoMapMaxSpeed`, `autoMapSpeed` - height and speeds for flights.
- `bountyEnabled`, `bountyLimit` - the bounty switch and how many cells to show (50).
- `waystoneMarkers` - show waystones on Xaero's World Map (off); `waystoneBotName` - the bot
  name you typed on the Waystones tab, empty to use the one ARGUS gives;
  `waystoneTpaDelaySeconds` - the pause between the bot being ready and the `/tpa` (5).
- `enableAddonApi`, `discordWebhookUrl`, `autoReportToDiscord`.

Live and whole-map upload are deliberately not settings.

## Good to know

- **Where the map files come from.** Xaero saves each 512 x 512 block region as a file
  named like `3_-1.zip` in its own folder for each world. The mod finds the right folder
  for your current server; `/argus scan` always says which one it used, and
  `xaeroRootOverride` fixes a wrong guess. Only real region saves are sent - Xaero's
  temporary cache isn't.
- **Upload logs.** Each upload run writes a plain-text log to `argus-mapper-upload-log`
  in your game folder (the newest 30 are kept) showing how it went, including how fast
  ARGUS replied and any slowdowns. Your token is never written to it.
- **What's already uploaded** is remembered in `argus-mapper-manifest.txt`, so a big first
  upload can be split across sessions.
- **Minecraft 26.2** has everything above except auto-map, the calibration flight, the
  bounty, waystone teleports, the Road Department tools, the distance-travelled stat and the real
  Nether-highway check (it leaves out Nether regions while the Nether gate is on). See
  [26.2/NOTES.md](26.2/NOTES.md).

## For developers

- `core/` is plain Java with no Minecraft code (scanning, limits, blackzones, uploading,
  live upload, the bounty and auto-map logic) and has a JUnit test suite.
- `fabric-common/` is the shared game-facing code; `1.21.11/` and `26.2/` are the two
  Fabric Loom projects that ship. `1.21.4/` and `1.21.8/` are legacy (see above) and
  are not built anywhere.
- Build locally with Gradle, e.g. `gradle :1.21.11:build`; the JDK each module needs is
  fetched automatically. The manual checklist is [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md).
- CI (`.github/workflows/build.yml`) runs the core tests and builds 1.21.11 and 26.2 on every
  push. Pushing a tag such as `v1.0.0` publishes a GitHub Release with both jars.
