package dev.flipscout.core.market;

import dev.flipscout.core.Config;
import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.CoflnetApi;
import dev.flipscout.core.api.NeuApi;
import dev.flipscout.core.item.ItemKey;

import java.util.Collections;
import java.util.Map;
import java.util.function.LongSupplier;

/** Combines our own data, NEU, Coflnet and the bazaar into one {@link Valuation}. */
public final class PriceService {
    private final Config config;
    private final AuctionIndex index;
    private final SalesHistory sales;
    private final CoflnetSource coflnet;
    private final LongSupplier clock;

    private volatile Map<String, Double> neuLowestBins = Collections.emptyMap();
    private volatile Map<String, NeuApi.Average> neuAverages = Collections.emptyMap();
    private volatile BazaarSnapshot bazaar;

    public PriceService(Config config, AuctionIndex index, SalesHistory sales, CoflnetSource coflnet, LongSupplier clock) {
        this.config = config;
        this.index = index;
        this.sales = sales;
        this.coflnet = coflnet;
        this.clock = clock;
    }

    public void setNeuLowestBins(Map<String, Double> values) {
        neuLowestBins = values;
    }

    public void setNeuAverages(Map<String, NeuApi.Average> values) {
        neuAverages = values;
    }

    public void setBazaar(BazaarSnapshot snapshot) {
        bazaar = snapshot;
    }

    public BazaarSnapshot bazaar() {
        return bazaar;
    }

    public int neuLowestBinCount() {
        return neuLowestBins.size();
    }

    public int neuAverageCount() {
        return neuAverages.size();
    }

    /**
     * Values one unit of {@code key}, ignoring the auction {@code excludeUuid} itself.
     *
     * @param unitPrice price per unit of the auction being judged, used to tell whether a
     *                  source's lowest BIN is that very auction
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

        double neuVolume = Double.NaN;
        if (config.sourceNeu && key.neuKey != null && key.neuExact) {
            Double lbin = neuLowestBins.get(key.neuKey);
            // NEU's lowest BIN may already be this very auction: only use it when it is higher.
            if (lbin != null && lbin > unitPrice * 1.001) {
                v.neuLbin = lbin;
            }
            NeuApi.Average average = neuAverages.get(key.neuKey);
            if (average != null) {
                v.neuAverage = average.price;
                neuVolume = average.salesPerDay;
            }
        }

        int nowMinute = (int) (clock.getAsLong() / 60_000L);
        SalesHistory.Stats stats = sales.stats(key.key, nowMinute, config.ahMinSalesSamples);
        if (stats.samples >= config.ahMinSalesSamples) {
            v.salesMedian = stats.median;
            v.salesSamples = stats.samples;
        }
        if (stats.volumeReliable) {
            v.volumePerDay = stats.volumePerDay;
            v.volumeSource = "Hypixel";
        } else if (!Double.isNaN(neuVolume)) {
            v.volumePerDay = neuVolume;
            v.volumeSource = "NEU";
        } else if (!Double.isNaN(stats.volumePerDay)) {
            v.volumePerDay = stats.volumePerDay;
            v.volumeSource = "Hypixel";
        }

        if (config.sourceCoflnet && key.coflTag != null) {
            boolean missingData = Double.isNaN(v.salesReference()) || Double.isNaN(v.volumePerDay);
            CoflnetApi.Price price = allowRemote && missingData ? coflnet.get(key.coflTag) : coflnet.cached(key.coflTag);
            if (price != null) {
                v.coflMedian = price.median;
                if (Double.isNaN(v.volumePerDay) && !Double.isNaN(price.volume)) {
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
     * True when only Coflnet could give {@code key} a sales price right now: no bazaar price, no
     * NEU average, too few sales of our own, and Coflnet not asked recently.
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
        if (config.sourceNeu && key.neuKey != null && key.neuExact && neuAverages.containsKey(key.neuKey)) {
            return false;
        }
        int nowMinute = (int) (clock.getAsLong() / 60_000L);
        return sales.stats(key.key, nowMinute, config.ahMinSalesSamples).samples < config.ahMinSalesSamples;
    }

    /** True when a Coflnet lookup could add something the local data lacks. */
    public boolean wouldUseRemote(ItemKey key, Valuation v) {
        return config.sourceCoflnet && key.coflTag != null && !v.bazaar
            && (Double.isNaN(v.salesReference()) || Double.isNaN(v.volumePerDay));
    }
}
