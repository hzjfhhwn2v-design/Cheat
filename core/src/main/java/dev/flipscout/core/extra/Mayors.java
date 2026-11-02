package dev.flipscout.core.extra;

import com.google.gson.stream.JsonWriter;
import dev.flipscout.core.Config;
import dev.flipscout.core.Log;
import dev.flipscout.core.api.ApiException;
import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.JsonTree;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The mayor, the open election and how prices moved under past mayors.
 *
 * <p>For about a hundred of the most traded items, a year of daily prices (SkyCofl) is laid over
 * a year of election results (SkyCofl): during each term, how far the price was from the ten
 * days around it. A mayor's effect on an item is the median over their terms, kept only when
 * most terms agree. With the favourite of the open election (Hypixel) that says what usually
 * rises or falls once they take office.
 */
public final class Mayors {
    /** A SkyBlock year, and so a mayor's term: 124 hours. */
    static final long YEAR_MS = 124 * 3_600_000L;
    static final long DAY_MS = 86_400_000L;
    /** History the analysis reads. */
    static final long HISTORY_MS = 400 * DAY_MS;
    /** A term is compared with the prices this long before and after it. */
    static final long AROUND_MS = 10 * DAY_MS;
    static final long ELECTION_TTL_MS = 10 * 60_000L;
    static final long ANALYSIS_TTL_MS = 3 * DAY_MS;
    private static final long RETRY_MS = 30 * 60_000L;
    /** Between two SkyCofl requests, well within its limits. */
    private static final long REQUEST_GAP_MS = 1_200L;
    static final int MIN_TERMS = 2;
    static final double MIN_AGREEMENT = 0.6;
    /** A term counts as moving the price when it is at least this far off. */
    private static final double NOISE = 0.02;
    private static final DateTimeFormatter COFL_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss", Locale.ROOT);

    /** Waits between requests; false when interrupted. */
    public interface Pause {
        boolean pause(long millis);
    }

