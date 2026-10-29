package dev.flipscout.core.api;

/**
 * One bazaar product. Hypixel's naming is inverted: {@code buy_summary} lists the sell offers you
 * can instantly buy from, {@code sell_summary} the buy orders you can instantly sell into.
 */
public final class BazaarProduct {
    private static final double[] EMPTY = new double[0];

    public final String id;
    /** Sell offers (instant-buy side), cheapest first. */
    public double[] offerPrices = EMPTY;
    public double[] offerAmounts = EMPTY;
    /** Buy orders (instant-sell side), highest first. */
    public double[] orderPrices = EMPTY;
    public double[] orderAmounts = EMPTY;
    /** Items bought instantly over the last 7 days (fills sell offers). */
    public double buyMovingWeek;
    /** Items sold instantly over the last 7 days (fills buy orders). */
    public double sellMovingWeek;

    public BazaarProduct(String id) {
        this.id = id;
    }

    /** Price to buy one item right now, NaN when nobody is selling. */
    public double instaBuy() {
        return offerPrices.length > 0 ? offerPrices[0] : Double.NaN;
    }

    /** Price received selling one item right now, NaN when there are no buy orders. */
    public double instaSell() {
        return orderPrices.length > 0 ? orderPrices[0] : Double.NaN;
    }

    public double instaBuysPerHour() {
        return buyMovingWeek / 168.0;
    }

    public double instaSellsPerHour() {
        return sellMovingWeek / 168.0;
    }

    /** The API already sorts the summaries; this only guards against surprises. */
    void sortBook() {
        sortPairs(offerPrices, offerAmounts, true);
        sortPairs(orderPrices, orderAmounts, false);
    }

    private static void sortPairs(double[] prices, double[] amounts, boolean ascending) {
        for (int i = 1; i < prices.length; i++) {
            double price = prices[i];
            double amount = amounts[i];
            int j = i - 1;
            while (j >= 0 && (ascending ? prices[j] > price : prices[j] < price)) {
                prices[j + 1] = prices[j];
                amounts[j + 1] = amounts[j];
                j--;
            }
            prices[j + 1] = price;
            amounts[j + 1] = amount;
        }
    }
}
