package dev.flipscout.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {
    private static final String H = "https://api.hypixel.net";
    private static final String PAGE0 = H + "/v2/skyblock/auctions?page=0";
    private static final String ENDED = H + "/v2/skyblock/auctions_ended";
    private static final String BAZAAR = H + "/v2/skyblock/bazaar";
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    File dir;

    private final AtomicLong clock = new AtomicLong(T0 + 5_000);
    private FakeFetcher http;
    private FakePlatform platform;
    private Config config;
    private Engine engine;

    @BeforeEach
    void setUp() {
        http = new FakeFetcher();
        platform = new FakePlatform();
        platform.uuid = "myuuid";
        config = new Config();
        config.sourceCoflnet = false;
        engine = new Engine(config, platform, http, dir, clock::get);
        http.put(H + "/v2/resources/skyblock/items",
            "{\"success\":true,\"items\":[{\"id\":\"HYPERION\",\"name\":\"Hyperion\",\"tier\":\"LEGENDARY\"}]}");
        http.put(BAZAAR, TestData.bazaar(1L));
    }

    private static String hyperion(String uuid, String seller, long price, long start) {
        return TestData.auction(uuid, seller, "Hyperion", "LEGENDARY", price, true, start, TestData.plainItem("HYPERION"));
    }

    /** Two listings at 900M/910M and five sales around 895M. */
    private void initialMarket() {
        http.put(PAGE0, TestData.auctionPage(0, 1, T0,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("a2", "s2", 910_000_000L, T0 - 3_600_000L)));
        String[] sold = new String[5];
        for (int i = 0; i < sold.length; i++) {
            sold[i] = TestData.ended("e" + i, 893_000_000L + i * 1_000_000L, true, T0 - 30_000, TestData.plainItem("HYPERION"));
        }
        http.put(ENDED, TestData.endedPage(T0, sold));
        engine.tick();
    }

    @Test
    void firstScanIndexesWithoutNotifying() {
        initialMarket();
        assertTrue(engine.initialScanDone());
        assertEquals(2, engine.index.size());
        assertEquals(5, engine.sales.sampleCount());
        assertTrue(platform.messages.isEmpty());
        // NEU is not served by the fake: reported as an error, everything else keeps working.
        assertTrue(engine.status.errors().containsKey("NEU"));
        assertEquals(1, engine.items().size());
    }

    @Test
    void newCheapAuctionIsNotifiedOnce() {
        initialMarket();
        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("a2", "s2", 910_000_000L, T0 - 3_600_000L),
            hyperion("new", "s4", 800_000_000L, t1 - 10_000),
            hyperion("mine", "myuuid", 800_000_000L, t1 - 10_000)));
        clock.set(t1 + 5_000);
        engine.tick();

        List<Msg> flips = platform.withCommand("/viewauction new");
        assertEquals(1, flips.size());
        assertTrue(flips.get(0).toPlainString().contains("Hyperion"));
        assertTrue(platform.withCommand("/viewauction mine").isEmpty(), "own auctions are never suggested");
        assertEquals(1, platform.sounds);
        assertEquals(4, engine.index.size());
        assertEquals("/viewauction new", engine.lastNotification().command);

        // Same data again: nothing new.
        clock.addAndGet(65_000);
        engine.tick();
        assertEquals(1, platform.withCommand("/viewauction new").size());
    }

    @Test
    void localClockAheadOfHypixelStillCountsNewAuctions() {
        initialMarket();
        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("new", "s4", 800_000_000L, t1 - 10_000)));
        // The PC clock runs ten minutes fast: Hypixel's Date header says t1.
        http.serverDate = t1;
        clock.set(t1 + 10 * 60_000L + 5_000);
        engine.tick();
        assertEquals(1, platform.withCommand("/viewauction new").size());
        assertEquals(t1, engine.serverTime(), 5_000);
    }

    @Test
    void pollingFollowsNewAuctionsOntoLaterPages() {
        String page = H + "/v2/skyblock/auctions?page=";
        http.put(PAGE0, TestData.auctionPage(0, 4, T0,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L)));
        for (int p = 1; p < 4; p++) {
            http.put(page + p, TestData.auctionPage(p, 4, T0));
        }
        http.put(ENDED, TestData.endedPage(T0));
        engine.tick();
        assertEquals(1, http.count(page + 3));

        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 4, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("n0", "s2", 950_000_000L, t1 - 10_000)));
        http.put(page + 1, TestData.auctionPage(1, 4, t1, hyperion("n1", "s3", 950_000_000L, t1 - 20_000)));
        http.put(page + 2, TestData.auctionPage(2, 4, t1, hyperion("old", "s4", 950_000_000L, T0 - 3_600_000L)));
        http.put(page + 3, TestData.auctionPage(3, 4, t1, hyperion("n3", "s5", 950_000_000L, t1 - 5_000)));
        clock.set(t1 + 5_000);
        engine.tick();
        // Pages 0-1 always, page 2 because page 1 still had new auctions, then stop.
        assertEquals(2, http.count(page + 2));
        assertEquals(1, http.count(page + 3));
        assertEquals(4, engine.index.size());
    }

    @Test
    void staleCheapAuctionIsNotNotified() {
        initialMarket();
        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("a2", "s2", 910_000_000L, T0 - 3_600_000L),
            hyperion("missed", "s5", 700_000_000L, t1 - 2 * 3_600_000L)));
        clock.set(t1 + 5_000);
        engine.tick();
        assertTrue(platform.messages.isEmpty());
        assertEquals(3, engine.index.size());
        // ...but it does show up in /flipscout top.
        assertFalse(engine.topAuctions(10).isEmpty());
        assertEquals("missed", engine.topAuctions(10).get(0).listing.uuid);
    }

    @Test
    void outsideSkyblockFlipsAreOnlyRemembered() {
        initialMarket();
        platform.inSkyblock = false;
        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("new", "s4", 800_000_000L, t1 - 10_000)));
        clock.set(t1 + 5_000);
        engine.tick();
        assertTrue(platform.messages.isEmpty());
        assertEquals(1, engine.recent().size());
    }

    @Test
    void endedAuctionsLeaveTheIndexAndFeedSales() {
        initialMarket();
        long t1 = T0 + 60_000;
        http.put(ENDED, TestData.endedPage(t1,
            TestData.ended("a1", 900_000_000L, true, t1 - 5_000, TestData.plainItem("HYPERION"))));
        clock.set(t1 + 5_000);
        engine.tick();
        assertEquals(1, engine.index.size());
        assertEquals(6, engine.sales.sampleCount());
    }

    @Test
    void fullScanDropsAuctionsThatDisappeared() {
        initialMarket();
        http.put(PAGE0, TestData.auctionPage(0, 1, T0 + 600_000,
            hyperion("a2", "s2", 910_000_000L, T0 - 3_600_000L)));
        clock.set(T0 + 31 * 60_000);
        engine.tick();
        assertEquals(1, engine.index.size());
    }

    @Test
    void bazaarOpportunitiesRespectCooldowns() {
        config.ahEnabled = false;
        config.bzCrashEnabled = false;
        config.bzNpcEnabled = false;
        String good = TestData.product("GOOD", new double[][] {{12_500, 10}}, new double[][] {{10_000, 10}},
            168 * 1000, 168 * 800);
        http.put(BAZAAR, TestData.bazaar(1L, good));
        engine.tick();
        assertEquals(1, platform.withCommand("/bz Good").size());

        http.put(BAZAAR, TestData.bazaar(2L, good));
        clock.addAndGet(61_000);
        engine.tick();
        assertEquals(1, platform.withCommand("/bz Good").size());

        http.put(BAZAAR, TestData.bazaar(3L, good));
        clock.addAndGet(31 * 60_000L);
        engine.tick();
        assertEquals(2, platform.withCommand("/bz Good").size());
        assertNotNull(engine.topBazaar(5));
    }

    @Test
    void idleWhenNotOnHypixel() {
        platform.onHypixel = false;
        engine.tick();
        assertTrue(http.requests.isEmpty());
        config.onlyOnHypixel = false;
        initialMarket();
        assertFalse(http.requests.isEmpty());
    }

    @Test
    void salesArePersistedOnStop() {
        initialMarket();
        engine.start();
        engine.stop();
        Engine restarted = new Engine(config, platform, http, dir, clock::get);
        restarted.sales.load(new File(dir, "sales.bin"));
        assertEquals(5, restarted.sales.sampleCount());
    }

    @Test
    void fullScanRunsInTheBackgroundWithoutHoldingUpPolls() {
        ManualExecutor scans = new ManualExecutor();
        engine.useExecutors(scans, null);
        String page = H + "/v2/skyblock/auctions?page=";
        http.put(PAGE0, TestData.auctionPage(0, 3, T0, hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L)));
        http.put(page + 1, TestData.auctionPage(1, 3, T0, hyperion("a2", "s2", 910_000_000L, T0 - 3_600_000L)));
        http.put(page + 2, TestData.auctionPage(2, 3, T0, hyperion("a3", "s3", 920_000_000L, T0 - 3_600_000L)));
        String[] sold = new String[5];
        for (int i = 0; i < sold.length; i++) {
            sold[i] = TestData.ended("e" + i, 893_000_000L + i * 1_000_000L, true, T0 - 30_000, TestData.plainItem("HYPERION"));
        }
        http.put(ENDED, TestData.endedPage(T0, sold));

        // Page 0 is merged at once, the other pages are left to the scan threads.
        engine.tick();
        assertEquals(1, engine.index.size());
        assertEquals(2, scans.size());
        assertFalse(engine.initialScanDone());
        scans.runAll();
        engine.tick();
        assertTrue(engine.initialScanDone());
        assertEquals(3, engine.index.size());

        // The next full scan is still downloading when the minute's new auctions come out...
        long t2 = T0 + 31 * 60_000L;
        http.put(PAGE0, TestData.auctionPage(0, 3, t2, hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L)));
        http.put(page + 1, TestData.auctionPage(1, 3, t2, hyperion("a2", "s2", 910_000_000L, T0 - 3_600_000L)));
        http.put(page + 2, TestData.auctionPage(2, 3, t2, hyperion("a3", "s3", 920_000_000L, T0 - 3_600_000L)));
        http.put(ENDED, TestData.endedPage(t2));
        clock.set(t2 + 5_000);
        engine.tick();
        assertEquals(2, scans.size());
        long t3 = t2 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 3, t3,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("new", "s4", 800_000_000L, t3 - 10_000)));
        clock.set(t3 + 1_000);
        engine.tick();
        // ...and they are notified without waiting for it.
        assertEquals(1, platform.withCommand("/viewauction new").size());
        assertTrue(engine.status.scanning);

        scans.runAll();
        engine.tick();
        assertFalse(engine.status.scanning);
        assertEquals(4, engine.index.size());
        assertEquals(1, platform.withCommand("/viewauction new").size());
    }

    @Test
    void refreshDuringAFullScanRemovesNothing() {
        initialMarket();
        ManualExecutor scans = new ManualExecutor();
        engine.useExecutors(scans, null);
        long t2 = T0 + 31 * 60_000L;
        http.put(PAGE0, TestData.auctionPage(0, 2, t2, hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L)));
        clock.set(t2 + 5_000);
        engine.tick();
        assertEquals(1, scans.size());
        // Page 1 comes from the next refresh, where a2 has moved to page 0: it is seen nowhere.
        http.put(H + "/v2/skyblock/auctions?page=1", TestData.auctionPage(1, 2, t2 + 60_000));
        scans.runAll();
        engine.tick();
        assertFalse(engine.status.scanning);
        assertEquals(2, engine.index.size());
        // A scan like that is repeated soon rather than in half an hour.
        clock.addAndGet(2 * 60_000L + 1_000);
        engine.tick();
        assertEquals(1, scans.size());
    }

    @Test
    void weakFlipsAreCheckedWithCoflnetInTheBackground() {
        config.sourceCoflnet = true;
        ManualExecutor background = new ManualExecutor();
        engine.useExecutors(null, background);
        http.put(PAGE0, TestData.auctionPage(0, 1, T0, hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L)));
        http.put(ENDED, TestData.endedPage(T0));
        engine.tick();
        background.runAll();
        assertTrue(engine.initialScanDone());

        // No sales of our own: 800M against a lowest BIN of 900M is not enough on its own, but
        // Coflnet's median adds the second reference.
        http.put("https://sky.coflnet.com/api/item/price/HYPERION", "{\"median\":890000000,\"volume\":4}");
        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("new", "s4", 800_000_000L, t1 - 10_000)));
        clock.set(t1 + 1_000);
        engine.tick();
        assertTrue(platform.withCommand("/viewauction new").isEmpty());
        assertEquals(0, http.count("https://sky.coflnet.com/api/item/price/HYPERION"));
        background.runAll();
        assertEquals(1, http.count("https://sky.coflnet.com/api/item/price/HYPERION"));
        List<Msg> flips = platform.withCommand("/viewauction new");
        assertEquals(1, flips.size());
        assertFalse(flips.get(0).toPlainString().contains("⚠"), flips.get(0).toPlainString());
    }

    @Test
    void weakFlipsStillArriveWhenCoflnetIsDown() {
        config.sourceCoflnet = true;
        ManualExecutor background = new ManualExecutor();
        engine.useExecutors(null, background);
        // No sales of our own: the lowest BIN alone counts once there are a few other listings.
        http.put(PAGE0, TestData.auctionPage(0, 1, T0, hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("a2", "s2", 910_000_000L, T0 - 3_600_000L), hyperion("a3", "s3", 920_000_000L, T0 - 3_600_000L)));
        http.put(ENDED, TestData.endedPage(T0));
        engine.tick();
        background.runAll();

        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("a2", "s2", 910_000_000L, T0 - 3_600_000L), hyperion("a3", "s3", 920_000_000L, T0 - 3_600_000L),
            hyperion("new", "s4", 700_000_000L, t1 - 10_000)));
        clock.set(t1 + 1_000);
        engine.tick();
        background.runAll();
        List<Msg> flips = platform.withCommand("/viewauction new");
        assertEquals(1, flips.size());
        assertTrue(flips.get(0).toPlainString().contains("⚠"), flips.get(0).toPlainString());
        assertEquals(1, http.count("https://sky.coflnet.com/api/item/price/HYPERION"));
    }

    @Test
    void salesPricesAreFetchedAheadOfTimeForValuableItems() {
        config.sourceCoflnet = true;
        ManualExecutor background = new ManualExecutor();
        engine.useExecutors(null, background);
        String coflnet = "https://sky.coflnet.com/api/item/price/";
        http.put(coflnet + "HYPERION", "{\"median\":890000000,\"volume\":4}");
        http.put(coflnet + "GIANTS_SWORD", "{\"median\":48000000,\"volume\":10}");
        http.put(PAGE0, TestData.auctionPage(0, 1, T0,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            TestData.auction("g1", "s2", "Giant's Sword", "LEGENDARY", 50_000_000L, true, T0 - 3_600_000L,
                TestData.plainItem("GIANTS_SWORD")),
            TestData.auction("d1", "s3", "Dirt", "COMMON", 10L, true, T0 - 3_600_000L, TestData.plainItem("DIRT"))));
        http.put(ENDED, TestData.endedPage(T0));
        engine.tick();
        background.runAll();
        // Not right away: NEU and our own sales get their chance first.
        assertEquals(0, http.count(coflnet + "HYPERION"));

        // Then the most valuable item first, one every ten seconds; those too cheap to flip never.
        clock.addAndGet(2 * 60_000L);
        engine.tick();
        background.runAll();
        assertEquals(1, http.count(coflnet + "HYPERION"));
        assertEquals(0, http.count(coflnet + "GIANTS_SWORD"));
        clock.addAndGet(10_000);
        engine.tick();
        background.runAll();
        assertEquals(1, http.count(coflnet + "GIANTS_SWORD"));
        clock.addAndGet(10_000);
        engine.tick();
        background.runAll();
        assertEquals(0, http.count(coflnet + "DIRT"));
        assertEquals(1, http.count(coflnet + "HYPERION"));

        // A cheap Hyperion is now judged on two prices straight away, with nothing to wait for.
        long t1 = T0 + 150_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("new", "s4", 800_000_000L, t1 - 10_000)));
        clock.set(t1 + 1_000);
        engine.tick();
        List<Msg> flips = platform.withCommand("/viewauction new");
        assertEquals(1, flips.size());
        assertFalse(flips.get(0).toPlainString().contains("⚠"), flips.get(0).toPlainString());
        assertEquals(1, http.count(coflnet + "HYPERION"));
    }

    @Test
    void notifiedAuctionsAreMarkedWhenTheySell() {
        initialMarket();
        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("fast", "s4", 800_000_000L, t1 - 10_000)));
        clock.set(t1 + 1_000);
        engine.tick();
        Engine.Notification fast = engine.recent().get(0);
        assertEquals("fast", fast.auctionUuid);
        assertEquals(0, fast.soldAt());

        // Someone bought it 12s after it was listed.
        long t2 = t1 + 60_000;
        http.put(ENDED, TestData.endedPage(t2,
            TestData.ended("fast", 800_000_000L, true, t1 + 2_000, TestData.plainItem("HYPERION"))));
        clock.set(t2 + 1_000);
        engine.tick();
        assertEquals(t1 + 2_000, fast.soldAt());
        assertEquals(12_000, fast.soldAt() - fast.listedAt);
        assertFalse(fast.boughtByYou());

        // The next one you bought yourself.
        long t3 = t2 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t3,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("mine", "s5", 805_000_000L, t3 - 9_000)));
        clock.set(t3 + 1_000);
        engine.tick();
        long t4 = t3 + 60_000;
        http.put(ENDED, TestData.endedPage(t4,
            TestData.ended("mine", 805_000_000L, true, t3 + 3_000, TestData.plainItem("HYPERION"))
                .replace("\"buyer\":\"b\"", "\"buyer\":\"myuuid\"")));
        clock.set(t4 + 1_000);
        engine.tick();
        Engine.Notification mine = engine.recent().get(0);
        assertEquals("mine", mine.auctionUuid);
        assertTrue(mine.boughtByYou());
        assertEquals(2, engine.recent().size());
    }

    @Test
    void notificationsShowHowOldTheAuctionIs() {
        initialMarket();
        long t1 = T0 + 60_000;
        http.put(PAGE0, TestData.auctionPage(0, 1, t1,
            hyperion("a1", "s1", 900_000_000L, T0 - 3_600_000L),
            hyperion("new", "s4", 800_000_000L, t1 - 12_000)));
        clock.set(t1 + 1_000);
        engine.tick();
        List<Msg> flips = platform.withCommand("/viewauction new");
        assertEquals(1, flips.size());
        assertTrue(flips.get(0).toPlainString().contains("| 13s"), flips.get(0).toPlainString());
    }

    @Test
    void unchangedPagesAreCheckedOftenButNotDownloaded() {
        config.bzEnabled = false;
        initialMarket();
        assertEquals(0, http.aborts.get());
        // Hypixel is a little late with the next refresh: both auction endpoints are asked again
        // every half second, and each answer is dropped as soon as it shows nothing changed.
        clock.set(T0 + 59_000);
        engine.tick();
        clock.addAndGet(250);
        engine.tick();
        clock.addAndGet(250);
        engine.tick();
        assertEquals(3, http.count(PAGE0));
        assertEquals(3, http.count(ENDED));
        assertEquals(4, http.aborts.get());
    }

    @Test
    void nextUpdateTiming() {
        long now = 10_000_000L;
        assertEquals(now + 500, Engine.expectedNextUpdate(now - 120_000, 0, now));
        assertEquals(now - 20_000 + 60_000 - 1_000, Engine.expectedNextUpdate(now - 20_000, now, now));
        assertEquals(now - 20_000 + 60_000 - 1_000 + 1_500, Engine.expectedNextUpdate(now - 20_000, now - 1_500, now));
        assertEquals(now + 60_000, Engine.expectedNextUpdate(0, 0, now));
    }
}
