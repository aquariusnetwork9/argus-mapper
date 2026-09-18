# 26.2 module

Ported for real against Minecraft 26.2's actual Mojang mappings (javap against the real jars in
`ARGUS-Mapper-Test-26.2`/the Loom cache, not guessed) - not the 26.1 stub this module started as.
Working and live-tested: the full `/argus` command tree, blackzones, config, server registry,
stats, Discord reporting, on-join server auto-detect, and Xaero World Map's right-click
integration ("Mark ARGUS Blackzone" / "Upload This Area to ARGUS") plus its region overlay
(blackzone outline / uploading-amber / uploaded-green).

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

## Deliberately still deferred

- **GUI** (`/argus gui`, the Xaero pause-menu button): `GuiLauncher` here is a no-op stub
  (`isAvailable()` returns `false`) - the real GUI reaches into a much larger vanilla widget/screen
  API surface than the Xaero integration did, and hasn't been verified against 26.2 yet.
- **Aquarius Road Department (ARD)**: no 26.2 port exists yet (see the root README's ARD section).
  Its own client code touches an even larger vanilla surface (HUD registration, the Mojang session
  service, Baritone reflection) than the GUI does.
- **Real nether-highway geometry check**: `NetherHighwayFilter` here is a stub, not the real
  ARD-backed one - it fails closed (excludes every nether region outright when
  `restrictNetherToHighways` is on) rather than doing the real per-region highway check, since the
  real filter depends on ARD's own networking classes. See its own javadoc.

None of these block real usage: `/argus scan`/`/argus upload`/blackzones/Xaero all work fully
without them.
