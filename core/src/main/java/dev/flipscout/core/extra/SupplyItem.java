package dev.flipscout.core.extra;

/** An item with few auctions up for sale (SkyCofl's low supply list). */
public final class SupplyItem {
    public final String tag;
    public final String name;
    public final String tier;
    /** Auctions currently listed. */
    public final int supply;
    public final double median;
    public final double lowest;
    /** The cheapest auction, or null. */
    public final String lowestUuid;
    public final double perDay;

    public SupplyItem(String tag, String name, String tier, int supply, double median, double lowest, String lowestUuid,
                      double perDay) {
        this.tag = tag;
        this.name = name;
        this.tier = tier;
        this.supply = supply;
        this.median = median;
        this.lowest = lowest;
        this.lowestUuid = lowestUuid;
        this.perDay = perDay;
    }

    /** Coins traded a day: how much the scarcity matters. */
    public double dailyValue() {
        double price = median > 0 ? median : lowest;
        return price > 0 && perDay > 0 ? price * perDay : 0;
    }
}
