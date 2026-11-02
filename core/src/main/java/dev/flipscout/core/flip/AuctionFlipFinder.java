package dev.flipscout.core.flip;

import dev.flipscout.core.Config;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.PriceService;
import dev.flipscout.core.market.Valuation;
import dev.flipscout.core.util.Coins;

/** Decides whether a BIN auction is worth buying to resell. */
public final class AuctionFlipFinder {
    /**
     * With no sales data the lowest BIN is the only reference, and with one or two other listings
     * it is often just an overpriced one: it needs at least this many.
     */
    static final int MIN_LISTINGS_WITHOUT_SALES = 3;
    /**
     * Above this margin a lowest BIN with no sales behind it is far more often a set of inflated
     * listings than a real bargain: such flips need a second reference (Coflnet) to go out.
     */
    static final double MAX_PERCENT_WITHOUT_SALES = 100;

    private final Config config;
    private final PriceService prices;

    public AuctionFlipFinder(Config config, PriceService prices) {
        this.config = config;
        this.prices = prices;
    }

    /** What the finder made of an auction: the flip, or why it is not one. */
    public static final class Verdict {
        static final Verdict NONE = new Verdict(null, null, Double.NaN, Double.NaN);

        public final AuctionFlip flip;
        /** Why it is not a flip; null when it is one, or when there was nothing to judge. */
        public final String reason;
        /** Profit and margin at the estimated value, NaN without an estimate. */
        public final double profit;
        public final double percent;

        Verdict(AuctionFlip flip, String reason, double profit, double percent) {
            this.flip = flip;
            this.reason = reason;
            this.profit = profit;
            this.percent = percent;
        }
    }

    /**
     * @param allowRemote whether a Coflnet lookup may be made to confirm a candidate
     * @return the flip, or null when the auction does not meet the configured thresholds
     */
    public AuctionFlip evaluate(AuctionIndex.Listing listing, boolean allowRemote) {
        return assess(listing, allowRemote).flip;
    }

    /** Like {@link #evaluate}, saying why an auction is not a flip. */
    public Verdict assess(AuctionIndex.Listing listing, boolean allowRemote) {
        ItemKey key = listing.key;
        if (config.isBlacklisted(key.id, key.key)) {
            return new Verdict(null, "blacklist", Double.NaN, Double.NaN);
        }
        return assessAnyway(listing, allowRemote);
    }

    /** What {@link #evaluate} would say if the item were not blacklisted (local data only). */
    public AuctionFlip evaluateBlacklisted(AuctionIndex.Listing listing) {
        return assessAnyway(listing, false).flip;
    }

    private Verdict assessAnyway(AuctionIndex.Listing listing, boolean allowRemote) {
        ItemKey key = listing.key;
        if (config.budget > 0 && listing.price > config.budget) {
            return new Verdict(null, "over your budget of " + Coins.compact(config.budget), Double.NaN, Double.NaN);
        }
        if (config.ahMinPrice > 0 && listing.price < config.ahMinPrice) {
            return new Verdict(null, "cheaper than your minimum price of " + Coins.compact(config.ahMinPrice), Double.NaN,
                Double.NaN);
        }
        Valuation valuation = prices.valuate(key, listing.uuid, listing.unitPrice, false);
        if (allowRemote && prices.wouldUseRemote(key, valuation) && !Double.isNaN(valuation.lbinReference())
            && profitAt(valuation.lbinReference(), listing) >= config.minProfitFor(key.id)) {
            // Local lowest BIN suggests a flip but there is no sales data to confirm it.
            valuation = prices.valuate(key, listing.uuid, listing.unitPrice, true);
        }
        Verdict verdict = judge(listing, valuation, false);
        if (!config.ahUpgrades || valuation.bazaar) {
            return verdict;
        }
        double extra = prices.addUpgrades(valuation, listing, config.ahUpgradePercent / 100.0);
        if (extra == 0) {
            return verdict;
        }
        // Worth more (or less) for its upgrades. A flip only thanks to them needs a bigger margin.
        return judge(listing, valuation, verdict.flip == null && extra > 0);
    }

