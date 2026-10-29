package dev.flipscout.core.api;

import java.util.ArrayList;
import java.util.List;

public final class AuctionPage {
    public int page;
    public int totalPages;
    public int totalAuctions;
    public long lastUpdated;
    /** Date header of the response (epoch millis), used to estimate clock skew. */
    public long serverDate;
    /** True when lastUpdated matched the caller's and the rest of the response was skipped. */
    public boolean notModified;
    public final List<Auction> auctions = new ArrayList<Auction>();
}
