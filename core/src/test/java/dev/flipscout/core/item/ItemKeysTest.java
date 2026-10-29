package dev.flipscout.core.item;

import dev.flipscout.core.TestData;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static dev.flipscout.core.TestData.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemKeysTest {
    @Test
    void plainItemUsesItsIdEverywhere() throws IOException {
        ItemKeys.Decoded d = ItemKeys.decode(TestData.plainItem("HYPERION"), "Hyperion ✪✪✪✪✪");
        assertEquals("HYPERION", d.key.key);
        assertEquals("HYPERION", d.key.neuKey);
        assertTrue(d.key.neuExact);
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
        assertEquals("ENDER_DRAGON;4", d.key.neuKey);
        assertFalse(d.key.neuExact);
        assertNull(d.key.coflTag);
        assertNull(d.key.bazaarId);
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
        assertEquals("ULTIMATE_WISE;5", d.key.neuKey);
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", d.key.bazaarId);
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", d.key.coflTag);
    }

    @Test
    void runesAndAttributes() throws IOException {
        ItemKeys.Decoded rune = ItemKeys.decode(
            TestData.itemBytes(map("id", "RUNE", "runes", map("MUSIC", 3)), null, 1), "◆ Music Rune III");
        assertEquals("RUNE_MUSIC_3", rune.key.key);
        assertEquals("MUSIC_RUNE;3", rune.key.neuKey);

        ItemKeys.Decoded armor = ItemKeys.decode(TestData.itemBytes(
            map("id", "CRIMSON_CHESTPLATE", "attributes", map("mana_regeneration", 4, "mana_pool", 5)), null, 1),
            "Crimson Chestplate");
        assertEquals("CRIMSON_CHESTPLATE[MANA_POOL:5,MANA_REGENERATION:4]", armor.key.key);
        assertEquals("CRIMSON_CHESTPLATE", armor.key.neuKey);
        assertFalse(armor.key.neuExact);
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
