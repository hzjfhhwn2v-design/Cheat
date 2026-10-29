package dev.flipscout.core.api;

import dev.flipscout.core.item.ItemKeys;

/** One entry of /v2/skyblock/auctions. */
public final class Auction {
    public String uuid;
    public String seller;
    public String itemName;
    public String tier;
    public String category;
    /** Base64 gzip NBT; dropped once decoded to save memory. */
    public String itemBytes;
    public long start;
    public long end;
    public long startingBid;
    public long highestBid;
    public boolean bin;
    /** Filled in when the item bytes are decoded. */
    public ItemKeys.Decoded decoded;
}
