package dev.flipscout.core.market;

import dev.flipscout.core.Log;
import dev.flipscout.core.api.ApiException;
import dev.flipscout.core.api.CoflnetApi;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Coflnet lookups with a cache and a polite rate limit. They fill in the sales price of items
 * our own data does not cover yet: ahead of time for the most valuable ones, and on the spot to
 * confirm a candidate flip.
 */
public final class CoflnetSource {
    /** A median over days of sales hardly moves within a few hours. */
    private static final long TTL_MS = 6 * 60 * 60_000L;
    private static final long MISS_TTL_MS = 60 * 60_000L;
    private static final int MAX_PER_MINUTE = 15;
    /** Lookups ahead of time stay within this share, so confirming live flips always has room. */
    private static final int PREFETCH_MAX_PER_MINUTE = 6;
    private static final long MIN_GAP_MS = 2_000L;
    private static final int MAX_CACHE = 5_000;

    private static final class Entry {
        final CoflnetApi.Price price;
        final long fetchedAt;

        Entry(CoflnetApi.Price price, long fetchedAt) {
            this.price = price;
            this.fetchedAt = fetchedAt;
        }
    }

    private final CoflnetApi api;
    private final LongSupplier clock;
    private final Map<String, Entry> cache = new HashMap<String, Entry>();
    /** Times of the lookups of the last minute: all of them, and those made ahead of time. */
    private final ArrayDeque<Long> calls = new ArrayDeque<Long>();
    private final ArrayDeque<Long> prefetches = new ArrayDeque<Long>();
    /** Time of the last lookup for a live flip, or null. */
    private Long lastLiveCall;
    /** Tags being looked up right now, so concurrent callers do not ask twice. */
    private final Set<String> inFlight = new HashSet<String>();
    private long pausedUntil;
    private volatile long lastSuccess;
    private volatile String lastError;

    public CoflnetSource(CoflnetApi api, LongSupplier clock) {
        this.api = api;
        this.clock = clock;
    }

    /** Cached value only, never touches the network. */
    public synchronized CoflnetApi.Price cached(String tag) {
        Entry entry = cache.get(tag);
        return fresh(entry, clock.getAsLong()) ? entry.price : null;
    }

    /** True when Coflnet was asked about the tag recently, whatever it answered, or is being asked now. */
    public synchronized boolean isKnown(String tag) {
        return inFlight.contains(tag) || fresh(cache.get(tag), clock.getAsLong());
    }

    /** Sales prices currently cached. */
    public synchronized int cachedPrices() {
        long now = clock.getAsLong();
        int count = 0;
        for (Entry entry : cache.values()) {
            if (entry.price != null && fresh(entry, now)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Cached value, or a network lookup when the rate limit allows it. The request runs outside
     * the lock, so {@link #cached} never waits for the network.
     */
    public CoflnetApi.Price get(String tag) {
        return lookup(tag, false);
    }

    /** Like {@link #get}, for a price wanted ahead of time: within a smaller share of the limit. */
    public CoflnetApi.Price prefetch(String tag) {
        return lookup(tag, true);
    }

    private CoflnetApi.Price lookup(String tag, boolean ahead) {
        long now;
        Entry entry;
        synchronized (this) {
            now = clock.getAsLong();
            entry = cache.get(tag);
            if (fresh(entry, now)) {
                return entry.price;
            }
            if (now < pausedUntil || inFlight.contains(tag) || !allowed(now, ahead)) {
                return entry != null ? entry.price : null;
            }
            calls.addLast(now);
            if (ahead) {
                prefetches.addLast(now);
            } else {
                lastLiveCall = now;
            }
            inFlight.add(tag);
        }
        try {
            CoflnetApi.Price price = api.fetchPrice(tag);
            store(tag, new Entry(price, now));
            lastSuccess = now;
            lastError = null;
            return price;
        } catch (ApiException e) {
            fail(e.getMessage(), now + (e.status == 429 ? Math.max(120_000L, e.retryAfterMs) : 60_000L));
            Log.debug("Coflnet " + tag + ": " + e.getMessage());
        } catch (IOException e) {
            fail(e.toString(), now + 60_000L);
            Log.debug("Coflnet " + tag + ": " + e);
        } catch (RuntimeException e) {
            fail(e.toString(), now + 60_000L);
            Log.debug("Coflnet " + tag + ": " + e);
        } finally {
            synchronized (this) {
                inFlight.remove(tag);
            }
        }
        return entry != null ? entry.price : null;
    }

    /**
     * At most {@link #MAX_PER_MINUTE} lookups a minute. Live ones keep two seconds apart from
     * each other; those ahead of time keep two seconds from any lookup and use only their share.
     */
    private boolean allowed(long now, boolean ahead) {
        prune(calls, now);
        prune(prefetches, now);
        if (calls.size() >= MAX_PER_MINUTE) {
            return false;
        }
        if (ahead) {
            Long last = calls.peekLast();
            return prefetches.size() < PREFETCH_MAX_PER_MINUTE && (last == null || now - last >= MIN_GAP_MS);
        }
        return lastLiveCall == null || now - lastLiveCall >= MIN_GAP_MS;
    }

    private static void prune(ArrayDeque<Long> times, long now) {
        while (!times.isEmpty() && times.peekFirst() < now - 60_000L) {
            times.pollFirst();
        }
    }

    private synchronized void store(String tag, Entry entry) {
        cache.put(tag, entry);
        if (cache.size() <= MAX_CACHE) {
            return;
        }
        Iterator<Entry> it = cache.values().iterator();
        while (it.hasNext()) {
            if (!fresh(it.next(), entry.fetchedAt)) {
                it.remove();
            }
        }
        if (cache.size() > MAX_CACHE) {
            cache.clear();
            cache.put(tag, entry);
        }
    }

    private static boolean fresh(Entry entry, long now) {
        return entry != null && now - entry.fetchedAt <= (entry.price == null ? MISS_TTL_MS : TTL_MS);
    }

    private synchronized void fail(String error, long pauseUntil) {
        lastError = error;
        pausedUntil = Math.max(pausedUntil, pauseUntil);
    }

    public long lastSuccess() {
        return lastSuccess;
    }

    public String lastError() {
        return lastError;
    }
}
