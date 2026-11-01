package dev.flipscout.core;

import dev.flipscout.core.api.Auction;
import dev.flipscout.core.api.AuctionPage;
import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.CoflnetApi;
import dev.flipscout.core.api.EndedAuction;
import dev.flipscout.core.api.EndedPage;
import dev.flipscout.core.api.HypixelApi;
import dev.flipscout.core.api.ItemCatalog;
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
import dev.flipscout.core.util.Text;

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
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Polls the data sources and turns new data into notifications.
 *
 * <p>The engine thread only does what sits on the path to an auction notification: checking
 * for the minute's new auctions and merging pages. Full scans, sold auctions, the bazaar,
 * reference data, saving and Coflnet confirmations run on background threads, so they never
 * hold a notification back.
 */
public final class Engine {
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    /** Auctions created this recently are new: older ones were simply missed, not just listed. */
    private static final long FRESH_MS = 5 * MINUTE;
    private static final int MAX_RECENT = 30;
    /** Extra auction pages read per poll while they still contain new auctions. */
    private static final int EXTRA_POLL_PAGES = 3;
    /** How often the engine thread wakes up, i.e. the most a due poll can be late. */
    private static final long TICK_MS = 250L;
    /** A full scan whose pages have not all arrived by then is closed as incomplete. */
    private static final long SCAN_TIMEOUT_MS = 3 * MINUTE;
    /**
     * Coflnet confirmations at the same time: up to six for flips missing a second price or their
     * volume, but only three for auctions that are not flips on local data, so that those never
     * take the place of real flips (Coflnet's own rate limit still applies).
     */
    private static final int MAX_PENDING_CONFIRMATIONS = 6;
    private static final int MAX_SPECULATIVE_CONFIRMATIONS = 3;
    /** Coflnet sales prices are looked up ahead of time at most this often... */
    private static final long PREFETCH_EVERY_MS = 10_000L;
    /** ...starting a while after the first scan, once our own sales had their chance. */
    private static final long PREFETCH_DELAY_MS = 2 * MINUTE;

    /** Who bought a notified auction. */
    public enum Buyer { YOU, COOP, SOMEONE_ELSE }

    /** A notification kept for /flipscout recent and /flipscout open. */
    public static final class Notification {
        public final long time;
        public final Msg message;
        /** Command with leading slash, or null. */
        public final String command;
        /** For auction flips: the auction, and when it was listed and notified (Hypixel's clock). */
        public final String auctionUuid;
        public final long listedAt;
        public final long notifiedAt;
        /** For auction flips: item name without colours, price, expected resale and profit. */
        public final String itemName;
        public final long price;
        public final double resale;
        public final double profit;
        private volatile Buyer buyer;
        private volatile String buyerName;
        private volatile long soldAt;

        Notification(long time, Msg message, String command) {
            this(time, message, command, null, 0, 0, null, 0, 0, 0);
        }

        Notification(long time, Msg message, String command, String auctionUuid, long listedAt, long notifiedAt,
                     String itemName, long price, double resale, double profit) {
            this.time = time;
            this.message = message;
            this.command = command;
            this.auctionUuid = auctionUuid;
            this.listedAt = listedAt;
            this.notifiedAt = notifiedAt;
            this.itemName = itemName;
            this.price = price;
            this.resale = resale;
            this.profit = profit;
        }

        /** When the auction sold (Hypixel's clock), 0 while we have not seen it sell. */
        public long soldAt() {
            return soldAt;
        }

        /** Who bought it, null while unsold. */
        public Buyer buyer() {
            return buyer;
        }

        /** Name of the co-op member who bought it, when known. */
        public String buyerName() {
            return buyerName;
        }

