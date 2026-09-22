# Manual test plan: blackzones, Uploads tab, Xaero map integration

The by-hand checklist for everything the automated tests can't reach: the parts of the
mod that only run inside a real game client.

**Status for the 1.0.0 release: scenarios 1-20 have been run by hand in a real 1.21.11
client and pass (2026-09-21). Scenario 21 (waystone teleports) is new and not yet run.**

## Prerequisites

- **Minecraft 1.21.11**, **Fabric Loader >= 0.19.5** (the mod's
  `fabric.mod.json` enforces this - an older loader will refuse to load it
  with a version-mismatch screen, not a silent skip).
- **Fabric API** for 1.21.11, matching version (see any existing instance's
  `mods/` folder for a known-good one).
- **Xaero's World Map** for 1.21.11 - needed for the "Xaero map
  integration" scenarios below (Mark Blackzone / Upload This Area). The mod
  is built and tested to also load fine **without** Xaero installed (that's
  its own scenario, see below) - the map right-click options just won't
  exist in that case.
- Java 21+ to run Gradle.

## Building the jar

**No Gradle wrapper is committed in this repo** - you need your own Gradle
install. Gradle 9.7.1 is confirmed to work (via `fabric-loom:1.17.20`,
JDK 21 or 25). From the repo root:

```
gradle :1.21.11:remapJar
```

The output lands at `1.21.11/build/libs/1.21.11-0.1.0.jar` - copy that into
your test instance's `mods/` folder. (Do **not** hand-edit the
`fabricloader` version requirement inside the jar to work around an older
loader, the way a throwaway local test might - fix the instance's loader
version instead. See Prerequisites.)

## Config setup

The mod refuses to attempt any network call until both `token` and `layer`
are set (`ArgusConfig.isUsable()`). To test the full confirm-popup /
upload-trigger flow without a real ARGUS API token, any non-blank
placeholder works - the upload will fail with a network/auth error, which
is fine, since these scenarios are testing that the *trigger* fires
correctly, not that a real upload succeeds. Set via chat:

```
/argus settoken test-token
/argus setlayer test-layer
```

or by hand in `<instance>/config/argus-mapper.properties` before launch.

## Scenarios

### 1. Loads cleanly with Xaero installed

