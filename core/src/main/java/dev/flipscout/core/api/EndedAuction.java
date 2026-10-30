package dev.flipscout.core.api;

/** One entry of /v2/skyblock/auctions_ended (auctions that sold in the last minute). */
public final class EndedAuction {
    public String auctionId;
    public String seller;
    public String buyer;
    /** Profile that bought the item. */
    public String buyerProfile;
    public String itemBytes;
    public long timestamp;
    public long price;
    public boolean bin;
}
