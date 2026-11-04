package dev.flipscout.core.skins;

import dev.flipscout.core.Config;
import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.extra.Mayors;
import dev.flipscout.core.flip.Fees;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.Valuation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinAdvisorTest {
    static final long NOW = SkinHistoryTest.NOW;
    static final int TODAY = SkinHistoryTest.TODAY;

    /** Auctions up for sale, by skin. */
    static final class FakeLive implements SkinAdvisor.Live {
        final Map<String, List<AuctionIndex.Listing>> listings = new HashMap<String, List<AuctionIndex.Listing>>();
        final Map<String, Double> sales = new HashMap<String, Double>();

        FakeLive list(String id, long... prices) {
            List<AuctionIndex.Listing> out = new ArrayList<AuctionIndex.Listing>();
            for (int i = 0; i < prices.length; i++) {
                out.add(new AuctionIndex.Listing(id.toLowerCase() + i, "seller" + i, id, "EPIC", new ItemKey(id, id, id, null),
                    prices[i], 1, NOW - 60_000, NOW + 86_400_000L, NOW, 1));
            }
            listings.put(id, out);
            return this;
        }

        @Override
        public AuctionIndex.Listing lowest(String id) {
            AuctionIndex.Listing best = null;
            for (AuctionIndex.Listing l : listings.getOrDefault(id, new ArrayList<AuctionIndex.Listing>())) {
                if (best == null || l.price < best.price) {
                    best = l;
                }
            }
            return best;
        }

        @Override
        public int listings(String id) {
            return listings.getOrDefault(id, new ArrayList<AuctionIndex.Listing>()).size();
        }

        @Override
        public Valuation valuate(AuctionIndex.Listing lowest) {
            Valuation v = new Valuation();
            for (AuctionIndex.Listing l : listings.get(lowest.key.id)) {
                if (!l.uuid.equals(lowest.uuid) && (Double.isNaN(v.lbinOther) || l.price < v.lbinOther)) {
                    v.lbinOther = l.price;
                }
            }
            Double median = sales.get(lowest.key.id);
            if (median != null) {
                v.salesMedian = median;
            }
            return v;
        }
    }

    private Config config;
    private SkinHistory history;
    private FireSales fireSales;
    private FakeLive live;
    private SkinAdvisor advisor;
    private final AtomicLong clock = new AtomicLong(NOW);

    @BeforeEach
    void setUp() {
        config = new Config();
        history = new SkinHistory(new FakeFetcher(), () -> "https://sky.coflnet.com", clock::get, null, new Mayors.Pause() {
            @Override
            public boolean pause(long millis) {
                return true;
            }
        });
        fireSales = new FireSales(new FakeFetcher(), () -> "https://api.hypixel.net", clock::get, null);
        live = new FakeLive();
        advisor = new SkinAdvisor(config, history, fireSales, live, clock::get);
    }

    /** A history ending today: {@code days} days around {@code price}, moving a little, with {@code sales} a day. */
    static SkinHistory.Series steady(String id, int days, double price, double wobble, int sales) {
        List<SkinHistory.Day> out = new ArrayList<SkinHistory.Day>();
        for (int i = 0; i < days; i++) {
            double noise = Math.sin(i * 1.7) * Math.cos(i * 0.3);
            out.add(new SkinHistory.Day(TODAY - days + 1 + i, price * (1 + wobble * noise), sales));
        }
        return new SkinHistory.Series(id, out, TODAY - days + 1, NOW);
    }

    /** A price swinging {@code swing} up and down every {@code period} days. */
    static SkinHistory.Series cycle(String id, int days, double price, double swing, int period) {
        List<SkinHistory.Day> out = new ArrayList<SkinHistory.Day>();
        for (int i = 0; i < days; i++) {
            out.add(new SkinHistory.Day(TODAY - days + 1 + i, price * (1 + swing * Math.sin(i * 2 * Math.PI / period)), 5));
        }
        return new SkinHistory.Series(id, out, TODAY - days + 1, NOW);
    }

    private void skin(String id, String name, SkinHistory.Series series) {
        history.learn(id, name);
        history.put(series);
    }

    @Test
    void aSkinListedBelowItsPriceIsAdvisedWithItsNumbers() {
        skin("PET_SKIN_CAT", "Catgirl Black Cat Skin", steady("PET_SKIN_CAT", 400, 9_900_000, 0.02, 5));
        long[] prices = {8_500_000, 10_000_000, 10_200_000, 10_500_000, 10_600_000, 10_700_000, 11_000_000, 11_200_000,
            11_500_000, 12_000_000, 12_500_000, 13_000_000};
        live.list("PET_SKIN_CAT", prices);

        SkinAdvisor.Ranking r = advisor.rank(10);
        assertEquals(1, r.picks.size());
        assertEquals(1, r.listed);
        assertEquals(1, r.analysed);
        SkinAdvisor.Pick p = r.picks.get(0);
        assertTrue(p.buy());
        assertNull(p.excluded);
        assertEquals(8_500_000, p.lowestBin);
        assertEquals("pet_skin_cat0", p.lowestUuid);
        assertEquals(12, p.listings);
        // Sells for the lower of the next cheapest BIN (10M) and this week's sales (about 9.9M).
        assertTrue(p.sellValue < 10_000_000 && p.sellValue > 9_500_000, "sell " + p.sellValue);
        assertEquals("this week's sales", p.sellSource);
        // One skin is too little to learn how skins of its age move: no drift.
        assertEquals(0, p.drift, 1e-12);
        assertEquals(p.sellValue, p.expectedSell, 1e-6);
        assertEquals(Fees.auctionSellFees(p.expectedSell, config), p.fees, 1e-6);
        assertEquals(p.expectedSell - p.fees - 8_500_000, p.profit, 1e-6);
        assertEquals(p.profit / 8_500_000, p.expectedReturn, 1e-12);
        assertTrue(p.profit > 1_000_000, "profit " + p.profit);
        // Bought 14% below what it usually sells for, it was nearly always in profit a month later.
        assertTrue(p.ownHistory);
        assertTrue(p.confidence > 0.95, "confidence " + p.confidence);
        assertTrue(p.confidenceSamples >= 100);
        assertEquals(SkinAdvisor.Risk.LOW, p.risk);
        assertTrue(p.volatility < 0.10);
        assertEquals(5, p.salesPerDay, 1e-9);
        assertEquals(399, p.ageDays);
        assertEquals(Arrays.asList("PET_SKIN_CAT"), advisor.lastRanking());
    }

    @Test
    void atItsUsualPriceTheFeesMakeItALoss() {
        skin("PET_SKIN_CAT", "Catgirl Black Cat Skin", steady("PET_SKIN_CAT", 400, 10_000_000, 0.02, 5));
        live.list("PET_SKIN_CAT", 10_000_000, 10_000_000, 10_100_000, 10_200_000, 10_300_000);
        SkinAdvisor.Ranking r = advisor.rank(10);
        assertTrue(r.picks.isEmpty());
        assertEquals(1, r.notProfitable);
        SkinAdvisor.Pick p = advisor.analyse("PET_SKIN_CAT");
        assertFalse(p.buy());
        assertNull(p.excluded);
        assertTrue(p.profit < 0);
        assertTrue(p.confidence < 0.5, "confidence " + p.confidence);
    }

    @Test
    void skinsThatAreRiskyOrHardToSellAreLeftOut() {
        long[] many = {1_000_000, 1_500_000, 1_500_000, 1_500_000, 1_500_000, 1_500_000};
        skin("FEW", "Few Skin", steady("FEW", 300, 1_500_000, 0.02, 5));
        live.list("FEW", 1_000_000, 1_500_000);
        skin("NEW", "New Skin", steady("NEW", 20, 1_500_000, 0.02, 5));
        live.list("NEW", many);
        skin("RARE", "Rare Skin", steady("RARE", 300, 1_500_000, 0.02, 0));
        live.list("RARE", many);
        List<SkinHistory.Day> crash = new ArrayList<SkinHistory.Day>(steady("CRASH", 300, 5_000_000, 0.01, 5).days);
        for (int i = crash.size() - 60; i < crash.size(); i++) {
            SkinHistory.Day d = crash.get(i);
            crash.set(i, new SkinHistory.Day(d.day, 1_200_000, 5));
        }
        skin("CRASH", "Crash Skin", new SkinHistory.Series("CRASH", crash, TODAY - 299, NOW));
        live.list("CRASH", many);
        skin("GOOD", "Good Skin", steady("GOOD", 300, 1_500_000, 0.02, 5));
        live.list("GOOD", many);
        config.blacklist = new java.util.HashSet<String>(Arrays.asList("GOOD"));

        SkinAdvisor.Ranking r = advisor.rank(10);
        assertTrue(r.picks.isEmpty());
        assertEquals(5, r.analysed);
        assertEquals(Integer.valueOf(1), r.hidden.get("few listings"));
        assertEquals(Integer.valueOf(1), r.hidden.get("too new"));
        assertEquals(Integer.valueOf(1), r.hidden.get("rarely sold"));
        assertEquals(Integer.valueOf(1), r.hidden.get("falling"));
        assertEquals(Integer.valueOf(1), r.hidden.get("blacklisted"));
        assertEquals("only 2 BIN auctions up (minimum 3)", advisor.analyse("FEW").excluded);
        assertEquals("first sold 19 days ago: too new to judge", advisor.analyse("NEW").excluded);
        assertTrue(advisor.analyse("CRASH").excluded.startsWith("fell 76% from its high"), advisor.analyse("CRASH").excluded);
        // The threshold is a setting.
        config.skinsMinListings = 2;
        assertNull(advisor.analyse("FEW").excluded);
    }

    @Test
    void aSkinOnFireSaleWaitsUntilItEnds() throws Exception {
        FakeFetcher hypixel = new FakeFetcher();
        hypixel.put("https://api.hypixel.net/v2/skyblock/firesales", "{\"success\":true,\"sales\":[{\"item_id\":\"GOOD\","
            + "\"start\":" + (NOW - 86_400_000L) + ",\"end\":" + (NOW + 2 * 86_400_000L) + ",\"amount\":1450,\"price\":950}]}");
        fireSales = new FireSales(hypixel, () -> "https://api.hypixel.net", clock::get, null);
        fireSales.refresh();
        advisor = new SkinAdvisor(config, history, fireSales, live, clock::get);
        skin("GOOD", "Good Skin", steady("GOOD", 300, 1_500_000, 0.02, 5));
        live.list("GOOD", 1_000_000, 1_500_000, 1_500_000, 1_500_000, 1_500_000, 1_500_000);
        SkinAdvisor.Pick p = advisor.analyse("GOOD");
        assertEquals("fire sale", p.excludedGroup);
        assertEquals(1450, p.fireSale.amount);
        clock.set(NOW + 3 * 86_400_000L);
        assertTrue(advisor.analyse("GOOD").buy());
        assertEquals(1450, advisor.analyse("GOOD").fireSale.amount);
    }

    @Test
    void theBestReturnForItsRiskComesFirst() {
        // Same discount, but the second moves three times as much.
        skin("CALM", "Calm Skin", steady("CALM", 300, 2_000_000, 0.02, 5));
        live.list("CALM", 1_700_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000);
        skin("WILD", "Wild Skin", cycle("WILD", 300, 2_000_000, 0.25, 60));
        live.list("WILD", 1_700_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000);
        skin("CHEAP", "Cheap Skin", steady("CHEAP", 300, 2_000_000, 0.02, 5));
        live.list("CHEAP", 1_200_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000);
        SkinAdvisor.Ranking r = advisor.rank(10);
        assertEquals(3, r.picks.size());
        assertEquals("CHEAP", r.picks.get(0).id);
        assertEquals("CALM", r.picks.get(1).id);
        assertEquals("WILD", r.picks.get(2).id);
        assertTrue(r.picks.get(2).volatility > r.picks.get(1).volatility);
        assertEquals(SkinAdvisor.Risk.HIGH, r.picks.get(2).risk);
        assertEquals(2, advisor.rank(2).picks.size());
    }

    @Test
    void theSalePriceFollowsTheFlipStrategy() {
        skin("CAT", "Cat Skin", steady("CAT", 300, 9_000_000, 0.02, 5));
        live.list("CAT", 8_000_000, 9_500_000, 9_600_000, 9_700_000);
        live.sales.put("CAT", 9_200_000.0);
        assertEquals(9_200_000, advisor.analyse("CAT").sellValue, 1e-6);
        assertEquals("recent sales", advisor.analyse("CAT").sellSource);
        config.ahStrategy = "lbin";
        assertEquals(9_500_000, advisor.analyse("CAT").sellValue, 1e-6);
        assertEquals("the next cheapest BIN", advisor.analyse("CAT").sellSource);
        config.ahStrategy = "sales";
        assertEquals(9_200_000, advisor.analyse("CAT").sellValue, 1e-6);
    }

    @Test
    void aSkinWithoutAuctionsOrHistoryIsExplained() {
        history.learn("GONE", "Gone Skin");
        SkinAdvisor.Pick p = advisor.analyse("GONE");
        assertEquals("its price history has not been read yet", p.excluded);
        history.put(steady("GONE", 300, 1_000_000, 0.02, 5));
        p = advisor.analyse("GONE");
        assertEquals("no BIN auction of it is up right now", p.excluded);
        assertEquals("Gone Skin", p.name);
        assertTrue(p.change30 > -0.2 && p.change30 < 0.2);
        assertTrue(advisor.rank(10).picks.isEmpty());
    }

    @Test
    void skinsOfTheSameAgeTellTheUsualChange() {
        // Forty skins two years old that each rose 1% a week: their age group rose about 4.4% a month.
        for (int s = 0; s < 40; s++) {
            List<SkinHistory.Day> days = new ArrayList<SkinHistory.Day>();
            for (int i = 0; i < 400; i++) {
                days.add(new SkinHistory.Day(TODAY - 399 + i, 1_000_000 * Math.pow(1.01, i / 7.0) * (1 + 0.003 * (s % 3)), 4));
            }
            history.put(new SkinHistory.Series("OLD" + s, days, TODAY - 900, NOW));
        }
        AgeModel m = advisor.model();
        assertTrue(m.samples(800) >= AgeModel.MIN_SAMPLES);
        assertEquals(30 / 7.0 * Math.log(1.01), m.drift(800), 0.002);
        assertEquals(1, m.rise(800), 1e-9);
        // Nothing known about young skins: the median of all is used.
        assertEquals(m.drift(800), m.drift(10), 1e-12);
        assertEquals("1 to 2 years", AgeModel.label(500));
        assertEquals(4, AgeModel.group(800));
        assertEquals(0, AgeModel.group(0));
    }
}
