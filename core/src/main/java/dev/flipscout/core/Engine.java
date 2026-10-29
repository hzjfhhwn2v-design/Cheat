package dev.flipscout.core;

import dev.flipscout.core.api.Auction;
import dev.flipscout.core.api.AuctionPage;
import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.CoflnetApi;
import dev.flipscout.core.api.EndedAuction;
import dev.flipscout.core.api.EndedPage;
import dev.flipscout.core.api.HypixelApi;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.api.NeuApi;
import dev.flipscout.core.flip.AuctionFlip;
import dev.flipscout.core.flip.AuctionFlipFinder;
import dev.flipscout.core.flip.BazaarFlip;
import dev.flipscout.core.flip.BazaarFlipFinder;
import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.item.ItemKeys;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.BazaarHistory;
import dev.flipscout.core.market.CoflnetSource;
import dev.flipscout.core.market.PriceService;
import dev.flipscout.core.market.SalesHistory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Polls the data sources on a background thread and turns new data into notifications.
 * All mutable state below is only touched by the engine thread unless noted otherwise.
 */
public final class Engine {
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    /** Auctions created this recently are new: older ones were simply missed, not just listed. */
    private static final long FRESH_MS = 5 * MINUTE;
    private static final int MAX_RECENT = 30;

    /** A notification kept for /flipscout recent and /flipscout open. */
    public static final class Notification {
        public final long time;
        public final Msg message;
        /** Command with leading slash, or null. */
        public final String command;

        Notification(long time, Msg message, String command) {
            this.time = time;
            this.message = message;
            this.command = command;
        }
    }

    private static final class Backoff {
        private int failures;

        long fail() {
            long delay = Math.min(5 * MINUTE, 5_000L << Math.min(failures, 6));
            failures++;
            return delay;
        }

        void reset() {
            failures = 0;
        }
    }

    private final Config config;
    private final Platform platform;
    private final LongSupplier clock;
    private final File salesFile;
    private final HypixelApi hypixel;
    private final NeuApi neu;

    final AuctionIndex index = new AuctionIndex();
    final SalesHistory sales = new SalesHistory();
    final BazaarHistory bazaarHistory = new BazaarHistory();
    final CoflnetSource coflnet;
    final PriceService prices;
    final AuctionFlipFinder auctionFinder;
    final BazaarFlipFinder bazaarFinder;
    final Status status = new Status();
    private final Notifier notifier = new Notifier();

    private final Map<String, ItemKey> canonicalKeys = new ConcurrentHashMap<String, ItemKey>();
    private final Set<String> evaluated = boundedSet(50_000);
    private final Map<String, Long> cooldowns = new HashMap<String, Long>();
    /** Guarded by itself: read by commands on other threads. */
    private final Deque<Notification> recent = new ArrayDeque<Notification>();

    private volatile ItemCatalog items = ItemCatalog.EMPTY;
    private volatile boolean initialScanDone;
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService workers;

    private boolean salesLoaded;
    private long scanSeq;
    private long nextFullScan;
    private long nextAuctionPoll;
    private long nextEndedPoll;
    private long nextBazaarPoll;
    private long nextNeuLowestBins;
    private long nextNeuAverages;
    private long nextItems;
    private long nextSave;
    private long nextExpire;
    private long lastAuctionsUpdate;
    private long lastEndedUpdate;
    private long lastBazaarUpdate;
    private int auctionStale;
    private int endedStale;
    private final Backoff auctionBackoff = new Backoff();
    private final Backoff endedBackoff = new Backoff();
    private final Backoff bazaarBackoff = new Backoff();
    private final Backoff neuBackoff = new Backoff();
    private final Backoff itemsBackoff = new Backoff();

    public Engine(final Config config, Platform platform, HttpFetcher http, File dataDir, LongSupplier clock) {
        this.config = config;
        this.platform = platform;
        this.clock = clock;
        this.salesFile = new File(dataDir, "sales.bin");
        this.hypixel = new HypixelApi(http, () -> config.urlHypixel);
        this.neu = new NeuApi(http, () -> config.urlNeu);
        this.coflnet = new CoflnetSource(new CoflnetApi(http, () -> config.urlCoflnet), clock);
        this.prices = new PriceService(config, index, sales, coflnet, clock);
        this.auctionFinder = new AuctionFlipFinder(config, prices);
        this.bazaarFinder = new BazaarFlipFinder(config);
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        workers = Executors.newFixedThreadPool(4, threads("FlipScout-download"));
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(threads("FlipScout"));
        s.scheduleWithFixedDelay(this::tickSafely, 1, 1, TimeUnit.SECONDS);
        scheduler = s;
    }

