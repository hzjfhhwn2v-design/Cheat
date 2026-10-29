package dev.flipscout.core.market;

import dev.flipscout.core.Log;
import dev.flipscout.core.api.ApiException;
import dev.flipscout.core.api.CoflnetApi;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Coflnet lookups with a cache and a polite rate limit: they are only used to confirm a
 * candidate flip when our own sales data is missing.
 */
public final class CoflnetSource {
    private static final long TTL_MS = 15 * 60_000L;
    private static final long MISS_TTL_MS = 60 * 60_000L;
    private static final int MAX_PER_MINUTE = 15;
    private static final long MIN_GAP_MS = 2_000L;

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
    private final ArrayDeque<Long> calls = new ArrayDeque<Long>();
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
        if (entry == null) {
            return null;
        }
        long age = clock.getAsLong() - entry.fetchedAt;
        return age <= (entry.price == null ? MISS_TTL_MS : TTL_MS) ? entry.price : null;
    }

    /** Cached value, or a network lookup when the rate limit allows it. */
    public synchronized CoflnetApi.Price get(String tag) {
        long now = clock.getAsLong();
        Entry entry = cache.get(tag);
        if (entry != null && now - entry.fetchedAt <= (entry.price == null ? MISS_TTL_MS : TTL_MS)) {
            return entry.price;
        }
        if (now < pausedUntil) {
            return entry != null ? entry.price : null;
        }
        while (!calls.isEmpty() && calls.peekFirst() < now - 60_000L) {
            calls.pollFirst();
        }
        Long lastCall = calls.peekLast();
        if (calls.size() >= MAX_PER_MINUTE || (lastCall != null && now - lastCall < MIN_GAP_MS)) {
            return entry != null ? entry.price : null;
        }
        calls.addLast(now);
        try {
            CoflnetApi.Price price = api.fetchPrice(tag);
            cache.put(tag, new Entry(price, now));
            lastSuccess = now;
            lastError = null;
            if (cache.size() > 5_000) {
                cache.clear();
            }
            return price;
        } catch (ApiException e) {
            lastError = e.getMessage();
            pausedUntil = now + (e.status == 429 ? Math.max(120_000L, e.retryAfterMs) : 60_000L);
            Log.debug("Coflnet " + tag + ": " + e.getMessage());
        } catch (IOException e) {
            lastError = e.toString();
            pausedUntil = now + 60_000L;
            Log.debug("Coflnet " + tag + ": " + e);
        } catch (RuntimeException e) {
            lastError = e.toString();
            pausedUntil = now + 60_000L;
            Log.debug("Coflnet " + tag + ": " + e);
        }
        return entry != null ? entry.price : null;
    }

    public long lastSuccess() {
        return lastSuccess;
    }

    public String lastError() {
        return lastError;
    }
}
