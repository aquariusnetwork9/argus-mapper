package tools.argus.uploader.fabric;

import tools.argus.uploader.core.RegionFile;

/**
 * Stub, not the real ARD-backed filter: the real {@code NetherHighwayFilter} (fabric-common)
 * depends on Aquarius Road Department's own highway-geometry fetch ({@code
 * com.aquariusnetwork.highwayconditions.net.*}), and ARD itself is deliberately not ported to
 * 26.2 yet (see the root README and this module's own scope notes - Xaero came first).
 *
 * <p>Fails closed, not open: with no real geometry to check a region against, {@link
 * #regionAllowed} always returns false, which - via {@code RegionResolver}'s own loop - excludes
 * every nether region outright whenever {@code restrictNetherToHighways} is on, rather than
 * silently uploading nether regions this safety setting was meant to gate. Same fallback the old
 * 26.1 draft documented for this exact situation.
 */
final class NetherHighwayFilter {

    boolean isGeometryLoaded() {
        return false;
    }

    boolean regionAllowed(RegionFile region) {
        return false;
    }
}
