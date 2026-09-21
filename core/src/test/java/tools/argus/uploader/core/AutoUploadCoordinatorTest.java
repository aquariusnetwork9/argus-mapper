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
    private static final double FAR = (CoordLimits.MAX_ABS_REGION + 10) * 512.0;

    private static final class FakeHost implements AutoUploadCoordinator.Host {
        boolean running;
        boolean alreadyCovered;
        int cancels;
        final List<Long> cycleLiveSince = new ArrayList<>();
        final List<AutoUploadCoordinator.Teleport> protectedArrivals = new ArrayList<>();
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
        public boolean protectArrival(AutoUploadCoordinator.Teleport teleport) {
            protectedArrivals.add(teleport);
            return !alreadyCovered;
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

    private void teleportOutsideTheArea() {
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", FAR, 64, FAR, 150);
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
    void aTeleportOutsideTheAreaPausesCancelsTheRunAndProtectsTheArrival() {
        coordinator.setLive(true, 0);
        int cancelsBefore = host.cancels;
        teleportOutsideTheArea();

        assertTrue(coordinator.isPaused());
        assertEquals(cancelsBefore + 1, host.cancels);
        assertEquals(1, host.protectedArrivals.size());
        assertEquals(FAR, host.protectedArrivals.get(0).x());
        assertEquals(AutoUploadCoordinator.Cause.JUMP, host.protectedArrivals.get(0).cause());
        assertTrue(host.prompts.isEmpty(), "the prompt waits for the teleport to settle");
        coordinator.tick(10 * MAX);
        assertTrue(host.cycleLiveSince.isEmpty(), "no upload while paused");
    }

    @Test
    void aTeleportInsideTheAreaDoesNothing() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", 50_000, 64, -50_000, 150);

        assertFalse(coordinator.isPaused());
        assertTrue(host.protectedArrivals.isEmpty());
        coordinator.tick(150 + SETTLE);
        assertTrue(host.prompts.isEmpty());
    }

    @Test
    void theEdgeOfTheAreaIsStillInside() {
        coordinator.setLive(true, 0);
        double lastInside = (CoordLimits.MAX_ABS_REGION + 1) * 512.0 - 1;
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", lastInside, 64, 0, 150);
        assertFalse(coordinator.isPaused());

        coordinator.onPlayerPosition("overworld", lastInside + 1, 64, 0, 200);
        assertFalse(coordinator.isPaused(), "walking across the edge is not a teleport");
        coordinator.onPlayerPosition("overworld", lastInside + 1, 64, 600, 250);
        assertTrue(coordinator.isPaused());
    }

    @Test
    void wholeMapUploadDoesNotWidenTheTeleportArea() {
        CoordLimits.setLifted(true);
        try {
            coordinator.setLive(true, 0);
            teleportOutsideTheArea();
            assertTrue(coordinator.isPaused());
        } finally {
            CoordLimits.setLifted(false);
        }
    }

    @Test
    void anArrivalAlreadyCoveredByABlackzoneDoesNotPauseOrAsk() {
        coordinator.setLive(true, 0);
        host.alreadyCovered = true;
        teleportOutsideTheArea();

        assertFalse(coordinator.isPaused());
        coordinator.tick(150 + SETTLE);
        assertTrue(host.prompts.isEmpty());
    }

    @Test
    void theDestinationIsCheckedInTheDimensionThePlayerArrivedIn() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 100_000, 64, 100_000, 100);
        coordinator.onPlayerPosition("the_nether", 12_500, 64, 12_500, 150);
        assertFalse(coordinator.isPaused(), "a portal trip that lands inside the area is not a pause");

        coordinator.onPlayerPosition("overworld", FAR, 64, FAR, 200);
        assertTrue(coordinator.isPaused());
        assertEquals("overworld", host.protectedArrivals.get(0).dimension());
        assertEquals(AutoUploadCoordinator.Cause.DIMENSION_CHANGE, host.protectedArrivals.get(0).cause());
    }

    @Test
    void arrivalRaisesOnePromptOnceItHasSettled() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", FAR, 70, FAR, 4_000);
        coordinator.onPlayerPosition("overworld", FAR + 3, 70, FAR + 2, 6_000);
        assertTrue(host.prompts.isEmpty(), "not the moment it arrives");

        coordinator.tick(4_000 + SETTLE - 1);
        assertTrue(host.prompts.isEmpty(), "still settling");
        coordinator.tick(4_000 + SETTLE);

        assertEquals(1, host.prompts.size());
        assertEquals(FAR, host.prompts.get(0).x());
        coordinator.onPlayerPosition("overworld", 9_000, 70, 9_000, 9_100);
        assertEquals(1, host.prompts.size(), "no second prompt while one is outstanding");
    }

    @Test
    void aSecondFarJumpWhileSettlingRestartsTheWaitAndIsProtectedToo() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 0, 64, 0, 100);
        coordinator.onPlayerPosition("overworld", FAR, 64, 0, 150);
        coordinator.onPlayerPosition("overworld", FAR, 64, FAR + 5_000, 3_000);

        coordinator.tick(150 + SETTLE);
        assertTrue(host.prompts.isEmpty(), "the first jump's deadline no longer applies");
        coordinator.tick(3_000 + SETTLE);

        assertEquals(2, host.protectedArrivals.size());
        assertEquals(1, host.prompts.size());
        assertEquals(FAR + 5_000, host.prompts.get(0).z());
    }

    @Test
    void aDimensionChangeAcrossALoadingScreenIsStillSeen() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", 100, 64, 100, 100);
        coordinator.onPlayerGone();
        coordinator.onPlayerPosition("the_nether", FAR, 64, FAR, 200);

        assertTrue(coordinator.isPaused());
        coordinator.tick(200 + SETTLE);
        assertEquals(AutoUploadCoordinator.Cause.DIMENSION_CHANGE, host.prompts.get(0).cause());
    }

    @Test
    void movementBelowTheThresholdNeverPauses() {
        coordinator.setLive(true, 0);
        coordinator.onPlayerPosition("overworld", FAR, 64, FAR, 100);
        coordinator.onPlayerPosition("overworld", FAR + 60, 64, FAR + 60, 150);
        assertFalse(coordinator.isPaused());
    }

    @Test
    void nothingIsWatchedWhileLiveIsOff() {
        teleportOutsideTheArea();
        assertFalse(coordinator.isPaused());
        assertTrue(host.protectedArrivals.isEmpty());
        assertTrue(host.prompts.isEmpty());
    }

    @Test
    void resumeContinuesWithAFreshDelayNotAnImmediateUpload() {
        coordinator.setLive(true, 0);
        teleportOutsideTheArea();

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
        teleportOutsideTheArea();
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
        teleportOutsideTheArea();
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
        teleportOutsideTheArea();
        assertTrue(coordinator.statusLine(0).startsWith("Paused"));
    }
}
