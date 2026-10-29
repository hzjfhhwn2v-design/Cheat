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
    void nextUpdateTiming() {
        long now = 10_000_000L;
        assertEquals(now + 3_000, Engine.expectedNextUpdate(now - 120_000, 0, now));
        assertEquals(now - 20_000 + 60_000 + 2_000, Engine.expectedNextUpdate(now - 20_000, now, now));
        assertEquals(now - 20_000 + 60_000 + 2_000 + 1_500, Engine.expectedNextUpdate(now - 20_000, now - 1_500, now));
        assertEquals(now + 60_000, Engine.expectedNextUpdate(0, 0, now));
    }
}