    public static final Pause SLEEP = new Pause() {
        @Override
        public boolean pause(long millis) {
            try {
                Thread.sleep(millis);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    };

    public static final class Candidate {
        public final String key;
        public final String name;
        public final int votes;
        public final List<String> perks;

        Candidate(String key, String name, int votes, List<String> perks) {
            this.key = key;
            this.name = name;
            this.votes = votes;
            this.perks = perks;
        }
    }

    /** The mayor in office and the election open now (if any). */
    public static final class Election {
        public final String mayorKey;
        public final String mayorName;
        public final List<String> mayorPerks;
        public final String ministerName;
        public final String ministerPerk;
        /** Year of the open election, 0 when none is open. */
        public final int year;
        /** Most votes first. */
        public final List<Candidate> candidates;

        Election(String mayorKey, String mayorName, List<String> mayorPerks, String ministerName, String ministerPerk, int year,
                 List<Candidate> candidates) {
            this.mayorKey = mayorKey;
            this.mayorName = mayorName;
            this.mayorPerks = mayorPerks;
            this.ministerName = ministerName;
            this.ministerPerk = ministerPerk;
            this.year = year;
            this.candidates = candidates;
        }

        public int totalVotes() {
            int total = 0;
            for (Candidate candidate : candidates) {
                total += candidate.votes;
            }
            return total;
        }

        /** The candidate with the most votes, or null when no election is open. */
        public Candidate favourite() {
            return candidates.isEmpty() ? null : candidates.get(0);
        }
    }

    /** A past (or the current) term. */
    public static final class Period {
        public final long start;
        public final long end;
        public final String key;
        public final String name;

        Period(long start, long end, String key, String name) {
            this.start = start;
            this.end = end;
            this.key = key;
            this.name = name;
        }
    }

    /** How an item's price usually moves under one mayor. */
    public static final class Effect {
        public final String id;
        public final String name;
        public final boolean bazaar;
        /** Median change during their terms, 0.18 for +18%. */
        public final double change;
        public final int terms;
        /** Terms that moved the same way as the median. */
        public final int agreeing;

        Effect(String id, String name, boolean bazaar, double change, int terms, int agreeing) {
            this.id = id;
            this.name = name;
            this.bazaar = bazaar;
            this.change = change;
            this.terms = terms;
            this.agreeing = agreeing;
        }
    }

    static final class Point {
        final long time;
        final double price;

        Point(long time, double price) {
            this.time = time;
            this.price = price;
        }
    }

    private final Config config;
    private final HttpFetcher http;
    private final Market market;
    private final LongSupplier clock;
    private final File file;
    /** Auction item ids worth analysing, most traded first. */
    private final Supplier<List<String>> auctionItems;
    private final Pause pause;

    private volatile Election election;
    private volatile long electionAt;
    private volatile List<Period> periods = Collections.emptyList();
    /** By mayor key. */
    private volatile Map<String, List<Effect>> effects = Collections.emptyMap();
    private volatile long analyzedAt;
    private volatile int analyzedItems;
    private volatile long nextAttempt;
    private volatile boolean analyzing;
    private volatile int progress;
    private volatile int progressTotal;
    private volatile int alertedYear;
    private volatile boolean wanted;
    private volatile boolean loaded;
    private volatile String lastError;

    public Mayors(Config config, HttpFetcher http, Market market, LongSupplier clock, File file,
                  Supplier<List<String>> auctionItems, Pause pause) {
        this.config = config;
        this.http = http;
        this.market = market;
        this.clock = clock;
        this.file = file;
        this.auctionItems = auctionItems;
        this.pause = pause;
    }

    // ---- Scheduling ----

    /** Someone asked for the mayor view: keep the data fresh even with the alert off. */
    public void want() {
        wanted = true;
    }

    public boolean wanted() {
        return wanted || config.mayorNotify;
    }

    /** Whether {@link #update} has something to do. */
    public boolean due(long now) {
        if (!wanted() || now < nextAttempt) {
            return false;
        }
        if (now - electionAt >= ELECTION_TTL_MS) {
            return true;
        }
        return analysisDue(now);
    }

    private boolean analysisDue(long now) {
        if (!config.sourceCoflnet || now - analyzedAt < ANALYSIS_TTL_MS) {
            return false;
        }
        if (!loaded) {
            // The file may hold a recent analysis.
            load();
            if (now - analyzedAt < ANALYSIS_TTL_MS) {
                return false;
            }
        }
        return !market.bazaarProducts().isEmpty() || !auctionItems.get().isEmpty();
    }

    /** Refreshes the election and, every few days, the price analysis (a couple of minutes, in the background). */
    public void update() throws IOException {
        if (!loaded) {
            load();
        }
        long now = clock.getAsLong();
        try {
            if (now - electionAt >= ELECTION_TTL_MS) {
                refreshElection();
            }
            if (analysisDue(now)) {
                analyze();
                save();
            }
            lastError = null;
        } catch (IOException e) {
            lastError = e.getMessage();
            nextAttempt = clock.getAsLong() + RETRY_MS;
            throw e;
        }
    }

    /** A failure outside {@link #update}'s own handling: waits before trying again. */
    public void failed(Exception e) {
        lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        nextAttempt = clock.getAsLong() + RETRY_MS;
    }

    public void refreshElection() throws IOException {
        Map<String, Object> root = getObject(stripSlash(config.urlHypixel) + "/v2/resources/skyblock/election");
        election = parseElection(root);
        electionAt = clock.getAsLong();
    }

    // ---- Reading ----

    public Election election() {
        return election;
    }

    public long analyzedAt() {
        return analyzedAt;
    }

    public int analyzedItems() {
        return analyzedItems;
    }

    public boolean analyzing() {
        return analyzing;
    }

    public int progress() {
        return progress;
    }

    public int progressTotal() {
        return progressTotal;
    }

    public String lastError() {
        return lastError;
    }

    /** When the next mayor takes office, from the terms SkyCofl knows; 0 when unknown. */
    public long nextMayorAt(long now) {
        long latest = 0;
        for (Period p : periods) {
            if (p.start <= now && p.end > now) {
                return p.end;
            }
            latest = Math.max(latest, p.start);
        }
        if (latest == 0) {
            return 0;
        }
        long terms = (now - latest) / YEAR_MS + 1;
        return latest + terms * YEAR_MS;
    }

    /** Items that usually rise ({@code up}) or fall under a mayor, strongest first. */
    public List<Effect> movers(String mayorKey, boolean up, int limit) {
        List<Effect> all = mayorKey == null ? null : effects.get(mayorKey.toLowerCase(Locale.ROOT));
        if (all == null) {
            return Collections.emptyList();
        }
        double min = config.mayorMinChange / 100.0;
        List<Effect> out = new ArrayList<Effect>();
        for (Effect e : all) {
            if (e.terms >= MIN_TERMS && e.agreeing >= Math.ceil(MIN_AGREEMENT * e.terms)
                && (up ? e.change >= min : e.change <= -min)) {
                out.add(e);
            }
        }
        Collections.sort(out, new Comparator<Effect>() {
            @Override
            public int compare(Effect a, Effect b) {
                return Double.compare(Math.abs(b.change), Math.abs(a.change));
            }
        });
        return out.size() > limit ? new ArrayList<Effect>(out.subList(0, limit)) : out;
    }

    /** Whether the analysis knows anything about a mayor. */
    public boolean knows(String mayorKey) {
        return mayorKey != null && effects.containsKey(mayorKey.toLowerCase(Locale.ROOT));
    }

    /**
     * The open election's favourite, once per election and only when clearly ahead and the
     * analysis has something to say about them; null otherwise.
     */
    public Candidate alertFavourite() {
        Election e = election;
        if (!config.mayorNotify || e == null || e.year == 0 || e.year == alertedYear || e.candidates.size() < 2) {
            return null;
        }
        int total = e.totalVotes();
        Candidate favourite = e.candidates.get(0);
        if (total < 1_000 || (favourite.votes - e.candidates.get(1).votes) * 100.0 / total < 5) {
            return null;
        }
        if (movers(favourite.key, true, 1).isEmpty() && movers(favourite.key, false, 1).isEmpty()) {
            return null;
        }
        alertedYear = e.year;
        saveQuietly();
        return favourite;
    }

    // ---- Analysis ----

    void analyze() throws IOException {
        long now = clock.getAsLong();
        List<Period> terms = fetchPeriods(now - HISTORY_MS, now);
        periods = terms;
        List<String[]> items = candidates();
        Map<String, List<Effect>> byMayor = new HashMap<String, List<Effect>>();
        analyzing = true;
        progress = 0;
        progressTotal = items.size();
        try {
            for (String[] item : items) {
                if (!pause.pause(REQUEST_GAP_MS)) {
                    break;
                }
                boolean bazaar = "bz".equals(item[1]);
                List<Point> points;
                try {
                    points = bazaar ? fetchBazaar(item[0], now) : fetchAuctions(item[0], now);
                } catch (ApiException e) {
                    if (e.status == 429) {
                        throw e;
                    }
                    progress++;
                    continue;
                }
                for (Map.Entry<String, List<Double>> entry : termChanges(points, terms).entrySet()) {
                    Effect effect = summarize(item[0], market.name(item[0]), bazaar, entry.getValue());
                    if (effect != null) {
                        List<Effect> list = byMayor.get(entry.getKey());
                        if (list == null) {
                            list = new ArrayList<Effect>();
                            byMayor.put(entry.getKey(), list);
                        }
                        list.add(effect);
                    }
                }
                progress++;
            }
        } finally {
            analyzing = false;
        }
        if (progress == 0) {
            throw new IOException("no price history could be read");
        }
        effects = byMayor;
        analyzedAt = now;
        analyzedItems = progress;
    }

    /** The items to analyse: the most traded bazaar products and auction items, half each. */
    List<String[]> candidates() {
        int limit = Math.max(2, config.mayorItems);
        List<BazaarProduct> products = new ArrayList<BazaarProduct>(market.bazaarProducts());
        final Map<String, Double> weekly = new HashMap<String, Double>();
        for (BazaarProduct p : products) {
            double sold = p.sellMovingWeek * (p.instaSell() > 0 ? p.instaSell() : 0);
            double bought = p.buyMovingWeek * (p.instaBuy() > 0 ? p.instaBuy() : 0);
            weekly.put(p.id, (sold + bought) / 2);
        }
        Collections.sort(products, new Comparator<BazaarProduct>() {
            @Override
            public int compare(BazaarProduct a, BazaarProduct b) {
                return Double.compare(weekly.get(b.id), weekly.get(a.id));
            }
        });
        List<String[]> out = new ArrayList<String[]>();
        Set<String> taken = new HashSet<String>();
        List<String> auctions = auctionItems.get();
        int bazaarShare = auctions.isEmpty() ? limit : limit / 2;
        for (BazaarProduct p : products) {
            if (out.size() >= bazaarShare) {
                break;
            }
            if (weekly.get(p.id) > 0 && taken.add(p.id)) {
                out.add(new String[] {p.id, "bz"});
            }
        }
        for (String id : auctions) {
            if (out.size() >= limit) {
                break;
            }
            if (market.bazaar(id) == null && taken.add(id)) {
                out.add(new String[] {id, "ah"});
            }
        }
        return out;
    }

    /**
     * For each mayor, how far the price was during each of their terms from the ten days
     * around it (0.1 = 10% higher). Terms with too few prices are left out.
     */
    static Map<String, List<Double>> termChanges(List<Point> points, List<Period> periods) {
        Map<String, List<Double>> out = new HashMap<String, List<Double>>();
        if (points.size() < 10) {
            return out;
        }
        for (Period p : periods) {
            if (p.key == null || p.end <= p.start) {
                continue;
            }
            List<Double> during = new ArrayList<Double>();
            List<Double> around = new ArrayList<Double>();
            for (Point point : points) {
                if (point.time >= p.start && point.time < p.end) {
                    during.add(point.price);
                } else if (point.time >= p.start - AROUND_MS && point.time < p.end + AROUND_MS) {
                    around.add(point.price);
                }
            }
            if (during.size() < 3 || around.size() < 6) {
                continue;
            }
            double term = median(during);
            double base = median(around);
            if (!(term > 0) || !(base > 0)) {
                continue;
            }
            List<Double> list = out.get(p.key);
            if (list == null) {
                list = new ArrayList<Double>();
                out.put(p.key, list);
            }
            list.add(term / base - 1);
        }
        return out;
    }

    static Effect summarize(String id, String name, boolean bazaar, List<Double> changes) {
        if (changes.size() < MIN_TERMS) {
            return null;
        }
        double median = median(changes);
        int agreeing = 0;
        for (double change : changes) {
            if (Math.abs(change) >= NOISE && Math.signum(change) == Math.signum(median)) {
                agreeing++;
            }
        }
        return new Effect(id, name, bazaar, median, changes.size(), agreeing);
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 0) {
            return Double.NaN;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    // ---- Downloads ----

    List<Period> fetchPeriods(long from, long to) throws IOException {
        List<Object> list = JsonTree.arr(getJson(stripSlash(config.urlCoflnet) + "/api/mayor?from=" + iso(from) + "&to=" + iso(to)));
        return parsePeriods(list);
    }

    static List<Period> parsePeriods(List<Object> list) {
        List<Period> out = new ArrayList<Period>();
        for (Object o : list) {
            Map<String, Object> p = JsonTree.obj(o);
            Map<String, Object> winner = JsonTree.obj(p.get("winner"));
            long start = parseCoflDate(JsonTree.str(p.get("start")));
            long end = parseCoflDate(JsonTree.str(p.get("end")));
            String key = JsonTree.str(winner.get("key"));
            if (start > 0 && end > start && key != null) {
                out.add(new Period(start, end, key.toLowerCase(Locale.ROOT), JsonTree.str(winner.get("name"))));
            }
        }
        Collections.sort(out, new Comparator<Period>() {
            @Override
            public int compare(Period a, Period b) {
                return Long.compare(a.start, b.start);
            }
        });
        return out;
    }

    private List<Point> fetchAuctions(String id, long now) throws IOException {
        List<Object> list = JsonTree.arr(getJson(stripSlash(config.urlCoflnet) + "/api/item/price/"
            + URLEncoder.encode(id, "UTF-8") + "/history/full"));
        return points(list, "time", now);
    }

    private List<Point> fetchBazaar(String id, long now) throws IOException {
        List<Object> list = JsonTree.arr(getJson(stripSlash(config.urlCoflnet) + "/api/bazaar/" + URLEncoder.encode(id, "UTF-8")
            + "/history?start=" + iso(now - HISTORY_MS) + "&end=" + iso(now)));
        return points(list, "timestamp", now);
    }

    /** Daily prices from a history: "avg" for auctions, the middle of "buy" and "sell" for the bazaar. */
    static List<Point> points(List<Object> list, String timeField, long now) {
        List<Point> out = new ArrayList<Point>();
        for (Object o : list) {
            Map<String, Object> entry = JsonTree.obj(o);
            long time = parseIso(JsonTree.str(entry.get(timeField)));
            if (time <= 0 || time < now - HISTORY_MS - AROUND_MS) {
                continue;
            }
            double price = JsonTree.num(entry.get("avg"), Double.NaN);
            if (Double.isNaN(price)) {
                double buy = JsonTree.num(entry.get("buy"), Double.NaN);
                double sell = JsonTree.num(entry.get("sell"), Double.NaN);
                price = buy > 0 && sell > 0 ? (buy + sell) / 2 : buy > 0 ? buy : sell;
            }
            if (price > 0) {
                out.add(new Point(time, price));
            }
        }
        Collections.sort(out, new Comparator<Point>() {
            @Override
            public int compare(Point a, Point b) {
                return Long.compare(a.time, b.time);
            }
        });
        return out;
    }

    static Election parseElection(Map<String, Object> root) {
        Map<String, Object> mayor = JsonTree.obj(root.get("mayor"));
        Map<String, Object> minister = JsonTree.obj(mayor.get("minister"));
        Map<String, Object> current = JsonTree.obj(root.get("current"));
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (Object o : JsonTree.arr(current.get("candidates"))) {
            Map<String, Object> c = JsonTree.obj(o);
            String key = JsonTree.str(c.get("key"));
            if (key != null) {
                candidates.add(new Candidate(key.toLowerCase(Locale.ROOT), JsonTree.str(c.get("name")),
                    (int) JsonTree.num(c.get("votes"), 0), perks(c.get("perks"))));
            }
        }
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Integer.compare(b.votes, a.votes);
            }
        });
        String key = JsonTree.str(mayor.get("key"));
        return new Election(key == null ? null : key.toLowerCase(Locale.ROOT), JsonTree.str(mayor.get("name")),
            perks(mayor.get("perks")), JsonTree.str(minister.get("name")),
            JsonTree.str(JsonTree.obj(minister.get("perk")).get("name")),
            candidates.isEmpty() ? 0 : (int) JsonTree.num(current.get("year"), 0), candidates);
    }

