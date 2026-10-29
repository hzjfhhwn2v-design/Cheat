package dev.flipscout.core.flip;

import dev.flipscout.core.Config;
import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.market.BazaarHistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Looks for bazaar opportunities in a snapshot. */
public final class BazaarFlipFinder {
    /** Bazaar price step: orders are placed one tenth of a coin above/below the best one. */
    private static final double STEP = 0.1;

    private static final Comparator<BazaarFlip> BY_PROFIT = new Comparator<BazaarFlip>() {
        @Override
        public int compare(BazaarFlip a, BazaarFlip b) {
            return Double.compare(b.profit, a.profit);
        }
    };

    private final Config config;

    public BazaarFlipFinder(Config config) {
        this.config = config;
    }

    /** Every opportunity that meets the thresholds, best first. Cooldowns are not applied here. */
    public List<BazaarFlip> find(BazaarSnapshot snapshot, BazaarHistory history, ItemCatalog items, long now) {
        List<BazaarFlip> out = new ArrayList<BazaarFlip>();
        double tax = config.bzTax / 100.0;
        for (BazaarProduct product : snapshot.products.values()) {
            if (config.isBlacklisted(product.id)) {
                continue;
            }
            double instaBuy = product.instaBuy();
            if (!(instaBuy > 0)) {
                continue;
            }
            if (config.bzFlipEnabled) {
                BazaarFlip flip = orderFlip(product, items, tax);
                if (flip != null) {
                    out.add(flip);
                }
            }
            if (config.bzCrashEnabled) {
                BazaarFlip flip = crash(product, history.baselineInstaBuy(product.id, now), items, tax);
                if (flip != null) {
                    out.add(flip);
                }
            }
            if (config.bzNpcEnabled) {
                BazaarFlip flip = npcFlip(product, items);
                if (flip != null) {
                    out.add(flip);
                }
            }
        }
        Collections.sort(out, BY_PROFIT);
        return out;
    }

    private BazaarFlip orderFlip(BazaarProduct product, ItemCatalog items, double tax) {
        double instaBuy = product.instaBuy();
        double instaSell = product.instaSell();
        if (!(instaSell > 0)) {
            return null;
        }
        double buyAt = instaSell + STEP;
        double sellAt = instaBuy - STEP;
        double margin = sellAt * (1 - tax) - buyAt;
        if (!(margin > 0)) {
            return null;
        }
        double marginPercent = margin * 100.0 / buyAt;
        double flow = Math.min(product.instaBuysPerHour(), product.instaSellsPerHour());
        double perHour = margin * flow;
        if (marginPercent < config.bzFlipMinMarginPercent || flow < config.bzFlipMinHourlyVolume
            || perHour < config.bzFlipMinProfitPerHour) {
            return null;
        }
        if (config.budget > 0 && buyAt > config.budget) {
            return null;
        }
        BazaarFlip flip = new BazaarFlip(BazaarFlip.Type.ORDER, product.id, items.displayName(product.id),
            items.bazaarSearch(product.id), perHour);
        flip.instaBuy = instaBuy;
        flip.instaSell = instaSell;
        flip.buyOrderPrice = buyAt;
        flip.sellOfferPrice = sellAt;
        flip.margin = margin;
        flip.marginPercent = marginPercent;
        flip.buysPerHour = product.instaBuysPerHour();
        flip.sellsPerHour = product.instaSellsPerHour();
        return flip;
    }

    private BazaarFlip crash(BazaarProduct product, double baseline, ItemCatalog items, double tax) {
        double instaBuy = product.instaBuy();
        if (!(baseline > 0) || product.instaBuysPerHour() < config.bzCrashMinHourlyVolume) {
            return null;
        }
        if (instaBuy > baseline * (1 - config.bzCrashDropPercent / 100.0)) {
            return null;
        }
        // Everything cheaper than the usual price (after tax) can be relisted at that price.
        double resale = baseline * (1 - tax);
        double[] taken = takeBelow(product, resale, resale);
        double units = taken[0];
        double cost = taken[1];
        double profit = taken[2];
        if (profit < config.bzCrashMinProfit) {
            return null;
        }
        BazaarFlip flip = new BazaarFlip(BazaarFlip.Type.CRASH, product.id, items.displayName(product.id),
            items.bazaarSearch(product.id), profit);
        flip.instaBuy = instaBuy;
        flip.instaSell = product.instaSell();
        flip.baseline = baseline;
        flip.dropPercent = (1 - instaBuy / baseline) * 100.0;
        flip.units = units;
        flip.cost = cost;
        flip.buysPerHour = product.instaBuysPerHour();
        return flip;
    }

    private BazaarFlip npcFlip(BazaarProduct product, ItemCatalog items) {
        ItemCatalog.Info info = items.get(product.id);
        double npc = info != null ? info.npcSellPrice : 0;
        if (!(npc > product.instaBuy())) {
            return null;
        }
        double[] taken = takeBelow(product, npc, npc);
        if (taken[2] < config.bzNpcMinProfit) {
            return null;
        }
        BazaarFlip flip = new BazaarFlip(BazaarFlip.Type.NPC, product.id, items.displayName(product.id),
            items.bazaarSearch(product.id), taken[2]);
        flip.instaBuy = product.instaBuy();
        flip.npcPrice = npc;
        flip.units = taken[0];
        flip.cost = taken[1];
        return flip;
    }

    /**
     * Walks the sell offers cheaper than {@code limit}, within the budget.
     *
     * @return {units, cost, profit} where profit assumes each unit is worth {@code worth}
     */
    private double[] takeBelow(BazaarProduct product, double limit, double worth) {
        double units = 0;
        double cost = 0;
        double profit = 0;
        long budget = config.budget;
        for (int i = 0; i < product.offerPrices.length; i++) {
            double price = product.offerPrices[i];
            if (price >= limit) {
                break;
            }
            double amount = product.offerAmounts[i];
            if (budget > 0) {
                double affordable = Math.floor((budget - cost) / price);
                if (affordable <= 0) {
                    break;
                }
                amount = Math.min(amount, affordable);
            }
            units += amount;
            cost += amount * price;
            profit += amount * (worth - price);
        }
        return new double[] {units, cost, profit};
    }
}
