package dev.flipscout.core.flip;

import dev.flipscout.core.Config;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.PriceService;
import dev.flipscout.core.market.Valuation;

/** Decides whether a BIN auction is worth buying to resell. */
public final class AuctionFlipFinder {
    /**
     * With no sales data the lowest BIN is the only reference, and with one or two other listings
     * it is often just an overpriced one: it needs at least this many.
     */
    static final int MIN_LISTINGS_WITHOUT_SALES = 3;

    private final Config config;
    private final PriceService prices;

    public AuctionFlipFinder(Config config, PriceService prices) {
        this.config = config;
        this.prices = prices;
    }

    /**
     * @param allowRemote whether a Coflnet lookup may be made to confirm a candidate
     * @return the flip, or null when the auction does not meet the configured thresholds
     */
    public AuctionFlip evaluate(AuctionIndex.Listing listing, boolean allowRemote) {
        ItemKey key = listing.key;
        if (config.isBlacklisted(key.id, key.key)) {
            return null;
        }
        if (config.budget > 0 && listing.price > config.budget) {
            return null;
        }
        Valuation valuation = prices.valuate(key, listing.uuid, listing.unitPrice, false);
        if (allowRemote && prices.wouldUseRemote(key, valuation) && !Double.isNaN(valuation.lbinReference())
            && profitAt(valuation.lbinReference(), listing) >= config.ahMinProfit) {
            // Local lowest BIN suggests a flip but there is no sales data to confirm it.
            valuation = prices.valuate(key, listing.uuid, listing.unitPrice, true);
        }
        AuctionFlip flip = judge(listing, valuation, false);
        if (!config.ahUpgrades || valuation.bazaar) {
            return flip;
        }
        double extra = prices.addUpgrades(valuation, listing, config.ahUpgradePercent / 100.0);
        if (extra == 0) {
            return flip;
        }
        // Worth more (or less) for its upgrades. A flip only thanks to them needs a bigger margin.
        return judge(listing, valuation, flip == null && extra > 0);
    }

    /**
     * True when the local data hints at a flip but lacks sales data to confirm it, so a
     * (slower) Coflnet lookup might turn it into one.
     */
    public boolean worthConfirming(AuctionIndex.Listing listing) {
        ItemKey key = listing.key;
        if (config.isBlacklisted(key.id, key.key) || (config.budget > 0 && listing.price > config.budget)) {
            return false;
        }
        Valuation valuation = prices.valuate(key, listing.uuid, listing.unitPrice, false);
        return prices.wouldUseRemote(key, valuation) && !Double.isNaN(valuation.lbinReference())
            && profitAt(valuation.lbinReference(), listing) >= config.ahMinProfit;
    }

    AuctionFlip judge(AuctionIndex.Listing listing, Valuation valuation) {
        return judge(listing, valuation, false);
    }

    private AuctionFlip judge(AuctionIndex.Listing listing, Valuation valuation, boolean upgradeOnly) {
        if (!(valuation.value > 0)) {
            return null;
        }
        double resale = valuation.value * listing.count;
        double fees = valuation.bazaar ? Fees.bazaarSellTax(resale, config) : Fees.auctionSellFees(resale, config);
        double profit = resale - fees - listing.price;
        double percent = listing.price > 0 ? profit * 100.0 / listing.price : Double.POSITIVE_INFINITY;
        boolean weak = !valuation.bazaar && valuation.references < 2;
        if (weak && Double.isNaN(valuation.salesReference()) && valuation.otherListings < MIN_LISTINGS_WITHOUT_SALES) {
            return null;
        }
        double minPercent = weak || upgradeOnly ? config.ahMinPercent * 2 : config.ahMinPercent;
        if (profit < config.ahMinProfit || percent < minPercent) {
            return null;
        }
        if (!Double.isNaN(valuation.volumePerDay)) {
            if (valuation.volumePerDay < config.ahMinVolume) {
                return null;
            }
        } else if (config.ahRequireVolume) {
            return null;
        }
        return new AuctionFlip(listing, valuation, resale, fees, profit, percent, weak, upgradeOnly);
    }

    private double profitAt(double unitValue, AuctionIndex.Listing listing) {
        double resale = unitValue * listing.count;
        return resale - Fees.auctionSellFees(resale, config) - listing.price;
    }
}
