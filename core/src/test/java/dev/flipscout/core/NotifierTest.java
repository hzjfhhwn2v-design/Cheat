package dev.flipscout.core;

import dev.flipscout.core.flip.AuctionFlip;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.item.ItemKeys;
import dev.flipscout.core.item.Upgrades;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.Valuation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifierTest {
    @Test
    void upgradeFlipsShowWhatTheUpgradesAreWorth() {
        ItemKey key = new ItemKey("HYPERION", "HYPERION", "HYPERION", "HYPERION");
        Upgrades upgrades = ItemKeys.upgrades("HYPERION", TestData.map("rarity_upgrades", 1));
        AuctionIndex.Listing listing = new AuctionIndex.Listing("u", "s", "Hyperion", "LEGENDARY", key, 850_000_000L, 1,
            0, 0, 0, 0, upgrades);
        Valuation v = new Valuation();
        v.value = 906_400_000;
        v.lbinOther = 900_000_000;
        v.otherListings = 3;
        v.references = 1;
        v.upgradeParts.add(new Valuation.Part(upgrades.parts().get(0), 8_000_000));
        v.upgradeExtra = 6_400_000;
        v.upgradeShare = 0.8;
        AuctionFlip flip = new AuctionFlip(listing, v, 906_400_000, 31_700_000, 24_700_000, 2.9, false, true);
        Msg msg = new Notifier().auction(flip, 0);
        String line = msg.toPlainString();
        assertTrue(line.contains("+upgrades"), line);
        String hover = msg.parts().get(0).hover;
        assertTrue(hover.contains("Upgrades counted: §b+6.4M §8(80% of their value)"), hover);
        assertTrue(hover.contains("Recombobulator 3000: §f8M"), hover);
        // Not a pet: nothing about candy.
        assertFalse(line.contains("candy"), line);
        assertFalse(hover.contains("candy"), hover);
    }

    /** A level 100 Ender Dragon with {@code candy} candies, against a lowest BIN with {@code referenceCandy}. */
    private static AuctionFlip dragon(int candy, int referenceCandy) {
        ItemKey key = new ItemKey("PET", "PET_ENDER_DRAGON;LEGENDARY;L100", null, null);
        AuctionIndex.Listing listing = new AuctionIndex.Listing("u", "s", "[Lvl 100] Ender Dragon", "LEGENDARY", key,
            500_000_000L, 1, 0, 0, 0, 0, null, candy);
        Valuation v = new Valuation();
        v.value = 600_000_000;
        v.lbinOther = 600_000_000;
        v.otherListings = 4;
        v.lbinCandy = referenceCandy;
        v.references = 1;
        return new AuctionFlip(listing, v, 600_000_000, 21_000_000, 79_000_000, 15.8, false);
    }

    @Test
    void petsSayWhetherTheyHavePetCandy() {
        Msg clean = new Notifier().auction(dragon(0, 0), 0);
        assertTrue(clean.toPlainString().contains("[Lvl 100] Ender Dragon [no candy] »"), clean.toPlainString());
        String cleanHover = clean.parts().get(0).hover;
        assertTrue(cleanHover.contains("§7Pet candy: §anone"), cleanHover);
        assertTrue(cleanHover.contains("(4 listed, cheapest with no candy)"), cleanHover);
        assertFalse(cleanHover.contains("may sell for less"), cleanHover);

        Msg candied = new Notifier().auction(dragon(10, 0), 0);
        assertTrue(candied.toPlainString().contains("[Lvl 100] Ender Dragon [10 candy] »"), candied.toPlainString());
        String hover = candied.parts().get(0).hover;
        assertTrue(hover.contains("§7Pet candy: §c10 used"), hover);
        assertTrue(hover.contains("This pet has candy, the lowest BIN has none: it may sell for less"), hover);

        // Against a candied pet, or one that does not say: no warning.
        String sameKind = new Notifier().auction(dragon(10, 3), 0).parts().get(0).hover;
        assertTrue(sameKind.contains("cheapest with 3 candy"), sameKind);
        assertFalse(sameKind.contains("may sell for less"), sameKind);
        String unknown = new Notifier().auction(dragon(10, -1), 0).parts().get(0).hover;
        assertTrue(unknown.contains("(4 listed)"), unknown);
        assertFalse(unknown.contains("may sell for less"), unknown);

        // The list of flips the blacklist hid says it too.
        assertTrue(new Notifier().blockedLine(dragon(2, 0), "PET_ENDER_DRAGON").contains("Ender Dragon §c[2 candy]"));
    }
}
