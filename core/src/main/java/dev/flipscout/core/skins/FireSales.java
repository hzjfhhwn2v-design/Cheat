package dev.flipscout.core.skins;

import com.google.gson.stream.JsonWriter;
import dev.flipscout.core.Log;
import dev.flipscout.core.api.ApiException;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Fire sales (Hypixel, no API key): skins sold for gems for a few days, in a fixed amount. Hypixel
 * only lists the current and the coming ones, so the ones seen are kept on disk.
 */
public final class FireSales {
    static final long REFRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;

    public static final class Sale {
        public final String itemId;
        /** For runes: which rune. */
        public final String rune;
        public final long start;
        public final long end;
        public final int amount;
        /** Gems each. */
        public final int price;

        Sale(String itemId, String rune, long start, long end, int amount, int price) {
            this.itemId = itemId;
            this.rune = rune;
            this.start = start;
            this.end = end;
            this.amount = amount;
            this.price = price;
        }

        /** On now, or announced and about to start. */
        public boolean current(long now) {
            return now < end;
        }

        boolean same(Sale o) {
            return itemId.equals(o.itemId) && start == o.start && (rune == null ? o.rune == null : rune.equals(o.rune));
        }
    }

    private final HttpFetcher http;
    private final Supplier<String> baseUrl;
    private final LongSupplier clock;
    private final File file;
    private final List<Sale> sales = new ArrayList<Sale>();
    private boolean loaded;
    private volatile long nextRefresh;

    public FireSales(HttpFetcher http, Supplier<String> baseUrl, LongSupplier clock, File file) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.file = file;
    }

    public boolean due(long now) {
        return now >= nextRefresh;
    }

    /** Reads the current fire sales; returns the ones not seen before. */
    public List<Sale> refresh() throws IOException {
        ensureLoaded();
        long now = clock.getAsLong();
        nextRefresh = now + RETRY_MS;
        List<Sale> latest;
        HttpFetcher.Response response = http.get(stripSlash(baseUrl.get()) + "/v2/skyblock/firesales");
        try {
            if (response.status >= 400) {
                throw new ApiException("Hypixel: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            latest = parse(JsonTree.obj(JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8))));
        } finally {
            response.close();
        }
        nextRefresh = now + REFRESH_MS;
        List<Sale> fresh = new ArrayList<Sale>();
        synchronized (this) {
            for (Sale sale : latest) {
                boolean known = false;
                for (Sale old : sales) {
                    if (old.same(sale)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    sales.add(sale);
                    fresh.add(sale);
                }
            }
        }
        if (!fresh.isEmpty()) {
            saveQuietly();
        }
        return fresh;
    }

    /** {"success": true, "sales": [{"item_id", "start", "end", "amount", "price"}]} */
    static List<Sale> parse(Map<String, Object> root) {
        List<Sale> out = new ArrayList<Sale>();
        for (Object o : JsonTree.arr(root.get("sales"))) {
            Map<String, Object> s = JsonTree.obj(o);
            String id = JsonTree.str(s.get("item_id"));
            long start = (long) JsonTree.num(s.get("start"), 0);
            long end = (long) JsonTree.num(s.get("end"), 0);
            if (id != null && end > start) {
                out.add(new Sale(id, JsonTree.str(s.get("rune")), start, end, (int) JsonTree.num(s.get("amount"), 0),
                    (int) JsonTree.num(s.get("price"), 0)));
            }
        }
        return out;
    }

    /** The latest fire sale of an item, or null. */
    public synchronized Sale latest(String id) {
        ensureLoaded();
        Sale best = null;
        for (Sale s : sales) {
            if (s.itemId.equals(id) && s.rune == null && (best == null || s.start > best.start)) {
                best = s;
            }
        }
        return best;
    }

    /** Fire sales on now or announced. */
    public synchronized List<Sale> current() {
        ensureLoaded();
        long now = clock.getAsLong();
        List<Sale> out = new ArrayList<Sale>();
        for (Sale s : sales) {
            if (s.current(now)) {
                out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (file == null || !file.isFile()) {
            return;
        }
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            sales.addAll(parse(JsonTree.obj(JsonTree.parse(in))));
        } catch (IOException | RuntimeException e) {
            Log.warn("Unreadable fire sale file: " + file, e);
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
            w.beginObject().name("sales").beginArray();
            for (Sale s : sales) {
                w.beginObject().name("item_id").value(s.itemId);
                if (s.rune != null) {
                    w.name("rune").value(s.rune);
                }
                w.name("start").value(s.start).name("end").value(s.end).name("amount").value(s.amount)
                    .name("price").value(s.price).endObject();
            }
            w.endArray().endObject();
            w.flush();
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String stripSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
