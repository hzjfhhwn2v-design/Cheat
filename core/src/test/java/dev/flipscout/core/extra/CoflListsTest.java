package dev.flipscout.core.extra;

import dev.flipscout.core.json.JsonTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoflListsTest {
    private final FakeMarket market = new FakeMarket();

    private static List<Object> json(String text) throws IOException {
        return JsonTree.arr(JsonTree.parse(text));
    }

    private static final String KAT = "[{\"volume\":4.0,\"median\":20000000,\"upgradeCost\":1000000.0,\"materialCost\":500000.0,"
        + "\"originAuction\":\"aaa\",\"coreData\":{\"name\":\"Seal\",\"baseRarity\":\"RARE\",\"hours\":24.0,\"cost\":0,"
        + "\"material\":\"LARGE_SEAL_PLUSHIE\",\"amount\":1,\"materials\":{\"LARGE_SEAL_PLUSHIE\":1},\"itemTag\":\"PET_SEAL\"},"
        + "\"targetRarity\":\"EPIC\",\"profit\":6000000,\"referenceAuction\":\"r\",\"purchaseCost\":12500000,"
        + "\"originAuctionName\":\"[Lvl 1] Seal\"},"
        + "{\"volume\":4.0,\"median\":20000000,\"upgradeCost\":1000000.0,\"materialCost\":500000.0,"
        + "\"originAuction\":\"bbb\",\"coreData\":{\"baseRarity\":\"RARE\",\"hours\":24.0,\"itemTag\":\"PET_SEAL\"},"
        + "\"targetRarity\":\"EPIC\",\"purchaseCost\":14000000,\"originAuctionName\":\"[Lvl 1] Seal\"}]";

    @Test
    void katFlipsUseTheLivePriceAndKeepTheBestAuctionPerPet() throws IOException {
        List<Opportunity> list = CoflLists.parse(OpportunityKind.KAT, json(KAT), market);
        assertEquals(1, list.size());
        Opportunity o = list.get(0);
        assertEquals("/viewauction aaa", o.command);
        // 20M sells with 2% + 1% fees: 19.4M; costs 12.5M + 1M + 0.5M.
        assertEquals(20_000_000 - 600_000 - 14_000_000, o.profit, 1);
        assertEquals(24, o.hours, 1e-9);
        assertTrue(o.note.contains("Rare") && o.note.contains("Epic"), o.note);
        assertTrue(o.name.contains("[Lvl 1] Seal"), o.name);
        assertTrue(String.join("\n", o.details).contains("1x Large Seal Plushie"), o.details.toString());

        // Once every auction is known: a sold one is skipped, a listed one is priced as it is now.
        market.indexed = true;
        market.auctions.put("bbb", 11_000_000L);
        list = CoflLists.parse(OpportunityKind.KAT, json(KAT), market);
        assertEquals(1, list.size());
        assertEquals("/viewauction bbb", list.get(0).command);
        assertEquals(11_000_000 + 1_500_000, list.get(0).cost, 1);
    }

    private static final String CRAFT = "[{\"itemId\":\"FLAME_BREAKER_BOOTS\",\"itemName\":\"§5Flamebreaker Boots\",\"sellPrice\":3000000,"
        + "\"craftCost\":900000,\"ingredients\":[{\"itemId\":\"ENCHANTED_HARD_STONE\",\"count\":80,\"cost\":900000}],"
        + "\"reqCollection\":{\"name\":\"Hard Stone\",\"level\":5},\"type\":\"crafting\",\"volume\":17.0,\"median\":2500000}]";

    @Test
    void craftsArePricedWithLiveIngredientsAndTheLowerSalePrice() throws IOException {
        market.product("ENCHANTED_HARD_STONE", 10_000, 9_000);
        List<Opportunity> list = CoflLists.parse(OpportunityKind.CRAFT, json(CRAFT), market);
        assertEquals(1, list.size());
        Opportunity o = list.get(0);
        assertEquals(800_000, o.cost, 1e-6);
        // Conservative: the median (2.5M) is below the lowest BIN (3M); 1% + 1% fees.
        assertEquals(2_500_000 - 50_000, o.value, 1e-6);
        assertEquals("/viewrecipe FLAME_BREAKER_BOOTS", o.command);
        assertTrue(o.suggest);
        assertTrue(o.note.contains("Hard Stone V"), o.note);
        assertEquals(17, o.perDay, 1e-9);

        market.config.ahStrategy = "lbin";
        assertEquals(3_000_000 - 60_000, CoflLists.parse(OpportunityKind.CRAFT, json(CRAFT), market).get(0).value, 1e-6);
    }

    private static final String FORGE = "[{\"craftData\":{\"itemId\":\"PORTABLE_CAMPFIRE\",\"itemName\":\"§9Portable Campfire\","
        + "\"sellPrice\":3400000,\"craftCost\":2700000,\"ingredients\":[{\"itemId\":\"X\",\"count\":1,\"cost\":2700000}],"
        + "\"volume\":40.0,\"median\":3400000},\"duration\":1800,\"requiredHotMLevel\":8,\"profitPerHour\":5,"
        + "\"requirements\":{\"HotM\":8}},"
        + "{\"craftData\":{\"itemId\":\"TUNGSTEN_KEY\",\"sellPrice\":3000000,\"ingredients\":[{\"itemId\":\"Y\",\"count\":1,"
        + "\"cost\":1000000}],\"volume\":400.0,\"median\":3000000},\"duration\":30,\"requiredHotMLevel\":7}]";

    @Test
    void forgesUseTheirTimeAndYourHotmLevel() throws IOException {
        List<Opportunity> list = CoflLists.parse(OpportunityKind.FORGE, json(FORGE), market);
        assertEquals(2, list.size());
        Opportunity campfire = list.get(0).command.endsWith("PORTABLE_CAMPFIRE") ? list.get(0) : list.get(1);
        assertEquals(0.5, campfire.hours, 1e-9);
        assertEquals(campfire.profit * 2, campfire.perHour(), 1e-6);
        assertEquals("§8HotM 8", campfire.note);
        // "30" is not a real forge time: no profit per hour for it.
        Opportunity key = campfire == list.get(0) ? list.get(1) : list.get(0);
        assertTrue(Double.isNaN(key.hours));

        market.config.otherForgeHotm = 7;
        list = CoflLists.parse(OpportunityKind.FORGE, json(FORGE), market);
        assertEquals(1, list.size());
        assertTrue(list.get(0).command.endsWith("TUNGSTEN_KEY"));
    }

    @Test
    void fusionsAreRepricedFromTheBazaar() throws IOException {
        String fusion = "[{\"inputs\":{\"SHARD_HONEYBUZZ\":5,\"SHARD_SALMON\":5},\"inputCost\":44079.0,\"volume\":24032.0,"
            + "\"outputValue\":45702.2,\"output\":\"SHARD_GROVE\",\"outputCount\":2,\"depth\":1}]";
        market.product("SHARD_HONEYBUZZ", 4_000, 3_500).product("SHARD_SALMON", 5_000, 4_500).product("SHARD_GROVE", 60_000, 50_000);
        Opportunity o = CoflLists.parse(OpportunityKind.FUSION, json(fusion), market).get(0);
        assertEquals(45_000, o.cost, 1e-6);
        assertEquals(100_000 * (1 - 0.0125), o.value, 1e-6);
        assertEquals("§7x2", o.note);
        assertEquals("/bz Honeybuzz Shard", o.command);
        assertTrue(o.key.startsWith("FUSION:SHARD_GROVE:"), o.key);
    }

    @Test
    void npcFlipsCountCoinsAndItemsAndOpenTheRightMarket() throws IOException {
        String npc = "[{\"npcName\":\"Moby\",\"itemId\":\"EMPTY_CHUM_BUCKET\",\"itemName\":\"Empty Chum Bucket\",\"npcBuyPrice\":5885.6,"
            + "\"sellPrice\":12000.5,\"profit\":6114.9,\"costs\":[{\"itemName\":\"Coins\",\"price\":2000.0,\"amount\":2000,"
            + "\"itemTag\":\"SKYBLOCK_COINS\"},{\"itemName\":\"Glowing Mushroom\",\"price\":3885.6,\"amount\":8,"
            + "\"itemTag\":\"GLOWING_MUSHROOM\"}],\"volume\":6309.0},"
            + "{\"npcName\":\"Ophelia\",\"itemId\":\"CANDY_TALISMAN\",\"itemName\":\"Candy Talisman\",\"npcBuyPrice\":19195.2,"
            + "\"sellPrice\":280000.0,\"costs\":[{\"itemName\":\"Green Candy\",\"price\":19195.2,\"amount\":16,"
            + "\"itemTag\":\"GREEN_CANDY\"}],\"volume\":130.5}]";
        market.product("GLOWING_MUSHROOM", 500, 400).product("EMPTY_CHUM_BUCKET", 13_000, 12_000);
        List<Opportunity> list = CoflLists.parse(OpportunityKind.NPC, json(npc), market);
        assertEquals(2, list.size());
        Opportunity talisman = list.get(0);
        assertEquals("/ahs Candy Talisman", talisman.command);
        assertTrue(talisman.suggest);
        assertEquals(19_195.2, talisman.cost, 1e-6);
        Opportunity bucket = list.get(1);
        assertEquals(2_000 + 8 * 500, bucket.cost, 1e-6);
        assertEquals(12_000 * (1 - 0.0125), bucket.value, 1e-6);
        assertEquals("/bz Empty Chum Bucket", bucket.command);
        assertEquals("§8from Moby", bucket.note);
    }

    @Test
    void bookCombinationsNeedTwoBooksPerLevel() throws IOException {
        String books = "[{\"startTag\":\"ENCHANTMENT_PROSPERITY_3\",\"endTag\":\"ENCHANTMENT_PROSPERITY_5\",\"profitPerHour\":10417,"
            + "\"hourlyVolume\":2,\"estimatedFees\":1205},"
            + "{\"startTag\":\"ENCHANTMENT_GREEN_THUMB_3\",\"endTag\":\"ENCHANTMENT_GREEN_THUMB_4\",\"hourlyVolume\":62}]";
        market.product("ENCHANTMENT_PROSPERITY_3", 100_000, 90_000).product("ENCHANTMENT_PROSPERITY_5", 900_000, 800_000);
        List<Opportunity> list = CoflLists.parse(OpportunityKind.BOOKS, json(books), market);
        // Green Thumb is not on our bazaar snapshot: skipped.
        assertEquals(1, list.size());
        Opportunity o = list.get(0);
        assertEquals(400_000, o.cost, 1e-6);
        assertEquals(800_000 * (1 - 0.0125), o.value, 1e-6);
        assertEquals("§8from 4x III", o.note);
        assertEquals(48, o.perDay, 1e-9);
    }

    @Test
    void lowSupplyIsSortedByCoinsTradedADay() throws IOException {
        String low = "[{\"tag\":\"A\",\"supply\":3,\"median\":1000,\"lbinData\":{\"lowest\":900,\"uuid\":\"u1\"},\"volume\":1.0},"
            + "{\"tag\":\"B\",\"supply\":1,\"median\":500000,\"lbinData\":{\"lowest\":450000,\"uuid\":\"u2\"},\"volume\":2.0},"
            + "{\"supply\":1}]";
        List<SupplyItem> list = CoflLists.parseLowSupply(json(low), market);
        assertEquals(2, list.size());
        assertEquals("B", list.get(0).tag);
        assertEquals("u2", list.get(0).lowestUuid);
        assertEquals(1, list.get(0).supply);
    }

    @Test
    void oddEntriesAreSkipped() throws IOException {
        assertTrue(CoflLists.parse(OpportunityKind.CRAFT, json("[{},{\"itemId\":\"X\"},5,null]"), market).isEmpty());
        assertTrue(CoflLists.parse(OpportunityKind.KAT, json("[{\"coreData\":{}}]"), market).isEmpty());
        assertNull(OpportunityKind.byId("nope"));
        assertEquals(OpportunityKind.BOOKS, OpportunityKind.byId("Books"));
    }

    @Test
    void auctionPricesFollowTheStrategy() {
        assertEquals(90, CoflLists.auctionPrice("conservative", 100, 90), 1e-9);
        assertEquals(100, CoflLists.auctionPrice("lbin", 100, 90), 1e-9);
        assertEquals(90, CoflLists.auctionPrice("sales", 100, 90), 1e-9);
        assertEquals(100, CoflLists.auctionPrice("conservative", 100, Double.NaN), 1e-9);
        assertEquals(4, CoflLists.level("ENCHANTMENT_GREEN_THUMB_4"));
    }
}
