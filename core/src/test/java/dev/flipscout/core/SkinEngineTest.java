package dev.flipscout.core;

import dev.flipscout.core.skins.SkinAdvisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Skins seen on the auction house, their prices read in the background, and fire sale alerts. */
class SkinEngineTest {
    private static final String H = "https://api.hypixel.net";
    private static final String C = "https://sky.coflnet.com";
    private static final String PAGE0 = H + "/v2/skyblock/auctions?page=0";
    private static final String CAT = "PET_SKIN_BLACK_CAT_CATGIRL";
    private static final String HISTORY = C + "/api/item/price/" + CAT + "/history/full";
    /** 3 November 2026, 12:00 UTC. */
    private static final long T0 = 1_793_707_200_000L;

    @TempDir
    File dir;

    private final AtomicLong clock = new AtomicLong(T0);
    private FakeFetcher http;
    private FakePlatform platform;
    private Config config;
    private Engine engine;

    @BeforeEach
    void setUp() {
        http = new FakeFetcher();
        platform = new FakePlatform();
        config = new Config();
        config.mayorNotify = false;
        engine = new Engine(config, platform, http, dir, clock::get);
        http.put(H + "/v2/resources/skyblock/items", "{\"success\":true,\"items\":[]}");
        http.put(H + "/v2/skyblock/bazaar", TestData.bazaar(1L));
    }

    private static String cat(String uuid, long price) {
        return TestData.auction(uuid, "s-" + uuid, "Catgirl Black Cat Skin", "EPIC", price, true, T0 - 3_600_000L,
            TestData.plainItem(CAT));
    }

    /** 300 days around 10M, a few sales each. */
    private static String history() {
        StringBuilder sb = new StringBuilder("[");
        int today = (int) (T0 / 86_400_000L);
        for (int i = 0; i < 300; i++) {
            double price = 10_000_000 * (1 + 0.02 * Math.sin(i * 1.7));
            String date = java.time.LocalDate.ofEpochDay(today - 299 + i).toString();
            sb.append(i == 0 ? "" : ",").append("{\"min\":").append(price * 0.98).append(",\"max\":").append(price * 1.02)
                .append(",\"avg\":").append(price).append(",\"volume\":4,\"time\":\"").append(date).append("T00:00:00\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void skinsOnTheAuctionHouseAreLearnedPricedAndAdvised() {
        http.put(PAGE0, TestData.auctionPage(0, 1, T0, cat("c1", 8_000_000), cat("c2", 9_500_000), cat("c3", 10_100_000),
            TestData.auction("h", "s", "Hyperion", "LEGENDARY", 900_000_000L, true, T0, TestData.plainItem("HYPERION"))));
        http.put(HISTORY, history());
        http.put(H + "/v2/skyblock/firesales", "{\"success\":true,\"sales\":[{\"item_id\":\"PET_SKIN_SKELETON_FALLEN_KING\","
            + "\"start\":" + (T0 - 86_400_000L) + ",\"end\":" + (T0 + 86_400_000L) + ",\"amount\":1450,\"price\":950}]}");
        engine.tick();

        assertTrue(engine.initialScanDone());
        assertEquals("Catgirl Black Cat Skin", engine.skinHistory().name(CAT));
        assertEquals(1, engine.skinHistory().names().size());
        assertEquals(Arrays.asList(CAT), engine.skinCandidates());
        assertEquals(1, http.count(HISTORY));
        assertEquals(300, engine.skinHistory().get(CAT).days.size());
        assertTrue(new File(dir, "skins.json.gz").isFile());

        SkinAdvisor.Ranking ranking = engine.skins().rank(10);
        assertEquals(1, ranking.picks.size());
        SkinAdvisor.Pick pick = ranking.picks.get(0);
        assertEquals("c1", pick.lowestUuid);
        assertEquals(9_500_000, pick.sellValue, 1e-6);
        assertEquals(9_500_000 * 0.98 - 8_000_000, pick.profit, 1);

        // The fire sale is announced once.
        List<Msg> alerts = platform.messages;
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).toPlainString().startsWith("[FlipScout] Fire sale: Skeleton Fallen King Skin - 1,450 for 950 gems each"),
            alerts.get(0).toPlainString());
        assertTrue(new File(dir, "firesales.json").isFile());
        clock.addAndGet(31 * 60_000L);
        engine.tick();
        assertEquals(1, platform.messages.size());
        // Read once a day only.
        assertEquals(1, http.count(HISTORY));
        assertTrue(engine.status.errors().get("skins") == null);
    }

    @Test
    void nothingIsReadWithSkinInvestmentsOff() {
        config.skinsEnabled = false;
        http.put(PAGE0, TestData.auctionPage(0, 1, T0, cat("c1", 8_000_000), cat("c2", 9_900_000), cat("c3", 10_100_000)));
        engine.tick();
        assertEquals(0, http.count(HISTORY));
        assertEquals(0, http.count(H + "/v2/skyblock/firesales"));
        // Skins are still recognised, for when it is turned on.
        assertEquals(Arrays.asList(CAT), engine.skinCandidates());
        assertFalse(new File(dir, "skins.json.gz").isFile());
    }
}