    /**
     * True when the local data hints at a flip but lacks sales data to confirm it, so a
     * (slower) Coflnet lookup might turn it into one.
     */
    public boolean worthConfirming(AuctionIndex.Listing listing) {
        ItemKey key = listing.key;
        if (config.isBlacklisted(key.id, key.key) || (config.budget > 0 && listing.price > config.budget)
            || (config.ahMinPrice > 0 && listing.price < config.ahMinPrice)) {
            return false;
        }
        Valuation valuation = prices.valuate(key, listing.uuid, listing.unitPrice, false);
        return prices.wouldUseRemote(key, valuation) && !Double.isNaN(valuation.lbinReference())
            && profitAt(valuation.lbinReference(), listing) >= config.minProfitFor(key.id);
    }

    AuctionFlip judge(AuctionIndex.Listing listing, Valuation valuation) {
        return judge(listing, valuation, false).flip;
    }

    private Verdict judge(AuctionIndex.Listing listing, Valuation valuation, boolean upgradeOnly) {
        if (!(valuation.value > 0)) {
            return new Verdict(null, "no price to compare it with", Double.NaN, Double.NaN);
        }
        double resale = valuation.value * listing.count;
        double fees = valuation.bazaar ? Fees.bazaarSellTax(resale, config) : Fees.auctionSellFees(resale, config);
        double profit = resale - fees - listing.price;
        double percent = listing.price > 0 ? profit * 100.0 / listing.price : Double.POSITIVE_INFINITY;
        boolean weak = !valuation.bazaar && valuation.references < 2;
        if (weak && Double.isNaN(valuation.salesReference()) && valuation.otherListings < MIN_LISTINGS_WITHOUT_SALES) {
            return new Verdict(null, "only " + valuation.otherListings + " other listing" + (valuation.otherListings == 1 ? "" : "s")
                + " and no sales to compare with", profit, percent);
        }
        boolean whitelisted = config.isWhitelisted(listing.key.id, listing.key.key);
        if (whitelisted) {
            if (!(profit > 0)) {
                return new Verdict(null, "no profit after fees", profit, percent);
            }
        } else {
            long minProfit = config.minProfitFor(listing.key.id);
            double minPercent = weak || upgradeOnly ? config.ahMinPercent * 2 : config.ahMinPercent;
            if (profit < minProfit) {
                return new Verdict(null, "profit " + Coins.compact(profit) + " below "
                    + (minProfit != config.ahMinProfit ? "this item's" : "the") + " minimum of " + Coins.compact(minProfit),
                    profit, percent);
            }
            if (percent < minPercent) {
                return new Verdict(null, "margin " + Coins.decimal(percent, 1) + "% below " + Coins.decimal(minPercent, 1) + "%"
                    + (weak ? " (doubled: only one price reference)" : upgradeOnly ? " (doubled: a flip only thanks to upgrades)" : ""),
                    profit, percent);
            }
        }
        if (weak && Double.isNaN(valuation.salesReference()) && percent > MAX_PERCENT_WITHOUT_SALES) {
            return new Verdict(null, "over " + Coins.decimal(MAX_PERCENT_WITHOUT_SALES, 0) + "% on the lowest BIN alone:"
                + " most likely inflated listings", profit, percent);
        }
        if (!whitelisted) {
            if (!Double.isNaN(valuation.volumePerDay)) {
                if (valuation.volumePerDay < config.ahMinVolume) {
                    return new Verdict(null, "sells " + Coins.decimal(valuation.volumePerDay, 1) + " a day, below "
                        + Coins.decimal(config.ahMinVolume, 1), profit, percent);
                }
            } else if (config.ahRequireVolume) {
                return new Verdict(null, "sales a day unknown (ah.requireVolume is on)", profit, percent);
            }
        }
        return new Verdict(new AuctionFlip(listing, valuation, resale, fees, profit, percent, weak, upgradeOnly), null, profit,
            percent);
    }

    private double profitAt(double unitValue, AuctionIndex.Listing listing) {
        double resale = unitValue * listing.count;
        return resale - Fees.auctionSellFees(resale, config) - listing.price;
    }
}
