# 26.2 test session findings: XaeroPlus fast-mapping gap

Written 2026-09-30, for a future session investigating a private XaeroPlus implementation/fork or
modification to restore fast-mapping-at-speed. Context below is everything that session needs to
pick this up without re-deriving it.

## Background

This test session brought the `26.2` module of ARGUS Mapper to full feature parity with `1.21.11`
(Waystones, Bounty, AutoMapper - see `26.2/NOTES.md` for the full port history). AutoMapper flies a
box of the map by steering Meteor Client's Elytra Fly; a calibration flight
(`/argus automap calibrate <speeds>`) exists specifically to measure how many chunks actually get
mapped by Xaero's World Map at a range of flight speeds, and suggest a safe operating speed.

Test instance: `C:\Users\hellj\AppData\Roaming\PrismLauncher\instances\26.2`. Mod build under test:
`argus-mapper-26.2-1.0.0-dev.a008a87.2.jar` (dev build, not a tagged release - built from working-tree
changes on top of commit `a008a87`).

## What was tested and worked

- The mod loaded and ran in a live client against a real server (6b6t) with no crashes.
- `/argus automap calibrate` end-to-end flow: confirm popup, flight start, steering (yaw, forward/
  jump/sneak keys, Elytra Fly speed), all six safety-stop conditions available (damage, teleport,
  dimension change, not gliding, Elytra Fly off, disconnect, game closing - not all individually
  triggered in this session, but the code paths are unchanged from the already-shipped 1.21.x
  behavior; see `26.2/NOTES.md`), and calibration report writing to
  `argus-mapper-calibration/<timestamp>-fastmapping-<value>/` (`summary.txt`, `rows.csv`,
  `samples.csv`).
- A real bug was found and fixed during testing: the finish-message duration always displayed
  "1 min" even for flights under a minute (`Math.max(1, seconds/60)` in `AutoMapper.end()`, present
  identically in `fabric-common` and therefore all of 1.21.4/1.21.8/1.21.11 too, not a 26.2-specific
  bug). Fixed in both `26.2/src/main/java/tools/argus/uploader/fabric/AutoMapper.java` and
  `fabric-common/src/main/java/tools/argus/uploader/fabric/AutoMapper.java` by reusing the file's
  existing (already-correct) `formatDuration(double seconds)` helper instead of the raw
  `Math.max(1, ...)` line. Both modules recompiled clean after the fix.

## The open problem: chunk mapping collapses at higher flight speeds

Five calibration runs were captured under `argus-mapper-calibration/` on the test instance:

| Run | Outcome | Speeds tested | Result |
|---|---|---|---|
| `20260929-234139` | Stopped manually, 92s | 0.99, 1.25, 1.60 b/t (stages 1-3 only; 4-9 never reached) | Stages 1-3 mapped a healthy ~15-16 chunk wide band each. `autoMapWidthTable=0.99:17,1.25:15,1.60:15`, suggested best speed 5.95 b/t - **but this suggestion is an extrapolation artifact**, since stages 4-9 (2.0-5.99 b/t) never actually ran; see the next row for what really happens up there. |
| `20260929-234233`, `20260929-234434` | Not yet reviewed in depth | - | - |
| `20260929-234958` | Finished naturally, 30s | 6.20 b/t (single custom stage) | Only 1 stage completed in 30s; speed ramp-up dominated (measured speed oscillated 0.95-6.20 against a flat 6.20 target); not enough data for a width table. |
| `20260930-000444` | Finished naturally, 90s | 4.00, 4.61, 5.99 b/t (custom `/argus automap calibrate 4,4.61,5.99`) | **`mapped@6` and `mapped@20` were 0 at all three speeds**, despite `loaded` staying a healthy 33 chunks throughout. Rubberband count (`rb`) rose to 8-9 at the two fastest stages vs 1 at the slowest. Suggested table: `autoMapWidthTable=3.75:1,2.76:1,2.80:1`, best speed **1.00 b/t (20 b/s)** - i.e. the calibration logic itself is reporting that nothing faster than ~20 b/s reliably maps at all. |

**Read together**: chunks keep *loading* fine at high speed (vanilla/Fabric chunk loading isn't the
bottleneck), but Xaero's World Map stops *writing* those loaded chunks to disk/tiles somewhere
around the 20 b/s mark. Above that, `mapped@6`/`mapped@20` (chunks mapped within 6 or 20 ticks of
being loaded, in at least 90% of measured cells) drop to zero. This is the user's own observation
from watching the flight live: "it was a narrow, 3 chunk band at the higher speed" - now confirmed
numerically as actually *zero* reliably-mapped width at 55-75 b/s in the most targeted run.

