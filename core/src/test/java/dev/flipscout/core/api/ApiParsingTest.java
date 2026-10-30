package dev.flipscout.core.api;

import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.TestData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
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
        // An unknown tag is a 400 on Coflnet: no data, not an error.
        http.put(base + "/api/item/price/UNKNOWN", 400, "{\"slug\":\"item_not_found\"}");
        assertNull(api.fetchPrice("UNKNOWN"));
    }

    @Test
    void itemIdsFromNamesInChat() {
        Map<String, ItemCatalog.Info> items = new HashMap<String, ItemCatalog.Info>();
        items.put("HYPERION", new ItemCatalog.Info("HYPERION", "Hyperion", "LEGENDARY", 0));
        items.put("NECRON_BLADE", new ItemCatalog.Info("NECRON_BLADE", "Necron's Blade (Unrefined)", "LEGENDARY", 0));
        items.put("GRAPPLING_HOOK", new ItemCatalog.Info("GRAPPLING_HOOK", "Grappling Hook", "UNCOMMON", 0));
        Map<String, String> names = new HashMap<String, String>();
        names.put("SHARD_BIRRIES", "Birries Shard");
        names.put("ENCHANTMENT_ULTIMATE_WISE_5", "ultimate wise 5 enchant");
        ItemCatalog catalog = new ItemCatalog(items).withNames(names);
        assertEquals("HYPERION", catalog.idForName("Hyperion"));
        assertEquals("HYPERION", catalog.idForName("§dHeroic Hyperion §6✪✪✪✪✪§c➎"));
        assertEquals("GRAPPLING_HOOK", catalog.idForName("Grappling Hook"));
        assertEquals("SHARD_BIRRIES", catalog.idForName("Birries Shard"));
        assertEquals("PET_ENDER_DRAGON", catalog.idForName("[Lvl 100] Ender Dragon"));
        assertNull(catalog.idForName("Something Unknown"));
        assertNull(catalog.idForName(""));
    }

    @Test
    void coflnetFiltersAreEncodedOneByOne() throws IOException {
        assertEquals("/api/item/price/PET_ENDER_DRAGON?Rarity=LEGENDARY&PetLevel=90-99&PetSkin=None",
            CoflnetApi.pricePath("PET_ENDER_DRAGON?Rarity=LEGENDARY&PetLevel=90-99&PetSkin=None"));
        assertEquals("/api/item/price/HYPERION", CoflnetApi.pricePath("HYPERION"));
        String base = "https://sky.coflnet.com";
        FakeFetcher http = new FakeFetcher().put(base + "/api/item/price/PET_ENDER_DRAGON?Rarity=LEGENDARY&PetLevel=100"
            + "&PetSkin=None", "{\"median\":460000000,\"volume\":50}");
        CoflnetApi.Price price = new CoflnetApi(http, () -> base)
            .fetchPrice("PET_ENDER_DRAGON?Rarity=LEGENDARY&PetLevel=100&PetSkin=None");
        assertEquals(460_000_000.0, price.median, 1e-3);
        assertEquals(50.0, price.volume, 1e-9);
    }

    @Test
    void coflnetNamesFillTheGapsOfTheItemList() throws IOException {
        String base = "https://sky.coflnet.com";
        FakeFetcher http = new FakeFetcher().put(base + "/api/items", "[{\"name\":\"Birries Shard\",\"tag\":\"SHARD_BIRRIES\","
            + "\"flags\":\"BAZAAR\"},{\"name\":\"Loch Emperor Shard\",\"tag\":\"SHARD_SEA_EMPEROR\"},"
            + "{\"name\":\"duplex 1 enchant\",\"tag\":\"ENCHANTMENT_ULTIMATE_REITERATE_1\"},"
            + "{\"name\":\"FACTION_RABBIT_WALKER\",\"tag\":\"FACTION_RABBIT_WALKER\"},"
            + "{\"name\":\"§4Sin§5seeker Scythe\",\"tag\":\"SINSEEKER_SCYTHE\"},{\"name\":null,\"tag\":\"PET_BEE\"}]");
        Map<String, String> names = new CoflnetApi(http, () -> base).fetchItemNames();
        assertEquals("Birries Shard", names.get("SHARD_BIRRIES"));
        assertEquals("Sinseeker Scythe", names.get("SINSEEKER_SCYTHE"));
        // A name that is just the tag says nothing.
        assertNull(names.get("FACTION_RABBIT_WALKER"));
        assertNull(names.get("PET_BEE"));

        ItemCatalog catalog = new ItemCatalog(new HashMap<String, ItemCatalog.Info>()).withNames(names);
        // The bug: /bz "Shard Birries" found nothing.
        assertEquals("Birries Shard", catalog.bazaarSearch("SHARD_BIRRIES"));
        assertEquals("Loch Emperor Shard", catalog.displayName("SHARD_SEA_EMPEROR"));
        // Enchantments named differently in game.
        assertEquals("Duplex I", catalog.displayName("ENCHANTMENT_ULTIMATE_REITERATE_1"));
        assertEquals("Duplex", catalog.bazaarSearch("ENCHANTMENT_ULTIMATE_REITERATE_1"));
        // Without Coflnet's name a shard is still "<X> Shard".
        assertEquals("Mist Shard", catalog.bazaarSearch("SHARD_MIST"));
        assertEquals("Faction Rabbit Walker", catalog.displayName("FACTION_RABBIT_WALKER"));
    }
}