        void sold(long at, Buyer by, String name) {
            buyer = by;
            buyerName = name;
            soldAt = at;
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

    /** A download of the whole auction house; pages are merged as they arrive. */
    private static final class FullScan {
        final long id;
        final long startedAt;
        final int pages;
        /** lastUpdated of page 0: pages from another refresh may have shifted. */
        final long lastUpdated;
        final Queue<AuctionPage> fetched = new ConcurrentLinkedQueue<AuctionPage>();
        final AtomicInteger pending;
        volatile boolean failed;
        boolean mixed;
        int mergedPages;
        int auctions;

        FullScan(long id, long startedAt, AuctionPage first) {
            this.id = id;
            this.startedAt = startedAt;
            this.pages = Math.max(1, first.totalPages);
            this.lastUpdated = first.lastUpdated;
            this.pending = new AtomicInteger(pages - 1);
        }
    }

    private final Config config;
    private final Platform platform;
    private final LongSupplier clock;
    private final File salesFile;
    private final HttpFetcher http;
    private final HypixelApi hypixel;
    private final CoflnetApi coflnetApi;

    final AuctionIndex index = new AuctionIndex();
    final SalesHistory sales = new SalesHistory();
    final BazaarHistory bazaarHistory = new BazaarHistory();
    final CoflnetSource coflnet;
    final PriceService prices;
    final AuctionFlipFinder auctionFinder;
    final BazaarFlipFinder bazaarFinder;
    final Status status = new Status();
    final Coop coop;
    private final Notifier notifier = new Notifier();
    private final BlockedFlips blocked = new BlockedFlips();

    private final Map<String, ItemKey> canonicalKeys = new ConcurrentHashMap<String, ItemKey>();
    /** Guarded by itself: read by commands on other threads. */
    private final Deque<Notification> recent = new ArrayDeque<Notification>();

    private volatile ItemCatalog items = ItemCatalog.EMPTY;
    private volatile boolean initialScanDone;
    /** Local clock minus Hypixel's, from the Date header of the last auction page. */
    private volatile long clockSkew;
    /**
     * How long after its lastUpdated a refresh shows up in the API (about 8s when measured),
     * learned from the refreshes we were waiting for; 0 until then.
     */
    private volatile long publishDelay;
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService scanPool;
    private volatile ExecutorService backgroundPool;

    // Engine thread only.
    private final Set<String> evaluated = boundedSet(50_000);
    private volatile boolean salesLoaded;
    private long scanSeq;
    private FullScan scan;
    private long nextFullScan;
    private long nextAuctionPoll;
    private long nextExpire;
    private long nextSave;
    private long nextPrefetch;
    private long lastAuctionsUpdate;
    private int auctionStale;
    private final Backoff auctionBackoff = new Backoff();

    // Background jobs: each kind runs one at a time (see background()), so their own state needs
    // no locking; the due times are volatile because the engine thread reads them.
    private final AtomicBoolean referenceJob = new AtomicBoolean();
    private final AtomicBoolean endedJob = new AtomicBoolean();
    private final AtomicBoolean bazaarJob = new AtomicBoolean();
    private final AtomicBoolean saveJob = new AtomicBoolean();
    private final AtomicBoolean prefetchJob = new AtomicBoolean();
    private final AtomicBoolean coopJob = new AtomicBoolean();
    private final AtomicInteger pendingConfirmations = new AtomicInteger();
    private volatile long nextItems;
    private volatile long nextNames;
    private volatile long nextEndedPoll;
    private volatile long nextBazaarPoll;
    private long lastEndedUpdate;
    private int endedStale;
    private long lastBazaarUpdate;
    private final Map<String, Long> cooldowns = new HashMap<String, Long>();
    private final Backoff endedBackoff = new Backoff();
    private final Backoff bazaarBackoff = new Backoff();
    private final Backoff itemsBackoff = new Backoff();
    private final Backoff namesBackoff = new Backoff();

    public Engine(final Config config, Platform platform, HttpFetcher http, File dataDir, LongSupplier clock) {
        this.config = config;
        this.platform = platform;
        this.clock = clock;
        this.salesFile = new File(dataDir, "sales.bin");
        this.http = http;
        this.hypixel = new HypixelApi(http, () -> config.urlHypixel);
        this.coflnetApi = new CoflnetApi(http, () -> config.urlCoflnet);
        this.coflnet = new CoflnetSource(coflnetApi, clock);
        this.coop = new Coop(platform, clock);
        this.prices = new PriceService(config, index, sales, coflnet, clock);
        this.auctionFinder = new AuctionFlipFinder(config, prices);
        this.bazaarFinder = new BazaarFlipFinder(config);
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scanPool = Executors.newFixedThreadPool(4, threads("FlipScout-scan"));
        backgroundPool = Executors.newFixedThreadPool(3, threads("FlipScout-background"));
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(threads("FlipScout"));
        s.scheduleWithFixedDelay(this::tickSafely, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        scheduler = s;
    }

    public void stop() {
        ScheduledExecutorService s;
        ExecutorService scans;
        ExecutorService background;
        synchronized (this) {
            s = scheduler;
            scans = scanPool;
            background = backgroundPool;
            scheduler = null;
            scanPool = null;
            backgroundPool = null;
        }
        if (s == null) {
            return;
        }
        s.shutdownNow();
        if (scans != null) {
            scans.shutdownNow();
        }
        if (background != null) {
            background.shutdownNow();
        }
        try {
            s.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (salesLoaded) {
            saveSales();
        }
    }

    /** Tests run everything inline by default; this lets them supply their own executors. */
    void useExecutors(ExecutorService scans, ExecutorService background) {
        scanPool = scans;
        backgroundPool = background;
    }

    /** Runs a slow task off the calling thread (inline when the engine is not running). */
    public void runAsync(Runnable task) {
        ExecutorService pool = backgroundPool;
        if (pool == null) {
            task.run();
            return;
        }
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    /** Runs {@code job} in the background unless the same kind of job is still running. */
    private void background(final AtomicBoolean running, final Runnable job) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Runnable wrapped = new Runnable() {
            @Override
            public void run() {
                try {
                    job.run();
                } catch (Throwable t) {
                    Log.error("Unexpected error in a background task", t);
                } finally {
                    running.set(false);
                }
            }
        };
        ExecutorService pool = backgroundPool;
        if (pool == null) {
            wrapped.run();
            return;
        }
        try {
            pool.execute(wrapped);
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            Log.error("Unexpected error in the main loop", t);
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
        if (now >= nextItems || (config.sourceCoflnet && now >= nextNames)) {
            background(referenceJob, this::refreshReferenceData);
        }
        if (coop.namesMissing()) {
            background(coopJob, () -> coop.lookUpNames(http));
        }
        if (config.ahEnabled) {
            now = clock.getAsLong();
            if (scan == null && now >= nextFullScan) {
                startFullScan(now);
            }
            if (scan != null) {
                drainFullScan(clock.getAsLong());
            }
            now = clock.getAsLong();
            if (now >= nextAuctionPoll) {
                pollAuctions(now);
            }
            now = clock.getAsLong();
            if (now >= nextEndedPoll) {
                background(endedJob, this::pollEnded);
            }
            if (now >= nextExpire) {
                index.removeExpired(now - clockSkew);
                nextExpire = now + 30_000L;
            }
            if (config.sourceCoflnet && initialScanDone && now >= nextPrefetch) {
                nextPrefetch = now + PREFETCH_EVERY_MS;
                background(prefetchJob, this::prefetchSalesPrice);
            }
        }
        now = clock.getAsLong();
        if (config.bzEnabled && now >= nextBazaarPoll) {
            background(bazaarJob, this::pollBazaar);
        }
        if (now >= nextSave) {
            nextSave = now + 5 * MINUTE;
            background(saveJob, this::saveSales);
        }
    }

    // ---- Auctions (engine thread) ----

    private void startFullScan(long now) {
        AuctionPage first;
        try {
            first = hypixel.fetchAuctionPage(0, 0);
        } catch (IOException e) {
            failAuctions(e);
            return;
        } catch (RuntimeException e) {
            failAuctions(e);
            return;
        }
        noteServerTime(first);
        final FullScan s = new FullScan(++scanSeq, now, first);
        scan = s;
        status.scanning = true;
        // Page 0 holds the newest auctions: merge it now rather than after the whole scan.
        s.auctions += merge(decodeAll(first), s.id, clock.getAsLong());
        s.mergedPages++;
        if (first.lastUpdated > lastAuctionsUpdate) {
            lastAuctionsUpdate = first.lastUpdated;
        }
        nextAuctionPoll = expectedNextUpdate(first.lastUpdated + publishDelay, first.serverDate, clock.getAsLong());
        ExecutorService pool = scanPool;
        for (int p = 1; p < s.pages; p++) {
            final int page = p;
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    try {
                        s.fetched.add(decodeAll(hypixel.fetchAuctionPage(page, 0)));
                    } catch (IOException e) {
                        s.failed = true;
                        Log.debug("Page " + page + " not downloaded: " + e);
                    } catch (RuntimeException e) {
                        s.failed = true;
                        Log.debug("Page " + page + " unreadable: " + e);
                    } finally {
                        s.pending.decrementAndGet();
                    }
                }
            };
            if (pool == null) {
                task.run();
                continue;
            }
            try {
                pool.execute(task);
            } catch (RejectedExecutionException e) {
                s.failed = true;
                s.pending.decrementAndGet();
            }
        }
    }

    private void drainFullScan(long now) {
        FullScan s = scan;
        AuctionPage page;
        while ((page = s.fetched.poll()) != null) {
            if (page.lastUpdated != s.lastUpdated) {
                // Hypixel refreshed mid-scan, so some auctions may have moved to pages already read.
                s.mixed = true;
            }
            s.auctions += merge(page, s.id, clock.getAsLong());
            s.mergedPages++;
        }
        boolean timedOut = now - s.startedAt > SCAN_TIMEOUT_MS;
        if (!timedOut && (s.pending.get() > 0 || !s.fetched.isEmpty())) {
            return;
        }
        scan = null;
        status.scanning = false;
        boolean complete = !s.failed && !timedOut && s.mergedPages == s.pages;
        if (complete && !s.mixed) {
            // Only a complete scan of one refresh can tell which auctions disappeared (sold or
            // cancelled): with a refresh in between, an auction may have slipped past us.
            index.removeUnseen(s.id, s.startedAt);
        }
        long end = clock.getAsLong();
        status.lastFullScan = end;
        status.fullScanPages = s.pages;
        status.fullScanAuctions = s.auctions;
        status.fullScanMillis = end - s.startedAt;
        // With pages missing some cheaper listings may be unknown, which would make flips look
        // better than they are: notify only once at least nine pages out of ten are in.
        if (!initialScanDone && (complete || s.mergedPages * 10 >= s.pages * 9)) {
            initialScanDone = true;
            nextPrefetch = end + PREFETCH_DELAY_MS;
        }
        if (complete && !s.mixed) {
            status.ok("auctions");
            auctionBackoff.reset();
            nextFullScan = end + config.ahFullScanMinutes * MINUTE;
        } else {
            if (complete) {
                Log.debug("The auctions changed during the full scan, repeating it shortly");
            } else {
                Log.warn("Incomplete full scan: " + s.mergedPages + "/" + s.pages + " pages");
            }
            nextFullScan = end + 2 * MINUTE;
        }
        Log.debug("Full scan: " + s.auctions + " auctions in " + s.pages + " pages, " + index.size()
            + " BINs indexed");
    }

    private void failAuctions(Exception e) {
        status.error("auctions", e);
        Log.warn("Auctions unavailable: " + e);
        nextFullScan = clock.getAsLong() + auctionBackoff.fail();
    }

    private void pollAuctions(long now) {
        try {
            AuctionPage first = hypixel.fetchAuctionPage(0, lastAuctionsUpdate);
            noteServerTime(first);
            status.lastAuctionPoll = now;
            if (first.notModified || (first.lastUpdated != 0 && first.lastUpdated <= lastAuctionsUpdate)) {
                auctionStale++;
                nextAuctionPoll = now + staleDelay(auctionStale);
                return;
            }
            notePublished(first.lastUpdated, auctionStale > 0);
            auctionStale = 0;
            lastAuctionsUpdate = first.lastUpdated;
            long recentSince = now - clockSkew - 2 * MINUTE;
            merge(decodeAll(first), scanSeq, now);
            int recent = countStartedSince(first, recentSince);
            int pages = Math.max(1, first.totalPages);
            int required = Math.min(config.ahPollPages, pages);
            int max = Math.min(pages, config.ahPollPages + EXTRA_POLL_PAGES);
            // New auctions sit on the first pages; keep reading while a page still has some.
            for (int p = 1; p < max && (p < required || recent > 0); p++) {
                AuctionPage page = decodeAll(hypixel.fetchAuctionPage(p, 0));
                merge(page, scanSeq, clock.getAsLong());
                recent = countStartedSince(page, recentSince);
            }
            status.ok("auctions");
            auctionBackoff.reset();
            nextAuctionPoll = expectedNextUpdate(first.lastUpdated + publishDelay, first.serverDate, clock.getAsLong());
            // The minute's flips are out: say what the blacklist kept back.
            summarizeBlocked();
        } catch (IOException e) {
            status.error("auctions", e);
            nextAuctionPoll = clock.getAsLong() + auctionBackoff.fail();
        } catch (RuntimeException e) {
            status.error("auctions", e);
            nextAuctionPoll = clock.getAsLong() + auctionBackoff.fail();
        }
    }

    /** Decodes item bytes of BIN auctions we do not know yet (runs on scan threads too). */
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
            return new ItemKeys.Decoded(existing, decoded.count, decoded.name, decoded.upgrades, decoded.petCandy);
        }
        return decoded;
    }

