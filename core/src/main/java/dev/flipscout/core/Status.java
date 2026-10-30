package dev.flipscout.core;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** What the engine is doing, for /flipscout status. */
public final class Status {
    public volatile boolean active;
    public volatile boolean scanning;
    public volatile long lastFullScan;
    public volatile int fullScanPages;
    public volatile int fullScanAuctions;
    public volatile long fullScanMillis;
    public volatile long lastAuctionPoll;
    /** How long after Hypixel prepared it the last auction refresh was seen, and the usual delay. */
    public volatile long lastRefreshDelay;
    public volatile long publishDelay;
    public volatile long lastEndedPoll;
    public volatile long lastBazaarPoll;
    public volatile long lastItems;
    public final AtomicInteger auctionNotifications = new AtomicInteger();
    public final AtomicInteger bazaarNotifications = new AtomicInteger();

    private final Map<String, String> errors = new ConcurrentHashMap<String, String>();

    void error(String source, Throwable error) {
        String message = error.getMessage();
        errors.put(source, message != null && !message.isEmpty() ? message : error.getClass().getSimpleName());
    }

    void ok(String source) {
        errors.remove(source);
    }

    public Map<String, String> errors() {
        return new TreeMap<String, String>(errors);
    }
}
