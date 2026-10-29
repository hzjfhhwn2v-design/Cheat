package dev.flipscout.core.api;

import java.util.Map;

public final class BazaarSnapshot {
    public final long lastUpdated;
    public final Map<String, BazaarProduct> products;

    public BazaarSnapshot(long lastUpdated, Map<String, BazaarProduct> products) {
        this.lastUpdated = lastUpdated;
        this.products = products;
    }

    public BazaarProduct get(String id) {
        return id == null ? null : products.get(id);
    }
}
