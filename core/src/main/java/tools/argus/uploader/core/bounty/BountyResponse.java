package tools.argus.uploader.core.bounty;

import java.util.ArrayList;
import java.util.List;

/** What {@code needed-regions} returned: the nearest uncovered cells, closest first, and today's bounty cell if it sent one. */
public record BountyResponse(String dimension, List<BountyRegion> needed, BountyRegion bounty) {

    public BountyResponse {
        needed = List.copyOf(needed);
    }

    /** Everything worth marking: the needed cells, with the bounty cell flagged and added if the list left it out. */
    public List<BountyRegion> markers() {
        List<BountyRegion> out = new ArrayList<>();
        boolean bountyListed = false;
        for (BountyRegion region : needed) {
            if (bounty != null && region.sameBox(bounty)) {
                out.add(region.asBounty());
                bountyListed = true;
            } else {
                out.add(region);
            }
        }
        if (bounty != null && !bountyListed) {
            out.add(0, bounty);
        }
        return out;
    }
}
