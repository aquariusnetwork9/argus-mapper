package tools.argus.uploader.fabric;

import net.minecraft.client.MinecraftClient;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.BlackzoneStore;
import tools.argus.uploader.core.RegionFile;
import tools.argus.uploader.core.XaeroRootFinder;
import tools.argus.uploader.core.XaeroScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The scan-and-filter pipeline (Xaero root discovery, {@link XaeroScanner}'s own coordinate-limit
 * and cave filtering, nether highway restriction, blackzone exclusion) shared by every upload
 * entry point - {@code /argus scan}, {@code /argus upload}, and the Xaero map-selection upload
 * trigger ({@link MapUploadTrigger}) - computed in exactly one place so a filter fix can't
 * silently apply to one entry point and not the other.
 */
final class RegionResolver {

    private RegionResolver() {
    }

    record Resolved(Path root, List<RegionFile> regions, int rejectedOutOfRange, int rejectedOffHighway,
                     boolean netherRestrictedButGeometryUnloaded, int rejectedByBlackzone, String error) {

        boolean isError() {
            return error != null;
        }

        private static Resolved error(String message) {
            return new Resolved(null, List.of(), 0, 0, false, 0, message);
        }
    }

    /** @param dimensionFilter one of "overworld"/"the_nether"/"theend", or null for all three */
    static Resolved resolve(String dimensionFilter) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            Path root;
            if (!config.xaeroRootOverride.isBlank()) {
                root = Path.of(config.xaeroRootOverride);
            } else {
                String token = GameWorldContext.currentWorldToken(client);
                List<Path> candidates = XaeroRootFinder.findCandidates(GameWorldContext.gameDir(), token);
                if (candidates.isEmpty()) {
                    return Resolved.error("No Xaero world-map folder found matching '" + token
                            + "'. Set xaeroRootOverride in the config to the exact folder and /argus reload.");
                }
                if (candidates.size() > 1) {
                    StringBuilder message = new StringBuilder("Multiple candidate folders found, set xaeroRootOverride to disambiguate:");
                    candidates.forEach(p -> message.append('\n').append("  ").append(p));
                    return Resolved.error(message.toString());
                }
                root = candidates.get(0);
            }
            XaeroScanner.ScanResult scanResult = XaeroScanner.scan(root, config.includeCaves);
            List<RegionFile> regions = scanResult.regions();
            if (dimensionFilter != null) {
                regions = regions.stream().filter(r -> r.dimension().equals(dimensionFilter)).toList();
            }

            int rejectedOffHighway = 0;
            boolean netherRestrictedButGeometryUnloaded = false;
            if (config.restrictNetherToHighways) {
                NetherHighwayFilter gate = ArgusUploaderClientMod.netherHighwayFilter();
                boolean anyNetherPresent = regions.stream().anyMatch(r -> r.dimension().equals("the_nether"));
                if (anyNetherPresent && !gate.isGeometryLoaded()) {
                    netherRestrictedButGeometryUnloaded = true;
                }
                List<RegionFile> filtered = new ArrayList<>();
                for (RegionFile r : regions) {
                    if (!r.dimension().equals("the_nether") || gate.regionAllowed(r)) {
                        filtered.add(r);
                    } else {
                        rejectedOffHighway++;
                    }
                }
                regions = filtered;
            }

            BlackzoneStore blackzones = ArgusUploaderClientMod.blackzoneStore();
            int rejectedByBlackzone = 0;
            List<RegionFile> afterBlackzones = new ArrayList<>();
            for (RegionFile r : regions) {
                if (blackzones.isBlackzoned(r.dimension(), config.layer, r)) {
                    rejectedByBlackzone++;
                } else {
                    afterBlackzones.add(r);
                }
            }
            regions = afterBlackzones;

            return new Resolved(root, regions, scanResult.rejectedOutOfRange(), rejectedOffHighway,
                    netherRestrictedButGeometryUnloaded, rejectedByBlackzone, null);
        } catch (IOException e) {
            return Resolved.error("Scan failed: " + e.getMessage());
        }
    }
}
