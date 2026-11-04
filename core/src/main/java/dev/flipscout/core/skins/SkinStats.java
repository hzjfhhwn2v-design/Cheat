package dev.flipscout.core.skins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Numbers about one skin's daily prices. Every window is measured in days, not in points: a
 * skin can go days without a sale.
 */
final class SkinStats {
    /** Days the typical price takes the median of. */
    static final int TYPICAL_DAYS = 7;
    /** A price "N days ago" may come from a day this close to it. */
    static final int SLACK_DAYS = 3;
    /** Robust standard deviation from the median absolute deviation (normal distribution). */
    private static final double MAD_TO_STD = 1.4826;

    private SkinStats() {
    }

    /** At each day, the median price of the {@link #TYPICAL_DAYS} days up to it. */
    static double[] typical(List<SkinHistory.Day> days) {
        double[] out = new double[days.size()];
        double[] window = new double[TYPICAL_DAYS];
        int from = 0;
        for (int i = 0; i < days.size(); i++) {
            int day = days.get(i).day;
            while (days.get(from).day <= day - TYPICAL_DAYS) {
                from++;
            }
            int n = 0;
            for (int j = from; j <= i; j++) {
                window[n++] = days.get(j).price;
            }
            out[i] = median(window, n);
        }
        return out;
    }

    /** Index of the last day on or before {@code day}, if within {@link #SLACK_DAYS} of it; else -1. */
    static int at(List<SkinHistory.Day> days, int day) {
        int lo = 0;
        int hi = days.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (days.get(mid).day <= day) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found >= 0 && day - days.get(found).day <= SLACK_DAYS ? found : -1;
    }

    /** Change of the typical price over the last {@code back} days (0.1 = +10%), NaN when unknown. */
    static double change(List<SkinHistory.Day> days, double[] typical, int back) {
        if (days.isEmpty()) {
            return Double.NaN;
        }
        int last = days.size() - 1;
        int then = at(days, days.get(last).day - back);
        return then < 0 || !(typical[then] > 0) ? Double.NaN : typical[last] / typical[then] - 1;
    }

    /** Largest fall of the typical price from an earlier high, within the last {@code window} days (0.3 = 30%). */
    static double drawdown(List<SkinHistory.Day> days, double[] typical, int window) {
        if (days.isEmpty()) {
            return 0;
        }
        int since = days.get(days.size() - 1).day - window;
        double peak = 0;
        double worst = 0;
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).day < since) {
                continue;
            }
            peak = Math.max(peak, typical[i]);
            if (peak > 0) {
                worst = Math.max(worst, 1 - typical[i] / peak);
            }
        }
        return worst;
    }

    /** Median price of the days within the last {@code window} days before {@code today}, NaN when none. */
    static double median(List<SkinHistory.Day> days, int today, int window) {
        double[] prices = new double[days.size()];
        int n = 0;
        for (SkinHistory.Day d : days) {
            if (d.day > today - window && d.day <= today) {
                prices[n++] = d.price;
            }
        }
        return median(prices, n);
    }

    /** Sales a day over the last {@code window} days before {@code today}. */
    static double salesPerDay(List<SkinHistory.Day> days, int today, int window) {
        long sales = 0;
        for (SkinHistory.Day d : days) {
            if (d.day > today - window && d.day <= today) {
                sales += d.sales;
            }
        }
        return sales / (double) window;
    }

    /** Days with sales within the last {@code window} days before {@code today}. */
    static int daysWithSales(List<SkinHistory.Day> days, int today, int window) {
        int n = 0;
        for (SkinHistory.Day d : days) {
            if (d.day > today - window && d.day <= today) {
                n++;
            }
        }
        return n;
    }

    /**
     * Log changes of the typical price over {@code horizon} days, starting every {@code step}
     * days from {@code fromDay} on; ages (days since {@code firstDay}) at each start go to
     * {@code ages} when it is not null.
     */
    static double[] changes(List<SkinHistory.Day> days, double[] typical, int horizon, int fromDay, int step,
                            int firstDay, List<Integer> ages) {
        List<Double> out = new ArrayList<Double>();
        if (days.isEmpty()) {
            return new double[0];
        }
        int lastDay = days.get(days.size() - 1).day;
        int start = Math.max(fromDay, days.get(0).day);
        for (int day = start; day + horizon <= lastDay; day += step) {
            int a = at(days, day);
            int b = at(days, day + horizon);
            if (a < 0 || b < 0 || b <= a || !(typical[a] > 0) || !(typical[b] > 0)) {
                continue;
            }
            out.add(Math.log(typical[b] / typical[a]));
            if (ages != null) {
                ages.add(firstDay >= 0 ? day - firstDay : -1);
            }
        }
        double[] array = new double[out.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = out.get(i);
        }
        return array;
    }

    /**
     * Spread of a price over {@code horizon} days from the day-to-day changes of the last
     * {@code window} days (robust: one odd day does not count), NaN with too few days.
     */
    static double dailySpread(List<SkinHistory.Day> days, int today, int window, int horizon) {
        List<Double> moves = new ArrayList<Double>();
        SkinHistory.Day previous = null;
        for (SkinHistory.Day d : days) {
            if (d.day <= today - window || d.day > today) {
                continue;
            }
            if (previous != null && previous.price > 0 && d.price > 0) {
                moves.add(Math.log(d.price / previous.price) / Math.sqrt(d.day - previous.day));
            }
            previous = d;
        }
        if (moves.size() < 8) {
            return Double.NaN;
        }
        double[] array = new double[moves.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = moves.get(i);
        }
        return robustStd(array) * Math.sqrt(horizon);
    }

    /** Standard deviation estimated from the median absolute deviation, NaN when empty. */
    static double robustStd(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double center = median(values, values.length);
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(values[i] - center);
        }
        return median(deviations, deviations.length) * MAD_TO_STD;
    }

    /** Median of the first {@code n} values (the array is not changed), NaN when n is 0. */
    static double median(double[] values, int n) {
        if (n <= 0) {
            return Double.NaN;
        }
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /** Share of {@code values} at or above {@code threshold}. */
    static double shareAtLeast(double[] values, double shift, double threshold) {
        if (values.length == 0) {
            return Double.NaN;
        }
        int n = 0;
        for (double v : values) {
            if (v + shift >= threshold) {
                n++;
            }
        }
        return n / (double) values.length;
    }

    /** The values minus their median. */
    static double[] centered(double[] values) {
        double center = median(values, values.length);
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] - center;
        }
        return out;
    }
}
