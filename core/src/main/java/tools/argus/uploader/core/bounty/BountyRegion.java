package tools.argus.uploader.core.bounty;

/**
 * One box the ARGUS map wants filled in, in block coordinates ({@code x1}/{@code z1} exclusive
 * edges as the server sends them). {@code bounty} marks today's double-token cell.
 */
public record BountyRegion(int cellX, int cellZ, int ring, int blockX0, int blockZ0, int blockX1, int blockZ1,
                           boolean bounty) {

    public int centerX() {
        return Math.floorDiv(blockX0 + blockX1, 2);
    }

    public int centerZ() {
        return Math.floorDiv(blockZ0 + blockZ1, 2);
    }

    public boolean sameBox(BountyRegion other) {
        return blockX0 == other.blockX0 && blockZ0 == other.blockZ0
                && blockX1 == other.blockX1 && blockZ1 == other.blockZ1;
    }

    public BountyRegion asBounty() {
        return bounty ? this : new BountyRegion(cellX, cellZ, ring, blockX0, blockZ0, blockX1, blockZ1, true);
    }
}
