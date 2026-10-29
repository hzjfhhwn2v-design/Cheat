package dev.flipscout.core.profit;

import dev.flipscout.core.Config;
import dev.flipscout.core.FakePlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitTrackerTest {
    @TempDir
    File dir;

    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final Config config = new Config();
    private final FakePlatform platform = new FakePlatform();
    private ProfitTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = newTracker();
    }

    private ProfitTracker newTracker() {
        ProfitTracker t = new ProfitTracker(config, new File(dir, "guadagni.json"), clock::get, platform, Runnable::run);
        t.load();
        return t;
    }

    private void chat(String line) {
        tracker.onChat(line);
    }

    @Test
    void auctionFlipIsCountedOnceWhenSoldAndWhenCollected() {
        chat("§eYou purchased §5Hyperion§e for §6800,000,000 coins§e!");
        assertEquals(1, tracker.lots().size());
        clock.addAndGet(3_600_000L);
        chat("§6[Auction] §b[MVP§c+§b] Bob §ebought §5Hyperion §efor §6900,000,000 coins §lCLICK");
        // 900M minus 2.5% to list it and 1% to collect: 868.5M.
        assertEquals(68_500_000, tracker.total().profit, 1);
        assertEquals(1, tracker.total().flips);
        assertTrue(tracker.lots().isEmpty());
        assertEquals(1, tracker.unclaimed().size());

        chat("You collected 891,000,000 coins from selling Hyperion to [MVP+] Bob in an auction!");
        assertEquals(68_500_000, tracker.total().profit, 1);
        assertEquals(1, tracker.total().sold);
        assertTrue(tracker.unclaimed().isEmpty());
        assertEquals(3_600_000L, tracker.flips().get(0).soldAt - tracker.flips().get(0).boughtAt);
        String text = platform.allText();
        assertTrue(text.contains("Acquisto registrato: Hyperion per 800M"), text);
        assertTrue(text.contains("Flip chiuso: Hyperion 800M → 869M +68.5M | sessione +68.5M"), text);
    }

    @Test
    void saleWhileAwayIsCountedWhenCollected() {
        chat("You purchased Hyperion for 800,000,000 coins!");
        // Reforged before selling, still the same Hyperion. 990M collected means it sold for 1B.
        chat("You collected 990,000,000 coins from selling Heroic Hyperion to Bob in an auction!");
        assertEquals(1_000_000_000 * (1 - 0.035) - 800_000_000, tracker.total().profit, 1);
        assertEquals(1, tracker.total().flips);
    }

    @Test
    void bazaarSalesUseTheOldestPurchasesFirst() {
        chat("[Bazaar] Bought 64x Enchanted Diamond for 10,240 coins!");
        chat("[Bazaar] Claimed 160x Enchanted Diamond worth 27,200 coins bought for 170.0 each!");
        chat("[Bazaar] Claimed 33,000 coins from selling 200x Enchanted Diamond at 167.1 each!");
        // 64 x 160 + 136 x 170 = 33,360 for 33,000.
        assertEquals(-360, tracker.total().profit, 1e-6);
        chat("[Bazaar] Sold 24x Enchanted Diamond for 4,500 coins!");
        // The last 24 cost 170 each: 4,080.
        assertEquals(60, tracker.total().profit, 1e-6);
        assertEquals(2, tracker.total().flips);
        assertTrue(tracker.lots().isEmpty());
        assertFalse(platform.allText().contains("Acquisto registrato"), platform.allText());
    }

    @Test
    void salesWithoutAPurchaseAreNotProfit() {
        chat("[Bazaar] Sold 64x Wheat for 100 coins!");
        assertEquals(0, tracker.total().profit, 1e-9);
        assertEquals(1, tracker.total().unmatched);
        assertEquals(100, tracker.total().earned, 1e-9);
    }

    @Test
    void playersCannotFakeTrades() {
        chat("[MVP+] Bob: You purchased Hyperion for 1 coins!");
        chat("Guild > Bob: [Bazaar] Sold 64x Wheat for 100 coins!");
        assertEquals(0, tracker.total().bought);
        assertEquals(0, tracker.total().sold);
    }

    @Test
    void pausedSessionsOnlyCountInTheTotal() {
        chat("You purchased Hyperion for 800,000,000 coins!");
        assertTrue(tracker.pauseSession());
        assertFalse(tracker.pauseSession());
        chat("You collected 891,000,000 coins from selling Hyperion to Bob in an auction!");
        assertEquals(68_500_000, tracker.total().profit, 1);
        assertEquals(0, tracker.session().stats.profit, 1e-9);
        assertEquals(1, tracker.session().stats.bought);
        assertTrue(tracker.session().paused);
        assertTrue(platform.allText().contains("sessione in pausa"), platform.allText());

        assertTrue(tracker.resumeSession());
        chat("[Bazaar] Bought 1x Enchanted Diamond for 160 coins!");
        chat("[Bazaar] Sold 1x Enchanted Diamond for 200 coins!");
        assertEquals(40, tracker.session().stats.profit, 1e-9);
    }

    @Test
    void sessionTimeRunsOnlyWhileActive() {
        clock.addAndGet(30 * 60_000L);
        assertEquals(30 * 60_000L, tracker.session().activeMillis);
        tracker.pauseSession();
        clock.addAndGet(60 * 60_000L);
        assertEquals(30 * 60_000L, tracker.session().activeMillis);
        tracker.resumeSession();
        clock.addAndGet(10 * 60_000L);
        assertEquals(40 * 60_000L, tracker.session().activeMillis);
    }

    @Test
    void resetArchivesTheSession() {
        chat("[Bazaar] Bought 1x Enchanted Diamond for 160 coins!");
        chat("[Bazaar] Sold 1x Enchanted Diamond for 200 coins!");
        clock.addAndGet(5 * 60_000L);
        tracker.resetSession();
        assertEquals(0, tracker.session().stats.profit, 1e-9);
        assertEquals(1, tracker.history().size());
        assertEquals(40, tracker.history().get(0).stats.profit, 1e-9);
        assertEquals(5 * 60_000L, tracker.history().get(0).activeMillis);
        assertEquals(40, tracker.total().profit, 1e-9);
        // An empty session is not worth keeping.
        tracker.resetSession();
        assertEquals(1, tracker.history().size());

        tracker.resetTotal();
        assertEquals(0, tracker.total().profit, 1e-9);
        assertTrue(tracker.history().isEmpty());
        assertTrue(tracker.flips().isEmpty());
    }

    @Test
    void everythingSurvivesARestart() {
        chat("You purchased Hyperion for 800,000,000 coins!");
        chat("[Auction] Bob bought Hyperion for 900,000,000 coins CLICK");
        chat("You purchased Terminator for 1,000,000,000 coins!");
        clock.addAndGet(20 * 60_000L);
        tracker.stop();

        // The game starts again two hours later: those two hours are not session time.
        clock.addAndGet(2 * 3_600_000L);
        tracker = newTracker();
        assertEquals(68_500_000, tracker.total().profit, 1);
        assertEquals(68_500_000, tracker.session().stats.profit, 1);
        assertEquals(20 * 60_000L, tracker.session().activeMillis);
        assertEquals(1, tracker.lots().size());
        assertEquals("Terminator", tracker.lots().get(0).item);
        assertEquals(1, tracker.flips().size());
        // The Hyperion sold while online is not counted again when its coins are collected.
        chat("You collected 891,000,000 coins from selling Hyperion to Bob in an auction!");
        assertEquals(68_500_000, tracker.total().profit, 1);
        assertEquals(1, tracker.total().flips);
    }

    @Test
    void daysAreKeptApart() {
        chat("[Bazaar] Bought 1x Enchanted Diamond for 160 coins!");
        chat("[Bazaar] Sold 1x Enchanted Diamond for 200 coins!");
        clock.addAndGet(24 * 3_600_000L);
        chat("[Bazaar] Bought 1x Enchanted Diamond for 160 coins!");
        chat("[Bazaar] Sold 1x Enchanted Diamond for 260 coins!");
        assertEquals(100, tracker.today().profit, 1e-9);
        assertEquals(2, tracker.days().size());
        assertEquals(140, tracker.total().profit, 1e-9);
    }

    @Test
    void reforgedItemsAreStillTheSameItem() {
        assertTrue(ProfitTracker.sameItem("heroic hyperion", "hyperion"));
        assertTrue(ProfitTracker.sameItem("withered hyperion", "heroic hyperion"));
        assertFalse(ProfitTracker.sameItem("strong dragon helmet", "superior dragon helmet"));
        assertFalse(ProfitTracker.sameItem("wise dragon helmet", "strong dragon helmet"));
        assertFalse(ProfitTracker.sameItem("hyperion", ""));
        assertEquals("heroic hyperion", ProfitTracker.normalize("§6Heroic Hyperion §6✪✪✪✪✪§c➌"));
        assertEquals("[lvl 100] ender dragon", ProfitTracker.normalize("[Lvl 100] Ender Dragon"));
    }
}
