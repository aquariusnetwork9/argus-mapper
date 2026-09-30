# 26.2 module

Ported for real against Minecraft 26.2's actual Mojang mappings (javap against the real jars in
`ARGUS-Mapper-Test-26.2`/the Loom cache, not guessed) - not the 26.1 stub this module started as.
Working and live-tested: the full `/argus` command tree, blackzones, config, server registry,
stats, Discord reporting, on-join server auto-detect, Xaero World Map's right-click integration
("Mark ARGUS Blackzone" / "Upload This Area to ARGUS") plus its region overlay (blackzone
outline / uploading-amber / uploaded-green), the GUI (`/argus gui`), and the pause-menu "ARGUS
Menu" button.

Note: this file hasn't been re-verified against a real client launch for the waystone GUI tab and
`MixinWaypointReader` specifically (compiled and javap-checked only, same caveat as
`ArgusXaeroMixinPlugin`'s own javadoc) - the "working and live-tested" claim above doesn't yet cover
them.

Waystone teleports are now fully ported: `Waystones`, `WaystoneMarkers`, the GUI's Waystones tab,
the World Map waypoint menu item (`MixinWaypointReader`), and `/argus waystone`.

Bounty markers are now ported too: `Bounty`, `BountyMarkers`, `BountyOverlay`, the GUI's Bounty tab,
and `/argus bounty` (parsing/fetching is in core, unchanged). Only real touchpoints were
`World.OVERWORLD` -> `Level.OVERWORLD` (`ResourceKey<Level>`, same as `WaystoneMarkers`) and
`DrawContext` -> `GuiGraphicsExtractor` for `BountyOverlay` (`fill(int,int,int,int,int)` unchanged,
confirmed by `MixinGuiMapOverlay`'s existing use of it). `BountyOverlay.draw(...)` is now called
from `MixinGuiMapOverlay` for the overworld case, matching the 1.21.x builds' own
`MixinGuiMapOverlay` (it is *not* dead code upstream - it's called from that sibling mixin class,
not from `MixinGuiMap`; easy to miss since `MixinGuiMap` itself never references it).

`AutoMapper` is now ported too - the full feature: `/argus automap` (`status`/`stop`/`calibrate`/
`start`), the GUI's Auto-map tab, and the World Map right-click "Auto-Map This Area (ARGUS)" /
"Fly to Bounty Region and Auto-Map (ARGUS)" options in `MixinGuiMap`. `Bounty.requestFlight` now
calls `AutoMapper.requestBountyFromCommand(pick)` and returns `null` on success, matching
fabric-common exactly (the old stub message and its javadoc note are gone). The GUI's
`bountyFlight` helper was updated to match fabric-common's own null-handling: a `null` return from
`Bounty.requestFlight` means "queued, the confirm popup is coming next tick," so the helper shows
`AutoMapper.readinessProblem()`'s message (if any) instead, rather than treating `null` as "nothing
to show." `XaeroCoverage` (ported earlier, previously unused) is now exercised for real by
`AutoMapper`'s `covered`/`writtenRaw` calls into it.

`MeteorElytra` needed no porting at all - it lives in `core/` (`tools.argus.uploader.core.automap.
MeteorElytra`), shared by every module, and is pure reflection against Meteor Client's own runtime
classes (`Modules`, its settings map) with zero Minecraft-mapping surface. It isn't in
`fabric-common` (checked - only `core/`), so there was nothing fabric-common-specific to find or
port.

New Yarn -> Mojang facts this port needed, verified via javap against the real 26.2 client jar
(none of these were in this file before `AutoMapper`):

- **`Options` has no `forwardKey`/`jumpKey`/`sneakKey` fields at all** (checked the complete field
  list) - the real names are `keyUp` (forward), `keyJump`, and `keyShift` (sneak), still
  `KeyMapping`.
- **`KeyMapping` has no `setPressed(boolean)`** - Mojang's real name is `setDown(boolean)`. (There
  is also a static `KeyMapping.set(InputConstants.Key, boolean)`, which isn't the right tool here
  since AutoMapper drives specific mappings, not raw keys.)
- **`Options.getViewDistance()`/`getSimulationDistance()`/`getMaxFps()` don't exist** - the real
  accessors are `renderDistance()`/`simulationDistance()`/`framerateLimit()`, each returning
  `OptionInstance<Integer>`. **`OptionInstance<T>` has no `getValue()`** - it's `.get()`/`.set(T)`.
- `Heightmap.Type.MOTION_BLOCKING` -> `Heightmap.Types.MOTION_BLOCKING` (the enum is literally
  named `Types`, plural - confirmed on the class itself). `EquipmentSlot.CHEST` and `Items.ELYTRA`
  are unchanged.
- `LivingEntity.getEquippedStack(EquipmentSlot)` (Yarn) -> `LivingEntity.getItemBySlot
  (EquipmentSlot)` (inherited by `LocalPlayer`, not redeclared there). `ItemStack` has no
  `isOf(Item)` method here; comparing `stack.getItem() == Items.X` directly is what this port uses
  instead (no bare-`Item` overload of `ItemStack.is(...)` exists - only a
  `Predicate<Holder<Item>>` one).
- `ClientPlayerEntity.isGliding()` (Yarn) -> `Entity`/`LivingEntity`'s real name is
  `isFallFlying()`.
- `ClientWorld.isChunkLoaded(int, int)` -> `ClientLevel.hasChunk(int, int)`.
- `World.getTopY(Heightmap.Type, int, int)` -> `Level.getHeight(Heightmap.Types, int, int)` (same
  argument shape, new name).
- `ClientPlayNetworkHandler.getPlayerListEntry(UUID)` -> `Minecraft.getConnection()
  .getPlayerInfo(UUID)`; Mojang's `PlayerInfo` (Yarn's `PlayerListEntry`) keeps `getLatency()`
  unchanged.
