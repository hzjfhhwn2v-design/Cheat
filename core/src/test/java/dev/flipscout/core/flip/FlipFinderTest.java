package dev.flipscout.core.flip;

import dev.flipscout.core.Config;
import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.TestData;
import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.CoflnetApi;
import dev.flipscout.core.api.HypixelApi;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.item.ItemKeys;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.BazaarHistory;
import dev.flipscout.core.market.CoflnetSource;
import dev.flipscout.core.market.PriceService;
import dev.flipscout.core.market.SalesHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlipFinderTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final int NOW_MINUTE = (int) (NOW / 60_000L);
    private static final ItemKey HYPERION = new ItemKey("HYPERION", "HYPERION", "HYPERION", true, "HYPERION", "HYPERION");

    private final AtomicLong clock = new AtomicLong(NOW);
    private Config config;
    private AuctionIndex index;
    private SalesHistory sales;
    private FakeFetcher coflnetHttp;
    private PriceService prices;
    private AuctionFlipFinder finder;

    @BeforeEach
    void setUp() {
        config = new Config();
        index = new AuctionIndex();
        sales = new SalesHistory();
        coflnetHttp = new FakeFetcher();
        CoflnetSource coflnet = new CoflnetSource(new CoflnetApi(coflnetHttp, () -> "https://sky.coflnet.com"), clock::get);
        prices = new PriceService(config, index, sales, coflnet, clock::get);
        finder = new AuctionFlipFinder(config, prices);
    }

    private AuctionIndex.Listing listing(String uuid, ItemKey key, long price, int count) {
        return new AuctionIndex.Listing(uuid, "seller", key.id, "LEGENDARY", key, price, count, NOW - 1_000, NOW + 3_600_000L,
            NOW, 1);
    }

    private void hyperionMarket() {
        index.add(listing("a1", HYPERION, 900_000_000L, 1));
        index.add(listing("a2", HYPERION, 910_000_000L, 1));
        for (int i = 0; i < 5; i++) {
            sales.add("HYPERION", NOW_MINUTE - 10 + i, 893_000_000 + i * 1_000_000);
        }
    }

    @Test
    void fees() {
        assertEquals(5_000, Fees.auctionSellFees(500_000, config), 1e-6);
        assertEquals(100_000, Fees.auctionSellFees(5_000_000, config), 1e-6);
        assertEquals(1_500_000, Fees.auctionSellFees(50_000_000, config), 1e-6);
        assertEquals(17_500_000, Fees.auctionSellFees(500_000_000, config), 1e-6);
    }

    @Test
    void cheapBinWithTwoReferencesIsAFlip() {
        hyperionMarket();
        AuctionIndex.Listing cheap = listing("new", HYPERION, 800_000_000L, 1);
        index.add(cheap);
        AuctionFlip flip = finder.evaluate(cheap, false);
        assertNotNull(flip);
        // min(lowest other BIN 900M, median sale 895M) = 895M; fees 3.5% = 31.325M.
        assertEquals(895_000_000, flip.resale, 1e-3);
        assertEquals(895_000_000 - 31_325_000 - 800_000_000, flip.profit, 1e-3);
        assertFalse(flip.weak);
        assertEquals(2, flip.valuation.references);
        assertEquals(2, flip.valuation.otherListings);
        assertEquals("/viewauction new", flip.command());
    }

    private AuctionIndex.Listing upgraded(String uuid, long price, Map<String, Object> extra) {
        return new AuctionIndex.Listing(uuid, "seller", "Hyperion", "LEGENDARY", HYPERION, price, 1, NOW - 1_000,
            NOW + 3_600_000L, NOW, 1, ItemKeys.upgrades("HYPERION", extra));
    }

    @Test
    void upgradesAreCountedBeyondThoseOfTheCheapestListing() throws IOException {
        hyperionMarket();
        prices.setBazaar(snapshot(
            TestData.product("RECOMBOBULATOR_3000", new double[][] {{11_000_000, 5}}, new double[][] {{10_000_000, 5}},
                1000, 1000),
            TestData.product("FIRST_MASTER_STAR", new double[][] {{21_000_000, 5}}, new double[][] {{20_000_000, 5}},
                1000, 1000),
            TestData.product("SECOND_MASTER_STAR", new double[][] {{41_000_000, 5}}, new double[][] {{40_000_000, 5}},
                1000, 1000),
            TestData.product("THIRD_MASTER_STAR", new double[][] {{81_000_000, 5}}, new double[][] {{80_000_000, 5}},
                1000, 1000)));
        Map<String, Object> extra = TestData.map("rarity_upgrades", 1, "upgrade_level", 8);
        // 850M against a plain value of 895M: not a flip as a plain Hyperion...
        AuctionIndex.Listing listing = upgraded("up", 850_000_000L, extra);
        config.ahUpgrades = false;
        assertNull(finder.evaluate(listing, false));

        // ...but its recombobulator and three master stars are worth 0.8 x 10M + 20M + 40M + 80M = 148M,
        // 80% of which counts: 118.4M.
        config.ahUpgrades = true;
        AuctionFlip flip = finder.evaluate(listing, false);
        assertNotNull(flip);
        assertTrue(flip.upgradeOnly);
        assertEquals(118_400_000, flip.valuation.upgradeExtra, 1);
        assertEquals(895_000_000 + 118_400_000, flip.resale, 1);
        assertEquals(4, flip.valuation.upgradeParts.size());

        // When the cheapest other Hyperion has the same upgrades, they are already in its price.
        index.add(upgraded("a0", 880_000_000L, extra));
        assertNull(finder.evaluate(listing, false));
    }

    @Test
    void singleReferenceNeedsTwiceTheMargin() {
        index.add(listing("a1", HYPERION, 900_000_000L, 1));
        AuctionIndex.Listing slightlyCheap = listing("new", HYPERION, 800_000_000L, 1);
        // 900M - 31.5M fees - 800M = 68.5M = 8.6%: enough with two references, not with one.
        assertNull(finder.evaluate(slightlyCheap, false));
        AuctionIndex.Listing veryCheap = listing("new2", HYPERION, 700_000_000L, 1);
        AuctionFlip flip = finder.evaluate(veryCheap, false);
        assertNotNull(flip);
        assertTrue(flip.weak);
    }

    @Test
    void thresholdsBudgetAndBlacklist() {
        hyperionMarket();
        AuctionIndex.Listing cheap = listing("new", HYPERION, 800_000_000L, 1);
        config.ahMinProfit = 100_000_000L;
        assertNull(finder.evaluate(cheap, false));
        config.ahMinProfit = 500_000L;
        config.budget = 500_000_000L;
        assertNull(finder.evaluate(cheap, false));
        config.budget = 0;
        config.blacklist = Collections.singleton("HYPERION");
        assertNull(finder.evaluate(cheap, false));
        config.blacklist = Collections.emptySet();
        assertNotNull(finder.evaluate(cheap, false));
    }

    @Test
    void lowVolumeIsRejected() {
        hyperionMarket();
        for (int m = NOW_MINUTE - 1439; m <= NOW_MINUTE; m++) {
            sales.markObserved(m);
        }
        AuctionIndex.Listing cheap = listing("new", HYPERION, 800_000_000L, 1);
        AuctionFlip flip = finder.evaluate(cheap, false);
        assertNotNull(flip);
        assertEquals(5.0, flip.valuation.volumePerDay, 1e-9);
        config.ahMinVolume = 6;
        assertNull(finder.evaluate(cheap, false));
    }

    @Test
    void neuLowestBinEqualToThePriceIsIgnored() {
        Map<String, Double> lbin = new HashMap<String, Double>();
        lbin.put("HYPERION", 800_000_000.0);
        prices.setNeuLowestBins(lbin);
        AuctionIndex.Listing cheap = listing("new", HYPERION, 800_000_000L, 1);
        assertTrue(Double.isNaN(prices.valuate(HYPERION, "new", 800_000_000, false).neuLbin));
        lbin.put("HYPERION", 1_000_000_000.0);
        assertEquals(1_000_000_000.0, prices.valuate(HYPERION, "new", 800_000_000, false).neuLbin, 1e-3);
        assertNotNull(finder.evaluate(cheap, false));
    }

    @Test
    void coflnetConfirmsWhenOwnSalesAreMissing() {
        index.add(listing("a1", HYPERION, 900_000_000L, 1));
        coflnetHttp.put("https://sky.coflnet.com/api/item/price/HYPERION", "{\"median\":890000000,\"volume\":4}");
        AuctionIndex.Listing cheap = listing("new", HYPERION, 800_000_000L, 1);
        assertNull(finder.evaluate(cheap, false));
        AuctionFlip flip = finder.evaluate(cheap, true);
        assertNotNull(flip);
        assertEquals(890_000_000, flip.resale, 1e-3);
        assertEquals("Coflnet", flip.valuation.volumeSource);
        assertEquals(1, coflnetHttp.count("https://sky.coflnet.com/api/item/price/HYPERION"));
        // Cached afterwards.
        assertNotNull(finder.evaluate(cheap, true));
        assertEquals(1, coflnetHttp.count("https://sky.coflnet.com/api/item/price/HYPERION"));
    }

    @Test
    void bazaarItemsOnTheAuctionHouse() throws IOException {
        config.ahMinProfit = 1_000;
        ItemKey diamond = new ItemKey("ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND", true,
            "ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND");
        prices.setBazaar(snapshot(TestData.product("ENCHANTED_DIAMOND", new double[][] {{210, 100}},
            new double[][] {{200, 100}}, 1000, 1000)));
        AuctionIndex.Listing stack = listing("stack", diamond, 64 * 100L, 64);
        AuctionFlip flip = finder.evaluate(stack, false);
        assertNotNull(flip);
        assertTrue(flip.valuation.bazaar);
        assertEquals(12_800, flip.resale, 1e-6);
        assertEquals(12_800 - 160 - 6_400, flip.profit, 1e-6);
    }

    // ---- Bazaar ----

    private static BazaarSnapshot snapshot(String... products) throws IOException {
        FakeFetcher http = new FakeFetcher().put("https://api.hypixel.net/v2/skyblock/bazaar", TestData.bazaar(1L, products));
        return new HypixelApi(http, () -> "https://api.hypixel.net").fetchBazaar();
    }

    @Test
    void orderFlip() throws IOException {
        config.bzCrashEnabled = false;
        config.bzNpcEnabled = false;
        BazaarSnapshot s = snapshot(
            TestData.product("GOOD", new double[][] {{12_500, 10}}, new double[][] {{10_000, 10}}, 168 * 1000, 168 * 800),
            TestData.product("THIN", new double[][] {{10_100, 10}}, new double[][] {{10_000, 10}}, 168 * 1000, 168 * 800),
            TestData.product("SLOW", new double[][] {{12_500, 10}}, new double[][] {{10_000, 10}}, 168 * 10, 168 * 10));
        List<BazaarFlip> flips = new BazaarFlipFinder(config).find(s, new BazaarHistory(), ItemCatalog.EMPTY, NOW);
        assertEquals(1, flips.size());
        BazaarFlip flip = flips.get(0);
        assertEquals(BazaarFlip.Type.ORDER, flip.type);
        assertEquals("GOOD", flip.productId);
        assertEquals(10_000.1, flip.buyOrderPrice, 1e-9);
        assertEquals(12_499.9, flip.sellOfferPrice, 1e-9);
        double margin = 12_499.9 * (1 - 0.0125) - 10_000.1;
        assertEquals(margin, flip.margin, 1e-6);
        assertEquals(margin * 800, flip.profit, 1e-6);
        assertEquals("/bz Good", flip.command());
    }

    @Test
    void crashNeedsHistoryThenFires() throws IOException {
        config.bzFlipEnabled = false;
        config.bzNpcEnabled = false;
        BazaarHistory history = new BazaarHistory();
        BazaarFlipFinder finder = new BazaarFlipFinder(config);
        for (int i = 60; i >= 3; i--) {
            history.record(snapshot(TestData.product("X", new double[][] {{100, 1000}}, new double[][] {{95, 1000}},
                168 * 100, 168 * 100)), NOW - i * 60_000L);
        }
        BazaarSnapshot dump = snapshot(TestData.product("X",
            new double[][] {{70, 20_000}, {75, 5_000}, {99, 1_000}}, new double[][] {{60, 1000}}, 168 * 100, 168 * 100));
        List<BazaarFlip> flips = finder.find(dump, history, ItemCatalog.EMPTY, NOW);
        assertEquals(1, flips.size());
        BazaarFlip flip = flips.get(0);
        assertEquals(BazaarFlip.Type.CRASH, flip.type);
        assertEquals(100, flip.baseline, 1e-9);
        assertEquals(30, flip.dropPercent, 1e-9);
        assertEquals(25_000, flip.units, 1e-9);
        assertEquals(20_000 * 28.75 + 5_000 * 23.75, flip.profit, 1e-6);

        assertTrue(finder.find(dump, new BazaarHistory(), ItemCatalog.EMPTY, NOW).isEmpty());
    }

    @Test
    void npcFlipRespectsBudget() throws IOException {
        config.bzFlipEnabled = false;
        config.bzCrashEnabled = false;
        Map<String, ItemCatalog.Info> info = new HashMap<String, ItemCatalog.Info>();
        info.put("Y", new ItemCatalog.Info("Y", "Thing", "COMMON", 10));
        ItemCatalog items = new ItemCatalog(info);
        BazaarSnapshot s = snapshot(TestData.product("Y", new double[][] {{8, 100_000}, {9, 100_000}, {11, 1_000}},
            new double[][] {{7, 10}}, 168, 168));
        List<BazaarFlip> flips = new BazaarFlipFinder(config).find(s, new BazaarHistory(), items, NOW);
        assertEquals(1, flips.size());
        assertEquals(BazaarFlip.Type.NPC, flips.get(0).type);
        assertEquals(300_000, flips.get(0).profit, 1e-6);
        assertEquals("Thing", flips.get(0).name);

        config.budget = 500_000;
        assertTrue(new BazaarFlipFinder(config).find(s, new BazaarHistory(), items, NOW).isEmpty());
    }
}