    private int merge(AuctionPage page, long scan, long now) {
        for (Auction auction : page.auctions) {
            if (auction.coop != null && auction.coop.length > 1) {
                coop.learn(auction.profileId, auction.coop);
            }
            if (!auction.bin || index.touch(auction.uuid, scan) || auction.decoded == null) {
                continue;
            }
            ItemKeys.Decoded decoded = auction.decoded;
            AuctionIndex.Listing listing = new AuctionIndex.Listing(auction.uuid, normalizeUuid(auction.seller),
                decoded.name, auction.tier == null ? null : auction.tier.intern(), decoded.key, auction.startingBid,
                decoded.count, auction.start, auction.end, now, scan, decoded.upgrades, decoded.petCandy);
            index.add(listing);
            if (initialScanDone && now - clockSkew - auction.start < FRESH_MS) {
                consider(listing);
            }
        }
        return page.auctions.size();
    }

    private void consider(final AuctionIndex.Listing listing) {
        if (!evaluated.add(listing.uuid)) {
            return;
        }
        // Your own auctions and your co-op's: buying them back would only cost the taxes.
        if (coop.isUs(listing.seller)) {
            return;
        }
        String blockedBy = config.blacklistMatch(listing.key.id, listing.key.key);
        if (blockedBy != null) {
            if (config.blockedSummary) {
                AuctionFlip hidden = auctionFinder.evaluateBlacklisted(listing);
                if (hidden != null) {
                    blocked.add(notifier.blockedLine(hidden, blockedBy), hidden.profit);
                }
            }
            return;
        }
        AuctionFlip flip = auctionFinder.evaluate(listing, false);
        // A flip backed by two prices and a known sales volume goes out at once. One resting on a
        // single price, without a volume, or not a flip on local data alone, is first checked
        // against Coflnet when that could add the missing data: in the background, so the round
        // trip never delays anything else.
        boolean unsure = flip == null || flip.weak || Double.isNaN(flip.valuation.volumePerDay);
        int slots = flip != null ? MAX_PENDING_CONFIRMATIONS : MAX_SPECULATIVE_CONFIRMATIONS;
        if (unsure && pendingConfirmations.get() < slots && auctionFinder.worthConfirming(listing)) {
            confirm(listing);
        } else if (flip != null) {
            notifyAuction(flip);
        }
    }