- **`LevelRenderer.getChunksDebugString()` does not exist in 26.2 at all** - checked the complete
  method list; this version's `LevelRenderer` was rewritten around a frame-graph renderer (fits the
  Vulkan/`dynamicRendering` evidence already noted below). It's diagnostic-only text for the
  calibration report, so `AutoMapper` just reports it as empty rather than guessing at a
  replacement. `MinecraftClient.getCurrentFps()` -> `Minecraft.getFps()`, and Yarn's
  `worldRenderer` field is Mojang's public `Minecraft.levelRenderer`.
- `AutoUploads.dimensionOf(ClientLevel)` had to be widened from `private static` to package-private
  `static` (matching fabric-common's own visibility) so `AutoMapper`, in the same package, can call
  it. `AutoUploads.cancelLiveRun()` was missing from this module's `AutoUploads` entirely (not a
  mapping gap - it was simply never carried over during the earlier `AutoUploads` port) and has now
  been added, ported verbatim from fabric-common (no vanilla touchpoints of its own).

`PlayerDistanceTracker` (the "distance traveled" leaderboard stat) is now ported and wired into
`ArgusUploaderClientMod.onInitializeClient()` - only `Vec3d` -> `net.minecraft.world.phys.Vec3`
needed remapping (constructor and `distanceTo` unchanged), confirmed via javap against the real
26.2 client jar. `XaeroCoverage` is also ported (unchanged - pure reflection against Xaero's own
classes, no Minecraft mapping surface), and is now exercised by `AutoMapper`, its only consumer.

