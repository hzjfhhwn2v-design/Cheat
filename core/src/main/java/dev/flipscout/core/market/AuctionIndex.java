package dev.flipscout.core.market;

import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.item.Upgrades;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Active BIN auctions grouped by item key and sorted by unit price. Thread-safe. */
public final class AuctionIndex {
    public static final class Listing {
        public final String uuid;
        public final String seller;
        public final String itemName;
        public final String tier;
        public final ItemKey key;
        public final long price;
        public final int count;
        public final double unitPrice;
        public final long start;
        public final long end;
        /** Local time at which we first saw the auction. */
        public final long firstSeen;
        /** Valuable upgrades applied to the item, or null. */
        public final Upgrades upgrades;
        long lastScan;

        public Listing(String uuid, String seller, String itemName, String tier, ItemKey key, long price, int count,
                       long start, long end, long firstSeen, long scan) {
            this(uuid, seller, itemName, tier, key, price, count, start, end, firstSeen, scan, null);
        }

        public Listing(String uuid, String seller, String itemName, String tier, ItemKey key, long price, int count,
                       long start, long end, long firstSeen, long scan, Upgrades upgrades) {
            this.uuid = uuid;
            this.seller = seller;
            this.itemName = itemName;
            this.tier = tier;
            this.key = key;
            this.price = price;
            this.count = Math.max(1, count);
            this.unitPrice = (double) price / this.count;
            this.start = start;
            this.end = end;
            this.firstSeen = firstSeen;
            this.lastScan = scan;
            this.upgrades = upgrades;
        }
    }

    private static final Comparator<Listing> BY_PRICE = new Comparator<Listing>() {
        @Override
        public int compare(Listing a, Listing b) {
            int c = Double.compare(a.unitPrice, b.unitPrice);
            return c != 0 ? c : a.uuid.compareTo(b.uuid);
        }
    };

    private final Map<String, Listing> byUuid = new HashMap<String, Listing>();
    private final Map<String, TreeSet<Listing>> byKey = new HashMap<String, TreeSet<Listing>>();

    public synchronized boolean contains(String uuid) {
        return byUuid.containsKey(uuid);
    }

    public synchronized Listing get(String uuid) {
        return byUuid.get(uuid);
    }

    /** Marks a known listing as present in {@code scan}; returns false when it is unknown. */
    public synchronized boolean touch(String uuid, long scan) {
        Listing listing = byUuid.get(uuid);
        if (listing == null) {
            return false;
        }
        listing.lastScan = scan;
        return true;
    }

    public synchronized void add(Listing listing) {
        Listing old = byUuid.put(listing.uuid, listing);
        if (old != null) {
            unlink(old);
        }
        TreeSet<Listing> set = byKey.get(listing.key.key);
        if (set == null) {
            set = new TreeSet<Listing>(BY_PRICE);
            byKey.put(listing.key.key, set);
        }
        set.add(listing);
    }

    public synchronized Listing remove(String uuid) {
        Listing listing = byUuid.remove(uuid);
        if (listing != null) {
            unlink(listing);
        }
        return listing;
    }

    /** Drops auctions whose end time has passed. */
    public synchronized int removeExpired(long now) {
        int removed = 0;
        Iterator<Listing> it = byUuid.values().iterator();
        while (it.hasNext()) {
            Listing listing = it.next();
            if (listing.end > 0 && listing.end < now) {
                it.remove();
                unlink(listing);
                removed++;
            }
        }
        return removed;
    }

    /**
     * After a complete scan: drops listings that were not in it (sold or cancelled) unless they
     * were first seen after the scan started.
     */
    public synchronized int removeUnseen(long scan, long scanStartedAt) {
        int removed = 0;
        Iterator<Listing> it = byUuid.values().iterator();
        while (it.hasNext()) {
            Listing listing = it.next();
            if (listing.lastScan != scan && listing.firstSeen < scanStartedAt) {
                it.remove();
                unlink(listing);
                removed++;
            }
        }
        return removed;
    }

    /** Cheapest listing of {@code key} other than {@code excludeUuid}, or null. */
    public synchronized Listing lowest(String key, String excludeUuid) {
        TreeSet<Listing> set = byKey.get(key);
        if (set == null) {
            return null;
        }
        for (Listing listing : set) {
            if (!listing.uuid.equals(excludeUuid)) {
                return listing;
            }
        }
        return null;
    }

    /** Number of listings of {@code key}, not counting {@code excludeUuid}. */
    public synchronized int count(String key, String excludeUuid) {
        TreeSet<Listing> set = byKey.get(key);
        if (set == null) {
            return 0;
        }
        Listing excluded = excludeUuid == null ? null : byUuid.get(excludeUuid);
        return set.size() - (excluded != null && excluded.key.key.equals(key) ? 1 : 0);
    }

    public synchronized int size() {
        return byUuid.size();
    }

    public synchronized int keyCount() {
        return byKey.size();
    }

    public synchronized List<Listing> cheapestPerKey() {
        List<Listing> out = new ArrayList<Listing>(byKey.size());
        for (TreeSet<Listing> set : byKey.values()) {
            if (!set.isEmpty()) {
                out.add(set.first());
            }
        }
        return out;
    }

    /** Every listing that has upgrades worth looking at. */
    public synchronized List<Listing> withUpgrades() {
        List<Listing> out = new ArrayList<Listing>();
        for (Listing listing : byUuid.values()) {
            if (listing.upgrades != null) {
                out.add(listing);
            }
        }
        return out;
    }

    private void unlink(Listing listing) {
        TreeSet<Listing> set = byKey.get(listing.key.key);
        if (set != null) {
            set.remove(listing);
            if (set.isEmpty()) {
                byKey.remove(listing.key.key);
            }
        }
    }
}
