package tools.argus.uploader.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoUploadCoordinatorTest {

    private static final long MIN = AutoUploadCoordinator.MIN_DELAY_MILLIS;
    private static final long MAX = AutoUploadCoordinator.MAX_DELAY_MILLIS;
    private static final long SETTLE = AutoUploadCoordinator.SETTLE_MILLIS;

    private static final class FakeHost implements AutoUploadCoordinator.Host {
        boolean running;
        int cancels;
        final List<Long> cycleLiveSince = new ArrayList<>();
        final List<AutoUploadCoordinator.Teleport> prompts = new ArrayList<>();

        @Override
        public boolean isUploadRunning() {
            return running;
        }

        @Override
        public void startCycle(long liveSinceMillis) {
            cycleLiveSince.add(liveSinceMillis);
        }

        @Override
        public void cancelAutoRun() {
            cancels++;
        }

        @Override
        public void promptTeleport(AutoUploadCoordinator.Teleport teleport) {
            prompts.add(teleport);
        }
    }

    private FakeHost host;
    private AutoUploadCoordinator coordinator;

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        coordinator = new AutoUploadCoordinator(host, new Random(42));
    }

    private void jumpFarAway() {
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", 400, 64, 0, 150);
    }

    @Test
    void startsOffAndNothingEverRuns() {
        assertFalse(coordinator.isLive());
        coordinator.tick(10 * MAX);
        assertTrue(host.cycleLiveSince.isEmpty());
    }

    @Test
    void firstCycleWaitsAtLeastTheMinimumDelayAndNoMoreThanTheMaximum() {
        coordinator.setLive(true, 0);
        coordinator.tick(MIN - 1);
        assertTrue(host.cycleLiveSince.isEmpty(), "never before the minimum");
        coordinator.tick(MAX);
        assertEquals(1, host.cycleLiveSince.size(), "always by the maximum");
    }

    @Test
    void delaysAreRandomWithinTheRangeAcrossManyCycles() {
        coordinator.setLive(true, 0);
        long now = 0;
        long previousStart = 0;
        List<Long> gaps = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int before = host.cycleLiveSince.size();
            while (host.cycleLiveSince.size() == before) {
                now += 60_000L;
                coordinator.tick(now);
            }
            gaps.add(now - previousStart);
            previousStart = now;
        }
        for (long gap : gaps) {
            assertTrue(gap >= MIN && gap <= MAX + 60_000L, "gap " + gap);
        }
        assertTrue(gaps.stream().distinct().count() > 3, "delays should vary, not repeat one value");
    }

    @Test
    void cycleCarriesWhenLiveWasTurnedOn() {
        coordinator.setLive(true, 1_000);
        coordinator.tick(MAX + 1_000);
        assertEquals(List.of(1_000L), host.cycleLiveSince);
    }

    @Test
    void skipsACycleWhileAnUploadIsAlreadyRunning() {
        coordinator.setLive(true, 0);
        host.running = true;
        coordinator.tick(MAX);
        assertTrue(host.cycleLiveSince.isEmpty());
    }

    @Test
    void turningItOffStopsTheScheduleAndCancelsARun() {
        coordinator.setLive(true, 0);
        coordinator.setLive(false, 100);
        assertTrue(host.cancels > 0);
        coordinator.tick(10 * MAX);
        assertTrue(host.cycleLiveSince.isEmpty());
    }

    @Test
    void theServersTeleportCountdownPausesAtOnceAndCancelsTheRun() {
        coordinator.setLive(true, 0);
        int cancelsBefore = host.cancels;
        coordinator.onChatMessage("Teleporting to home in 15 seconds", 1_000);

        assertTrue(coordinator.isPaused());
        assertEquals(cancelsBefore + 1, host.cancels);
        coordinator.tick(10 * MAX - 1);
        assertTrue(host.cycleLiveSince.isEmpty(), "no upload while paused");
        assertTrue(host.prompts.isEmpty(), "the prompt waits for arrival");
    }

    @Test
    void arrivalAfterAnAnnouncedTeleportRaisesOnePrompt() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 500);
        coordinator.onChatMessage("Teleporting to home in 3 seconds", 1_000);
        coordinator.onPlayerPosition("overworld", 5_000, 70, 5_000, 4_000);
        assertTrue(host.prompts.isEmpty(), "not the moment it arrives");

        coordinator.onPlayerPosition("overworld", 5_003, 70, 5_002, 6_000);
        coordinator.tick(4_000 + SETTLE - 1);
        assertTrue(host.prompts.isEmpty(), "still settling");
        coordinator.tick(4_000 + SETTLE);

        assertEquals(1, host.prompts.size());
        AutoUploadCoordinator.Teleport t = host.prompts.get(0);
        assertEquals(AutoUploadCoordinator.Cause.JUMP, t.cause());
        assertEquals(5_003, t.x(), "refers to where the player ended up, not the first tick of the jump");
        coordinator.onPlayerPosition("overworld", 9_000, 70, 9_000, 9_100);
        assertEquals(1, host.prompts.size(), "no second prompt while one is outstanding");
    }

    @Test
    void aSecondJumpWhileSettlingRestartsTheWait() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", 400, 64, 0, 150);
        coordinator.onPlayerPosition("overworld", 900, 64, 0, 3_000);

        coordinator.tick(150 + SETTLE);
        assertTrue(host.prompts.isEmpty(), "the first jump's deadline no longer applies");
        coordinator.tick(3_000 + SETTLE);

        assertEquals(1, host.prompts.size());
        assertEquals(900, host.prompts.get(0).x());
    }

    @Test
    void aSecondAnnouncedTeleportWhileSettlingWaitsForItsArrival() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", 400, 64, 0, 150);
        coordinator.onChatMessage("Teleporting to spawn in 10 seconds", 2_000);

        coordinator.tick(150 + SETTLE + 1_000);
        assertTrue(host.prompts.isEmpty(), "another teleport is on its way");

        coordinator.onPlayerPosition("overworld", 50, 64, 0, 12_000);
        coordinator.tick(12_000 + SETTLE);
        assertEquals(1, host.prompts.size());
        assertEquals(50, host.prompts.get(0).x());
    }

    @Test
    void aDimensionChangeAcrossALoadingScreenIsStillSeen() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 100, 64, 100, 100);
        coordinator.onPlayerGone();
        coordinator.onPlayerPosition("the_nether", 12, 64, 12, 200);

        assertTrue(coordinator.isPaused());
        coordinator.tick(200 + SETTLE);
        assertEquals(AutoUploadCoordinator.Cause.DIMENSION_CHANGE, host.prompts.get(0).cause());
    }

    @Test
    void announcedTeleportWithNoArrivalStillPromptsAfterTheGracePeriod() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 10, 64, 10, 500);
        coordinator.onChatMessage("Teleporting to home in 3 seconds", 1_000);

        coordinator.tick(1_000 + 3_000);
        assertTrue(host.prompts.isEmpty());
        coordinator.tick(1_000 + 3_000 + 21_000);
        assertEquals(1, host.prompts.size());
        assertEquals(AutoUploadCoordinator.Cause.ANNOUNCED, host.prompts.get(0).cause());
    }

    @Test
    void aJumpWithNoCountdownMessageAlsoPausesAndPrompts() {
        coordinator.setLive(true, 0);
        jumpFarAway();

        assertTrue(coordinator.isPaused(), "paused the instant it happens");
        assertTrue(host.prompts.isEmpty());
        coordinator.tick(150 + SETTLE);
        assertEquals(1, host.prompts.size(), "asked once it has settled");
    }

    @Test
    void movementBelowTheThresholdNeverPauses() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", 60, 64, 60, 150);
        assertFalse(coordinator.isPaused());
    }

    @Test
    void nothingIsWatchedWhileLiveIsOff() {
        jumpFarAway();
        coordinator.onChatMessage("Teleporting to home in 3 seconds", 200);
        assertFalse(coordinator.isPaused());
        assertTrue(host.prompts.isEmpty());
    }

    @Test
    void resumeContinuesWithAFreshDelayNotAnImmediateUpload() {
        coordinator.setLive(true, 0);
        jumpFarAway();

        long resumeAt = 2 * MAX;
        coordinator.resume(resumeAt);

        assertFalse(coordinator.isPaused());
        coordinator.tick(resumeAt + MIN - 1);
        assertTrue(host.cycleLiveSince.isEmpty());
        assertTrue(host.prompts.isEmpty(), "resuming before it settled cancels the pending prompt");
        coordinator.tick(resumeAt + MAX);
        assertEquals(1, host.cycleLiveSince.size());
    }

    @Test
    void stayingPausedKeepsUploadsBlockedUntilResumed() {
        coordinator.setLive(true, 0);
        jumpFarAway();
        coordinator.tick(150 + SETTLE);
        assertEquals(1, host.prompts.size());
        coordinator.promptFinished();

        coordinator.tick(20 * MAX);

        assertTrue(coordinator.isPaused());
        assertTrue(host.cycleLiveSince.isEmpty());
    }

    @Test
    void resetTurnsItOffLikeARestart() {
        coordinator.setLive(true, 0);
        jumpFarAway();
        coordinator.tick(150 + SETTLE);

        coordinator.reset();

        assertFalse(coordinator.isLive());
        assertFalse(coordinator.isPaused());
        coordinator.tick(20 * MAX);
        assertEquals(1, host.prompts.size(), "no new prompt after reset");
        assertTrue(host.cycleLiveSince.isEmpty());
    }

    @Test
    void statusLineDescribesOffOnAndPaused() {
        assertEquals("Off", coordinator.statusLine(0));
        coordinator.setLive(true, 0);
        assertTrue(coordinator.statusLine(0).startsWith("On - next upload in about "));
        coordinator.onChatMessage("Teleporting to home in 3 seconds", 0);
        assertTrue(coordinator.statusLine(0).startsWith("Paused"));
    }
}
