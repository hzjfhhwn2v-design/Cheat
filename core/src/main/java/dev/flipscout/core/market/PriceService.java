package dev.flipscout.core.market;

import dev.flipscout.core.Config;
import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.CoflnetApi;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.item.ItemKeys;
import dev.flipscout.core.item.Upgrades;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Combines our own data, Coflnet and the bazaar into one {@link Valuation}. */
public final class PriceService {
    /** Upgrades worth less than this (most enchantments, a few cheap gems) are noise. */
    private static final double MIN_PART_VALUE = 100_000;
    private static final long PART_PRICE_TTL_MS = 60_000L;

    private final Config config;
    private final AuctionIndex index;
    private final SalesHistory sales;
    private final CoflnetSource coflnet;
    private final LongSupplier clock;

    private volatile BazaarSnapshot bazaar;
    /** For star costs. */
    private volatile ItemCatalog catalog = ItemCatalog.EMPTY;
    /** Part id -> {price, time}: prices of upgrade parts, briefly cached. */
    private final Map<String, double[]> partPrices = new ConcurrentHashMap<String, double[]>();

    public PriceService(Config config, AuctionIndex index, SalesHistory sales, CoflnetSource coflnet, LongSupplier clock) {
        this.config = config;
        this.index = index;
        this.sales = sales;
        this.coflnet = coflnet;
        this.clock = clock;
    }

    public void setBazaar(BazaarSnapshot snapshot) {
        bazaar = snapshot;
    }

    public void setCatalog(ItemCatalog items) {
        catalog = items;
    }

    public BazaarSnapshot bazaar() {
        return bazaar;
    }

    /**
     * Values one unit of {@code key}, ignoring the auction {@code excludeUuid} itself.
     *
     * @param unitPrice price per unit of the auction being judged
     * @param allowRemote whether Coflnet may be queried over the network
     */
    public Valuation valuate(ItemKey key, String excludeUuid, double unitPrice, boolean allowRemote) {
        Valuation v = new Valuation();
        BazaarSnapshot snapshot = bazaar;
        if (key.bazaarId != null && snapshot != null) {
            BazaarProduct product = snapshot.get(key.bazaarId);
            if (product != null && product.instaSell() > 0) {
                // Sold instantly to buy orders: the most reliable price there is.
                v.bazaar = true;
                v.bazaarInstaSell = product.instaSell();
                v.value = v.bazaarInstaSell;
                v.references = 2;
                v.volumePerDay = product.sellMovingWeek / 7.0;
                v.volumeSource = "Bazaar";
                return v;
            }
        }

        AuctionIndex.Listing lowest = index.lowest(key.key, excludeUuid);
        if (lowest != null) {
            v.lbinOther = lowest.unitPrice;
            v.otherListings = index.count(key.key, excludeUuid);
        }

        int nowMinute = (int) (clock.getAsLong() / 60_000L);
        SalesHistory.Stats stats = sales.stats(key.key, nowMinute, config.ahMinSalesSamples);
        if (stats.samples >= config.ahMinSalesSamples) {
            v.salesMedian = stats.median;
            v.salesSamples = stats.samples;
        }
        if (!Double.isNaN(stats.volumePerDay)) {
            v.volumePerDay = stats.volumePerDay;
            v.volumeSource = "Hypixel";
        }

        if (config.sourceCoflnet && key.coflTag != null) {
            boolean missingData = Double.isNaN(v.salesReference()) || !stats.volumeReliable;
            CoflnetApi.Price price = allowRemote && missingData ? coflnet.get(key.coflTag) : coflnet.cached(key.coflTag);
            if (price != null) {
                v.coflMedian = price.median;
                // Coflnet counts days of sales: better than a few hours of our own watching.
                if (!stats.volumeReliable && !Double.isNaN(price.volume)) {
                    v.volumePerDay = price.volume;
                    v.volumeSource = "Coflnet";
                }
            }
        }

        combine(v);
        return v;
    }

    private void combine(Valuation v) {
        double lbin = v.lbinReference();
        double sold = v.salesReference();
        boolean hasLbin = !Double.isNaN(lbin);
        boolean hasSold = !Double.isNaN(sold);
        v.references = (hasLbin ? 1 : 0) + (hasSold ? 1 : 0);
        String strategy = config.ahStrategy;
        if ("lbin".equals(strategy)) {
            v.value = hasLbin ? lbin : sold;
        } else if ("sales".equals(strategy)) {
            v.value = hasSold ? sold : lbin;
        } else {
            // Conservative: an item is worth what it sells for, capped by what you could buy it for.
            v.value = !hasLbin ? sold : !hasSold ? lbin : Math.min(lbin, sold);
        }
    }

    /**
     * True when only Coflnet could give {@code key} a sales price or a sales volume right now: no
     * bazaar price, too few sales of our own or too short a watch, and Coflnet not asked recently.
     */
    public boolean needsRemoteSales(ItemKey key) {
        if (!config.sourceCoflnet || key.coflTag == null || coflnet.isKnown(key.coflTag)) {
            return false;
        }
        BazaarSnapshot snapshot = bazaar;
        if (key.bazaarId != null && snapshot != null) {
            BazaarProduct product = snapshot.get(key.bazaarId);
            if (product != null && product.instaSell() > 0) {
                return false;
            }
        }
        int nowMinute = (int) (clock.getAsLong() / 60_000L);
        SalesHistory.Stats stats = sales.stats(key.key, nowMinute, config.ahMinSalesSamples);
        return stats.samples < config.ahMinSalesSamples || !stats.volumeReliable;
    }

