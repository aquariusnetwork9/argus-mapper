# 26.1 module — best-effort, unverified

I could not compile-test this module. 26.1 (Fabric release 2026-03-14) is the
first Minecraft version Mojang ships unobfuscated, and Fabric dropped Yarn
for it — every class/method name a 1.21.x mod used through Yarn may now be a
different, Mojang-chosen name, and I have no way to check the actual 26.1
mappings from here. Confirmed facts (checked against Fabric's meta/version
APIs, not guessed):

- Fabric API for 26.1: `net.fabricmc.fabric-api:fabric-api:0.155.3+26.1.2`
- Fabric Loader: `0.19.5` (same as the 1.21.x line; loader itself isn't
  mapping-dependent)
- Minecraft: `26.1.2` (latest patch as of 2026-09)
- Java: 25 minimum, per Fabric's own migration post

**Unverified / needs your confirmation once opened in a real 26.1 dev env:**

- `build.gradle`'s `mappings` line uses `loom.officialMojangMappings()`,
  which is a real, long-standing Loom feature for Mojang-mapped projects —
  but I can't confirm it's what Fabric's own 26.1 template uses, or whether
  26.1 needs no mappings block at all since the game ships unobfuscated.
  Check Fabric's official 26.1 example mod template and copy its mappings
  line if it differs.
- Loom plugin version `1.18.0-alpha.21` — the newest listed on Fabric's
  maven at the time I checked, but it's an alpha and may have moved on.
- `GameWorldContext.currentWorldToken()` in this module is a stub that
  always returns `""` — I did not want to guess Mojang-mapped method names
  for "current server entry" / "save properties" and ship something
  confidently wrong. This only affects auto-detection of the Xaero folder;
  `/argus scan` and `/argus upload` still work fully via the
  `xaeroRootOverride` config setting regardless. Once you have the real
  mappings in your IDE, fill in the two calls — `fabric-common`'s
  `GameWorldContext.java` (used by the 1.21.x builds) shows the intent in
  Yarn names as a reference.

Everything else — `core/` (scanning, rate limiting, upload, config,
manifest) and the command tree in this module's `ArgusCommand.java` — uses
no vanilla Minecraft classes at all, only Fabric API and JDK, so it should
need no changes.
