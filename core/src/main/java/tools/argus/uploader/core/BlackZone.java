package tools.argus.uploader.core;

/**
 * A player-declared area that {@link BlackzoneStore} enforces is never uploaded, in either
 * direction: {@link XaeroScanner} should never return a region file it covers, and
 * {@link ArgusUploadClient}/{@link UploadRunner} refuse to send one even if it slipped through -
 * same double-enforcement shape as {@link CoordLimits}, so a bug in one layer can't alone defeat
 * this.
 *
 * @param dimension one of "overworld", "the_nether", "theend"
 * @param layer     the ARGUS layer (server) this blackzone applies to - blackzones are
 *                  per-server, since the same region coordinates mean different physical
 *                  locations on different servers
 * @param label     a short human name the player chose (e.g. "main base"), shown back to them in
 *                  {@code /argus blackzone list} - never sent anywhere, purely local
 */
public record BlackZone(String id, String dimension, String layer, RegionBounds bounds, String label) {

    public boolean covers(String dimension, String layer, RegionFile region) {
        return this.dimension.equals(dimension) && this.layer.equals(layer) && bounds.contains(region);
    }
}
