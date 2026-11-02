package dev.flipscout.core.extra;

import dev.flipscout.core.Config;
import dev.flipscout.core.api.BazaarProduct;

import java.util.Collection;

/** The live data the extra features price things with. */
public interface Market {
    Config config();

    /** The bazaar product with this id, or null when it is not on the bazaar or the bazaar is not loaded yet. */
    BazaarProduct bazaar(String id);

    /** Every bazaar product of the latest snapshot (empty before the first one). */
    Collection<BazaarProduct> bazaarProducts();

    /** Display name of an item id, without colour codes. */
    String name(String id);

    /** Rarity of an item id (LEGENDARY...), or null. */
    String tier(String id);

    /** Text to type after /bz to find the product. */
    String bazaarSearch(String id);

    /** True once every active auction has been read, so {@link #auctionPrice} can be trusted. */
    boolean auctionsIndexed();

    /** Price of an active auction, or -1 when it is not (or no longer) listed. */
    long auctionPrice(String uuid);
}
