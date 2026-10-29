package dev.flipscout.core.market;

/** What an item is worth per unit, and which references the estimate is based on. */
public final class Valuation {
    /** Estimated resale price per unit before fees; NaN when unknown. */
    public double value = Double.NaN;
    /** The value is the bazaar instant-sell price. */
    public boolean bazaar;
    public double bazaarInstaSell = Double.NaN;

    /** Cheapest other BIN of the same key in our own index. */
    public double lbinOther = Double.NaN;
    public int otherListings;
    public double neuLbin = Double.NaN;

    /** Median of BIN sales we recorded ourselves. */
    public double salesMedian = Double.NaN;
    public int salesSamples;
    public double neuAverage = Double.NaN;
    public double coflMedian = Double.NaN;

    public double volumePerDay = Double.NaN;
    public String volumeSource;

    /** How many independent references (lowest BIN, recent sales) back the value: 0-2. */
    public int references;

    public double lbinReference() {
        return !Double.isNaN(lbinOther) ? lbinOther : neuLbin;
    }

    public String lbinSource() {
        return !Double.isNaN(lbinOther) ? "Hypixel" : !Double.isNaN(neuLbin) ? "NEU" : null;
    }

    public double salesReference() {
        if (!Double.isNaN(salesMedian)) {
            return salesMedian;
        }
        if (!Double.isNaN(neuAverage)) {
            return neuAverage;
        }
        return coflMedian;
    }

    public String salesSource() {
        if (!Double.isNaN(salesMedian)) {
            return "Hypixel";
        }
        if (!Double.isNaN(neuAverage)) {
            return "NEU";
        }
        return !Double.isNaN(coflMedian) ? "Coflnet" : null;
    }
}
