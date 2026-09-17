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

- [ ] Launch with the jar in `mods/` alongside Xaero's World Map. Client
      reaches the main menu with no crash, no "incompatible mod set"
      screen.
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
- [ ] `/argus blackzone remove test1` removes it; a re-scan no longer
      excludes that area.

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