    private static List<String> perks(Object list) {
        List<String> out = new ArrayList<String>();
        for (Object o : JsonTree.arr(list)) {
            String name = JsonTree.str(JsonTree.obj(o).get("name"));
            if (name != null) {
                out.add(name);
            }
        }
        return out;
    }

    /** "12/30/2025 03:15:00 +00:00" or "01/04/2026 07:15:00" (UTC), 0 when unreadable. */
    static long parseCoflDate(String text) {
        if (text == null || text.length() < 19) {
            return 0;
        }
        try {
            return LocalDateTime.parse(text.substring(0, 19), COFL_DATE).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** "2026-10-31T00:00:00" (UTC, maybe with more after), 0 when unreadable. */
    static long parseIso(String text) {
        if (text == null || text.length() < 19) {
            return 0;
        }
        try {
            return LocalDateTime.parse(text.substring(0, 19)).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String iso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    private Object getJson(String url) throws IOException {
        HttpFetcher.Response response = http.get(url);
        try {
            if (response.status >= 400) {
                throw new ApiException("HTTP " + response.status + " from " + url.replaceAll("\\?.*", ""), response.status,
                    response.retryAfterMs);
            }
            return JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8));
        } finally {
            response.close();
        }
    }

    private Map<String, Object> getObject(String url) throws IOException {
        return JsonTree.obj(getJson(url));
    }

    private static String stripSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ---- Cache file ----

    /** Reads the last analysis, so a restart does not download it all again. */
    void load() {
        loaded = true;
        if (file == null || !file.isFile()) {
            return;
        }
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<String, Object> root = JsonTree.obj(JsonTree.parse(in));
            Map<String, List<Effect>> byMayor = new HashMap<String, List<Effect>>();
            for (Map.Entry<String, Object> entry : JsonTree.obj(root.get("effects")).entrySet()) {
                List<Effect> list = new ArrayList<Effect>();
                for (Object o : JsonTree.arr(entry.getValue())) {
                    Map<String, Object> e = JsonTree.obj(o);
                    String id = JsonTree.str(e.get("id"));
                    if (id != null) {
                        list.add(new Effect(id, JsonTree.str(e.get("name")), Boolean.TRUE.equals(e.get("bz")),
                            JsonTree.num(e.get("change"), 0), (int) JsonTree.num(e.get("terms"), 0),
                            (int) JsonTree.num(e.get("agree"), 0)));
                    }
                }
                byMayor.put(entry.getKey(), list);
            }
            List<Period> terms = new ArrayList<Period>();
            for (Object o : JsonTree.arr(root.get("periods"))) {
                Map<String, Object> p = JsonTree.obj(o);
                terms.add(new Period((long) JsonTree.num(p.get("start"), 0), (long) JsonTree.num(p.get("end"), 0),
                    JsonTree.str(p.get("key")), JsonTree.str(p.get("name"))));
            }
            effects = byMayor;
            periods = terms;
            analyzedAt = (long) JsonTree.num(root.get("analyzedAt"), 0);
            analyzedItems = (int) JsonTree.num(root.get("items"), 0);
            alertedYear = (int) JsonTree.num(root.get("alertedYear"), 0);
        } catch (IOException | RuntimeException e) {
            Log.warn("Unreadable mayor file, analysing again: " + file, e);
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            Log.warn("Could not save " + file, e);
        }
    }

    synchronized void save() throws IOException {
        if (file == null) {
            return;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            JsonWriter w = new JsonWriter(out);
            w.beginObject();
            w.name("analyzedAt").value(analyzedAt);
            w.name("items").value(analyzedItems);
            w.name("alertedYear").value(alertedYear);
            w.name("periods").beginArray();
            for (Period p : periods) {
                w.beginObject().name("start").value(p.start).name("end").value(p.end).name("key").value(p.key)
                    .name("name").value(p.name).endObject();
            }
            w.endArray();
            w.name("effects").beginObject();
            for (Map.Entry<String, List<Effect>> entry : effects.entrySet()) {
                w.name(entry.getKey()).beginArray();
                for (Effect e : entry.getValue()) {
                    w.beginObject().name("id").value(e.id).name("name").value(e.name).name("bz").value(e.bazaar)
                        .name("change").value(e.change).name("terms").value(e.terms).name("agree").value(e.agreeing)
                        .endObject();
                }
                w.endArray();
            }
            w.endObject();
            w.endObject();
            w.flush();
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
