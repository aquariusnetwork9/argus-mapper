package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportChatTest {

    @Test
    void readsTheSecondsFromTheCountdownMessage() {
        assertEquals(15, TeleportChat.secondsUntilTeleport("Teleporting to base home in 15 seconds").getAsInt());
        assertEquals(3, TeleportChat.secondsUntilTeleport("Teleporting to X home in 3 seconds").getAsInt());
        assertEquals(1, TeleportChat.secondsUntilTeleport("teleporting to spawn in 1 second").getAsInt());
    }

    @Test
    void ignoresUnrelatedMessages() {
        assertTrue(TeleportChat.secondsUntilTeleport("You teleported to home").isEmpty());
        assertTrue(TeleportChat.secondsUntilTeleport("Teleporting to home").isEmpty());
        assertTrue(TeleportChat.secondsUntilTeleport("<Steve> see you in 5 seconds").isEmpty());
        assertTrue(TeleportChat.secondsUntilTeleport(null).isEmpty());
        assertTrue(TeleportChat.secondsUntilTeleport("").isEmpty());
    }
}
