package tools.argus.uploader.fabric;

import com.aquariusnetwork.highwayconditions.net.Geo;
import com.aquariusnetwork.highwayconditions.net.GeoCache;
import com.aquariusnetwork.highwayconditions.net.IngestClient;
import tools.argus.uploader.core.HighwayProximity;
import tools.argus.uploader.core.RegionFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gates ARGUS Mapper's own nether region uploads to areas near an ARD (Aquarius Road Department)
 * highway - see {@link HighwayProximity} for the geometry test this is built on. Maintains its
 * OWN {@link GeoCache}/{@link IngestClient} rather than reaching into ARD's own reporter/HUD
 * modules' instances: this filter must stay correct even if the user has ARD's reporting
 * disabled, or ARD hasn't ticked yet, or isn't installed for this MC target at all - it only
 * needs ARD's fully-public, unauthenticated {@code GET /geometry/<server>} read, the same route
 * ARD's own hazard HUD depends on (PROTOCOL.md §7).
 */
final class NetherHighwayFilter {

    private static final String INGEST_URL = "https://map.aquariusconnect.org";
    // ARD only publishes geometry for servers it actually has road data for (see this project's
    // README "geometry/*.nether_highways.json"); extend this map as ARD adds more, the same way
    // argus-mapper-servers.properties grows for new anarchy servers.
    private static final Map<String, String> KNOWN_ARD_SERVER_IDS = Map.of(
            "6b6t", "6b6t.org",
            "2b2t", "2b2t.org");

    private final GeoCache geoCache = new GeoCache();
    private final IngestClient client = new IngestClient(INGEST_URL, null);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "argus-nether-gate");
        t.setDaemon(true);
        return t;
    });

    static String ardServerIdFor(String argusServerName) {
        if (argusServerName == null) {
            return null;
        }
        return KNOWN_ARD_SERVER_IDS.get(argusServerName.toLowerCase());
    }

    /** Call from END_CLIENT_TICK every tick - drives the same fetch-with-retry cadence ARD's own
     *  GeoCache.poll uses, just against an independent instance/server-id resolution. */
    void tick(String ardServerId) {
        if (ardServerId == null || ardServerId.isBlank()) {
            return;
        }
        geoCache.poll(ardServerId, client, executor, g -> {}, ex -> {});
    }

    /**
     * Whether a nether region is allowed to be uploaded. Fails CLOSED whenever geometry hasn't
     * loaded yet for the current server - "can't verify this is highway-adjacent" must mean
     * "don't upload it", never "upload it anyway". See {@link HighwayProximity}'s own javadoc for
     * the eligibility/tolerance math this delegates to.
     */
    boolean regionAllowed(RegionFile region) {
        Geo g = geoCache.get();
        if (g == null || g.roads == null) {
            return false;
        }
        List<HighwayProximity.Segment> eligible = new ArrayList<>();
        for (Geo.Road road : g.roads) {
            if (road.segments == null) {
                continue;
            }
            if (!HighwayProximity.isRoadEligible(road.category, road.radius, g.nearSpawnRadius)) {
                continue;
            }
            for (int[] s : road.segments) {
                eligible.add(new HighwayProximity.Segment(s[0], s[1], s[2], s[3]));
            }
        }
        int minX = region.regionX() * 512;
        int minZ = region.regionZ() * 512;
        return HighwayProximity.boxNearAnySegment(minX, minZ, minX + 511, minZ + 511, eligible, g.tolerance);
    }

    /** True once geometry has successfully loaded at least once - lets callers distinguish
     *  "nothing excluded because nothing was near a highway" from "nothing excluded because
     *  geometry never loaded", for accurate /argus scan messaging. */
    boolean isGeometryLoaded() {
        return geoCache.get() != null;
    }

    void shutdown() {
        executor.shutdown();
        client.close();
    }
}
