package tools.argus.uploader.core.waystone;

/**
 * Whose token this is and how many teleports they can afford.
 *
 * @param regionsPerToken regions uploaded per Waystone token earned
 */
public record TokenInfo(String ign, int balance, int earned, int spent, int regions, int regionsPerToken) {
}
