package tools.argus.uploader.core.waystone;

/** One approved waystone teleport point. {@code name} is the id the backend wants back when requesting a teleport. */
public record Waystone(String name, String label, int x, int y, int z, String dimension) {

    public String displayName() {
        return label == null || label.isBlank() ? name : label;
    }

    /** "the_nether" as "Nether", for lists. */
    public String dimensionLabel() {
        return switch (dimension) {
            case "the_nether" -> "Nether";
            case "the_end" -> "End";
            default -> "Overworld";
        };
    }
}
