package dev.flipscout.core;

import dev.flipscout.core.flip.AuctionFlip;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.item.ItemKeys;
import dev.flipscout.core.item.Upgrades;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.Valuation;
import org.junit.jupiter.api.Test;

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
        assertTrue(line.contains("+upgrade"), line);
        String hover = msg.parts().get(0).hover;
        assertTrue(hover.contains("Upgrade contati: §b+6.4M §8(80% del loro valore)"), hover);
        assertTrue(hover.contains("Recombobulator 3000: §f8M"), hover);
    }
}
