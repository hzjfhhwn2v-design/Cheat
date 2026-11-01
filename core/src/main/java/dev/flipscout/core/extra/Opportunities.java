package dev.flipscout.core.extra;

import dev.flipscout.core.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Keeps SkyCofl's flip lists fresh and picks the flips to alert about: those above the user's
 * thresholds that were not alerted recently. The first download of each list after the game
 * starts only takes note of what is there (plus one summary line), so joining never floods the
 * chat with flips that were there all along.
 */
public final class Opportunities {
    /** Alerts per list and refresh: more would bury the auction flips. */
    static final int MAX_ALERTS_PER_REFRESH = 3;
    private static final long LOW_SUPPLY_TTL_MS = 10 * 60_000L;
    /** A failed download is retried after this long, whatever the refresh interval. */
    private static final long RETRY_MS = 2 * 60_000L;

    private final Config config;
    private final CoflLists lists;
    private final Market market;
    private final LongSupplier clock;
    private final Map<OpportunityKind, List<Opportunity>> cache = new EnumMap<OpportunityKind, List<Opportunity>>(OpportunityKind.class);
    private final Map<OpportunityKind, Long> fetchedAt = new EnumMap<OpportunityKind, Long>(OpportunityKind.class);
    private final Map<OpportunityKind, Long> nextRefresh = new EnumMap<OpportunityKind, Long>(OpportunityKind.class);
    private final Set<OpportunityKind> seeded = EnumSet.noneOf(OpportunityKind.class);
    /** Flips above the thresholds when each list was first read, for the summary line. */
    private final Map<OpportunityKind, Integer> seedCounts = new EnumMap<OpportunityKind, Integer>(OpportunityKind.class);
    private boolean summaryGiven;
    /** When each flip was last alerted (or seen at the first download). */
    private final Map<String, Long> alerted = new HashMap<String, Long>();
    private List<SupplyItem> lowSupply;
    private long lowSupplyAt;
    private volatile long lastSuccess;
    private volatile String lastError;

    public Opportunities(Config config, CoflLists lists, Market market, LongSupplier clock) {
        this.config = config;
        this.lists = lists;
        this.market = market;
        this.clock = clock;
    }

    /** Whether alerts are on for a kind. */
    public boolean alerts(OpportunityKind kind) {
        switch (kind) {
            case KAT:
                return config.otherKatNotify;
            case CRAFT:
                return config.otherCraftNotify;
            case FORGE:
                return config.otherForgeNotify;
            case FUSION:
                return config.otherFusionNotify;
            case NPC:
                return config.otherNpcNotify;
            default:
                return config.otherBooksNotify;
        }
    }

    /** Whether a flip is good enough for an alert. */
    public boolean passes(Opportunity o) {
        switch (o.kind) {
            case KAT:
                return o.profit >= config.otherKatMinProfit;
            case CRAFT:
                return o.profit >= config.otherCraftMinProfit && !(o.perDay < config.otherCraftMinVolume)
                    && !(config.otherCraftMinVolume > 0 && Double.isNaN(o.perDay));
            case FORGE:
                return o.hours > 0 && o.perHour() >= config.otherForgeMinProfitPerHour;
            case FUSION:
                return o.profit >= config.otherFusionMinProfit;
            case NPC:
                return o.profit >= config.otherNpcMinProfit && !(o.perDay < config.otherNpcMinVolume)
                    && !(config.otherNpcMinVolume > 0 && Double.isNaN(o.perDay));
            default:
                return o.profit >= config.otherBooksMinProfit;
        }
    }

    /**
     * A list due for its scheduled download (alerts on, interval passed), reserved so the next
     * call picks another one; null when none is due.
     */
    public synchronized OpportunityKind due(long now) {
        for (OpportunityKind kind : OpportunityKind.values()) {
            if (!alerts(kind)) {
                continue;
            }
            // Kat flips point at auctions: wait until we know which ones are still listed.
            if (kind == OpportunityKind.KAT && config.ahEnabled && !market.auctionsIndexed()) {
                continue;
            }
            Long next = nextRefresh.get(kind);
            if (next == null || now >= next) {
                nextRefresh.put(kind, now + intervalMs());
                return kind;
            }
        }
        return null;
    }

