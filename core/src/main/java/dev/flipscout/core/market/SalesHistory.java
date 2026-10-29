package dev.flipscout.core.market;

import dev.flipscout.core.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * BIN sale prices seen in /auctions_ended, per item key, plus the minutes during which we were
 * actually watching (so sales per day can be estimated even with gaps). Saved to disk so the data
 * keeps building up across sessions. Thread-safe.
 */
public final class SalesHistory {
    private static final int MAGIC = 0x46535348;
    private static final int VERSION = 1;
    private static final int MAX_PER_KEY = 120;
    private static final int DAY_MINUTES = 24 * 60;
    private static final int MAX_MEDIAN_SAMPLES = 50;

    /** Price statistics for one key. */
    public static final class Stats {
        public final double median;
        public final int samples;
        /** Sales per day, NaN while we have not watched long enough to say. */
        public final double volumePerDay;
        /** True when the volume is based on a full day of watching or several sales. */
        public final boolean volumeReliable;

        Stats(double median, int samples, double volumePerDay, boolean volumeReliable) {
            this.median = median;
            this.samples = samples;
            this.volumePerDay = volumePerDay;
            this.volumeReliable = volumeReliable;
        }
    }

    private static final class Ring {
        int[] minutes = new int[4];
        double[] prices = new double[4];
        int start;
        int size;

        void add(int minute, double price) {
            if (size == minutes.length && size < MAX_PER_KEY) {
                int capacity = Math.min(MAX_PER_KEY, minutes.length * 2);
                int[] m = new int[capacity];
                double[] p = new double[capacity];
                for (int i = 0; i < size; i++) {
                    m[i] = minutes[(start + i) % minutes.length];
                    p[i] = prices[(start + i) % prices.length];
                }
                minutes = m;
                prices = p;
                start = 0;
            }
            if (size == minutes.length) {
                minutes[start] = minute;
                prices[start] = price;
                start = (start + 1) % minutes.length;
            } else {
                int index = (start + size) % minutes.length;
                minutes[index] = minute;
                prices[index] = price;
                size++;
            }
        }

        int minuteAt(int i) {
            return minutes[(start + i) % minutes.length];
        }

        double priceAt(int i) {
            return prices[(start + i) % prices.length];
        }

        void dropOlderThan(int minMinute) {
            while (size > 0 && minuteAt(0) < minMinute) {
                start = (start + 1) % minutes.length;
                size--;
            }
        }
    }

    private final Map<String, Ring> byKey = new HashMap<String, Ring>();
    private final ArrayDeque<Integer> observed = new ArrayDeque<Integer>();
    private int totalSales;
    private boolean dirty;

    /** Records that the sales of {@code minute} were collected. */
    public synchronized void markObserved(int minute) {
        Integer last = observed.peekLast();
        if (last == null || minute > last) {
            observed.addLast(minute);
            dirty = true;
        }
    }

    public synchronized void add(String key, int minute, double unitPrice) {
        if (!(unitPrice > 0)) {
            return;
        }
        Ring ring = byKey.get(key);
        if (ring == null) {
            ring = new Ring();
            byKey.put(key, ring);
        }
        // Sales arrive roughly in order; keep the ring sorted enough for age pruning.
        ring.add(minute, unitPrice);
        totalSales++;
        dirty = true;
    }

