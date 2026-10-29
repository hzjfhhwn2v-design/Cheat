package dev.flipscout.core.flip;

import dev.flipscout.core.Config;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.PriceService;
import dev.flipscout.core.market.Valuation;

/** Decides whether a BIN auction is worth buying to resell. */
public final class AuctionFlipFinder {
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
        return judge(listing, valuation);
    }

    AuctionFlip judge(AuctionIndex.Listing listing, Valuation valuation) {
        if (!(valuation.value > 0)) {
            return null;
        }
        double resale = valuation.value * listing.count;
        double fees = valuation.bazaar ? Fees.bazaarSellTax(resale, config) : Fees.auctionSellFees(resale, config);
        double profit = resale - fees - listing.price;
        double percent = listing.price > 0 ? profit * 100.0 / listing.price : Double.POSITIVE_INFINITY;
        boolean weak = !valuation.bazaar && valuation.references < 2;
        double minPercent = weak ? config.ahMinPercent * 2 : config.ahMinPercent;
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
        return new AuctionFlip(listing, valuation, resale, fees, profit, percent, weak);
    }

    private double profitAt(double unitValue, AuctionIndex.Listing listing) {
        double resale = unitValue * listing.count;
        return resale - Fees.auctionSellFees(resale, config) - listing.price;
    }
}
