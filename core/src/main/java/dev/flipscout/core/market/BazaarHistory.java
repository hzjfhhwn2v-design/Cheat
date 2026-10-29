package dev.flipscout.core.market;

import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.api.BazaarSnapshot;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Recent instant-buy prices per bazaar product (about two hours), to spot sudden drops. */
public final class BazaarHistory {
    private static final int CAPACITY = 256;
    private static final long KEEP_MS = 2 * 60 * 60_000L;

    private static final class Ring {
        final long[] times = new long[CAPACITY];
        final double[] prices = new double[CAPACITY];
        int start;
        int size;

        void add(long time, double price) {
            if (size == CAPACITY) {
                times[start] = time;
                prices[start] = price;
                start = (start + 1) % CAPACITY;
            } else {
                int index = (start + size) % CAPACITY;
                times[index] = time;
                prices[index] = price;
                size++;
            }
        }

        long timeAt(int i) {
            return times[(start + i) % CAPACITY];
        }

        double priceAt(int i) {
            return prices[(start + i) % CAPACITY];
        }
    }

    private final Map<String, Ring> rings = new HashMap<String, Ring>();

    public synchronized void record(BazaarSnapshot snapshot, long now) {
        for (BazaarProduct product : snapshot.products.values()) {
            double price = product.instaBuy();
            if (!(price > 0)) {
                continue;
            }
            Ring ring = rings.get(product.id);
            if (ring == null) {
                ring = new Ring();
                rings.put(product.id, ring);
            }
            while (ring.size > 0 && ring.timeAt(0) < now - KEEP_MS) {
                ring.start = (ring.start + 1) % CAPACITY;
                ring.size--;
            }
            ring.add(now, price);
        }
    }

    /**
     * Median instant-buy price between 90 and 2 minutes ago, or NaN when there is not enough
     * history yet (at least 10 samples spread over 15 minutes).
     */
    public synchronized double baselineInstaBuy(String id, long now) {
        Ring ring = rings.get(id);
        if (ring == null || ring.size == 0) {
            return Double.NaN;
        }
        long from = now - 90 * 60_000L;
        long to = now - 2 * 60_000L;
        double[] values = new double[ring.size];
        int n = 0;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (int i = 0; i < ring.size; i++) {
            long time = ring.timeAt(i);
            if (time >= from && time <= to) {
                values[n++] = ring.priceAt(i);
                first = Math.min(first, time);
                last = Math.max(last, time);
            }
        }
        if (n < 10 || last - first < 15 * 60_000L) {
            return Double.NaN;
        }
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    public synchronized int productCount() {
        return rings.size();
    }
}
