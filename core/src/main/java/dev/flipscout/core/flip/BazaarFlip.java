package dev.flipscout.core.flip;

/** A bazaar opportunity. Which fields are meaningful depends on {@link #type}. */
public final class BazaarFlip {
    public enum Type {
        /** Place a buy order and a sell offer and pocket the spread. */
        ORDER,
        /** The instant-buy price dropped well below its recent median. */
        CRASH,
        /** Buying instantly and selling to an NPC is profitable. */
        NPC
    }

    public final Type type;
    public final String productId;
    public final String name;
    public final String search;
    /** ORDER: potential coins per hour; CRASH and NPC: total coins for the available amount. */
    public final double profit;

    public double instaBuy;
    public double instaSell;
    public double buyOrderPrice;
    public double sellOfferPrice;
    public double margin;
    public double marginPercent;
    public double buysPerHour;
    public double sellsPerHour;
    public double baseline;
    public double dropPercent;
    public double npcPrice;
    public double units;
    public double cost;

    public BazaarFlip(Type type, String productId, String name, String search, double profit) {
        this.type = type;
        this.productId = productId;
        this.name = name;
        this.search = search;
        this.profit = profit;
    }

    public String command() {
        return "/bz " + search;
    }

    public String cooldownKey() {
        return type + ":" + productId;
    }
}