    /** Downloads a list and returns the flips to alert about now. */
    public List<Opportunity> refresh(OpportunityKind kind) throws IOException {
        List<Opportunity> list = download(kind);
        long now = clock.getAsLong();
        List<Opportunity> alerts = new ArrayList<Opportunity>();
        synchronized (this) {
            pruneAlerted(now);
            boolean first = seeded.add(kind);
            int passing = 0;
            for (Opportunity o : list) {
                if (!passes(o)) {
                    continue;
                }
                passing++;
                Long at = alerted.get(o.key);
                if (at != null && now - at < cooldownMs()) {
                    continue;
                }
                if (first) {
                    alerted.put(o.key, now);
                } else if (alerts.size() < MAX_ALERTS_PER_REFRESH) {
                    alerts.add(o);
                    alerted.put(o.key, now);
                }
            }
            if (first) {
                seedCounts.put(kind, passing);
            }
        }
        return alerts;
    }

    /** Called when a scheduled download failed: tries again sooner than the usual interval. */
    public synchronized void failed(OpportunityKind kind, Exception e) {
        lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        nextRefresh.put(kind, clock.getAsLong() + Math.min(RETRY_MS, intervalMs()));
    }

    /**
     * Once every list with alerts on has been read: how many flips of each kind were above the
     * thresholds then. Returned a single time; null before that and afterwards.
     */
    public synchronized Map<OpportunityKind, Integer> startupSummary() {
        if (summaryGiven) {
            return null;
        }
        Map<OpportunityKind, Integer> counts = new LinkedHashMap<OpportunityKind, Integer>();
        for (OpportunityKind kind : OpportunityKind.values()) {
            if (!alerts(kind)) {
                continue;
            }
            Integer count = seedCounts.get(kind);
            if (count == null) {
                return null;
            }
            counts.put(kind, count);
        }
        if (counts.isEmpty()) {
            return null;
        }
        summaryGiven = true;
        return counts;
    }

    /** The best {@code limit} flips of a kind, downloading the list first when it is older than the interval. */
    public List<Opportunity> list(OpportunityKind kind, int limit) throws IOException {
        List<Opportunity> list;
        synchronized (this) {
            Long at = fetchedAt.get(kind);
            list = at != null && clock.getAsLong() - at < intervalMs() ? cache.get(kind) : null;
        }
        if (list == null) {
            list = download(kind);
        }
        return new ArrayList<Opportunity>(list.subList(0, Math.min(limit, list.size())));
    }

    /** Items in low supply, most traded first (kept for ten minutes). */
    public List<SupplyItem> lowSupply(int limit) throws IOException {
        List<SupplyItem> list;
        synchronized (this) {
            list = lowSupply != null && clock.getAsLong() - lowSupplyAt < LOW_SUPPLY_TTL_MS ? lowSupply : null;
        }
        if (list == null) {
            list = lists.fetchLowSupply(market);
            synchronized (this) {
                lowSupply = list;
                lowSupplyAt = clock.getAsLong();
            }
        }
        return new ArrayList<SupplyItem>(list.subList(0, Math.min(limit, list.size())));
    }

    public long lastSuccess() {
        return lastSuccess;
    }

    public String lastError() {
        return lastError;
    }

    /** Lists downloaded at least once, for /flipscout status. */
    public synchronized int listsLoaded() {
        return cache.size();
    }

    private List<Opportunity> download(OpportunityKind kind) throws IOException {
        List<Opportunity> list;
        try {
            list = lists.fetch(kind, market);
        } catch (IOException e) {
            lastError = e.getMessage();
            throw e;
        }
        long now = clock.getAsLong();
        synchronized (this) {
            cache.put(kind, list);
            fetchedAt.put(kind, now);
        }
        lastSuccess = now;
        lastError = null;
        return list;
    }

    private void pruneAlerted(long now) {
        long keep = Math.max(cooldownMs() * 2, 24 * 60 * 60_000L);
        Iterator<Map.Entry<String, Long>> it = alerted.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > keep) {
                it.remove();
            }
        }
    }

    private long intervalMs() {
        return Math.max(1, config.otherRefreshMinutes) * 60_000L;
    }

    private long cooldownMs() {
        return Math.max(1, config.otherCooldownHours) * 60 * 60_000L;
    }
}
