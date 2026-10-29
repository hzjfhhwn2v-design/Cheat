package dev.flipscout.core.api;

import java.util.Map;

public final class BazaarSnapshot {
    public final long lastUpdated;
    public final Map<String, BazaarProduct> products;
    /** True when lastUpdated matched the caller's and the products were not downloaded. */
    public final boolean notModified;

    public BazaarSnapshot(long lastUpdated, Map<String, BazaarProduct> products) {
        this(lastUpdated, products, false);
    }

    BazaarSnapshot(long lastUpdated, Map<String, BazaarProduct> products, boolean notModified) {
        this.lastUpdated = lastUpdated;
        this.products = products;
        this.notModified = notModified;
    }

    public BazaarProduct get(String id) {
        return id == null ? null : products.get(id);
    }
}
