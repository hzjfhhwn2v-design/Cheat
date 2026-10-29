package dev.flipscout.core.flip;

import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.Valuation;

/** A BIN auction priced below what the item resells for. */
public final class AuctionFlip {
    public final AuctionIndex.Listing listing;
    public final Valuation valuation;
    /** Expected resale of the whole stack, before fees. */
    public final double resale;
    public final double fees;
    public final double profit;
    public final double percent;
    /** Only one price reference was available: worth a manual check. */
    public final boolean weak;

    public AuctionFlip(AuctionIndex.Listing listing, Valuation valuation, double resale, double fees, double profit,
                       double percent, boolean weak) {
        this.listing = listing;
        this.valuation = valuation;
        this.resale = resale;
        this.fees = fees;
        this.profit = profit;
        this.percent = percent;
        this.weak = weak;
    }

    public String command() {
        return "/viewauction " + listing.uuid;
    }
}
