package tools.argus.uploader.core.bounty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BountyPollerTest {

    private static final long MINUTE = 60_000L;

    @Test
    void asksAtOnceThenEveryInterval() {
        BountyPoller poller = new BountyPoller(2 * MINUTE, 15_000);

        assertTrue(poller.isDue(1_000_000, true));
        poller.started(1_000_000);
        assertFalse(poller.isDue(1_000_001, true), "not while an answer is pending");
        poller.succeeded(1_000_500);

        assertFalse(poller.isDue(1_000_500 + MINUTE, true));
        assertTrue(poller.isDue(1_000_500 + 2 * MINUTE, true));
    }

    @Test
    void neverAsksWhenNotAllowed() {
        BountyPoller poller = new BountyPoller(2 * MINUTE, 15_000);

        assertFalse(poller.isDue(1_000_000, false));
    }

    @Test
    void backsOffAfterFailuresAndRecoversOnSuccess() {
        BountyPoller poller = new BountyPoller(2 * MINUTE, 15_000);
        long now = 1_000_000;

        poller.started(now);
        poller.failed(now);
        assertFalse(poller.isDue(now + MINUTE, true));
        assertTrue(poller.isDue(now + 2 * MINUTE, true));

        now += 2 * MINUTE;
        poller.started(now);
        poller.failed(now);
        assertFalse(poller.isDue(now + 3 * MINUTE, true), "the wait doubles");
        assertTrue(poller.isDue(now + 4 * MINUTE, true));

        now += 4 * MINUTE;
        poller.started(now);
        poller.succeeded(now);
        assertTrue(poller.isDue(now + 2 * MINUTE, true), "back to the normal interval");
    }

    @Test
    void theWaitNeverGrowsPastTenMinutes() {
        BountyPoller poller = new BountyPoller(2 * MINUTE, 15_000);
        long now = 1_000_000;
        for (int i = 0; i < 12; i++) {
            poller.started(now);
            poller.failed(now);
            now += 10 * MINUTE;
        }

        assertTrue(poller.isDue(now, true));
    }

    @Test
    void askSoonStillRespectsTheMinimumGap() {
        BountyPoller poller = new BountyPoller(2 * MINUTE, 15_000);
        poller.started(1_000_000);
        poller.succeeded(1_000_100);

        poller.askSoon();

        assertFalse(poller.isDue(1_005_000, true), "only 5 s since the last request");
        assertTrue(poller.isDue(1_015_000, true));
    }

    @Test
    void resetForgetsAnAnswerThatWasStillPending() {
        BountyPoller poller = new BountyPoller(2 * MINUTE, 15_000);
        poller.started(1_000_000);

        poller.reset();

        assertFalse(poller.inFlight());
        assertTrue(poller.isDue(1_020_000, true));
    }
}
