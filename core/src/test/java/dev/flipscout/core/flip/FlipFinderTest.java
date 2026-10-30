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
import dev.flipscout.core.market.Valuation;
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
    private static final ItemKey HYPERION = new ItemKey("HYPERION", "HYPERION", "HYPERION", "HYPERION");

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

    /** Hyperion stars cost 150, 300, 500, 900 and 1,500 Wither essence; a crimson helmet has ten levels. */
    private void starCosts() {
        ItemCatalog.StarCost[][] hyperion = new ItemCatalog.StarCost[5][];
        int[] amounts = {150, 300, 500, 900, 1500};
        for (int i = 0; i < 5; i++) {
            hyperion[i] = new ItemCatalog.StarCost[] {new ItemCatalog.StarCost("ESSENCE_WITHER", amounts[i])};
        }
        ItemCatalog.StarCost[][] crimson = new ItemCatalog.StarCost[10][];
        for (int i = 0; i < 10; i++) {
            crimson[i] = new ItemCatalog.StarCost[] {new ItemCatalog.StarCost("ESSENCE_CRIMSON", 1000)};
        }
        Map<String, ItemCatalog.Info> infos = new HashMap<String, ItemCatalog.Info>();
        infos.put("HYPERION", new ItemCatalog.Info("HYPERION", "Hyperion", "LEGENDARY", 0, hyperion, true));
        infos.put("CRIMSON_HELMET", new ItemCatalog.Info("CRIMSON_HELMET", "Crimson Helmet", "LEGENDARY", 0, crimson, false));
        prices.setCatalog(new ItemCatalog(infos));
    }

    @Test
    void upgradesAreCountedBeyondThoseOfTheCheapestListing() throws IOException {
        hyperionMarket();
        starCosts();
        prices.setBazaar(snapshot(
            TestData.product("ESSENCE_WITHER", new double[][] {{2_100, 5}}, new double[][] {{2_000, 5}}, 1000, 1000),
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

        // ...but its upgrades are worth 0.8 x 10M (recombobulator) + 0.75 x 3,350 x 2,000 (five stars of
        // essence) + 20M + 40M + 80M (three master stars) = 153.025M, 80% of which counts: 122.42M.
        config.ahUpgrades = true;
        AuctionFlip flip = finder.evaluate(listing, false);
        assertNotNull(flip);
        assertTrue(flip.upgradeOnly);
        assertEquals(122_420_000, flip.valuation.upgradeExtra, 1);
        assertEquals(895_000_000 + 122_420_000, flip.resale, 1);
        assertEquals(2, flip.valuation.upgradeParts.size());

        // When the cheapest other Hyperion has the same upgrades, they are already in its price...
        index.add(upgraded("a0", 880_000_000L, extra));
        assertNull(finder.evaluate(listing, false));
        // ...and a plain one is worth that much less than it: 800M is not cheap after all.
        config.ahUpgrades = false;
        AuctionIndex.Listing plain = listing("plain", HYPERION, 800_000_000L, 1);
        assertNotNull(finder.evaluate(plain, false));
        config.ahUpgrades = true;
        assertNull(finder.evaluate(plain, false));
        assertEquals(880_000_000 - 122_420_000, prices.valuate(HYPERION, "plain", 800_000_000, false).value
            + upgradeAdjustment(plain), 1);
    }

    private double upgradeAdjustment(AuctionIndex.Listing listing) {
        Valuation v = prices.valuate(listing.key, listing.uuid, listing.unitPrice, false);
        return prices.addUpgrades(v, listing, config.ahUpgradePercent / 100.0);
    }

    @Test
    void crimsonStarsAreNotMasterStars() throws IOException {
        starCosts();
        prices.setBazaar(snapshot(
            TestData.product("ESSENCE_CRIMSON", new double[][] {{1_100, 5}}, new double[][] {{1_000, 5}}, 1000, 1000),
            TestData.product("FIRST_MASTER_STAR", new double[][] {{21_000_000, 5}}, new double[][] {{20_000_000, 5}},
                1000, 1000)));
        // Eight crimson stars: eight levels of 1,000 essence at 1,000 each, counted at 75%.
        assertEquals(8 * 1_000 * 1_000 * 0.75,
            prices.upgradeValue(ItemKeys.upgrades("CRIMSON_HELMET", TestData.map("upgrade_level", 8)), null), 1e-6);
    }

    @Test
    void singleReferenceNeedsTwiceTheMarginAndEnoughListings() {
        index.add(listing("a1", HYPERION, 900_000_000L, 1));
        AuctionIndex.Listing veryCheap = listing("new2", HYPERION, 700_000_000L, 1);
        // One other listing could just be overpriced.
        assertNull(finder.evaluate(veryCheap, false));
        index.add(listing("a2", HYPERION, 950_000_000L, 1));
        index.add(listing("a3", HYPERION, 990_000_000L, 1));
        AuctionFlip flip = finder.evaluate(veryCheap, false);
        assertNotNull(flip);
        assertTrue(flip.weak);
        // 900M - 31.5M fees - 800M = 68.5M = 8.6%: enough with two references, not with one.
        AuctionIndex.Listing slightlyCheap = listing("new", HYPERION, 800_000_000L, 1);
        assertNull(finder.evaluate(slightlyCheap, false));
    }

    @Test
    void hugeMarginsWithoutSalesNeedASecondReference() {
        index.add(listing("a1", HYPERION, 900_000_000L, 1));
        index.add(listing("a2", HYPERION, 950_000_000L, 1));
        index.add(listing("a3", HYPERION, 990_000_000L, 1));
        // 300M against a lowest BIN of 900M: +189%, more likely three inflated listings than a bargain...
        AuctionIndex.Listing suspicious = listing("new", HYPERION, 300_000_000L, 1);
        assertNull(finder.evaluate(suspicious, false));
        // ...unless Coflnet's sales confirm the price.
        coflnetHttp.put("https://sky.coflnet.com/api/item/price/HYPERION", "{\"median\":890000000,\"volume\":4}");
        AuctionFlip flip = finder.evaluate(suspicious, true);
        assertNotNull(flip);
        assertFalse(flip.weak);
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
    void petsGetTheirSalesVolumeFromCoflnet() {
        ItemKey dragon = new ItemKey("PET", "PET_ENDER_DRAGON;LEGENDARY;L100",
            "PET_ENDER_DRAGON?Rarity=LEGENDARY&PetLevel=100&PetSkin=None", null);
        index.add(listing("p1", dragon, 470_000_000L, 1));
        index.add(listing("p2", dragon, 475_000_000L, 1));
        index.add(listing("p3", dragon, 480_000_000L, 1));
        String url = "https://sky.coflnet.com/api/item/price/PET_ENDER_DRAGON?Rarity=LEGENDARY&PetLevel=100&PetSkin=None";
        coflnetHttp.put(url, "{\"median\":460000000,\"volume\":53.5}");
        AuctionIndex.Listing cheap = listing("cheap", dragon, 380_000_000L, 1);
        // Our own few sales of this pet tell nothing about its volume: Coflnet is asked.
        assertTrue(prices.needsRemoteSales(dragon));
        AuctionFlip flip = finder.evaluate(cheap, true);
        assertNotNull(flip);
        assertEquals(53.5, flip.valuation.volumePerDay, 1e-9);
        assertEquals("Coflnet", flip.valuation.volumeSource);
        assertEquals(460_000_000, flip.resale, 1e-3);
        assertFalse(flip.weak);
        assertEquals(1, coflnetHttp.count(url));
        // Known now: not asked again, and no longer missing.
        assertFalse(prices.needsRemoteSales(dragon));
        assertFalse(prices.wouldUseRemote(dragon, flip.valuation));
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
        ItemKey diamond = new ItemKey("ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND");
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
    void orderFlipsSkipHeldUpPrices() throws IOException {
        config.bzCrashEnabled = false;
        config.bzNpcEnabled = false;
        BazaarFlipFinder finder = new BazaarFlipFinder(config);
        BazaarSnapshot heldUp = snapshot(TestData.product("HELD", new double[][] {{250_000, 10}},
            new double[][] {{24_000, 10}}, 168 * 1000, 168 * 800));
        // No history yet: a 900% margin is not trusted.
        assertTrue(finder.find(heldUp, new BazaarHistory(), ItemCatalog.EMPTY, NOW).isEmpty());
        BazaarSnapshot usual = snapshot(TestData.product("HELD", new double[][] {{30_000, 10}},
            new double[][] {{24_000, 10}}, 168 * 1000, 168 * 800));
        BazaarHistory history = new BazaarHistory();
        for (int i = 60; i >= 3; i--) {
            history.record(usual, NOW - i * 60_000L);
        }
        // It usually sells around 30k: offered at 250k now, someone is holding the price up.
        assertTrue(finder.find(heldUp, history, ItemCatalog.EMPTY, NOW).isEmpty());
        assertEquals(1, finder.find(usual, history, ItemCatalog.EMPTY, NOW).size());
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
