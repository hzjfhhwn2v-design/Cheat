package dev.flipscout.core.item;

import dev.flipscout.core.TestData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static dev.flipscout.core.TestData.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemKeysTest {
    private static List<String> parts(Upgrades upgrades) {
        List<String> out = new ArrayList<String>();
        for (Upgrades.Part part : upgrades.parts()) {
            out.add(part.kind + ":" + part.id + "x" + part.count);
        }
        Collections.sort(out);
        return out;
    }

    @Test
    void upgradesAreReadFromTheNbt() throws IOException {
        Map<String, Object> gems = map("JASPER_0", "FINE", "COMBAT_0", map("quality", "PERFECT", "uuid", "x"),
            "COMBAT_0_gem", "SAPPHIRE", "unlocked_slots", Arrays.asList("JASPER_0", "COMBAT_0"));
        String bytes = TestData.itemBytes(map("id", "HYPERION", "rarity_upgrades", 1, "hot_potato_count", 15,
            "enchantments", map("sharpness", 5, "ultimate_wise", 5, "giant_killer", 7), "upgrade_level", 8,
            "gems", gems, "ability_scroll", Arrays.asList("IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL"),
            "art_of_war_count", 1, "ethermerge", (byte) 1), null, 1);
        ItemKeys.Decoded d = ItemKeys.decode(bytes, "Heroic Hyperion ✪✪✪✪✪➌");
        // Upgrades do not split the key: the price comparison still sees every Hyperion.
        assertEquals("HYPERION", d.key.key);
        List<String> expected = new ArrayList<String>(Arrays.asList(
            "RECOMBOBULATOR:RECOMBOBULATOR_3000x1", "HOT_POTATO_BOOK:HOT_POTATO_BOOKx10",
            "FUMING_POTATO_BOOK:FUMING_POTATO_BOOKx5", "ENCHANTMENT:ENCHANTMENT_ULTIMATE_WISE_5x1",
            "ENCHANTMENT:ENCHANTMENT_GIANT_KILLER_7x1", "STARS:HYPERIONx8", "GEMSTONE:FINE_JASPER_GEMx1",
            "GEMSTONE:PERFECT_SAPPHIRE_GEMx1", "ABILITY_SCROLL:IMPLOSION_SCROLLx1",
            "ABILITY_SCROLL:SHADOW_WARP_SCROLLx1", "ART_OF_WAR:THE_ART_OF_WARx1", "ETHERWARP:ETHERWARP_MERGERx1",
            "ETHERWARP:ETHERWARP_CONDUITx1"));
        Collections.sort(expected);
        assertEquals(expected, parts(d.upgrades));
    }

    @Test
    void newerUpgradeFormats() throws IOException {
        String drill = TestData.itemBytes(map("id", "TITANIUM_DRILL_4", "engine", map("id", "AMBER_POLISHED_DRILL_ENGINE"),
            "fuel_tank", map("id", "PERFECTLY_CUT_FUEL_TANK"), "drill_part_upgrade_module", "goblin_omelette_blue_cheese"),
            null, 1);
        assertEquals(Arrays.asList("DRILL_PART:AMBER_POLISHED_DRILL_ENGINEx1", "DRILL_PART:GOBLIN_OMELETTE_BLUE_CHEESEx1",
            "DRILL_PART:PERFECTLY_CUT_FUEL_TANKx1"), parts(ItemKeys.decode(drill, null).upgrades));
        // Most drills on the auction house name each part both ways: still one of each.
        String both = TestData.itemBytes(map("id", "TITANIUM_DRILL_4", "engine", map("id", "AMBER_POLISHED_DRILL_ENGINE"),
            "drill_part_engine", "amber_polished_drill_engine", "fuel_tank", map("id", "PERFECTLY_CUT_FUEL_TANK"),
            "drill_part_fuel_tank", "perfectly_cut_fuel_tank", "upgrade_module", map("id", "GOBLIN_OMELETTE_SUNNY_SIDE"),
            "drill_part_upgrade_module", "goblin_omelette_sunny_side", "drill_fuel", 86124), null, 1);
        assertEquals(Arrays.asList("DRILL_PART:AMBER_POLISHED_DRILL_ENGINEx1", "DRILL_PART:GOBLIN_OMELETTE_SUNNY_SIDEx1",
            "DRILL_PART:PERFECTLY_CUT_FUEL_TANKx1"), parts(ItemKeys.decode(both, null).upgrades));
        String rod = TestData.itemBytes(map("id", "ROD_OF_THE_SEA", "line", map("part", "speedy_line", "uuid", "x"),
            "hook", map("part", "hotspot_hook"), "wet_book_count", 5), null, 1);
        assertEquals(Arrays.asList("ROD_PART:HOTSPOT_HOOKx1", "ROD_PART:SPEEDY_LINEx1", "WET_BOOK:WET_BOOKx5"),
            parts(ItemKeys.decode(rod, null).upgrades));
        String helmet = TestData.itemBytes(map("id", "SHADOW_ASSASSIN_HELMET", "skin", "SHADOW_ASSASSIN_ADMIRAL",
            "divan_powder_coating", 1, "talisman_enrichment", "magic_find"), null, 1);
        assertEquals(Arrays.asList("DIVAN_POWDER_COATING:DIVAN_POWDER_COATINGx1", "SKIN:SHADOW_ASSASSIN_ADMIRALx1",
            "TALISMAN_ENRICHMENT:TALISMAN_ENRICHMENT_MAGIC_FINDx1"), parts(ItemKeys.decode(helmet, null).upgrades));
    }

    @Test
    void midasWeaponsAreKeptApartByWinningBid() throws IOException {
        String cheap = TestData.itemBytes(map("id", "MIDAS_SWORD", "winning_bid", 3_000_000), null, 1);
        String maxed = TestData.itemBytes(map("id", "MIDAS_SWORD", "winning_bid", 50_000_000), null, 1);
        assertEquals("MIDAS_SWORD[BID <10M]", ItemKeys.decode(cheap, null).key.key);
        assertEquals("MIDAS_SWORD[BID 50-100M]", ItemKeys.decode(maxed, null).key.key);
    }

    @Test
    void partyHatsAreKeptApartByColourEmojiAndYear() throws IOException {
        String hat = TestData.itemBytes(map("id", "PARTY_HAT_SLOTH", "party_hat_emoji", "tears", "party_hat_year", 2023),
            null, 1);
        assertEquals("PARTY_HAT_SLOTH[TEARS][2023]", ItemKeys.decode(hat, null).key.key);
    }

    @Test
    void petHeldItemIsAnUpgrade() throws IOException {
        String petInfo = "{\"type\":\"ENDER_DRAGON\",\"tier\":\"LEGENDARY\",\"heldItem\":\"PET_ITEM_TIER_BOOST\"}";
        ItemKeys.Decoded d = ItemKeys.decode(TestData.itemBytes(map("id", "PET", "petInfo", petInfo), null, 1),
            "[Lvl 100] Ender Dragon");
        assertEquals("PET_ENDER_DRAGON;LEGENDARY;L100", d.key.key);
        assertEquals(Collections.singletonList("PET_ITEM:PET_ITEM_TIER_BOOSTx1"), parts(d.upgrades));
    }

    @Test
    void itemsWithoutUpgradesAndStacksHaveNone() throws IOException {
        assertNull(ItemKeys.decode(TestData.plainItem("HYPERION"), null).upgrades);
        String tableEnchantsOnly = TestData.itemBytes(map("id", "ASPECT_OF_THE_END",
            "enchantments", map("sharpness", 5, "critical", 5)), null, 1);
        assertNull(ItemKeys.decode(tableEnchantsOnly, null).upgrades);
        String stack = TestData.itemBytes(map("id", "ENCHANTED_DIAMOND", "rarity_upgrades", 1), null, 64);
        assertNull(ItemKeys.decode(stack, null).upgrades);
    }

    @Test
    void upgradeLabels() {
        Upgrades upgrades = new Upgrades.Builder()
            .add(Upgrades.Kind.ENCHANTMENT, "ENCHANTMENT_ULTIMATE_WISE_5", 1)
            .add(Upgrades.Kind.HOT_POTATO_BOOK, "HOT_POTATO_BOOK", 10)
            .add(Upgrades.Kind.RECOMBOBULATOR, "RECOMBOBULATOR_3000", 1)
            .build();
        List<String> labels = new ArrayList<String>();
        for (Upgrades.Part part : upgrades.parts()) {
            labels.add(part.label());
        }
        assertEquals(Arrays.asList("Ultimate Wise V", "10x Hot Potato Book", "Recombobulator 3000"), labels);
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", ItemKeys.partKey("ENCHANTMENT_ULTIMATE_WISE_5").bazaarId);
        assertEquals("THE_ART_OF_WAR", ItemKeys.partKey("THE_ART_OF_WAR").key);
    }

    @Test
    void plainItemUsesItsIdEverywhere() throws IOException {
        ItemKeys.Decoded d = ItemKeys.decode(TestData.plainItem("HYPERION"), "Hyperion ✪✪✪✪✪");
        assertEquals("HYPERION", d.key.key);
        assertEquals("HYPERION", d.key.coflTag);
        assertEquals("HYPERION", d.key.bazaarId);
        assertEquals(1, d.count);
        assertEquals("Hyperion ✪✪✪✪✪", d.name);
    }

    @Test
    void stackCountIsRead() throws IOException {
        ItemKeys.Decoded d = ItemKeys.decode(TestData.stack("ENCHANTED_DIAMOND", 64), "Enchanted Diamond");
        assertEquals(64, d.count);
        assertEquals("ENCHANTED_DIAMOND", d.key.bazaarId);
    }

    @Test
    void petsAreKeyedByTypeTierAndLevel() throws IOException {
        ItemKeys.Decoded d = ItemKeys.decode(TestData.pet("ENDER_DRAGON", "LEGENDARY", null), "[Lvl 100] Ender Dragon");
        assertEquals("PET_ENDER_DRAGON;LEGENDARY;L100", d.key.key);
        // Coflnet with the same rarity, levels and (no) skin: so pets get a sales volume.
        assertEquals("PET_ENDER_DRAGON?Rarity=LEGENDARY&PetLevel=100&PetSkin=None", d.key.coflTag);
        assertNull(d.key.bazaarId);

        ItemKeys.Decoded young = ItemKeys.decode(TestData.pet("BLUE_WHALE", "LEGENDARY", null), "[Lvl 12] Blue Whale");
        assertEquals("PET_BLUE_WHALE?Rarity=LEGENDARY&PetLevel=2-29&PetSkin=None", young.key.coflTag);
        // Coflnet reads level 100 as "100 or more" for pets that level up to 200.
        ItemKeys.Decoded golden = ItemKeys.decode(TestData.pet("GOLDEN_DRAGON", "LEGENDARY", null), "[Lvl 100] Golden Dragon");
        assertNull(golden.key.coflTag);
        ItemKeys.Decoded maxed = ItemKeys.decode(TestData.pet("GOLDEN_DRAGON", "LEGENDARY", null), "[Lvl 200] Golden Dragon");
        assertEquals("PET_GOLDEN_DRAGON?Rarity=LEGENDARY&PetLevel=200&PetSkin=None", maxed.key.coflTag);
        // No level, no comparison.
        assertNull(ItemKeys.decode(TestData.pet("BEE", "RARE", null), "Bee").key.coflTag);
    }

    @Test
    void petLevelFallsBackToTheNbtName() throws IOException {
        ItemKeys.Decoded d = ItemKeys.decode(TestData.pet("BEE", "RARE", "§7[Lvl 1] §9Bee"), null);
        assertEquals("PET_BEE;RARE;L1", d.key.key);
        assertEquals("[Lvl 1] Bee", d.name);
    }

    @Test
    void singleEnchantBookMatchesTheBazaarProduct() throws IOException {
        ItemKeys.Decoded d = ItemKeys.decode(TestData.book("ultimate_wise", 5), "Enchanted Book");
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", d.key.key);
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", d.key.bazaarId);
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", d.key.coflTag);
    }

    @Test
    void runesAndAttributes() throws IOException {
        ItemKeys.Decoded rune = ItemKeys.decode(
            TestData.itemBytes(map("id", "RUNE", "runes", map("MUSIC", 3)), null, 1), "◆ Music Rune III");
        assertEquals("RUNE_MUSIC_3", rune.key.key);
        assertEquals("RUNE_MUSIC?MusicRune=3", rune.key.coflTag);

        ItemKeys.Decoded armor = ItemKeys.decode(TestData.itemBytes(
            map("id", "CRIMSON_CHESTPLATE", "attributes", map("mana_regeneration", 4, "mana_pool", 5)), null, 1),
            "Crimson Chestplate");
        assertEquals("CRIMSON_CHESTPLATE[MANA_POOL:5,MANA_REGENERATION:4]", armor.key.key);
        assertNull(armor.key.coflTag);
        assertNull(armor.key.bazaarId);
    }

    @Test
    void levelBuckets() {
        assertEquals(100, ItemKeys.petLevel("[Lvl 100] Golden Dragon"));
        assertEquals(0, ItemKeys.petLevel("Hyperion"));
        assertEquals("L?", ItemKeys.levelBucket(0));
        assertEquals("L2-29", ItemKeys.levelBucket(17));
        assertEquals("L90-99", ItemKeys.levelBucket(99));
        assertEquals("L200", ItemKeys.levelBucket(200));
    }

    @Test
    void badDataIsSkipped() {
        assertNull(ItemKeys.decodeQuiet("not base64 !!!", "x"));
        assertNull(ItemKeys.decodeQuiet(TestData.itemBytes(map("color", "red"), null, 1), "no id"));
        assertNull(ItemKeys.decodeQuiet(null, "x"));
    }
}
