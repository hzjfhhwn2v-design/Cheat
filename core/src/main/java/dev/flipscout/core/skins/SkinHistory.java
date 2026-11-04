package dev.flipscout.core.skins;

import com.google.gson.stream.JsonWriter;
import dev.flipscout.core.Log;
import dev.flipscout.core.api.ApiException;
import dev.flipscout.core.extra.Mayors;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Daily auction prices of skins (SkyCofl's price history), kept on disk and read again about
 * once a day, a few skins at a time so the requests stay well inside SkyCofl's limits.
 *
 * <p>A day's average is not used as it is: skins are often "sold" for hundreds of times their
 * price to move coins between accounts, or for a coin between friends. The day's price is the
 * average without its cheapest and dearest sale, and {@link SkinStats#typical} then takes the
 * median of a week of those.
 */
public final class SkinHistory {
    static final long DAY_MS = 86_400_000L;
    /** Days of history kept per skin. */
    static final int KEEP_DAYS = 400;
    /** A skin's history is read again after this long. */
    static final long REFRESH_MS = 20 * 3_600_000L;
    /** Between two requests: about 20 a minute, SkyCofl allows 100. */
    static final long REQUEST_GAP_MS = 3_000L;
    /** Requests per run (about half a minute), so the background thread is soon free for other jobs. */
    static final int REQUESTS_PER_RUN = 10;
    private static final long BACKOFF_MS = 10 * 60_000L;
    /** After a network error (no answer at all). */
    private static final long OFFLINE_MS = 60_000L;

    /** One day of sales: the price without the day's cheapest and dearest sale, and how many sold. */
    public static final class Day {
        /** Days since 1 January 1970 (UTC). */
        public final int day;
        public final double price;
        public final int sales;

        public Day(int day, double price, int sales) {
            this.day = day;
            this.price = price;
            this.sales = sales;
        }
    }

    /** A skin's history, oldest day first. */
    public static final class Series {
        public final String id;
        public final List<Day> days;
        /** First day SkyCofl recorded a sale, even when older than the days kept; -1 when none. */
        public final int firstDay;
        public final long fetchedAt;

        public Series(String id, List<Day> days, int firstDay, long fetchedAt) {
            this.id = id;
            this.days = Collections.unmodifiableList(days);
            this.firstDay = firstDay;
            this.fetchedAt = fetchedAt;
        }

        public boolean isEmpty() {
            return days.isEmpty();
        }

        public Day last() {
            return days.isEmpty() ? null : days.get(days.size() - 1);
        }
    }

    private final HttpFetcher http;
    private final Supplier<String> baseUrl;
    private final LongSupplier clock;
    private final File file;
    private final Mayors.Pause pause;
    private final Map<String, Series> series = new ConcurrentHashMap<String, Series>();
    /** Skins seen on the auction house: item ID to display name. */
    private final Map<String, String> names = new ConcurrentHashMap<String, String>();
    private volatile boolean loaded;
    private volatile boolean namesChanged;
    private volatile long blockedUntil;
    private volatile long lastRead;
    private volatile int version;
    private volatile boolean updating;

    public SkinHistory(HttpFetcher http, Supplier<String> baseUrl, LongSupplier clock, File file, Mayors.Pause pause) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.file = file;
        this.pause = pause;
    }

    /** Remembers a skin seen on the auction house. */
    public void learn(String id, String name) {
        ensureLoaded();
        if (id == null || name == null || name.isEmpty()) {
            return;
        }
        String clean = name.trim();
        if (!clean.equals(names.put(id, clean))) {
            namesChanged = true;
        }
    }

    /** Every skin seen on the auction house so far (also in earlier sessions). */
    public Map<String, String> names() {
        ensureLoaded();
        return Collections.unmodifiableMap(names);
    }

    public String name(String id) {
        ensureLoaded();
        return names.get(id);
    }

    public Series get(String id) {
        ensureLoaded();
        return series.get(id);
    }

    public Collection<Series> all() {
        ensureLoaded();
        return Collections.unmodifiableCollection(series.values());
    }

    /** Skins with a history read (possibly empty). */
    public int size() {
        ensureLoaded();
        return series.size();
    }

    /** Changes whenever a history is added or replaced. */
    public int version() {
        return version;
    }

    /** When a history was last read, 0 if never. */
    public long lastRead() {
        return lastRead;
    }

    public boolean updating() {
        return updating;
    }

    /** How many of {@code wanted} have a history no older than a day. */
    public int fresh(Collection<String> wanted) {
        ensureLoaded();
        long now = clock.getAsLong();
        int fresh = 0;
        for (String id : wanted) {
            Series s = series.get(id);
            if (s != null && now - s.fetchedAt < REFRESH_MS) {
                fresh++;
            }
        }
        return fresh;
    }

    /** Whether a skin's history is missing or older than a day. */
    public boolean stale(String id) {
        Series s = get(id);
        return s == null || clock.getAsLong() - s.fetchedAt >= REFRESH_MS;
    }

    /** Whether one of {@code wanted} has no history yet, or one older than a day. */
    public boolean due(Collection<String> wanted) {
        return clock.getAsLong() >= blockedUntil && fresh(wanted) < wanted.size();
    }

    /**
     * Reads the history of up to {@link #REQUESTS_PER_RUN} of {@code wanted} that have none or an
     * old one: missing ones first, in the order given, then the oldest. Returns how many were read.
     */
    public int update(List<String> wanted) throws IOException {
        ensureLoaded();
        final long now = clock.getAsLong();
        if (now < blockedUntil) {
            return 0;
        }
        List<String> missing = new ArrayList<String>();
        List<Series> stale = new ArrayList<Series>();
        for (String id : wanted) {
            Series s = series.get(id);
            if (s == null) {
                missing.add(id);
            } else if (now - s.fetchedAt >= REFRESH_MS) {
                stale.add(s);
            }
        }
        Collections.sort(stale, new Comparator<Series>() {
            @Override
            public int compare(Series a, Series b) {
                return Long.compare(a.fetchedAt, b.fetchedAt);
            }
        });
        List<String> todo = new ArrayList<String>(missing);
        for (Series s : stale) {
            todo.add(s.id);
        }
        int read = 0;
        updating = true;
        try {
            for (String id : todo) {
                if (read >= REQUESTS_PER_RUN || (read > 0 && !pause.pause(REQUEST_GAP_MS))) {
                    break;
                }
                try {
                    fetch(id);
                } catch (ApiException e) {
                    if (e.status == 429) {
                        blockedUntil = clock.getAsLong() + Math.max(BACKOFF_MS, e.retryAfterMs);
                        throw e;
                    }
                    if (e.status >= 500) {
                        blockedUntil = clock.getAsLong() + BACKOFF_MS;
                        throw e;
                    }
                    // Unknown to SkyCofl: remember that for a day rather than asking again and again.
                    store(new Series(id, new ArrayList<Day>(), -1, clock.getAsLong()));
                } catch (IOException e) {
                    blockedUntil = clock.getAsLong() + OFFLINE_MS;
                    throw e;
                }
                read++;
            }
        } finally {
            updating = false;
            if (read > 0 || namesChanged) {
                saveQuietly();
            }
        }
        return read;
    }

    /** Reads one skin's history now (for /flipscout skin), replacing the one kept. */
    public Series fetch(String id) throws IOException {
        ensureLoaded();
        long now = clock.getAsLong();
        List<Object> list = JsonTree.arr(getJson(stripSlash(baseUrl.get()) + "/api/item/price/"
            + URLEncoder.encode(id, "UTF-8") + "/history/full"));
        Series s = parse(id, list, now);
        store(s);
        return s;
    }

    /** Keeps a history as if just read (tests, and histories built elsewhere). */
    void put(Series s) {
        ensureLoaded();
        store(s);
    }

    private void store(Series s) {
        series.put(s.id, s);
        lastRead = s.fetchedAt;
        version++;
    }

    /** A history as SkyCofl sends it: [{"min", "max", "avg", "volume", "time": "2026-10-31T00:00:00"}, ...]. */
    static Series parse(String id, List<Object> list, long now) {
        int today = (int) (now / DAY_MS);
        int first = -1;
        TreeMap<Integer, Day> byDay = new TreeMap<Integer, Day>();
        for (Object o : list) {
            Map<String, Object> e = JsonTree.obj(o);
            long time = parseIso(JsonTree.str(e.get("time")));
            double avg = JsonTree.num(e.get("avg"), Double.NaN);
            if (time <= 0 || !(avg > 0)) {
                continue;
            }
            int day = (int) (time / DAY_MS);
            if (first < 0 || day < first) {
                first = day;
            }
            if (day < today - KEEP_DAYS || day > today) {
                continue;
            }
            int sales = (int) JsonTree.num(e.get("volume"), 1);
            double price = dayPrice(avg, JsonTree.num(e.get("min"), avg), JsonTree.num(e.get("max"), avg), sales);
            if (price > 0) {
                byDay.put(day, new Day(day, price, Math.max(1, sales)));
            }
        }
        return new Series(id, new ArrayList<Day>(byDay.values()), first, now);
    }

    /**
     * A day's price from its sales: the average without the cheapest and the dearest one when
     * there are three or more; NaN for two sales far apart, where either could be the odd one.
     */
    static double dayPrice(double avg, double min, double max, int sales) {
        if (sales >= 3 && min > 0 && max >= min) {
            double trimmed = (avg * sales - min - max) / (sales - 2);
            return trimmed >= min && trimmed <= max ? trimmed : avg;
        }
        if (sales == 2 && min > 0 && max > 3 * min) {
            return Double.NaN;
        }
        return avg;
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

    private Object getJson(String url) throws IOException {
        HttpFetcher.Response response = http.get(url);
        try {
            if (response.status >= 400) {
                throw new ApiException("SkyCofl: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            return JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8));
        } finally {
            response.close();
        }
    }

    private static String stripSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ---- File ----

    private void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    load();
                }
            }
        }
    }

    /** Reads the file kept from earlier sessions (gzipped JSON). */
    void load() {
        loaded = true;
        if (file == null || !file.isFile()) {
            return;
        }
        try (Reader in = new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8)) {
            Map<String, Object> root = JsonTree.obj(JsonTree.parse(in));
            for (Map.Entry<String, Object> e : JsonTree.obj(root.get("names")).entrySet()) {
                String name = JsonTree.str(e.getValue());
                if (name != null) {
                    names.put(e.getKey(), name);
                }
            }
            long newest = 0;
            for (Map.Entry<String, Object> e : JsonTree.obj(root.get("skins")).entrySet()) {
                Map<String, Object> s = JsonTree.obj(e.getValue());
                List<Day> days = new ArrayList<Day>();
                for (Object o : JsonTree.arr(s.get("d"))) {
                    List<Object> d = JsonTree.arr(o);
                    if (d.size() >= 3) {
                        days.add(new Day((int) JsonTree.num(d.get(0), 0), JsonTree.num(d.get(1), 0),
                            (int) JsonTree.num(d.get(2), 1)));
                    }
                }
                long fetched = (long) JsonTree.num(s.get("t"), 0);
                newest = Math.max(newest, fetched);
                series.put(e.getKey(), new Series(e.getKey(), days, (int) JsonTree.num(s.get("f"), -1), fetched));
            }
            lastRead = newest;
            version++;
        } catch (IOException | RuntimeException e) {
            Log.warn("Unreadable skin history file, reading it again: " + file, e);
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
        namesChanged = false;
        Map<String, String> namesCopy = new TreeMap<String, String>(names);
        Map<String, Series> copy = new HashMap<String, Series>(series);
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(tmp)), StandardCharsets.UTF_8)) {
            JsonWriter w = new JsonWriter(out);
            w.beginObject();
            w.name("v").value(1);
            w.name("names").beginObject();
            for (Map.Entry<String, String> e : namesCopy.entrySet()) {
                w.name(e.getKey()).value(e.getValue());
            }
            w.endObject();
            w.name("skins").beginObject();
            for (Series s : copy.values()) {
                w.name(s.id).beginObject().name("f").value(s.firstDay).name("t").value(s.fetchedAt).name("d").beginArray();
                for (Day d : s.days) {
                    w.beginArray().value(d.day);
                    if (d.price >= 100) {
                        w.value(Math.round(d.price));
                    } else {
                        w.value(Math.round(d.price * 100) / 100.0);
                    }
                    w.value(d.sales).endArray();
                }
                w.endArray().endObject();
            }
            w.endObject();
            w.endObject();
            w.flush();
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