    public synchronized Stats stats(String key, int nowMinute, int minSamples) {
        Ring ring = byKey.get(key);
        int observedDay = observedSince(nowMinute - DAY_MINUTES);
        if (ring == null || ring.size == 0) {
            boolean watchedLongEnough = observedDay >= 6 * 60;
            return new Stats(Double.NaN, 0, watchedLongEnough ? 0 : Double.NaN, observedDay >= DAY_MINUTES);
        }
        int lastDay = 0;
        double[] recent = new double[Math.min(ring.size, MAX_MEDIAN_SAMPLES)];
        int n = 0;
        for (int i = ring.size - 1; i >= 0; i--) {
            int minute = ring.minuteAt(i);
            if (minute >= nowMinute - DAY_MINUTES) {
                lastDay++;
                if (n < recent.length) {
                    recent[n++] = ring.priceAt(i);
                }
            }
        }
        if (n < minSamples) {
            // Rare items: fall back to everything we kept (up to the window).
            n = 0;
            for (int i = ring.size - 1; i >= 0 && n < recent.length; i--) {
                recent[n++] = ring.priceAt(i);
            }
        }
        double median = n == 0 ? Double.NaN : median(recent, n);
        // A short watch with no sales says little about slow items, so only trust the estimate
        // after six hours of data or a few actual sales.
        double volume = Double.NaN;
        if (observedDay >= 60 && (observedDay >= 6 * 60 || lastDay >= 3)) {
            volume = lastDay * (double) DAY_MINUTES / observedDay;
        }
        boolean reliable = !Double.isNaN(volume) && (observedDay >= DAY_MINUTES || lastDay >= 5);
        return new Stats(median, n, volume, reliable);
    }

    private int observedSince(int fromMinute) {
        int count = 0;
        Iterator<Integer> it = observed.descendingIterator();
        while (it.hasNext()) {
            if (it.next() < fromMinute) {
                break;
            }
            count++;
        }
        return count;
    }

    public synchronized int observedMinutes() {
        return observed.size();
    }

    public synchronized int keyCount() {
        return byKey.size();
    }

    public synchronized int sampleCount() {
        int total = 0;
        for (Ring ring : byKey.values()) {
            total += ring.size;
        }
        return total;
    }

    public synchronized int salesThisSession() {
        return totalSales;
    }

    /** Forgets everything older than {@code windowDays}. */
    public synchronized void prune(int nowMinute, int windowDays) {
        int minMinute = nowMinute - windowDays * DAY_MINUTES;
        while (!observed.isEmpty() && observed.peekFirst() < minMinute) {
            observed.pollFirst();
        }
        Iterator<Ring> it = byKey.values().iterator();
        while (it.hasNext()) {
            Ring ring = it.next();
            ring.dropOlderThan(minMinute);
            if (ring.size == 0) {
                it.remove();
            }
        }
    }

    public synchronized void save(File file) throws IOException {
        if (!dirty && file.isFile()) {
            return;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Impossibile creare " + parent);
        }
        File tmp = new File(file.getPath() + ".tmp");
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp))));
        try {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(observed.size());
            for (Integer minute : observed) {
                out.writeInt(minute);
            }
            out.writeInt(byKey.size());
            for (Map.Entry<String, Ring> entry : byKey.entrySet()) {
                Ring ring = entry.getValue();
                out.writeUTF(entry.getKey());
                out.writeInt(ring.size);
                for (int i = 0; i < ring.size; i++) {
                    out.writeInt(ring.minuteAt(i));
                    out.writeDouble(ring.priceAt(i));
                }
            }
        } finally {
            out.close();
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        dirty = false;
    }

    public synchronized void load(File file) {
        if (!file.isFile()) {
            return;
        }
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                Log.warn("File delle vendite non riconosciuto, lo ignoro: " + file);
                return;
            }
            int observedCount = in.readInt();
            for (int i = 0; i < observedCount; i++) {
                markObserved(in.readInt());
            }
            int keys = in.readInt();
            for (int k = 0; k < keys; k++) {
                String key = in.readUTF();
                int size = in.readInt();
                for (int i = 0; i < size; i++) {
                    int minute = in.readInt();
                    double price = in.readDouble();
                    Ring ring = byKey.get(key);
                    if (ring == null) {
                        ring = new Ring();
                        byKey.put(key, ring);
                    }
                    ring.add(minute, price);
                }
            }
            dirty = false;
        } catch (IOException e) {
            Log.warn("File delle vendite danneggiato, riparto da zero: " + file, e);
            byKey.clear();
            observed.clear();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    static double median(double[] values, int n) {
        double[] copy = Arrays.copyOf(values, n);
        Arrays.sort(copy);
        return n % 2 == 1 ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2.0;
    }
}