## Root cause investigation: `[XP] Fast Mapping` does not exist in current XaeroPlus

`AutoMapper.environmentLines()` (in both `26.2` and `fabric-common`) reads three XaeroPlus config
keys from `config/xaeroplus.txt` as diagnostic context for the calibration report:

```
[XP] Fast Mapping
[XP] Fast Mapping Delay
[XP] Fast Mapping Rate Limit
```

All three reported **"unknown"** in every calibration run on this test instance. This was
investigated directly, not assumed:

1. Read `config/xaeroplus.txt` in full (137 lines) - confirmed **no `[XP] Fast Mapping*` key exists
   anywhere in the file** that XaeroPlus itself wrote out. Every other `[XP] ...` setting XaeroPlus
   has is present; these three specifically are absent, meaning XaeroPlus never registered them as
   settings.
2. The user updated XaeroPlus from 2.36.2 to 2.36.3 mid-session specifically to check whether the
   update restored this feature. It did not - confirmed by decompiling the new jar.
3. Extracted `xaeroplus/settings/Settings.class` from
   `XaeroPlus-2.36.3+fabric-26.2-WM1.46.1-MM26.5.1.jar` (the actual installed jar on the test
   instance) via `javap -p`. This is the class that defines every setting XaeroPlus exposes and
   therefore every key that can ever appear in `xaeroplus.txt`. **No field name resembling
   "fastMap", "mappingRate", "mappingDelay", or any mapping-speed-throttle concept exists in the
   full field list.** The closest-sounding fields are unrelated: `fastZipWrite` (a disk I/O
   optimization for writing zip region files, not a live mapping throttle) and
   `paletteNewChunksRescan`/`markChunksDirtyInWriteDistance` (chunk-highlight redraw logic).

**Conclusion**: "Fast Mapping" was very likely a real XaeroPlus feature at some point in the past
(exactly the kind of mapping-rate-limiter/accelerator a fast-flying auto-mapper would want), but it
does not exist in any form in the current 2.36.3 release. The diagnostic keys `AutoMapper` reads are
stale/dead and will permanently report "unknown" against any current XaeroPlus install - this isn't
a bug in ARGUS Mapper's reading logic (the file path and parsing are both correct - verified by
manually reading the same file), it's that the feature itself is gone upstream.

## What the next session should investigate

The user's request is to look into a **private implementation or modification of XaeroPlus** to
restore fast-mapping-at-speed behavior - i.e., something that makes Xaero's World Map keep writing
loaded chunks to tiles reliably at 50-75+ blocks/second, not just ~20 b/s. Starting points:

- Search XaeroPlus's git history / changelog (if accessible) for when "Fast Mapping" existed and was
  removed, and why - understanding the removal reason may reveal whether it was replaced by
  something else under a different name, deliberately dropped as unsafe/exploitable, or genuinely
  just dead code cleanup.
- Check whether XaeroWorldMap itself (not XaeroPlus) gained an equivalent setting - the two are
  separate mods (`xaeroworldmap-fabric-26.2-1.46.1.jar` vs `XaeroPlus-...jar` in this instance's
  `mods/` folder) and a feature could have moved between them.
- If no equivalent exists anywhere upstream, the private-fork path means understanding XaeroWorldMap's
  actual tile-write pipeline (likely in `xaero.map.MapProcessor` or similar - see
  `26.2/src/main/java/tools/argus/uploader/fabric/XaeroCoverage.java` and `WaystoneMarkers.java` for
  already-reverse-engineered reflection targets into Xaero's classes from this codebase) to find
  where the write rate is throttled or bottlenecked, and what a safe modification would look like.
- Any fix should be validated the same way this session validated the problem: run
  `/argus automap calibrate <speeds>` targeting the speed range of interest and read the resulting
  `mapped@6`/`mapped@20` columns in `rows.csv` - 0 means the fix didn't help, consistently >0.85-0.90
  at a given speed means it's working at that speed.
- Once a real fix (or a confirmed "no fix possible, X b/s is the real ceiling" conclusion) exists,
  `AutoMapper.environmentLines()`'s three dead `[XP] Fast Mapping*` config reads should be updated or
  removed to stop reporting stale "unknown" diagnostics - low priority, cosmetic only, not blocking.

## Files changed this session (uncommitted, on branch `feature/26.2-parity`)

See `26.2/NOTES.md` for the full list of files ported/changed to reach 26.2/1.21.11 parity. The one
change from this specific testing session on top of that: the `Math.max(1, ...)` duration-rounding
fix in both `26.2/src/main/java/tools/argus/uploader/fabric/AutoMapper.java` and
`fabric-common/src/main/java/tools/argus/uploader/fabric/AutoMapper.java`.