    private void confirm(final AuctionIndex.Listing listing) {
        pendingConfirmations.incrementAndGet();
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    AuctionFlip flip = auctionFinder.evaluate(listing, true);
                    if (flip != null) {
                        notifyAuction(flip);
                    }
                } finally {
                    pendingConfirmations.decrementAndGet();
                }
            }
        });
    }

    private void notifyAuction(AuctionFlip flip) {
        status.auctionNotifications.incrementAndGet();
        deliver(notifier.auction(flip, serverTime()), clock.getAsLong(), flip);
    }

    /**
     * Both auction endpoints refresh once a minute: start asking a little before the next
     * refresh (a "not yet" costs only the first bytes of the page), correcting for the
     * difference between our clock and Hypixel's.
     */
    static long expectedNextUpdate(long lastUpdated, long serverDate, long now) {
        if (lastUpdated <= 0) {
            return now + MINUTE;
        }
        long skew = serverDate > 0 ? now - serverDate : 0;
        long next = lastUpdated + MINUTE + skew - 1_000L;
        return Math.max(now + 500L, Math.min(next, now + 70_000L));
    }

    /** Records how long after its lastUpdated a refresh became visible. */
    private void notePublished(long lastUpdated, boolean waiting) {
        long seen = serverTime() - lastUpdated;
        status.lastRefreshDelay = seen;
        if (seen < 0 || seen > 30_000L) {
            return;
        }
        if (waiting) {
            // We were asking every half second, so it has only just appeared.
            publishDelay = publishDelay == 0 ? seen : (publishDelay + seen) / 2;
        } else {
            // We only asked once it was out, so it may have come out a good while earlier (one slow
            // refresh can teach us too long a delay): start asking well before that next time.
            publishDelay = Math.max(0, Math.min(publishDelay, seen) - 3_000L);
        }
        status.publishDelay = publishDelay;
    }

    /** Retry fast right around the expected refresh, slower if Hypixel is late. */
    private static long staleDelay(int attempts) {
        return attempts < 40 ? 500L : attempts < 100 ? 2_000L : 10_000L;
    }

    private void noteServerTime(AuctionPage page) {
        if (page.serverDate > 0) {
            clockSkew = clock.getAsLong() - page.serverDate;
        }
    }

    private static int countStartedSince(AuctionPage page, long since) {
        int count = 0;
        for (Auction auction : page.auctions) {
            if (auction.start >= since) {
                count++;
            }
        }
        return count;
    }

    /** Current time on Hypixel's clock (auction timestamps use it). */
    public long serverTime() {
        return clock.getAsLong() - clockSkew;
    }

    // ---- Coflnet prices ahead of time (background) ----

    /**
     * Asks Coflnet for the sales price of the most valuable item we have no sales price for, so
     * that when one is listed cheap the flip is judged at once. One item per run.
     */
    private void prefetchSalesPrice() {
        long budget = config.budget;
        List<AuctionIndex.Listing> candidates = new ArrayList<AuctionIndex.Listing>();
        for (AuctionIndex.Listing listing : index.cheapestPerKey()) {
            ItemKey key = listing.key;
            // Worth less than the minimum profit it can never be a flip; far above the budget it
            // would never be bought.
            if (key.coflTag == null || listing.unitPrice < config.ahMinProfit
                || (budget > 0 && listing.unitPrice > 2.0 * budget) || config.isBlacklisted(key.id, key.key)) {
                continue;
            }
            candidates.add(listing);
        }
        Collections.sort(candidates, new Comparator<AuctionIndex.Listing>() {
            @Override
            public int compare(AuctionIndex.Listing a, AuctionIndex.Listing b) {
                return Double.compare(b.unitPrice, a.unitPrice);
            }
        });
        for (AuctionIndex.Listing listing : candidates) {
            if (prices.needsRemoteSales(listing.key)) {
                coflnet.prefetch(listing.key.coflTag);
                return;
            }
        }
    }

    // ---- Sold auctions (background) ----

    private void pollEnded() {
        long now = clock.getAsLong();
        try {
            EndedPage page = hypixel.fetchEnded(lastEndedUpdate);
            status.lastEndedPoll = now;
            if (page.notModified || (page.lastUpdated != 0 && page.lastUpdated <= lastEndedUpdate)) {
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
            markSold(page.auctions);
            status.ok("sales");
            endedBackoff.reset();
            nextEndedPoll = expectedNextUpdate(page.lastUpdated + publishDelay, page.serverDate, clock.getAsLong());
        } catch (IOException e) {
            status.error("sales", e);
            nextEndedPoll = clock.getAsLong() + endedBackoff.fail();
        } catch (RuntimeException e) {
            status.error("sales", e);
            nextEndedPoll = clock.getAsLong() + endedBackoff.fail();
        }
    }

    /** Marks the notified auctions that have just sold, so /flipscout recent can tell. */
    private void markSold(List<EndedAuction> ended) {
        String me = platform.playerUuid();
        Map<String, EndedAuction> byId = null;
        synchronized (recent) {
            for (Notification notification : recent) {
                if (notification.auctionUuid == null || notification.soldAt() > 0) {
                    continue;
                }
                if (byId == null) {
                    byId = new HashMap<String, EndedAuction>();
                    for (EndedAuction auction : ended) {
                        byId.put(normalizeUuid(auction.auctionId), auction);
                    }
                }
                EndedAuction sale = byId.get(normalizeUuid(notification.auctionUuid));
                if (sale != null) {
                    String buyer = normalizeUuid(sale.buyer);
                    Buyer who = me != null && me.equals(buyer) ? Buyer.YOU
                        : coop.isMember(buyer) || coop.isOurProfile(sale.buyerProfile) ? Buyer.COOP : Buyer.SOMEONE_ELSE;
                    notification.sold(sale.timestamp > 0 ? sale.timestamp : serverTime(), who,
                        who == Buyer.COOP ? coop.nameOf(buyer) : null);
                }
            }
        }
    }

    // ---- Bazaar (background) ----

    private void pollBazaar() {
        long now = clock.getAsLong();
        try {
            BazaarSnapshot snapshot = hypixel.fetchBazaar(lastBazaarUpdate);
            status.lastBazaarPoll = now;
            status.ok("bazaar");
            bazaarBackoff.reset();
            nextBazaarPoll = now + config.bzIntervalSeconds * 1000L;
            if (snapshot.notModified || (snapshot.lastUpdated != 0 && snapshot.lastUpdated <= lastBazaarUpdate)) {
                return;
            }
            lastBazaarUpdate = snapshot.lastUpdated;
            prices.setBazaar(snapshot);
            // Compare with the history before adding the new sample to it.
            List<BazaarFlip> hidden = config.blockedSummary && !config.blacklist.isEmpty()
                ? new ArrayList<BazaarFlip>() : null;
            List<BazaarFlip> flips = bazaarFinder.find(snapshot, bazaarHistory, items, now, hidden);
            bazaarHistory.record(snapshot, now);
            notifyBazaar(flips, now);
            if (hidden != null) {
                noteBlocked(hidden, now);
            }
            if (!config.ahEnabled) {
                // No auction round to close: the bazaar's own update closes it.
                summarizeBlocked();
            }
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
            status.bazaarNotifications.incrementAndGet();
            deliver(notifier.bazaar(flip), now, null);
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

    /** Bazaar flips the blacklist hid, each once per cooldown like real notifications. */
    private void noteBlocked(List<BazaarFlip> hidden, long now) {
        for (BazaarFlip flip : hidden) {
            String key = "blocked:" + flip.cooldownKey();
            Long until = cooldowns.get(key);
            if (until != null && until > now) {
                continue;
            }
            cooldowns.put(key, now + cooldownMinutes(flip.type) * MINUTE);
            String entry = config.blacklistMatch(flip.productId);
            blocked.add(notifier.blockedLine(flip, entry != null ? entry : flip.productId), flip.profit);
        }
    }

    /** "Your filters blocked N flips", when they did. */
    private void summarizeBlocked() {
        List<BlockedFlips.Entry> hidden = blocked.drain();
        if (hidden.isEmpty() || !config.blockedSummary) {
            return;
        }
        if (config.onlyInSkyblock && !platform.isInSkyblock()) {
            return;
        }
        platform.showMessage(notifier.blocked(hidden));
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

    // ---- Reference data and saving (background) ----

    private void refreshReferenceData() {
        if (clock.getAsLong() >= nextItems) {
            refreshItems(clock.getAsLong());
        }
        if (config.sourceCoflnet && clock.getAsLong() >= nextNames) {
            refreshNames(clock.getAsLong());
        }
    }

    private void refreshItems(long now) {
        try {
            ItemCatalog catalog = hypixel.fetchItems();
            if (catalog.size() > 0) {
                catalog = catalog.withNames(items.names());
                items = catalog;
                prices.setCatalog(catalog);
            }
            status.lastItems = now;
            status.ok("items");
            itemsBackoff.reset();
            nextItems = now + 6 * HOUR;
        } catch (IOException e) {
            status.error("items", e);
            nextItems = clock.getAsLong() + itemsBackoff.fail();
        } catch (RuntimeException e) {
            status.error("items", e);
            nextItems = clock.getAsLong() + itemsBackoff.fail();
        }
    }

    /** Coflnet's item names: Hypixel's item list lacks shards, enchantments and other bazaar products. */
    private void refreshNames(long now) {
        try {
            Map<String, String> names = coflnetApi.fetchItemNames();
            if (!names.isEmpty()) {
                items = items.withNames(names);
            }
            status.ok("names");
            namesBackoff.reset();
            nextNames = now + 24 * HOUR;
        } catch (IOException | RuntimeException e) {
            status.error("names", e);
            nextNames = clock.getAsLong() + Math.max(10 * MINUTE, namesBackoff.fail());
        }
    }

    private void saveSales() {
        try {
            sales.prune((int) (clock.getAsLong() / MINUTE), config.salesWindowDays);
            sales.save(salesFile);
        } catch (IOException e) {
            Log.warn("Could not save the sales", e);
        }
    }

    // ---- Notifications (any thread) ----

    private void deliver(Msg message, long now, AuctionFlip flip) {
        boolean auction = flip != null;
        AuctionIndex.Listing listing = auction ? flip.listing : null;
        Notification notification = listing == null
            ? new Notification(now, message, message.firstCommand())
            : new Notification(now, message, message.firstCommand(), listing.uuid, listing.start, serverTime(),
                Text.stripCodes(listing.itemName), listing.price, flip.resale, flip.profit);
        synchronized (recent) {
            recent.addFirst(notification);
            while (recent.size() > MAX_RECENT) {
                recent.removeLast();
            }
        }
        Log.debug("Notification: " + message.toPlainString());
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
        // The cheapest listing of each item, plus upgraded ones, which can be flips without being the cheapest.
        Map<String, AuctionIndex.Listing> candidates = new LinkedHashMap<String, AuctionIndex.Listing>();
        for (AuctionIndex.Listing listing : index.cheapestPerKey()) {
            candidates.put(listing.uuid, listing);
        }
        if (config.ahUpgrades) {
            for (AuctionIndex.Listing listing : index.withUpgrades()) {
                candidates.put(listing.uuid, listing);
            }
        }
        List<AuctionFlip> flips = new ArrayList<AuctionFlip>();
        for (AuctionIndex.Listing listing : candidates.values()) {
            if (coop.isUs(listing.seller)) {
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

    /**
     * The auction notification for an item you just bought for {@code price} coins, or null: so
     * the purchase report can say what the flip was expected to earn.
     */
    public Notification notifiedPurchase(String itemName, double price) {
        String wanted = comparable(itemName);
        synchronized (recent) {
            for (Notification notification : recent) {
                if (notification.auctionUuid != null && Math.abs(notification.price - price) < 1
                    && (wanted.isEmpty() || comparable(notification.itemName).contains(wanted)
                        || wanted.contains(comparable(notification.itemName)))) {
                    return notification;
                }
            }
        }
        return null;
    }

    /** Letters and digits only, lowercase: stars and symbols differ between chat and the API. */
    private static String comparable(String name) {
        String plain = Text.stripCodes(name == null ? "" : name).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
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
