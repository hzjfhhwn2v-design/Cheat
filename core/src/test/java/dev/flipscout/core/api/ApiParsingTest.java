package dev.flipscout.core.api;

import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.TestData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiParsingTest {
    private static final String H = "https://api.hypixel.net";

    @Test
    void auctionPage() throws IOException {
        String bytes = TestData.plainItem("HYPERION");
        String objectBytes = "{\"uuid\":\"u3\",\"auctioneer\":\"s\",\"item_name\":\"Old\",\"tier\":\"RARE\","
            + "\"starting_bid\":5,\"bin\":true,\"item_bytes\":{\"type\":0,\"data\":\"" + bytes + "\"}}";
        FakeFetcher http = new FakeFetcher().put(H + "/v2/skyblock/auctions?page=0", TestData.auctionPage(0, 7, 1234L,
            TestData.auction("u1", "seller1", "Hyperion", "LEGENDARY", 800_000_000L, true, 1000L, bytes),
            TestData.auction("u2", "seller2", "Hyperion", "LEGENDARY", 1L, false, 1000L, bytes),
            objectBytes));
        http.serverDate = 99L;
        AuctionPage page = new HypixelApi(http, () -> H).fetchAuctionPage(0);
        assertEquals(7, page.totalPages);
        assertEquals(1234L, page.lastUpdated);
        assertEquals(99L, page.serverDate);
        assertEquals(3, page.auctions.size());
        Auction first = page.auctions.get(0);
        assertEquals("u1", first.uuid);
        assertEquals("seller1", first.seller);
        assertEquals(800_000_000L, first.startingBid);
        assertTrue(first.bin);
        assertEquals(bytes, first.itemBytes);
        assertEquals(86_401_000L, first.end);
        assertFalse(page.auctions.get(1).bin);
        assertEquals(bytes, page.auctions.get(2).itemBytes);
    }

    @Test
    void unchangedResponsesStopAfterLastUpdated() throws IOException {
        String bytes = TestData.plainItem("HYPERION");
        FakeFetcher http = new FakeFetcher()
            .put(H + "/v2/skyblock/auctions?page=0", TestData.auctionPage(0, 3, 1234L,
                TestData.auction("u1", "s", "Hyperion", "LEGENDARY", 1L, true, 1000L, bytes)))
            .put(H + "/v2/skyblock/auctions_ended", TestData.endedPage(555L,
                TestData.ended("e1", 10L, true, 500L, bytes)))
            .put(H + "/v2/skyblock/bazaar", TestData.bazaar(42L,
                TestData.product("X", new double[][] {{2, 1}}, new double[][] {{1, 1}}, 1, 1)));
        HypixelApi api = new HypixelApi(http, () -> H);

        AuctionPage same = api.fetchAuctionPage(0, 1234L);
        assertTrue(same.notModified);
        assertEquals(1234L, same.lastUpdated);
        assertEquals(3, same.totalPages);
        assertTrue(same.auctions.isEmpty());
        AuctionPage changed = api.fetchAuctionPage(0, 1000L);
        assertFalse(changed.notModified);
        assertEquals(1, changed.auctions.size());

        assertTrue(api.fetchEnded(555L).notModified);
        assertEquals(1, api.fetchEnded(554L).auctions.size());

        BazaarSnapshot bazaar = api.fetchBazaar(42L);
        assertTrue(bazaar.notModified);
        assertTrue(bazaar.products.isEmpty());
        assertEquals(1, api.fetchBazaar(41L).products.size());
        assertEquals(3, http.aborts.get());
    }

    @Test
    void errorsBecomeApiExceptions() {
        FakeFetcher http = new FakeFetcher()
            .put(H + "/v2/skyblock/auctions?page=0", "{\"success\":false,\"cause\":\"Page not found\"}")
            .put(H + "/v2/skyblock/bazaar", 429, "{}");
        HypixelApi api = new HypixelApi(http, () -> H);
        ApiException notFound = assertThrows(ApiException.class, () -> api.fetchAuctionPage(0));
        assertTrue(notFound.getMessage().contains("Page not found"));
        ApiException limited = assertThrows(ApiException.class, api::fetchBazaar);
        assertEquals(429, limited.status);
    }

    @Test
    void endedAuctions() throws IOException {
        FakeFetcher http = new FakeFetcher().put(H + "/v2/skyblock/auctions_ended", TestData.endedPage(555L,
            TestData.ended("e1", 900_000_000L, true, 500L, TestData.plainItem("HYPERION")),
            TestData.ended("e2", 10L, false, 501L, TestData.plainItem("DIRT"))));
        EndedPage page = new HypixelApi(http, () -> H + "/").fetchEnded();
        assertEquals(555L, page.lastUpdated);
        assertEquals(2, page.auctions.size());
        assertEquals("e1", page.auctions.get(0).auctionId);
        assertEquals(900_000_000L, page.auctions.get(0).price);
        assertTrue(page.auctions.get(0).bin);
        assertFalse(page.auctions.get(1).bin);
    }

    @Test
    void bazaarSidesAreNotSwapped() throws IOException {
        // Deliberately unsorted: the parser must still find the best prices.
        String product = TestData.product("ENCHANTED_DIAMOND",
            new double[][] {{162.5, 100}, {160.1, 640}},
            new double[][] {{150.0, 10}, {155.3, 20569}},
            168 * 1000, 168 * 2000);
        FakeFetcher http = new FakeFetcher().put(H + "/v2/skyblock/bazaar", TestData.bazaar(42L, product));
        BazaarSnapshot snapshot = new HypixelApi(http, () -> H).fetchBazaar();
        assertEquals(42L, snapshot.lastUpdated);
        BazaarProduct p = snapshot.get("ENCHANTED_DIAMOND");
        assertEquals(160.1, p.instaBuy(), 1e-9);
        assertEquals(155.3, p.instaSell(), 1e-9);
        assertEquals(640, p.offerAmounts[0], 1e-9);
        assertEquals(1000, p.instaBuysPerHour(), 1e-9);
        assertEquals(2000, p.instaSellsPerHour(), 1e-9);
    }

    @Test
    void itemCatalog() throws IOException {
        FakeFetcher http = new FakeFetcher().put(H + "/v2/resources/skyblock/items", "{\"success\":true,\"lastUpdated\":1,"
            + "\"items\":[{\"material\":\"DIAMOND\",\"id\":\"ENCHANTED_DIAMOND\",\"name\":\"Enchanted Diamond\","
            + "\"tier\":\"UNCOMMON\",\"npc_sell_price\":1280.0,\"stats\":{\"x\":1}},{\"id\":\"HYPERION\",\"name\":\"Hyperion\"}]}");
        ItemCatalog catalog = new HypixelApi(http, () -> H).fetchItems();
        assertEquals(2, catalog.size());
        assertEquals(1280.0, catalog.get("ENCHANTED_DIAMOND").npcSellPrice, 1e-9);
        assertEquals(0.0, catalog.get("HYPERION").npcSellPrice, 1e-9);
        assertEquals("Enchanted Diamond", catalog.displayName("ENCHANTED_DIAMOND"));
        assertEquals("Ultimate Wise V", catalog.displayName("ENCHANTMENT_ULTIMATE_WISE_5"));
        assertEquals("Ultimate Wise", catalog.bazaarSearch("ENCHANTMENT_ULTIMATE_WISE_5"));
        assertEquals("Wither Essence", catalog.displayName("ESSENCE_WITHER"));
    }

    @Test
    void neuFiles() throws IOException {
        String base = "https://moulberry.codes";
        FakeFetcher http = new FakeFetcher()
            .put(base + "/lowestbin.json", "{\"HYPERION\":9.0E8,\"ENDER_DRAGON;4\":650000000,\"BAD\":\"x\"}")
            .put(base + "/auction_averages/3day.json", "{\"HYPERION\":{\"price\":950000000.0,\"count\":30,\"sales\":12,"
                + "\"clean_price\":910000000.0,\"clean_sales\":9},\"OLD\":12.5}");
        NeuApi neu = new NeuApi(http, () -> base);
        Map<String, Double> lbin = neu.fetchLowestBins();
        assertEquals(900_000_000.0, lbin.get("HYPERION"), 1e-3);
        assertEquals(650_000_000.0, lbin.get("ENDER_DRAGON;4"), 1e-3);
        assertNull(lbin.get("BAD"));
        Map<String, NeuApi.Average> averages = neu.fetchAverages();
        assertEquals(910_000_000.0, averages.get("HYPERION").price, 1e-3);
        assertEquals(4.0, averages.get("HYPERION").salesPerDay, 1e-9);
        assertEquals(12.5, averages.get("OLD").price, 1e-9);
        assertTrue(Double.isNaN(averages.get("OLD").salesPerDay));
    }

    @Test
    void coflnetPrice() throws IOException {
        String base = "https://sky.coflnet.com";
        FakeFetcher http = new FakeFetcher()
            .put(base + "/api/item/price/HYPERION", "{\"max\":1,\"min\":1,\"med\":905000000,\"mean\":1,\"mode\":1,\"volume\":7.5}")
            .put(base + "/api/item/price/JUNK", "{\"median\":0}");
        CoflnetApi api = new CoflnetApi(http, () -> base);
        CoflnetApi.Price price = api.fetchPrice("HYPERION");
        assertEquals(905_000_000.0, price.median, 1e-3);
        assertEquals(7.5, price.volume, 1e-9);
        assertNull(api.fetchPrice("JUNK"));
        assertNull(api.fetchPrice("MISSING"));
    }
}