Also at parity with the 1.21.x builds: live and whole-map upload (`AutoUploads`; the open screen is
read from `Minecraft.gui.screen()`), and the confirm-popup blackzone removal (GUI Blackzones tab, the
map's "Remove ARGUS Blackzone" right-click option, and `/argus blackzone remove`) and the opt-in
`reuploadChangedRegions`. Two 26.2-specific facts for the removal popup: `ConfirmScreen` and
`Minecraft.setScreenAndShow` are unchanged from `MapUploadTrigger`'s use of them, but the current
screen is no longer a public `Minecraft` field (it's `Minecraft.gui.screen()`), so `BlackzoneRemoval`
returns to the game rather than reading it - see its javadoc.

Real facts, checked against the actual installed jars and Fabric's own reference project
(`FabricMC/fabric-docs`), not assumed by analogy to older Mojang-mapping conventions:

- Fabric Loom plugin id changed outright to `net.fabricmc.fabric-loom` (not just a version bump
  on the old `fabric-loom` id) - 26.x has no remap step at all, so it's a genuinely different
  plugin. No `mappings` line in `build.gradle` either; there's nothing to map against.
  `modImplementation`/`modCompileOnly` are gone too - every dependency uses plain
  `implementation`/`compileOnly`.
- `net.fabricmc.fabric.api.client.command.v2.ClientCommandManager` was renamed to `ClientCommands`
  (same `literal`/`argument` statics). `Text`/`Formatting` became `Component`/`ChatFormatting`
  (`net.minecraft.network.chat`/`net.minecraft.ChatFormatting`), `MinecraftClient` became
  `Minecraft`, `Screen`'s package pluralized to `net.minecraft.client.gui.screens`, and
  `setScreen(Screen)` became `setScreenAndShow(Screen)`.
- What Yarn (and 1.21.x's own port) calls `DrawContext` is
  `net.minecraft.client.gui.GuiGraphicsExtractor` here - not the classic Mojang "GuiGraphics" name
  from earlier eras. This one genuinely surprised me; don't assume it carries over to a future
  26.x release without checking again.
- Xaero's World Map own API (`GuiMap`'s `cameraX`/`cameraZ`/`scale`/`screenScale`,
  `mapTileSelection`, `mapProcessor`, `getRightClickOptions()`, `renderPreDropdown`) kept the exact
  same names it uses on 1.21.x - Xaero isn't part of Minecraft's own mappings either way, so the
  Yarn/Mojang switch doesn't touch it. Pinned to `fabric-26.2-1.46.1` to match what's actually
  installed in the test instance.
- `EditBox` (Yarn's `TextFieldWidget`) has no `setTextPredicate`/filter hook at all here - checked
  via javap against the real 26.2 client jar's full `EditBox` method list, nothing matching
  `Predicate`/`Filter` exists. The Waystones tab's bot-name field therefore can't block invalid
  keystrokes as fabric-common's does; it relies solely on `BotName.validate`'s regex, which already
  gated every actual `/tpa` use before this GUI tab existed, so this is a UX-only gap, not a safety
  one. `Font.plainSubstrByWidth(String, int)` is Mojang's equivalent of Yarn's
  `TextRenderer.trimToWidth(String, int)` (same argument order), used for the Waystones tab's
  status lines.
- Xaero's waypoint-menu API needed for the World Map right-click teleport option
  (`xaero.map.mods.gui.Waypoint`/`WaypointReader`, `xaero.map.gui.IRightClickableElement`,
  `xaero.map.gui.dropdown.rightclick.RightClickOption`) is unchanged from 1.21.11's own copy -
  confirmed via javap against the real xaeros-world-map-fabric-26.2-1.46.1.jar:
  `WaypointReader.getRightClickOptions(Waypoint, IRightClickableElement)` still returns
  `ArrayList<RightClickOption>`, `Waypoint.getOriginal()` still returns plain `Object`, and
  `RightClickOption.onAction` already takes `net.minecraft.client.gui.screens.Screen` (the
  Mojang-mapped type `Waystones.requestTeleport` takes), so `MixinWaypointReader` ported over with
  no signature changes beyond the vanilla import path.
- The GUI's widget hierarchy is a deeper rewrite than 1.21.11's already-unusual one, part of a
  wider "extract"-prefixed rendering API across this whole version (consistent with the Vulkan
  render-pipeline warnings in the log - `VK_KHR_synchronization2`/`dynamicRendering` - this isn't
  just a mapping-scheme swap): `PressableWidget`/`ClickableWidget` -> `AbstractButton`/
  `AbstractWidget`, the click-handler param `AbstractInput` -> `InputWithModifiers`, the custom-
  render override point `drawIcon`/`renderWidget` -> `extractContents`/`extractWidgetRenderState`,
  and `Screen.render(...)` itself -> `Screen.extractRenderState(...)`. `TextFieldWidget` ->
  `EditBox`, `SliderWidget` -> `AbstractSliderButton` (this one kept its exact shape - the
  `value` field and `updateMessage()`/`applyValue()` hooks are unchanged). `GameMenuScreen` ->
  `PauseScreen`, `ButtonWidget` -> `Button` (`dimensions()` -> `bounds()`), `KeyBindingHelper`/
  `KeyBinding` -> `KeyMappingHelper`/`KeyMapping` (via `fabric-key-mapping-api-v1`, not the older
  `fabric-key-binding-api-v1`), and `Identifier.of(ns, path)` is gone entirely in favor of
  `Identifier.fromNamespaceAndPath(ns, path)`.

## Deliberately still deferred

Only ARD itself, and the nether-highway check that depends on it, remain deferred - auto-map no
longer does.

- **Aquarius Road Department (ARD)**: no 26.2 port exists yet (see the root README's ARD section).
  Its own client code touches a larger vanilla surface (HUD registration, the Mojang session
  service, Baritone reflection) than anything ported here so far - this GUI has no Road Dept. tab
  as a result (10 tabs, not 11; see `ArgusGuiScreen`'s own javadoc).
- **Real nether-highway geometry check**: `NetherHighwayFilter` here is a stub, not the real
  ARD-backed one - it fails closed (excludes every nether region outright when
  `restrictNetherToHighways` is on) rather than doing the real per-region highway check, since the
  real filter depends on ARD's own networking classes. See its own javadoc.

Neither blocks real usage: everything else - scanning, uploading, blackzones, Xaero, the GUI, and
now auto-map - works fully without them.
