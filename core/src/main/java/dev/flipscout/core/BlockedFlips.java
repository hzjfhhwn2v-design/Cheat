package dev.flipscout.core;

import java.util.ArrayList;
import java.util.List;

/** Flips the blacklist hid since the last summary. Thread-safe. */
final class BlockedFlips {
    /** Enough for any summary: past this the oldest ones are dropped. */
    private static final int MAX = 100;

    static final class Entry {
        /** Hover line with colour codes. */
        final String line;
        final double profit;

        Entry(String line, double profit) {
            this.line = line;
            this.profit = profit;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    synchronized void add(String line, double profit) {
        if (entries.size() == MAX) {
            entries.remove(0);
        }
        entries.add(new Entry(line, profit));
    }

    /** The flips collected so far; starts over empty. */
    synchronized List<Entry> drain() {
        List<Entry> out = new ArrayList<Entry>(entries);
        entries.clear();
        return out;
    }
}
