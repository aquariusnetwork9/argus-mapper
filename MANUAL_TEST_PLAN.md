# Manual test plan: blackzones, Uploads tab, Xaero map integration

Covers everything in [PR #1](https://github.com/aquariusnetwork9/argus-mapper/pull/1)
on this branch (`feature/blackzones-and-map-integration`) that could only be
compile-verified, not run in a real client, from the environment that built
it. Nothing here has been clicked through in a live game yet - that's what
this document is for. Tick the checkboxes as you go and commit the result
back to this branch so the PR reflects what's actually been verified.

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
- [ ] `/argus gui` opens the ARGUS panel; check the **Uploads** tab exists
      alongside the others.
- [ ] Visually check the panel isn't clipped or overflowing at its current
      width (widened 320px -> 360px on an estimate, not a real check - see
      `ArgusGuiScreen.PANEL_W`).

### 2. Loads cleanly *without* Xaero installed

- [ ] Remove/disable Xaero's World Map, relaunch. Client should still
      reach the main menu with no crash - `ArgusXaeroMixinPlugin` should
      gate the mixin off (`FabricLoader.isModLoaded("xaeroworldmap")` ->
      false) and the rest of the mod should be entirely unaffected.
- [ ] `/argus gui` still opens and works normally (minus anything
      Xaero-dependent, which there isn't - the map integration only adds
      right-click options on Xaero's own screen).

### 3. Blackzone CLI

- [ ] `/argus blackzone add test1 overworld 0 0 5 5 "test zone"` succeeds.
- [ ] `/argus blackzone list` shows it, with the current layer marked
      ACTIVE.
- [ ] `/argus scan` (with some region files present in range) reports a
      "skipped N region(s) covered by a blackzone" line if any fall inside
      `0 0` to `5 5`.
- [ ] `/argus blackzone remove test1` opens a confirm popup (nothing is
      removed yet); **No** leaves it in `/argus blackzone list`, **Yes**
      removes it and a re-scan no longer excludes that area.

### 4. Uploads tab live update

- [ ] Start a real (or fake-token) upload via `/argus upload`.
- [ ] Open `/argus gui` -> Uploads tab **while the run is in progress**.
      Aggregate progress bar and Queued/Done/Failed counts should update
      live, frame to frame, without needing to close/reopen the tab.
- [ ] Close the GUI screen (Esc) mid-run, reopen it - the tab should still
      reflect the run's current state (not reset), since it's backed by
      `UploadTracker` living on the background run, not the screen.

### 5. Xaero map integration - Mark Blackzone

- [ ] Open Xaero's World Map. Drag a selection (Xaero's native
      rectangle-select).
- [ ] Right-click inside the selection - confirm **both** "Upload This
      Area to ARGUS" and "Mark ARGUS Blackzone" appear in the menu.
- [ ] Click "Mark ARGUS Blackzone". Expect a chat message confirming the
      saved region-coordinate bounds and dimension.
- [ ] `/argus blackzone list` shows the new entry with an id like
      `map-<timestamp>`.

### 6. Xaero map integration - Select Area to Upload

- [ ] Drag a selection somewhere with real Xaero region data (i.e.
      somewhere you've actually explored/mapped, so the scan finds
      something).
- [ ] Right-click -> "Upload This Area to ARGUS". A confirm popup
      (`ConfirmScreen`) should appear over the map, stating a region count
      and approximate size.
- [ ] Click **No** - should return cleanly to the exact same map screen
      (same zoom/position), with a `[ARGUS]` chat message confirming
      nothing was uploaded.
- [ ] Repeat, click **Yes** - should see `[ARGUS]` chat feedback starting
      a real run (fails at the network step with a placeholder token,
      which is expected - see Config setup). Open the Uploads tab and
      confirm the run shows up there too.
- [ ] Try triggering "Upload This Area to ARGUS" over a selection that's
      entirely blackzoned or already-uploaded - should report "Nothing to
      upload in that selection" rather than opening a popup for zero
      regions.
- [ ] While a run from step above (or `/argus upload`) is still in
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

- [ ] Live re-check with a real slow/flaky connection: start an upload,
      `/argus cancel` mid-request, confirm `/argus status` flips to "No
      upload in progress" within a second or two rather than however long
      that one request would have taken.

### 8. Removing a blackzone (confirm popup on every path)

Add two blackzones first (e.g. one via the map, one via `/argus blackzone add`).

- [ ] GUI -> **Blackzones** tab lists both with dimension and region bounds.
      Click **Remove** on one: a popup names it and its bounds. **No** returns
      to the GUI with both still listed; **Yes** returns with one left and a
      `[ARGUS]` chat line confirming.
- [ ] With 5+ blackzones, the tab pages (`<` / `>`) and removing the only entry
      on the last page lands on a valid page rather than an empty one.
- [ ] Xaero map: select an area overlapping a blackzone, right-click - **Remove
      ARGUS Blackzone** is offered. Select an area overlapping none - it is
      not offered. Choosing it opens the same popup; **Yes** makes the red
      outline disappear from the map immediately.
- [ ] `/argus blackzone remove <id>` from chat: the popup opens (after chat
      closes), same **No**/**Yes** behavior. An unknown id reports an error
      and opens nothing.
- [ ] A removed blackzone's area is scanned/uploadable again; a blackzone you
      did not remove is still excluded.

### 9. Re-uploading changed regions (`reuploadChangedRegions`)

The ARGUS API replaces the stored region with a re-sent file, so this is safe
to run against the real API - but it does overwrite the region's stored copy, so
use a region you're happy to have replaced.

- [ ] Default config: after uploading a region, walk further into it so Xaero
      re-saves it, then `/argus scan` / `/argus upload` - it is still counted
      as "already uploaded" and not queued.
- [ ] Set `reuploadChangedRegions=true` (or the General-tab toggle), `/argus
      reload`. The first run after updating an older manifest baselines old
      entries and queues nothing extra; after walking further into an uploaded
      region, the next run queues exactly that region.
- [ ] Blackzone that region, change it again: it is excluded (counted under
      "excluded by blackzone"), not re-uploaded.
- [ ] `argus-mapper-manifest.txt` gains a `dimension|file<TAB>mtime` line for
      the re-upload; "Regions contributed" on the Stats tab does not go up.

### 10. Region distance limit and whole-map upload

- [ ] `/argus scan` with regions farther than 200 out on either axis: the
      "skipped N region(s) outside the +/-200 region limit" line appears and
      those files are not in the upload count.
- [ ] `/argus wholemap on` (or the Uploads-tab toggle) opens a popup saying the
      limit will be lifted. **Cancel** leaves it off (toggle snaps back);
      **Turn on** lifts it, and `/argus scan` no longer skips them.
- [ ] Blackzoned far regions are still excluded while it is on.
- [ ] Disconnect, and the switch is off again on rejoin. Same after a full
      restart.

### 11. Live upload

Use a scratch layer/token you are happy to upload to; the first cycle is 45-80
minutes after turning it on, so leave it running.

- [ ] `/argus live on` (or the toggle) opens its own popup; **Cancel** leaves it
      off. **Turn on** starts it and the Uploads tab shows "On - next upload in
      about N min". `/argus live` prints the same.
- [ ] After the delay, a `[ARGUS] Live upload: sending N region(s)` line appears
      and only regions saved since turning it on (new or changed) are sent.
      "nothing new to send" when nothing changed.
- [ ] Keep walking through a region until the cycle fires: that region is not
      sent ("holding back N still being mapped" / "held for next time"), and goes
      out on a later cycle once you have left it for 10 minutes.
- [ ] Disconnect: live upload is off on rejoin. Restart: off.
- [ ] `/argus live off` stops it and cancels a run in progress.

### 12. Live upload pauses on far teleports

The default area is 200 regions (about 102,400 blocks) each way, in every
dimension, even with whole-map upload on.

- [ ] With live on, teleport to somewhere inside the area (`/home`, a portal,
      `/tp`). Nothing pauses and no popup opens.
- [ ] Teleport to somewhere outside it (more than 102,400 blocks out). The
      Uploads tab shows "Paused - teleported outside the upload area" at once
      and a blackzone exists immediately (Blackzones tab: one for the dimension
      you're in and one for the Overworld/Nether counterpart, ids `auto-...`).
- [ ] After about 5 seconds of no further jump (not during the loading screen,
      not while another screen is open) the "Teleported outside the upload area"
      popup opens with **OK / Cancel / Modify**, and Esc does not close it.
- [ ] **OK** keeps the blackzones. **Cancel** removes them. Either way the
      resume popup follows.
- [ ] **Modify** opens Xaero's World Map (with Xaero not installed: a chat note
      and the blackzone is kept). Drag-select and **Mark Blackzone** works, and
      so does right-click on a blackzone and **Remove ARGUS Blackzone**. Closing
      the map opens the resume popup.
- [ ] The resume popup: **Stay paused** keeps it paused (`/argus live resume` or
      the Uploads-tab button resumes). **Resume** continues on a fresh random
      delay - nothing uploads right away.
- [ ] Teleport out of the area again while standing in a blackzone from the last
      time: no pause, no popup.
- [ ] Disconnect before answering: the blackzones are still there.
- [ ] Teleport while a live upload run is in flight (outside the area): the run
      is cancelled.
- [ ] Turn whole-map on and repeat the outside-the-area teleport: it still
      pauses.

### 13. Upload header

- [ ] Any upload's request carries `X-Region-Modified: <epoch ms>` equal to the
      file's modified time (check the ARGUS side or a local capture proxy).

## Known gaps going in (not yet fixed, just documented)

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

- [ ] Without Meteor, without Elytra Fly on, or while not gliding: the right-click
      option and `/argus automap start ...` explain what's missing instead of starting.
- [ ] Take off with Elytra Fly, drag-select a region on the map, right-click:
      **Auto-Map This Area (ARGUS)** opens a popup with the area, lane count and a
      time estimate. **Cancel** returns to the map; **Start** closes it and flies.
- [ ] It heads for the nearest corner, then flies lanes along the longer side at
      about Y 300, turning at the ends. Meteor's horizontal speed slider moves as it
      runs and is back at your value afterwards.
- [ ] Watch `/argus automap` (or the chat lines) while flying fast: speed drops when
      chunks either side of the lane are missing from the map, and creeps back up.
      Compare with the finished map: no unmapped stripes between lanes.
- [ ] A rubberband lowers the top speed for the rest of the run; a run at your
      known rubberband speed (5.99) should not keep tripping it.
- [ ] Afterwards it flies to and hovers over any gaps, then reports the percentage
      mapped and any chunks still missing.
- [ ] `/argus automap stop`, taking damage, `/home`, using a portal, switching
      Elytra Fly off, or disconnecting each end it with a message and release the keys.
- [ ] Over tall terrain (a mountain range higher than Y 300 is not needed - lower
      `autoMapCruiseY` to test): it climbs before reaching the slope instead of
      hitting it.
- [ ] With Xaero's world map not writing (or without Xaero) it says so and falls back
      to loaded chunks.

### 15. Auto-map calibration flight

Same setup as scenario 14 (Meteor Elytra Fly on in Vanilla mode, gliding). Fly over
ground you haven't mapped; the popup names the direction it chose and says if map
files already exist on that line.

- [ ] `/argus automap calibrate` opens a popup with the heading, length and speeds.
      **Start** flies straight, stepping through the speeds (chat shows nothing
      until the end; `/argus automap` shows the stage).
- [ ] At the end the chat names a folder under `argus-mapper-calibration/` and prints
      the suggested `autoMapWidthTable` and best speed. The folder holds
      `summary.txt`, `rows.csv` and `samples.csv`.
- [ ] `summary.txt` lists the environment (view distance, Xaero and XaeroPlus versions,
      Fast Mapping on/off) and, per speed, chunks across loaded / mapped@6 / mapped@20.
      Compare the widths with what the Xaero map shows along that line.
- [ ] Repeat with XaeroPlus Fast Mapping turned on (the folder name says which): it
      should show wider mapped strips or less lag between loaded and mapped.
- [ ] `/argus automap calibrate 1.25 2 3` runs just those speeds.
- [ ] Stopping early (`/argus automap stop`, damage, portal) still writes a report of
      the stages that finished.
