package dev.flipscout.core.extra;

import dev.flipscout.core.Config;
import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.api.ItemCatalog;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** A market with hand-set bazaar prices and auctions. */
final class FakeMarket implements Market {
    final Config config = new Config();
    final Map<String, BazaarProduct> bazaar = new HashMap<String, BazaarProduct>();
    final Map<String, Long> auctions = new HashMap<String, Long>();
    final Map<String, String> tiers = new HashMap<String, String>();
    boolean indexed;

    FakeMarket product(String id, double instaBuy, double instaSell) {
        BazaarProduct p = new BazaarProduct(id);
        if (instaBuy > 0) {
            p.offerPrices = new double[] {instaBuy};
            p.offerAmounts = new double[] {1000};
        }
        if (instaSell > 0) {
            p.orderPrices = new double[] {instaSell};
            p.orderAmounts = new double[] {1000};
        }
        bazaar.put(id, p);
        return this;
    }

    @Override
    public Config config() {
        return config;
    }

    @Override
    public BazaarProduct bazaar(String id) {
        return bazaar.get(id);
    }

    @Override
    public Collection<BazaarProduct> bazaarProducts() {
        return bazaar.values();
    }

    @Override
    public String name(String id) {
        return ItemCatalog.deriveName(id);
    }

    @Override
    public String tier(String id) {
        return tiers.get(id);
    }

    @Override
    public String bazaarSearch(String id) {
        return ItemCatalog.deriveName(id);
    }

    @Override
    public boolean auctionsIndexed() {
        return indexed;
    }

    @Override
    public long auctionPrice(String uuid) {
        Long price = auctions.get(uuid);
        return price == null ? -1 : price;
    }
}