    public void stop() {
        ScheduledExecutorService s;
        ExecutorService w;
        synchronized (this) {
            s = scheduler;
            w = workers;
            scheduler = null;
            workers = null;
        }
        if (s == null) {
            return;
        }
        s.shutdownNow();
        if (w != null) {
            w.shutdownNow();
        }
        try {
            s.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (salesLoaded) {
            saveSales(clock.getAsLong());
        }
    }

    /** Runs a slow query off the client thread (inline when the engine is not running). */
    public void runAsync(Runnable task) {
        ExecutorService w = workers;
        if (w == null) {
            task.run();
            return;
        }
        try {
            w.execute(task);
        } catch (RuntimeException e) {
            task.run();
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            Log.error("Errore inatteso nel ciclo principale", t);
        }
    }

    void tick() {
        if (!salesLoaded) {
            sales.load(salesFile);
            salesLoaded = true;
        }
        boolean active = config.enabled && (!config.onlyOnHypixel || platform.isOnHypixel());
        status.active = active;
        if (!active) {
            return;
        }
        long now = clock.getAsLong();
        if (now >= nextItems) {
            refreshItems(now);
        }
        if (config.sourceNeu) {
            now = clock.getAsLong();
            if (now >= nextNeuLowestBins) {
                refreshNeuLowestBins(now);
            }
            now = clock.getAsLong();
            if (now >= nextNeuAverages) {
                refreshNeuAverages(now);
            }
        }
        if (config.ahEnabled) {
            now = clock.getAsLong();
            if (now >= nextFullScan) {
                fullScan(now);
            } else if (now >= nextAuctionPoll) {
                pollAuctions(now);
            }
            now = clock.getAsLong();
            if (now >= nextEndedPoll) {
                pollEnded(now);
            }
            now = clock.getAsLong();
            if (now >= nextExpire) {
                index.removeExpired(now);
                nextExpire = now + 30_000L;
            }
        }
        now = clock.getAsLong();
        if (config.bzEnabled && now >= nextBazaarPoll) {
            pollBazaar(now);
        }
        now = clock.getAsLong();
        if (now >= nextSave) {
            saveSales(now);
            nextSave = now + 5 * MINUTE;
        }
    }

    // ---- Auctions ----

    private void fullScan(long startedAt) {
        long scan = ++scanSeq;
        status.scanning = true;
        try {
            AuctionPage first = hypixel.fetchAuctionPage(0);
            int pages = Math.max(1, first.totalPages);
            ExecutorService pool = workers;
            List<Future<AuctionPage>> futures = new ArrayList<Future<AuctionPage>>();
            if (pool != null) {
                for (int p = 1; p < pages; p++) {
                    final int page = p;
                    futures.add(pool.submit(new Callable<AuctionPage>() {
                        @Override
                        public AuctionPage call() throws IOException {
                            return decodeAll(hypixel.fetchAuctionPage(page));
                        }
                    }));
                }
            }
            int auctions = merge(decodeAll(first), scan, clock.getAsLong());
            boolean complete = true;
            if (pool == null) {
                for (int p = 1; p < pages; p++) {
                    auctions += merge(decodeAll(hypixel.fetchAuctionPage(p)), scan, clock.getAsLong());
                }
            } else {
                for (Future<AuctionPage> future : futures) {
                    try {
                        auctions += merge(future.get(2, TimeUnit.MINUTES), scan, clock.getAsLong());
                    } catch (ExecutionException e) {
                        complete = false;
                        Log.warn("Pagina di aste non scaricata: " + e.getCause());
                    } catch (TimeoutException e) {
                        complete = false;
                        future.cancel(true);
                        Log.warn("Pagina di aste troppo lenta, la salto");
                    }
                }
            }
            if (complete) {
                // Only a complete scan can tell which auctions disappeared (sold or cancelled).
                index.removeUnseen(scan, startedAt);
            }
            long end = clock.getAsLong();
            status.lastFullScan = end;
            status.fullScanPages = pages;
            status.fullScanAuctions = auctions;
            status.fullScanMillis = end - startedAt;
            status.ok("aste");
            auctionBackoff.reset();
            initialScanDone = true;
            nextFullScan = end + config.ahFullScanMinutes * MINUTE;
            if (first.lastUpdated > lastAuctionsUpdate) {
                lastAuctionsUpdate = first.lastUpdated;
            }
            nextAuctionPoll = expectedNextUpdate(first.lastUpdated, first.serverDate, end);
            Log.debug("Scansione completa: " + auctions + " aste in " + pages + " pagine, " + index.size() + " BIN indicizzate");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            failAuctions(e);
        } catch (RuntimeException e) {
            failAuctions(e);
        } finally {
            status.scanning = false;
        }
    }

    private void failAuctions(Exception e) {
        status.error("aste", e);
        Log.warn("Aste non disponibili: " + e);
        nextFullScan = clock.getAsLong() + auctionBackoff.fail();
    }

    private void pollAuctions(long now) {
        try {
            AuctionPage first = hypixel.fetchAuctionPage(0);
            status.lastAuctionPoll = now;
            if (first.lastUpdated != 0 && first.lastUpdated <= lastAuctionsUpdate) {
                auctionStale++;
                nextAuctionPoll = now + staleDelay(auctionStale);
                return;
            }
            auctionStale = 0;
            lastAuctionsUpdate = first.lastUpdated;
            merge(decodeAll(first), scanSeq, now);
            int pages = Math.min(config.ahPollPages, Math.max(1, first.totalPages));
            for (int p = 1; p < pages; p++) {
                merge(decodeAll(hypixel.fetchAuctionPage(p)), scanSeq, clock.getAsLong());
            }
            status.ok("aste");
            auctionBackoff.reset();
            nextAuctionPoll = expectedNextUpdate(first.lastUpdated, first.serverDate, clock.getAsLong());
        } catch (IOException e) {
            status.error("aste", e);
            nextAuctionPoll = clock.getAsLong() + auctionBackoff.fail();
        } catch (RuntimeException e) {
            status.error("aste", e);
            nextAuctionPoll = clock.getAsLong() + auctionBackoff.fail();
        }
    }

    /** Decodes item bytes of BIN auctions we do not know yet (runs on download threads too). */
    private AuctionPage decodeAll(AuctionPage page) {
        for (Auction auction : page.auctions) {
            if (!auction.bin || auction.decoded != null || index.contains(auction.uuid)) {
                auction.itemBytes = null;
                continue;
            }
            auction.decoded = canonical(ItemKeys.decodeQuiet(auction.itemBytes, auction.itemName));
            auction.itemBytes = null;
        }
        return page;
    }

    private ItemKeys.Decoded canonical(ItemKeys.Decoded decoded) {
        if (decoded == null) {
            return null;
        }
        ItemKey existing = canonicalKeys.putIfAbsent(decoded.key.key, decoded.key);
        if (existing != null && existing != decoded.key && existing.equals(decoded.key)) {
            return new ItemKeys.Decoded(existing, decoded.count, decoded.name);
        }
        return decoded;
    }

    private int merge(AuctionPage page, long scan, long now) {
        for (Auction auction : page.auctions) {
            if (!auction.bin || index.touch(auction.uuid, scan) || auction.decoded == null) {
                continue;
            }
            ItemKeys.Decoded decoded = auction.decoded;
            AuctionIndex.Listing listing = new AuctionIndex.Listing(auction.uuid, normalizeUuid(auction.seller),
                decoded.name, auction.tier, decoded.key, auction.startingBid, decoded.count,
                auction.start, auction.end, now, scan);
            index.add(listing);
            if (initialScanDone && now - auction.start < FRESH_MS) {
                consider(listing, now);
            }
        }
        return page.auctions.size();
    }

    private void consider(AuctionIndex.Listing listing, long now) {
        if (!evaluated.add(listing.uuid)) {
            return;
        }
        String me = platform.playerUuid();
        if (me != null && me.equals(listing.seller)) {
            return;
        }
        AuctionFlip flip = auctionFinder.evaluate(listing, true);
        if (flip == null) {
            return;
        }
        status.auctionNotifications++;
        deliver(notifier.auction(flip, now), true, now);
    }

    private void pollEnded(long now) {
        try {
            EndedPage page = hypixel.fetchEnded();
            status.lastEndedPoll = now;
            if (page.lastUpdated != 0 && page.lastUpdated <= lastEndedUpdate) {
                endedStale++;
                nextEndedPoll = now + staleDelay(endedStale);
                return;
            }
            endedStale = 0;
            lastEndedUpdate = page.lastUpdated;
            sales.markObserved((int) ((page.lastUpdated > 0 ? page.lastUpdated : now) / MINUTE));
            for (EndedAuction ended : page.auctions) {
                index.remove(ended.auctionId);
                if (!ended.bin || ended.price <= 0) {
                    continue;
                }
                ItemKeys.Decoded decoded = ItemKeys.decodeQuiet(ended.itemBytes, null);
                if (decoded == null) {
                    continue;
                }
                long time = ended.timestamp > 0 ? ended.timestamp : now;
                sales.add(decoded.key.key, (int) (time / MINUTE), (double) ended.price / decoded.count);
            }
            status.ok("vendite");
            endedBackoff.reset();
            nextEndedPoll = expectedNextUpdate(page.lastUpdated, page.serverDate, clock.getAsLong());
        } catch (IOException e) {
            status.error("vendite", e);
            nextEndedPoll = clock.getAsLong() + endedBackoff.fail();
        } catch (RuntimeException e) {
            status.error("vendite", e);
            nextEndedPoll = clock.getAsLong() + endedBackoff.fail();
        }
    }

    /**
     * Both auction endpoints refresh once a minute: aim just after the next refresh, correcting
     * for the difference between our clock and Hypixel's.
     */
    static long expectedNextUpdate(long lastUpdated, long serverDate, long now) {
        if (lastUpdated <= 0) {
            return now + MINUTE;
        }
        long skew = serverDate > 0 ? now - serverDate : 0;
        long next = lastUpdated + MINUTE + skew + 2_000L;
        return Math.max(now + 3_000L, Math.min(next, now + 70_000L));
    }

    private static long staleDelay(int attempts) {
        return attempts < 10 ? 3_000L : attempts < 30 ? 10_000L : 30_000L;
    }

    // ---- Bazaar ----

    private void pollBazaar(long now) {
        try {
            BazaarSnapshot snapshot = hypixel.fetchBazaar();
            status.lastBazaarPoll = now;
            status.ok("bazaar");
            bazaarBackoff.reset();
            nextBazaarPoll = now + config.bzIntervalSeconds * 1000L;
            if (snapshot.lastUpdated != 0 && snapshot.lastUpdated <= lastBazaarUpdate) {
                return;
            }
            lastBazaarUpdate = snapshot.lastUpdated;
            prices.setBazaar(snapshot);
            // Compare with the history before adding the new sample to it.
            List<BazaarFlip> flips = bazaarFinder.find(snapshot, bazaarHistory, items, now);
            bazaarHistory.record(snapshot, now);
            notifyBazaar(flips, now);
        } catch (IOException e) {
            status.error("bazaar", e);
            nextBazaarPoll = clock.getAsLong() + bazaarBackoff.fail();
        } catch (RuntimeException e) {
            status.error("bazaar", e);
            nextBazaarPoll = clock.getAsLong() + bazaarBackoff.fail();
        }
    }

    private void notifyBazaar(List<BazaarFlip> flips, long now) {
        int shown = 0;
        for (BazaarFlip flip : flips) {
            String key = flip.cooldownKey();
            Long until = cooldowns.get(key);
            if (until != null && until > now) {
                continue;
            }
            // Everything that qualifies goes on cooldown, shown or not, so that later cycles only
            // announce opportunities that are actually new.
            cooldowns.put(key, now + cooldownMinutes(flip.type) * MINUTE);
            if (shown >= config.bzMaxPerCycle) {
                continue;
            }
            shown++;
            status.bazaarNotifications++;
            deliver(notifier.bazaar(flip), false, now);
        }
        if (cooldowns.size() > 10_000) {
            Iterator<Map.Entry<String, Long>> it = cooldowns.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() <= now) {
                    it.remove();
                }
            }
        }
    }

    private int cooldownMinutes(BazaarFlip.Type type) {
        switch (type) {
            case ORDER:
                return config.bzFlipCooldownMinutes;
            case CRASH:
                return config.bzCrashCooldownMinutes;
            default:
                return config.bzNpcCooldownMinutes;
        }
    }

    // ---- Reference data ----

    private void refreshItems(long now) {
        try {
            ItemCatalog catalog = hypixel.fetchItems();
            if (catalog.size() > 0) {
                items = catalog;
            }
            status.lastItems = now;
            status.ok("oggetti");
            itemsBackoff.reset();
            nextItems = now + 6 * HOUR;
        } catch (IOException e) {
            status.error("oggetti", e);
            nextItems = clock.getAsLong() + itemsBackoff.fail();
        } catch (RuntimeException e) {
            status.error("oggetti", e);
            nextItems = clock.getAsLong() + itemsBackoff.fail();
        }
    }

    private void refreshNeuLowestBins(long now) {
        try {
            prices.setNeuLowestBins(neu.fetchLowestBins());
            status.lastNeu = now;
            status.ok("NEU");
            neuBackoff.reset();
            nextNeuLowestBins = now + 5 * MINUTE;
        } catch (IOException e) {
            failNeu(e);
        } catch (RuntimeException e) {
            failNeu(e);
        }
    }

    private void refreshNeuAverages(long now) {
        try {
            prices.setNeuAverages(neu.fetchAverages());
            status.ok("NEU");
            nextNeuAverages = now + HOUR;
        } catch (IOException e) {
            failNeu(e);
        } catch (RuntimeException e) {
            failNeu(e);
        }
    }

    private void failNeu(Exception e) {
        status.error("NEU", e);
        long retry = clock.getAsLong() + Math.max(5 * MINUTE, neuBackoff.fail());
        // Do not hammer a site that is down: both NEU files wait.
        nextNeuLowestBins = Math.max(nextNeuLowestBins, retry);
        nextNeuAverages = Math.max(nextNeuAverages, retry);
    }

    private void saveSales(long now) {
        try {
            sales.prune((int) (now / MINUTE), config.salesWindowDays);
            sales.save(salesFile);
        } catch (IOException e) {
            Log.warn("Impossibile salvare le vendite", e);
        }
    }

    // ---- Notifications ----

    private void deliver(Msg message, boolean auction, long now) {
        synchronized (recent) {
            recent.addFirst(new Notification(now, message, message.firstCommand()));
            while (recent.size() > MAX_RECENT) {
                recent.removeLast();
            }
        }
        Log.debug("Notifica: " + message.toPlainString());
        if (config.onlyInSkyblock && !platform.isInSkyblock()) {
            return;
        }
        platform.showMessage(message);
        if (config.sound) {
            platform.playSound(auction);
        }
    }

    // ---- Queries for commands (any thread) ----

    public List<AuctionFlip> topAuctions(int limit) {
        String me = platform.playerUuid();
        List<AuctionFlip> flips = new ArrayList<AuctionFlip>();
        for (AuctionIndex.Listing listing : index.cheapestPerKey()) {
            if (me != null && me.equals(listing.seller)) {
                continue;
            }
            AuctionFlip flip = auctionFinder.evaluate(listing, false);
            if (flip != null) {
                flips.add(flip);
            }
        }
        Collections.sort(flips, new Comparator<AuctionFlip>() {
            @Override
            public int compare(AuctionFlip a, AuctionFlip b) {
                return Double.compare(b.profit, a.profit);
            }
        });
        return flips.size() > limit ? new ArrayList<AuctionFlip>(flips.subList(0, limit)) : flips;
    }

    public List<BazaarFlip> topBazaar(int limit) {
        BazaarSnapshot snapshot = prices.bazaar();
        if (snapshot == null) {
            return Collections.emptyList();
        }
        List<BazaarFlip> flips = bazaarFinder.find(snapshot, bazaarHistory, items, clock.getAsLong());
        return flips.size() > limit ? new ArrayList<BazaarFlip>(flips.subList(0, limit)) : flips;
    }

    public List<Notification> recent() {
        synchronized (recent) {
            return new ArrayList<Notification>(recent);
        }
    }

    public Notification lastNotification() {
        synchronized (recent) {
            return recent.peekFirst();
        }
    }

    public ItemCatalog items() {
        return items;
    }

    public boolean initialScanDone() {
        return initialScanDone;
    }

    Notifier notifier() {
        return notifier;
    }

    private static String normalizeUuid(String uuid) {
        return uuid == null ? null : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static Set<String> boundedSet(final int max) {
        return Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > max;
            }
        });
    }

    private static ThreadFactory threads(final String name) {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }
        };
    }
}