    /** Price of one unit of an upgrade part, NaN when unknown: bazaar instant-sell, else its AH value. */
    public double partPrice(String id) {
        long now = clock.getAsLong();
        double[] cached = partPrices.get(id);
        if (cached != null && now - (long) cached[1] < PART_PRICE_TTL_MS) {
            return cached[0];
        }
        double price = Double.NaN;
        BazaarSnapshot snapshot = bazaar;
        BazaarProduct product = snapshot == null ? null : snapshot.get(id);
        if (product != null && product.instaSell() > 0) {
            price = product.instaSell();
        } else {
            Valuation v = valuate(ItemKeys.partKey(id), null, 0, false);
            if (v.value > 0) {
                price = v.value;
            }
        }
        partPrices.put(id, new double[] {price, now});
        return price;
    }

    /** What {@code upgrades} add to an item at current prices; the parts counted go into {@code out}. */
    public double upgradeValue(Upgrades upgrades, List<Valuation.Part> out) {
        if (upgrades == null) {
            return 0;
        }
        double total = 0;
        for (Upgrades.Part part : upgrades.parts()) {
            double value;
            if (part.kind == Upgrades.Kind.STARS) {
                value = starsValue(part.id, part.count);
            } else {
                double price = partPrice(part.id);
                value = price > 0 ? price * part.count * part.kind.weight : 0;
            }
            if (!(value >= MIN_PART_VALUE)) {
                continue;
            }
            total += value;
            if (out != null) {
                out.add(new Valuation.Part(part, value));
            }
        }
        return total;
    }

    /**
     * What {@code stars} stars add to item {@code id}: the essence and items they cost (counted at
     * {@link Upgrades#STAR_COST_WEIGHT}) plus, on dungeon items, the master stars after the fifth.
     */
    double starsValue(String id, int stars) {
        ItemCatalog.Info info = catalog.get(id);
        ItemCatalog.StarCost[][] costs = info == null ? null : info.upgradeCosts;
        int levels = costs == null ? 0 : costs.length;
        double value = 0;
        for (int i = 0; i < Math.min(stars, levels); i++) {
            for (ItemCatalog.StarCost cost : costs[i]) {
                double price = partPrice(cost.id);
                if (price > 0) {
                    value += price * cost.amount * Upgrades.STAR_COST_WEIGHT;
                }
            }
        }
        // Crimson Isle armour has ten or fifteen stars of its own; dungeon items have five, then master stars.
        boolean dungeon = levels == 5 || (levels == 0 && info != null && info.dungeonItem);
        if (dungeon) {
            for (int i = 0; i < Math.min(stars - 5, Upgrades.MASTER_STARS.length); i++) {
                double price = partPrice(Upgrades.MASTER_STARS[i]);
                if (price > 0) {
                    value += price;
                }
            }
        }
        return value;
    }

    /**
     * Adjusts {@code v} for the upgrades of {@code listing} compared with those of the listing its
     * lowest BIN comes from, counting {@code share} of the difference. More upgrades raise the value
     * (at most doubling it); fewer lower it when the value is that listing's price (at most halving
     * it). Returns the change (0 when none).
     */
    public double addUpgrades(Valuation v, AuctionIndex.Listing listing, double share) {
        if (!(v.value > 0) || !(share > 0) || listing.count != 1) {
            return 0;
        }
        AuctionIndex.Listing reference = index.lowest(listing.key.key, listing.uuid);
        if (listing.upgrades == null && (reference == null || reference.upgrades == null)) {
            return 0;
        }
        List<Valuation.Part> parts = new ArrayList<Valuation.Part>();
        double mine = upgradeValue(listing.upgrades, parts);
        double theirs = reference == null ? 0 : upgradeValue(reference.upgrades, null);
        double difference = mine - theirs;
        // Only when the value is the reference's own price do its upgrades come with it.
        boolean fromReference = reference != null && v.value >= reference.unitPrice - 0.5;
        if (difference < 0 && !fromReference) {
            difference = 0;
        }
        double extra = Math.max(-v.value / 2, Math.min(difference * share, v.value));
        if (extra == 0) {
            return 0;
        }
        v.upgradeParts.addAll(parts);
        v.referenceUpgrades = theirs;
        v.upgradeShare = share;
        v.upgradeExtra = extra;
        v.value += extra;
        return extra;
    }

    /** True when a Coflnet lookup could add something the local data lacks. */
    public boolean wouldUseRemote(ItemKey key, Valuation v) {
        return config.sourceCoflnet && key.coflTag != null && !v.bazaar && !coflnet.isKnown(key.coflTag)
            && (Double.isNaN(v.salesReference()) || Double.isNaN(v.volumePerDay));
    }
}
