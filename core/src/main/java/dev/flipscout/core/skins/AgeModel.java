package dev.flipscout.core.skins;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What skins of each age (days since their first sale) did over the horizon, from the histories
 * of every skin read: the median change of their price and how far it spread.
 *
 * <p>Trends are not followed on purpose. Over a year of prices of about 300 skins, a skin that
 * rose more than 5% in a month rose again the next month only 43% of the time, and one that fell
 * rose 57% of the time: betting on a trend loses. The age of a skin moves the usual change by a
 * few percent at most, and the model learns it again from the histories every day.
 */
final class AgeModel {
    /** Upper ends of the age groups, in days; the last group has no end. */
    static final int[] LIMITS = {60, 180, 365, 730};
    static final String[] LABELS = {"less than 2 months", "2 to 6 months", "6 to 12 months", "1 to 2 years",
        "more than 2 years"};
    /** A group with fewer changes than this uses the median of all skins. */
    static final int MIN_SAMPLES = 150;
    /** Changes start every few days: closer ones would only repeat each other. */
    static final int STEP_DAYS = 3;

    final int horizon;
    private final double[] drift = new double[LIMITS.length + 1];
    private final double[] rise = new double[LIMITS.length + 1];
    private final int[] samples = new int[LIMITS.length + 1];
    private final double[][] spreads = new double[LIMITS.length + 1][];
    private final double overall;
    private final double overallRise;
    private final int overallSamples;
    private final double[] overallSpread;

    private AgeModel(int horizon, List<List<Double>> byGroup) {
        this.horizon = horizon;
        List<Double> everything = new ArrayList<Double>();
        for (int g = 0; g < byGroup.size(); g++) {
            double[] values = toArray(byGroup.get(g));
            samples[g] = values.length;
            drift[g] = values.length > 0 ? SkinStats.median(values, values.length) : 0;
            rise[g] = SkinStats.shareAtLeast(values, 0, 1e-9);
            spreads[g] = SkinStats.centered(values);
            everything.addAll(byGroup.get(g));
        }
        double[] all = toArray(everything);
        overallSamples = all.length;
        overall = all.length >= MIN_SAMPLES ? SkinStats.median(all, all.length) : 0;
        overallRise = SkinStats.shareAtLeast(all, 0, 1e-9);
        overallSpread = SkinStats.centered(all);
    }

    static AgeModel build(Collection<SkinHistory.Series> histories, int horizon) {
        List<List<Double>> byGroup = new ArrayList<List<Double>>();
        for (int g = 0; g <= LIMITS.length; g++) {
            byGroup.add(new ArrayList<Double>());
        }
        for (SkinHistory.Series s : histories) {
            if (s.days.size() < 10 || s.firstDay < 0) {
                continue;
            }
            double[] typical = SkinStats.typical(s.days);
            List<Integer> ages = new ArrayList<Integer>();
            double[] changes = SkinStats.changes(s.days, typical, horizon, s.days.get(0).day, STEP_DAYS, s.firstDay, ages);
            for (int i = 0; i < changes.length; i++) {
                if (ages.get(i) >= 0) {
                    byGroup.get(group(ages.get(i))).add(changes[i]);
                }
            }
        }
        return new AgeModel(horizon, byGroup);
    }

    static int group(int age) {
        for (int i = 0; i < LIMITS.length; i++) {
            if (age < LIMITS[i]) {
                return i;
            }
        }
        return LIMITS.length;
    }

    private boolean known(int group) {
        return group >= 0 && samples[group] >= MIN_SAMPLES;
    }

    /** Usual log change over the horizon for a skin of this age (0 when nothing is known). */
    double drift(int age) {
        int g = age < 0 ? -1 : group(age);
        return known(g) ? drift[g] : overall;
    }

    /** Share of times a skin of this age was dearer after the horizon, NaN when unknown. */
    double rise(int age) {
        int g = age < 0 ? -1 : group(age);
        return known(g) ? rise[g] : overallSamples >= MIN_SAMPLES ? overallRise : Double.NaN;
    }

    /** How many changes the estimate for this age comes from. */
    int samples(int age) {
        int g = age < 0 ? -1 : group(age);
        return known(g) ? samples[g] : overallSamples;
    }

    /** Changes of skins of this age minus their median, for skins with too little history of their own. */
    double[] spread(int age) {
        int g = age < 0 ? -1 : group(age);
        return known(g) ? spreads[g] : overallSamples >= MIN_SAMPLES ? overallSpread : new double[0];
    }

    /** "1 to 2 years" for the group of this age. */
    static String label(int age) {
        return age < 0 ? "unknown" : LABELS[group(age)];
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
