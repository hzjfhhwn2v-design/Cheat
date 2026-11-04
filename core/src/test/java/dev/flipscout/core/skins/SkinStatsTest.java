package dev.flipscout.core.skins;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinStatsTest {
    static List<SkinHistory.Day> days(int firstDay, double... prices) {
        List<SkinHistory.Day> out = new ArrayList<SkinHistory.Day>();
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] > 0) {
                out.add(new SkinHistory.Day(firstDay + i, prices[i], 2));
            }
        }
        return out;
    }

    /** {@code n} days from {@code first}, the price growing by {@code daily} a day (0.01 = 1%). */
    static List<SkinHistory.Day> trend(int first, int n, double start, double daily) {
        List<SkinHistory.Day> out = new ArrayList<SkinHistory.Day>();
        for (int i = 0; i < n; i++) {
            out.add(new SkinHistory.Day(first + i, start * Math.pow(1 + daily, i), 3));
        }
        return out;
    }

    @Test
    void theTypicalPriceIsTheMedianOfAWeekOfDays() {
        // A day of 1,000 among days of 10-12 does not move it.
        List<SkinHistory.Day> d = days(100, 10, 1000, 11, 12);
        double[] t = SkinStats.typical(d);
        assertEquals(10, t[0], 1e-9);
        assertEquals(505, t[1], 1e-9);
        assertEquals(11, t[2], 1e-9);
        assertEquals(11.5, t[3], 1e-9);
        // Days, not points: a gap of a week leaves only the new day.
        List<SkinHistory.Day> gap = new ArrayList<SkinHistory.Day>(d);
        gap.add(new SkinHistory.Day(111, 50, 1));
        assertEquals(50, SkinStats.typical(gap)[4], 1e-9);
    }

    @Test
    void aPriceNDaysAgoMayComeFromAFewDaysEarlier() {
        List<SkinHistory.Day> d = days(100, 1, 0, 0, 0, 5);
        assertEquals(0, SkinStats.at(d, 100));
        assertEquals(0, SkinStats.at(d, 103));
        assertEquals(-1, SkinStats.at(d, 99));
        assertEquals(1, SkinStats.at(d, 107));
        assertEquals(-1, SkinStats.at(d, 108));
        // More than three days after the last day before it: unknown.
        List<SkinHistory.Day> sparse = days(100, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9);
        assertEquals(-1, SkinStats.at(sparse, 105));
    }

    @Test
    void changesAreMeasuredOnTheTypicalPrice() {
        List<SkinHistory.Day> d = trend(0, 100, 1000, 0.01);
        double[] t = SkinStats.typical(d);
        // The median of seven rising days is three days behind: the change still matches the trend.
        assertEquals(Math.pow(1.01, 7) - 1, SkinStats.change(d, t, 7), 1e-9);
        assertEquals(Math.pow(1.01, 30) - 1, SkinStats.change(d, t, 30), 1e-9);
        assertTrue(Double.isNaN(SkinStats.change(d, t, 200)));
        assertEquals(0, SkinStats.drawdown(d, t, 90), 1e-12);
    }

    @Test
    void theLargestFallOnlyLooksAtTheWindow() {
        List<SkinHistory.Day> d = new ArrayList<SkinHistory.Day>();
        // A crash 200 days ago, then 120 flat days, then a 25% fall.
        d.addAll(trend(0, 20, 100, 0));
        d.addAll(trend(20, 20, 20, 0));
        d.addAll(trend(200, 100, 40, 0));
        d.addAll(trend(300, 20, 30, 0));
        double[] t = SkinStats.typical(d);
        assertEquals(0.25, SkinStats.drawdown(d, t, 90), 1e-9);
        assertEquals(0.8, SkinStats.drawdown(d, t, 400), 1e-9);
    }

    @Test
    void salesAndMediansOverTheLastDays() {
        List<SkinHistory.Day> d = trend(100, 30, 10, 0);
        assertEquals(30 * 3 / 30.0, SkinStats.salesPerDay(d, 129, 30), 1e-9);
        // Ten days later only days 110-129 are within the last 30.
        assertEquals(20 * 3 / 30.0, SkinStats.salesPerDay(d, 139, 30), 1e-9);
        assertEquals(20, SkinStats.daysWithSales(d, 139, 30));
        assertEquals(10, SkinStats.median(d, 129, 30), 1e-9);
        assertTrue(Double.isNaN(SkinStats.median(d, 500, 30)));
    }

    @Test
    void changesOverAHorizonStartEveryFewDays() {
        List<SkinHistory.Day> d = trend(1000, 100, 50, 0.002);
        double[] t = SkinStats.typical(d);
        List<Integer> ages = new ArrayList<Integer>();
        double[] c = SkinStats.changes(d, t, 30, 1000, 3, 900, ages);
        // Starts on day 1000, 1003, ... up to 1069 (1069 + 30 = 1099, the last day).
        assertEquals(24, c.length);
        assertEquals(24, ages.size());
        assertEquals(100, (int) ages.get(0));
        // The first starts within the first week, where the median lags less.
        assertEquals(30 * Math.log(1.002), c[c.length - 1], 1e-9);
    }

    @Test
    void theRobustSpreadIgnoresOddValues() {
        double[] normal = {-0.2, -0.1, 0, 0.1, 0.2, -0.15, 0.15, -0.05, 0.05};
        double plain = SkinStats.robustStd(normal);
        double[] withOdd = Arrays.copyOf(normal, normal.length + 1);
        withOdd[normal.length] = 5;
        assertTrue(Math.abs(SkinStats.robustStd(withOdd) - plain) < 0.05);
        assertTrue(Double.isNaN(SkinStats.robustStd(new double[0])));
        assertEquals(0.5, SkinStats.shareAtLeast(new double[] {-1, 1}, 0, 0), 1e-9);
        assertEquals(1, SkinStats.shareAtLeast(new double[] {-1, 1}, 2, 0), 1e-9);
        double[] centered = SkinStats.centered(new double[] {1, 2, 3});
        assertEquals(Arrays.toString(new double[] {-1, 0, 1}), Arrays.toString(centered));
    }

    @Test
    void theDailySpreadNeedsEnoughDays() {
        assertTrue(Double.isNaN(SkinStats.dailySpread(trend(0, 5, 10, 0), 4, 90, 30)));
        double[] moves = {0.05, -0.03, 0.02, -0.04, 0.01};
        List<SkinHistory.Day> d = new ArrayList<SkinHistory.Day>();
        double price = 100;
        for (int i = 0; i < 60; i++) {
            d.add(new SkinHistory.Day(i, price, 1));
            price *= 1 + moves[i % moves.length];
        }
        double spread = SkinStats.dailySpread(d, 59, 90, 30);
        assertTrue(spread > 0.05 && spread < 0.5, "spread " + spread);
        // Four times the horizon, twice the spread.
        assertEquals(2 * spread, SkinStats.dailySpread(d, 59, 90, 120), 1e-9);
        assertTrue(SkinStats.typical(Collections.<SkinHistory.Day>emptyList()).length == 0);
    }
}
