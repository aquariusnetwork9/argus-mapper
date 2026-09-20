# 26.2 module

Ported for real against Minecraft 26.2's actual Mojang mappings (javap against the real jars in
`ARGUS-Mapper-Test-26.2`/the Loom cache, not guessed) - not the 26.1 stub this module started as.
Working and live-tested: the full `/argus` command tree, blackzones, config, server registry,
stats, Discord reporting, on-join server auto-detect, Xaero World Map's right-click integration
("Mark ARGUS Blackzone" / "Upload This Area to ARGUS") plus its region overlay (blackzone
outline / uploading-amber / uploaded-green), the GUI (`/argus gui`), and the pause-menu "ARGUS
Menu" button.

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

- **Aquarius Road Department (ARD)**: no 26.2 port exists yet (see the root README's ARD section).
  Its own client code touches a larger vanilla surface (HUD registration, the Mojang session
  service, Baritone reflection) than anything ported here so far - this GUI has no Road Dept. tab
  as a result (7 tabs, not 8; see `ArgusGuiScreen`'s own javadoc).
- **Real nether-highway geometry check**: `NetherHighwayFilter` here is a stub, not the real
  ARD-backed one - it fails closed (excludes every nether region outright when
  `restrictNetherToHighways` is on) rather than doing the real per-region highway check, since the
  real filter depends on ARD's own networking classes. See its own javadoc.

Neither blocks real usage: everything else - scanning, uploading, blackzones, Xaero, and now the
GUI - works fully without them.