- [x] Launch with the jar in `mods/` alongside Xaero's World Map. Client
      reaches the main menu with no crash, no "incompatible mod set"
      screen.

      **Found a real crash here, now fixed.** First launch against the
      unmodified branch crashed at `Loading XaeroLib client 2/2!` -
      `crash-2026-09-17_02.48.47-client.txt`:
      `ExceptionInInitializerError` / `IllegalStateException: GameOptions
      has already been initialised`, from `GuiLauncher.<clinit>` ->
      `KeyBindingHelper.registerKeyBinding`. Cause: both `GuiLauncher`
      copies (`fabric-common` and 1.21.11's own) are only touched via the
      method reference `GuiLauncher::tick` passed to
      `ClientTickEvents.END_CLIENT_TICK.register(...)` in
      `ArgusUploaderClientMod.onInitializeClient()` - a method reference
      does not trigger class loading at registration time, so the static
      initializer (and the keybinding registration inside it) didn't run
      until the first end-of-tick callback fired, by which point
      `GameOptions` was already loaded and Fabric refuses new keybinding
      registrations. Once that throws, the JVM marks the class erroneous
      and every later touch throws `NoClassDefFoundError` for the rest of
      the session - so this wasn't just a first-tick blip, it permanently
      killed `/argus gui` and the Xaero right-click integration for the
      whole run. Fixed by forcing an eager `GuiLauncher.isAvailable()`
      touch inside `onInitializeClient()`, before the class is ever
      referenced lazily - confirmed by rebuilding and relaunching: the
      client now passes the same point with zero exceptions and reaches
      the main menu cleanly. This same lazy-reference pattern is shared
      code, so it likely also affects 1.21.4/1.21.8 even though neither
      has been run live yet (see their rows below) - the fix is in the
      shared `ArgusUploaderClientMod.java`, so it covers all three.
- [x] `/argus gui` opens the ARGUS panel; check the **Uploads** tab exists
      alongside the others.
- [x] Visually check the panel isn't clipped or overflowing at its current
      width (widened 320px -> 360px on an estimate, not a real check - see
      `ArgusGuiScreen.PANEL_W`).

### 2. Loads cleanly *without* Xaero installed

- [x] Remove/disable Xaero's World Map, relaunch. Client should still
      reach the main menu with no crash - `ArgusXaeroMixinPlugin` should
      gate the mixin off (`FabricLoader.isModLoaded("xaeroworldmap")` ->
      false) and the rest of the mod should be entirely unaffected.
- [x] `/argus gui` still opens and works normally (minus anything
      Xaero-dependent, which there isn't - the map integration only adds
      right-click options on Xaero's own screen).

### 3. Blackzone CLI

- [x] `/argus blackzone add test1 overworld 0 0 5 5 "test zone"` succeeds.
- [x] `/argus blackzone list` shows it, with the current layer marked
      ACTIVE.
- [x] `/argus scan` (with some region files present in range) reports a
      "skipped N region(s) covered by a blackzone" line if any fall inside
      `0 0` to `5 5`.
- [x] `/argus blackzone remove test1` opens a confirm popup (nothing is
      removed yet); **No** leaves it in `/argus blackzone list`, **Yes**
      removes it and a re-scan no longer excludes that area.

### 4. Uploads tab live update

- [x] Start a real (or fake-token) upload via `/argus upload`.
- [x] Open `/argus gui` -> Uploads tab **while the run is in progress**.
      Aggregate progress bar and Queued/Done/Failed counts should update
      live, frame to frame, without needing to close/reopen the tab.
- [x] Close the GUI screen (Esc) mid-run, reopen it - the tab should still
      reflect the run's current state (not reset), since it's backed by
      `UploadTracker` living on the background run, not the screen.

### 5. Xaero map integration - Mark Blackzone

- [x] Open Xaero's World Map. Drag a selection (Xaero's native
      rectangle-select).
- [x] Right-click inside the selection - confirm **both** "Upload This
      Area to ARGUS" and "Mark ARGUS Blackzone" appear in the menu.
- [x] Click "Mark ARGUS Blackzone". Expect a chat message confirming the
      saved region-coordinate bounds and dimension.
- [x] `/argus blackzone list` shows the new entry with an id like
      `map-<timestamp>`.

### 6. Xaero map integration - Select Area to Upload

- [x] Drag a selection somewhere with real Xaero region data (i.e.
      somewhere you've actually explored/mapped, so the scan finds
      something).
- [x] Right-click -> "Upload This Area to ARGUS". A confirm popup
      (`ConfirmScreen`) should appear over the map, stating a region count
      and approximate size.
- [x] Click **No** - should return cleanly to the exact same map screen
      (same zoom/position), with a `[ARGUS]` chat message confirming
      nothing was uploaded.
- [x] Repeat, click **Yes** - should see `[ARGUS]` chat feedback starting
      a real run (fails at the network step with a placeholder token,
      which is expected - see Config setup). Open the Uploads tab and
      confirm the run shows up there too.
- [x] Try triggering "Upload This Area to ARGUS" over a selection that's
      entirely blackzoned or already-uploaded - should report "Nothing to
      upload in that selection" rather than opening a popup for zero
      regions.
- [x] While a run from step above (or `/argus upload`) is still in
      progress, try "Upload This Area to ARGUS" again on a different
      selection - should refuse with "A run is already in progress"
      instead of starting a second concurrent run.

### 7. Xaero map integration - region overlay (blackzone/upload coloring)

`MixinGuiMapOverlay` colors each visible region tile on the fullscreen
world map: red outline for a blackzone, translucent amber fill while it's
uploading this run, translucent green fill once uploaded (this run or a
past one). 1.21.11 only, same as the rest of this integration.

- [x] Drag-select an area and "Mark ARGUS Blackzone" - a red outline
      should snap onto that exact region immediately, with no GUI reopen
      needed.

      **Confirmed live.** The hard part here was the world-to-screen pixel
      math: `GuiMap` (Xaero's own class) keeps `cameraX`/`cameraZ`/`scale`/
      `screenScale` privately and does the actual projection inline inside
      its own ~6,000-instruction `render` method, with no public helper
      and no decompiler available in this environment to read it as real
      source. Fitted the transform instead from real logged
      `(mouseX, mouseY)` <-> `(mouseBlockPosX, mouseBlockPosZ)` sample
      pairs across three different zoom levels (a temporary diagnostic
      build of this same Mixin class, since removed) before writing the
      real drawing code - `screenX = width/2 + (blockX - cameraX) *
      (scale/screenScale)`, same for Z/height. Also hit, and fixed, the
      same "literal method name doesn't match runtime bytecode" trap as
      the rest of this integration: `render` is inherited from vanilla
      `Screen`, so a production launch has it compiled under its Fabric
      intermediary name with no refmap to translate it (this mixins.json
      has none, since GuiMap isn't Yarn-mapped) - injecting into
      `renderPreDropdown` instead (Xaero's own method, not vanilla, so its
      literal name resolves directly) sidesteps this entirely and happens
      to land at the right point in the frame anyway: after the map's own
      tiles are drawn (so the fill isn't painted over) but before the
      right-click dropdown/tooltips (so those still render on top).
- [x] Zoom and pan the map with that blackzone still on screen - the
      outline should track exactly, never drifting or detaching.

      **Confirmed live** - same transform as above, verified panning and
      zooming through several levels.
- [x] Run `/argus upload` or "Upload This Area to ARGUS" over a
      non-blackzoned, non-uploaded area - the region(s) should flash
      amber while `UploadTracker` reports them QUEUED/UPLOADING, then
      settle to green once the tracker reports DONE.

      **Confirmed live against a real 6b6t session**, but this is also
      where a real, separate scanner bug surfaced: an earlier version of
      this fix let `XaeroScanner` match `.xwmc` render-cache files as if
      they were real regions (see its own comment on `REGION_FILE`), so
      the first live attempt tried to upload one and got HTTP 400
      `render_failed` from ARGUS's own backend, which correctly only
      accepts real `.zip` saves. Fixed by reverting the scanner to
      `.zip`-only - real region files (confirmed against the same 6b6t
      session's own `xaero/world-map/Multiplayer_6b6t.cc/null/mw$default/`
      folder) upload and complete normally, and the amber-to-green
      transition works as designed.
- [x] Restart the client after an upload completes and reopen the map -
      previously-uploaded regions should still show green, read back from
      `<config dir>/argus-mapper-manifest.txt` rather than only this
      session's tracker.

      **Confirmed live** once the `.zip`-only scanner fix above landed -
      `RegionOverlayState`'s manifest lookup uses the same `.zip` filename
      convention as everywhere else in this codebase, consistently.

**Also found during this same live pass, now fixed:** `/argus cancel` could
go unanswered for minutes. `UploadRunner` processes one region at a time on
a single background thread and only checked the cancellation flag *between*
attempts - if the in-flight HTTP call to ARGUS was slow (a flaky connection,
a slow response), nothing else on that thread could run, including noticing
a cancel request, until that call resolved or timed out (up to ~60s, times
up to `maxRetries` retries on a timeout). Confirmed live: `/argus cancel`
twice, a minute apart, while `/argus status` stayed at a fixed "5 / 8" the
whole time.

Fixed by making the request itself abortable: `ArgusUploadClient.upload`
now runs on `HttpClient.sendAsync`, and `UploadRunner` holds the resulting
`CompletableFuture` so `cancel()` can call `.cancel(true)` on whatever's
actually in flight, immediately, instead of only ever preventing the *next*
one. `UploadRunnerCancelTest` (core, real loopback `HttpServer` that never
responds) is a permanent regression test for this - it can only pass if
cancellation genuinely aborts the in-flight request, not just skips ahead.

- [x] Live re-check with a real slow/flaky connection: start an upload,
      `/argus cancel` mid-request, confirm `/argus status` flips to "No
      upload in progress" within a second or two rather than however long
      that one request would have taken.

### 8. Removing a blackzone (confirm popup on every path)

Add two blackzones first (e.g. one via the map, one via `/argus blackzone add`).

- [x] GUI -> **Blackzones** tab lists both with dimension and region bounds.
      Click **Remove** on one: a popup names it and its bounds. **No** returns
      to the GUI with both still listed; **Yes** returns with one left and a
      `[ARGUS]` chat line confirming.
- [x] With 5+ blackzones, the tab pages (`<` / `>`) and removing the only entry
      on the last page lands on a valid page rather than an empty one.
- [x] Xaero map: select an area overlapping a blackzone, right-click - **Remove
      ARGUS Blackzone** is offered. Select an area overlapping none - it is
      not offered. Choosing it opens the same popup; **Yes** makes the red
      outline disappear from the map immediately.
- [x] `/argus blackzone remove <id>` from chat: the popup opens (after chat
      closes), same **No**/**Yes** behavior. An unknown id reports an error
      and opens nothing.
- [x] A removed blackzone's area is scanned/uploadable again; a blackzone you
      did not remove is still excluded.

### 9. Re-uploading changed regions (`reuploadChangedRegions`)

The ARGUS API replaces the stored region with a re-sent file, so this is safe
to run against the real API - but it does overwrite the region's stored copy, so
use a region you're happy to have replaced.

- [x] Default config: after uploading a region, walk further into it so Xaero
      re-saves it, then `/argus scan` / `/argus upload` - it is still counted
      as "already uploaded" and not queued.
- [x] Set `reuploadChangedRegions=true` (or the General-tab toggle), `/argus
      reload`. The first run after updating an older manifest baselines old
      entries and queues nothing extra; after walking further into an uploaded
      region, the next run queues exactly that region.
- [x] Blackzone that region, change it again: it is excluded (counted under
      "excluded by blackzone"), not re-uploaded.
- [x] `argus-mapper-manifest.txt` gains a `dimension|file<TAB>mtime` line for
      the re-upload; "Regions contributed" on the Stats tab does not go up.

### 10. Region distance limit and whole-map upload

- [x] `/argus scan` with regions farther than 200 out on either axis: the
      "skipped N region(s) outside the +/-200 region limit" line appears and
      those files are not in the upload count.
- [x] `/argus wholemap on` (or the Uploads-tab toggle) opens a popup saying the
      limit will be lifted. **Cancel** leaves it off (toggle snaps back);
      **Turn on** lifts it, and `/argus scan` no longer skips them.
- [x] Blackzoned far regions are still excluded while it is on.
- [x] Disconnect, and the switch is off again on rejoin. Same after a full
      restart.

### 11. Live upload

Use a scratch layer/token you are happy to upload to; the first cycle is 3-10
minutes after turning it on, but a region only goes once its file has been untouched for
3 minutes, so map a little, move on, and leave it running for 10-15 minutes.

- [x] `/argus live on` (or the toggle) opens its own popup; **Cancel** leaves it
      off. **Turn on** starts it and the Uploads tab shows "On - next upload in
      about N min". `/argus live` prints the same.
- [x] After the delay, a `[ARGUS] Live upload: sending N region(s)` line appears
      and only regions saved since turning it on (new or changed) are sent.
      "nothing new to send" when nothing changed.
- [x] Keep walking through a region until the cycle fires: that region is not
      sent ("holding back N still being mapped" / "held for next time"), and goes
      out on a later cycle once you have left it for 3 minutes.
- [x] Disconnect: live upload is off on rejoin. Restart: off.
- [x] `/argus live off` stops it and cancels a run in progress.

### 12. Live upload pauses on far teleports

The default area is 200 regions (about 102,400 blocks) each way, in every
dimension, even with whole-map upload on.

- [x] With live on, teleport to somewhere inside the area (`/home`, a portal,
      `/tp`). Nothing pauses and no popup opens.
- [x] Teleport to somewhere outside it (more than 102,400 blocks out). The
      Uploads tab shows "Paused - teleported outside the upload area" at once
      and a blackzone exists immediately (Blackzones tab: one for the dimension
      you're in and one for the Overworld/Nether counterpart, ids `auto-...`).
- [x] After about 5 seconds of no further jump (not during the loading screen,
      not while another screen is open) the "Teleported outside the upload area"
      popup opens with **OK / Cancel / Modify**, and Esc does not close it.
- [x] **OK** keeps the blackzones. **Cancel** removes them. Either way the
      resume popup follows.
- [x] **Modify** opens Xaero's World Map (with Xaero not installed: a chat note
      and the blackzone is kept). Drag-select and **Mark Blackzone** works, and
      so does right-click on a blackzone and **Remove ARGUS Blackzone**. Closing
      the map opens the resume popup.
- [x] The resume popup: **Stay paused** keeps it paused (`/argus live resume` or
      the Uploads-tab button resumes). **Resume** continues on a fresh random
      delay - nothing uploads right away.
- [x] Teleport out of the area again while standing in a blackzone from the last
      time: no pause, no popup.
- [x] Disconnect before answering: the blackzones are still there.
- [x] Teleport while a live upload run is in flight (outside the area): the run
      is cancelled.
- [x] Turn whole-map on and repeat the outside-the-area teleport: it still
      pauses.

### 13. Upload header

- [x] Any upload's request carries `X-Region-Modified: <epoch ms>` equal to the
      file's modified time (check the ARGUS side or a local capture proxy).

## Notes from earlier rounds (all since confirmed by the scenarios above)

- The `ConfirmScreen` -> return-to-map round trip
  (`MapUploadTrigger.confirmAndUpload`) follows the same pattern vanilla
  screens use elsewhere (e.g. disconnect/backup prompts reopening their
  parent screen), but has never actually been clicked through - scenario 6
  is the first real check of it.
- `ArgusGuiScreen.PANEL_W` (360px) is a font-width estimate, not a visual
  check - scenario 1's last item is the first real look at it.
- The right-click option ordering (Upload above Blackzone) was an
  arbitrary call with no stated preference either way - flag if it should
  be swapped.

## If something's wrong

Note which scenario/checkbox failed and what actually happened (chat
output, a screenshot, `logs/latest.log` around the failure) rather than
just unchecking the box - that's what turns this into a bug report instead
of a dead end.

### 14. Auto-map (experimental)

Needs Meteor Client with Elytra Fly on in Vanilla mode, and an elytra. Use a small
box first (one or two regions) somewhere quiet.

- [x] Without Meteor, without Elytra Fly on, or while not gliding: the right-click
      option and `/argus automap start ...` explain what's missing instead of starting.
- [x] Take off with Elytra Fly, drag-select a region on the map, right-click:
      **Auto-Map This Area (ARGUS)** opens a popup with the area, lane count and a
      time estimate. **Cancel** returns to the map; **Start** closes it and flies.
- [x] It heads for the nearest corner, then flies lanes along the longer side at
      about Y 475, turning at the ends. Meteor's horizontal speed slider moves as it
      runs and is back at your value afterwards.
- [x] Watch `/argus automap` (or the chat lines) while flying fast: speed drops when
      chunks either side of the lane are missing from the map, and creeps back up.
      Compare with the finished map: no unmapped stripes between lanes.
- [x] A rubberband lowers the top speed for the rest of the run; a run at your
      known rubberband speed (5.99) should not keep tripping it.
- [x] Afterwards it flies to and hovers over any gaps, then reports the percentage
      mapped and any chunks still missing.
- [x] `/argus automap stop`, taking damage, `/home`, using a portal, switching
      Elytra Fly off, or disconnecting each end it with a message and release the keys.
- [x] Over tall terrain (lower `autoMapCruiseY` well below the terrain to test): it climbs before reaching the slope instead of
      hitting it.
- [x] With Xaero's world map not writing (or without Xaero) it says so and falls back
      to loaded chunks.

### 15. Auto-map calibration flight

Same setup as scenario 14 (Meteor Elytra Fly on in Vanilla mode, gliding). Fly over
ground you haven't mapped; the popup names the direction it chose and says if map
files already exist on that line.

- [x] `/argus automap calibrate` opens a popup with the heading, length and speeds.
      **Start** flies straight, stepping through the speeds (chat shows nothing
      until the end; `/argus automap` shows the stage).
- [x] At the end the chat names a folder under `argus-mapper-calibration/` and prints
      the suggested `autoMapWidthTable` and best speed. The folder holds
      `summary.txt`, `rows.csv` and `samples.csv`.
- [x] `summary.txt` lists the environment (view distance, Xaero and XaeroPlus versions,
      Fast Mapping on/off) and, per speed, chunks across loaded / mapped@6 / mapped@20.
      Compare the widths with what the Xaero map shows along that line.
- [x] Repeat with XaeroPlus Fast Mapping turned on (the folder name says which): it
      should show wider mapped strips or less lag between loaded and mapped.
- [x] `/argus automap calibrate 1.25 2 3` runs just those speeds.
- [x] Stopping early (`/argus automap stop`, damage, portal) still writes a report of
      the stages that finished.

### 16. Auto-map tab in the GUI

- [x] `/argus gui` has an **Auto-map** tab between Uploads and Road Dept.; the tab bar
      still fits, and the panel is taller on that tab only.
- [x] The two status lines update live: "Off" and "Not ready: Turn on Elytra Fly
      first." before you fly, "Ready: ..." once Elytra Fly is on and you're gliding,
      and the stage or percentage while a run is going.
- [x] **Use my region** fills all four area fields with your region. Start with bad
      or empty fields shows "Enter four whole numbers..." in red; with good ones it
      opens the same confirm popup as the map option (Cancel returns to the GUI).
- [x] **Stop** ends a run. **Run calibration** with the speeds field blank runs the
      defaults; with `1.25 2 3` runs those.
- [x] Changing the speed fields, altitude and lane-width sliders and the width table,
      then **Save**, keeps them after a restart (`config/argus-mapper.properties`);
      an unparseable speed leaves the old value in place.

### 17. Uploads held while auto-map runs

Use a scratch layer/token, with some regions in the box already mapped and not yet uploaded.

- [x] Start an auto-map of that box. The start message says nothing in the area is
      uploaded until it finishes, and the Auto-map tab's status ends "(uploads of the
      area held)".
- [x] While it flies, `/argus upload` (or the map's Upload This Area popup, or a live
      cycle if live is on) leaves those regions out and prints "N region(s) are still
      being auto-mapped, so they were left out". Regions well outside the box still go.
- [x] `/argus automap stop` (or a finished run): the message disappears and a manual
      upload includes the regions again.
- [x] Turning live upload on during a flight: its cycle skips the held regions and
      says so; after the run, the next cycle sends them once they have been quiet 3 minutes.
- [x] The same holds during a calibration flight, along its line.

### 18. Overlapping uploads

Use a scratch layer/token and a few dozen not-yet-uploaded regions.

- [x] `/argus upload`: several regions show as uploading at once in the Uploads tab
      ("Uploading: ... (+N more in flight)"), and the run finishes in a fraction of the
      old ~15 s per region. Note the total time for the run.
- [x] Set "Uploads at once" (GUI, General tab) or `uploadConcurrency` to 1 and repeat
      with another batch: it should be slower but still overlap the server's replies.
- [x] `/argus cancel` mid-run stops it within a few seconds and prints one
      "Upload run finished" line.
- [x] Every region that reported done is in `argus-mapper-manifest.txt`; nothing appears twice.
- [x] A wrong token stops the run once with the "check the token" message, not once per file.
- [x] A big enough backlog to hit "server busy": one chat line about slowing down, the log's
      PAUSE lines show the open limit halving and creeping back, and no region fails for it.
- [x] Hitting the per-user limit prints one "per-user upload limit was reached ... resumes by
      itself" line; nothing is sent for the next ~30 s, then one probe per minute (see the log's
      QUOTA lines); when it clears the run continues without you doing anything and no region
      fails. `/argus cancel` while waiting ends the run at once.
- [x] `argus-mapper-upload-log/` in the game folder has one `.log` per run; open the newest:
      START/REPLY lines with timings, the summary at the end (regions/min, reply times),
      and no token anywhere in it.

### 19. Bounty markers

Needs Xaero's Minimap and World Map, and a server in the server list (6b6t).

- [x] Fresh install / bounty off: nothing is fetched (no `argus-bounty` traffic), no waypoints.
- [x] `/argus bounty on` in the overworld on 6b6t: within a few seconds "ARGUS Bounty Region"
      waypoints appear on the World Map (up to `bountyLimit`, aqua), at the middle of each
      cell, and one gold "ARGUS Bounty Region (2x)". They are NOT on the minimap or in the world.
- [x] Open Xaero's waypoint menu: the waypoints are there, temporary (not saved: restart the
      game with bounty off and they're gone).
- [x] `/argus bounty` shows "N region(s) marked ... updated Ns ago"; the GUI's Bounty tab
      shows the same and its toggle, slider and buttons work (Refresh now, Clear markers).
- [x] Wait ~2 minutes: the status "updated" resets (a refresh happened) without the waypoints flickering.
- [x] Go to the nether: status says waiting (not in the overworld) and no requests are made;
      back in the overworld it refreshes.
- [x] Join a server that isn't in the list: it waits ("not on a known ARGUS server").
- [x] `/argus bounty off`: the waypoints disappear at once and nothing more is fetched.
- [x] With the network blocked: "Couldn't refresh ..., will retry", old markers stay, no errors in chat.

### 20. Bounty boxes and the bounty flight

Bounty on (scenario 19), Meteor Elytra Fly in Vanilla mode, an elytra, you in the overworld.

- [x] Open the World Map: each bounty cell has an aqua box (border + light wash), and today's
      2x cell is gold. They line up with the cells (1024 x 1024 blocks) and follow pan/zoom.
- [x] `/argus bounty off`: the boxes disappear at once.
- [x] Not gliding yet: `/argus bounty go` says to take off first. After taking off, it opens
      "Fly to this bounty region and map it?" with the distance, top speed and mapping speed.
- [x] No: nothing changes. Yes: the flight starts, Elytra Fly's horizontal speed goes to ~5.99
      and you head for the box's NEAREST corner; the status (`/argus automap`) says
      "Flying to the area: N blocks to go".
- [x] On reaching the corner the speed drops to ~1.25 (about 25 blocks/s) within about two
      seconds and it starts flying lanes over the box; the status changes to "Flying lanes".
- [x] A rubberband on the way in lowers only the transit speed, not the mapping speed.
- [x] `/argus automap stop`, teleporting, damage, or turning Elytra Fly off stops it, and Elytra
      Fly's own speed setting goes back to what it was.
- [x] The box's regions aren't uploaded until it finishes (see scenario 17).
- [x] World Map: select an area over a bounty box, right-click: "Fly to Bounty Region and
      Auto-Map (ARGUS)" appears, and only when the selection touches a box.
- [x] GUI Bounty tab: "Map the 2x cell" / "Map the nearest" open the same popup; with no
      bounty loaded they say so instead.

### 21. Waystone teleports

A token set, your Minecraft username set at map.argus.tools/my-tokens, on 6b6t, a few
Waystone tokens in your balance (see the Waystones tab), Xaero's Minimap and World Map.

- [ ] GUI Waystones tab: opening it shows your username and balance, the bot's status
      ("ready now" / "ready in Ns" / "offline") and the waystones nearest first, with their
      dimension, coordinates and distance. The bot's countdown ticks down between refreshes.
- [ ] Closing the tab stops the polling (no waystone/bot requests while it is closed and the
      map markers are off).
- [ ] "Show waystones on the Xaero map": purple "ARGUS Waystone: <name>" markers appear on the
      World Map for the waystones in your current dimension, only on the world map, and swap
      when you change dimension. Turning it off removes them.
- [ ] Right-click a waystone marker on the World Map: "Teleport to this Waystone (ARGUS)" is in
      its menu; ordinary waypoints don't have it.
- [ ] Clicking Teleport (tab or map) asks "Teleport to this waystone?" with the cost, your
      balance and the bot's status. No: nothing happens, no token spent.
- [ ] Yes: chat says the teleport is queued (balance drops by 1, on the website too). Progress
      lines appear as it moves through queued / travelling / ready.
- [ ] When the bot is ready the mod sends `/tpa <botname>` by itself (visible in chat), the bot
      accepts, and you arrive at the waystone; chat says "Delivered".
- [ ] Bot name box: leave it empty (the normal case - ARGUS names the bot itself). The name shows
      greyed out in the box and the line above says "Will /tpa: <name> (from ARGUS)".
- [ ] Type a different name in the box: the line still says "(from ARGUS)" and ARGUS's name is
      still what gets sent - ARGUS's own answer always outranks a typed one. **Reset** clears
      the box back to empty.
- [ ] Only with ARGUS unable to name a bot at all does a typed name get used, and the line then
      says "(set by you)".
- [ ] If ARGUS's roster ever lists several bots with none assigned to your teleport yet, the line
      says to type the one to use; if a teleport goes ready with genuinely no name known, the mod
      tells you to send the /tpa yourself (nothing is typed for you) - this should be rare now
      that ARGUS assigns a specific bot to every teleport.
- [ ] The box only accepts letters, digits and underscores (max 16).
- [ ] Errors read clearly: no balance ("You don't have a Waystone token"), a teleport already
      in progress, bot offline, no username set, wrong token.
- [ ] `/argus waystone` shows the state; `/argus waystone cancel` stops watching mid-teleport.
- [ ] Only `/tpa <name>` is ever sent to chat, and only once per request (no re-sends), after
      the `waystoneTpaDelaySeconds` pause (chat says "sending /tpa ... in a moment", then "Sent
      /tpa ...").
