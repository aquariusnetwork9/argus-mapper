package tools.argus.uploader.core.waystone;

/**
 * Progress of a queued teleport: queued, homing (the bot is travelling), ready (the bot is at the
 * waystone), then delivered, failed or expired.
 *
 * @param position how many teleports are ahead of this one while queued
 * @param bot      the delivery bot's in-game name, if the backend says it; empty otherwise
 */
public record TeleportStatus(String status, String waystone, int position, String error, String bot) {

    public boolean isReady() {
        return "ready".equals(status);
    }

    public boolean isFinished() {
        return "delivered".equals(status) || "failed".equals(status) || "expired".equals(status);
    }
}
