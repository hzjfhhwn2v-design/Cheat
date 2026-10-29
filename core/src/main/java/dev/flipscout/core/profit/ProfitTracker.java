package dev.flipscout.core.profit;

import com.google.gson.stream.JsonWriter;
import dev.flipscout.core.Config;
import dev.flipscout.core.Log;
import dev.flipscout.core.Msg;
import dev.flipscout.core.Platform;
import dev.flipscout.core.flip.Fees;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.util.Coins;
import dev.flipscout.core.util.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * What your flips actually earned: purchases and sales recognised in chat, each sale matched with
 * the oldest purchase of the same item, totalled all time, per day and per session. Thread-safe;
 * saved as JSON.
 */
public final class ProfitTracker {
    private static final int MAX_FLIPS = 100;
    private static final int MAX_HISTORY = 20;
    private static final int MAX_DAYS = 31;
    private static final int MAX_LOTS = 500;
    private static final long UNCLAIMED_MAX_AGE_MS = 30L * 24 * 60 * 60_000L;
    /**
     * Reforges, so that an item reforged between purchase and sale is still recognised. Left out:
     * those that are also the first word of item names (Wise and Strong Dragon armor, Heavy and
     * Perfect armor), where guessing wrong would pair two different items.
     */
    private static final Set<String> REFORGES = new HashSet<String>(Arrays.asList(
        "gentle", "odd", "fast", "fair", "epic", "sharp", "heroic", "spicy", "legendary", "dirty", "fabled",
        "suspicious", "gilded", "warped", "withered", "bulky", "fanged", "coldfused", "jerry's", "deadly", "fine",
        "grand", "hasty", "neat", "rapid", "unreal", "awkward", "rich", "precise", "spiritual", "headstrong", "clean",
        "fierce", "light", "mythic", "pure", "smart", "titanic", "necrotic", "ancient", "spiked", "renowned",
        "cubic", "reinforced", "loving", "ridiculous", "empowered", "giant", "submerged", "jaded", "bustling",
        "mossy", "festive", "hyper", "undead", "candied", "blooming", "rooted", "royal", "blood-soaked", "moil",
        "toil", "blessed", "bountiful", "magnetic", "fruitful", "refined", "stellar", "mithraic", "auspicious",
        "fleet", "heated", "ambered", "lustrous", "glistening", "excellent", "sturdy", "fortunate", "unyielding",
        "prospector's", "great", "rugged", "lush", "peasant's", "robust", "zooming", "bizarre", "itchy", "ominous",
        "pleasant", "pretty", "shiny", "simple", "strange", "vivid", "godly", "demonic", "forceful", "hurtful",
        "keen", "unpleasant", "zealous", "silky", "bloody", "shaded", "sweet", "fortified", "salty", "treacherous",
        "lucky", "stiff"));

    /** Totals of a period. */
    public static final class Stats {
        public double profit;
        /** Sales matched with their purchase. */
        public int flips;
        public int bought;
        public double spent;
        public int sold;
        /** Coins received from sales, after fees. */
        public double earned;
        /** Sales of items we never saw you buy: not part of the profit. */
        public int unmatched;
        public String bestItem;
        public double bestProfit;

        Stats copy() {
            Stats s = new Stats();
            s.profit = profit;
            s.flips = flips;
            s.bought = bought;
            s.spent = spent;
            s.sold = sold;
            s.earned = earned;
            s.unmatched = unmatched;
            s.bestItem = bestItem;
            s.bestProfit = bestProfit;
            return s;
        }
    }

    /** The current session, as of when it was read. */
    public static final class Session {
        public final Stats stats;
        public final long startedAt;
        /** Time played while the session was running. */
        public final long activeMillis;
        public final boolean paused;

        Session(Stats stats, long startedAt, long activeMillis, boolean paused) {
            this.stats = stats;
            this.startedAt = startedAt;
            this.activeMillis = activeMillis;
            this.paused = paused;
        }

        /** Profit per hour played, NaN during the first minute. */
        public double profitPerHour() {
            return activeMillis < 60_000L ? Double.NaN : stats.profit * 3_600_000.0 / activeMillis;
        }
    }

    /** A session that was reset. */
    public static final class PastSession {
        public final long startedAt;
        public final long endedAt;
        public final long activeMillis;
        public final Stats stats;

        PastSession(long startedAt, long endedAt, long activeMillis, Stats stats) {
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.activeMillis = activeMillis;
            this.stats = stats;
        }
    }

    /** A sale matched with its purchase. */
    public static final class Flip {
        public final String item;
        public final int amount;
        public final double cost;
        /** Coins received after fees. */
        public final double revenue;
        public final double profit;
        public final long boughtAt;
        public final long soldAt;

        Flip(String item, int amount, double cost, double revenue, double profit, long boughtAt, long soldAt) {
            this.item = item;
            this.amount = amount;
            this.cost = cost;
            this.revenue = revenue;
            this.profit = profit;
            this.boughtAt = boughtAt;
            this.soldAt = soldAt;
        }
    }

    /** Something bought and not sold yet. */
    public static final class Lot {
        public final String item;
        public final int amount;
        public final double unitCost;
        public final long time;

        Lot(String item, int amount, double unitCost, long time) {
            this.item = item;
            this.amount = amount;
            this.unitCost = unitCost;
            this.time = time;
        }
    }

    /** An auction that sold while you were online and whose coins you have not collected yet. */
    public static final class Unclaimed {
        public final String item;
        final String norm;
        public final String buyer;
        public final double price;
        public final long time;

        Unclaimed(String item, String buyer, double price, long time) {
            this.item = item;
            this.norm = normalize(item);
            this.buyer = buyer;
            this.price = price;
            this.time = time;
        }
    }

    private final Config config;
    private final File file;
    private final LongSupplier clock;
    private final Platform platform;
    private final Consumer<Runnable> async;
    /** Held while writing the file, so saves never interleave. Taken before the tracker's own lock. */
    private final Object fileLock = new Object();

    private long since;
    private Stats total = new Stats();
    private final LinkedHashMap<String, Stats> days = new LinkedHashMap<String, Stats>();
    private Stats session = new Stats();
    private long sessionStartedAt;
    /** Session time played up to {@link #sessionRunningSince}. */
    private long sessionActive;
    /** When the session was last started or resumed; 0 while paused. */
    private long sessionRunningSince;
    private final Deque<PastSession> history = new ArrayDeque<PastSession>();
    private final Deque<Flip> flips = new ArrayDeque<Flip>();
    /** Unsold purchases by normalised item name, oldest first. */
    private final LinkedHashMap<String, Deque<Lot>> lots = new LinkedHashMap<String, Deque<Lot>>();
    private final List<Unclaimed> unclaimed = new ArrayList<Unclaimed>();
    private boolean dirty;

    /** @param async runs a task off the calling thread (for saving) */
    public ProfitTracker(Config config, File file, LongSupplier clock, Platform platform, Consumer<Runnable> async) {
        this.config = config;
        this.file = file;
        this.clock = clock;
        this.platform = platform;
        this.async = async;
        long now = clock.getAsLong();
        since = now;
        sessionStartedAt = now;
        sessionRunningSince = now;
    }

    // ---- Chat ----

    /** Handles one chat line from the server. */
    public void onChat(String line) {
        if (line == null) {
            return;
        }
        TradeParser.Trade trade = TradeParser.parse(Text.stripCodes(line).trim());
        if (trade == null) {
            return;
        }
        Msg message;
        synchronized (this) {
            message = record(trade, clock.getAsLong());
            dirty = true;
        }
        if (message != null && config.trackerMessages) {
            platform.showMessage(message);
        }
        async.accept(this::save);
    }

    private Msg record(TradeParser.Trade t, long now) {
        pruneUnclaimed(now);
        switch (t.kind) {
            case AUCTION_BOUGHT:
                buy(t, now);
                return Msg.prefixed("§7Acquisto registrato: §f" + t.item + " §7per §6" + Coins.compact(t.coins));
            case BAZAAR_BOUGHT:
                buy(t, now);
                return null;
            case AUCTION_SOLD:
                unclaimed.add(new Unclaimed(t.item, playerName(t.player), t.coins, now));
                return sell(t.item, t.amount, t.coins - Fees.auctionSellFees(t.coins, config), now, true);
            case AUCTION_COLLECTED: {
                if (claim(t)) {
                    // Already counted when it sold.
                    return null;
                }
                // Sold while you were away. Above 1M the collected coins are after the claim tax.
                double claimTax = Math.min(config.ahFeeClaim / 100.0, 0.5);
                double gross = t.coins >= 1_000_000 * (1 - claimTax) ? t.coins / (1 - claimTax) : t.coins;
                return sell(t.item, t.amount, gross - Fees.auctionSellFees(gross, config), now, true);
            }
            default:
                // Bazaar sales report the coins you received, tax already taken.
                return sell(t.item, t.amount, t.coins, now, false);
        }
    }

    private void buy(final TradeParser.Trade t, long now) {
        String norm = normalize(t.item);
        Deque<Lot> queue = lots.get(norm);
        if (queue == null) {
            queue = new ArrayDeque<Lot>();
            lots.put(norm, queue);
        }
        queue.addLast(new Lot(t.item, t.amount, t.coins / t.amount, now));
        trimLots();
        apply(now, new Consumer<Stats>() {
            @Override
            public void accept(Stats s) {
                s.bought++;
                s.spent += t.coins;
            }
        });
    }

    /** Matches a sale with the oldest purchases of the item and returns the chat message for it. */
    private Msg sell(final String item, final int amount, final double netRevenue, long now, boolean auction) {
        int units = 0;
        double cost = 0;
        long boughtAt = 0;
        Deque<Lot> queue = findLots(normalize(item));
        while (queue != null && units < amount && !queue.isEmpty()) {
            Lot lot = queue.pollFirst();
            int take = Math.min(lot.amount, amount - units);
            units += take;
            cost += take * lot.unitCost;
            if (boughtAt == 0) {
                boughtAt = lot.time;
            }
            if (take < lot.amount) {
                queue.addFirst(new Lot(lot.item, lot.amount - take, lot.unitCost, lot.time));
            }
        }
        removeEmptyLots();
        final int matched = units;
        final double revenue = netRevenue * units / amount;
        final double profit = revenue - cost;
        apply(now, new Consumer<Stats>() {
            @Override
            public void accept(Stats s) {
                s.sold++;
                s.earned += netRevenue;
                if (matched > 0) {
                    s.flips++;
                    s.profit += profit;
                    if (s.bestItem == null || profit > s.bestProfit) {
                        s.bestItem = item;
                        s.bestProfit = profit;
                    }
                }
                if (matched < amount) {
                    s.unmatched++;
                }
            }
        });
        if (matched == 0) {
            return auction ? Msg.prefixed("§7Vendita di §f" + item
                + " §7registrata, ma non ho visto l'acquisto: non conta nel profitto.") : null;
        }
        flips.addFirst(new Flip(item, matched, cost, revenue, profit, boughtAt, now));
        while (flips.size() > MAX_FLIPS) {
            flips.removeLast();
        }
        String note = sessionRunningSince > 0 ? " §8| sessione " + signed(session.profit) : " §8| sessione in pausa";
        return Msg.prefixed("§7Flip chiuso: §f" + item + (matched > 1 ? " §7x" + matched : "") + " §7"
            + Coins.compact(cost) + " → " + Coins.compact(revenue) + " " + signed(profit) + note);
    }

    /** Applies a change to the totals, today's figures and, unless paused, the session. */
    private void apply(long now, Consumer<Stats> change) {
        change.accept(total);
        change.accept(day(now));
        if (sessionRunningSince > 0) {
            change.accept(session);
        }
    }

    private Deque<Lot> findLots(String norm) {
        Deque<Lot> exact = lots.get(norm);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        // Reforged since it was bought.
        for (Map.Entry<String, Deque<Lot>> entry : lots.entrySet()) {
            if (!entry.getValue().isEmpty() && sameItem(norm, entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean claim(TradeParser.Trade t) {
        String norm = normalize(t.item);
        String buyer = playerName(t.player);
        Iterator<Unclaimed> it = unclaimed.iterator();
        while (it.hasNext()) {
            Unclaimed u = it.next();
            if (sameItem(u.norm, norm) && u.buyer.equalsIgnoreCase(buyer)
                && Math.abs(u.price - t.coins) <= u.price * 0.02 + 1) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void pruneUnclaimed(long now) {
        Iterator<Unclaimed> it = unclaimed.iterator();
        while (it.hasNext()) {
            if (now - it.next().time > UNCLAIMED_MAX_AGE_MS) {
                it.remove();
            }
        }
    }

    private void trimLots() {
        int count = 0;
        for (Deque<Lot> queue : lots.values()) {
            count += queue.size();
        }
        while (count > MAX_LOTS) {
            // Drop the oldest purchase overall.
            Deque<Lot> oldest = null;
            for (Deque<Lot> queue : lots.values()) {
                if (!queue.isEmpty() && (oldest == null || queue.peekFirst().time < oldest.peekFirst().time)) {
                    oldest = queue;
                }
            }
            if (oldest == null) {
                break;
            }
            oldest.pollFirst();
            count--;
        }
        removeEmptyLots();
    }

    private void removeEmptyLots() {
        Iterator<Deque<Lot>> it = lots.values().iterator();
        while (it.hasNext()) {
            if (it.next().isEmpty()) {
                it.remove();
            }
        }
    }

    private Stats day(long now) {
        String key = dayKey(now);
        Stats stats = days.get(key);
        if (stats == null) {
            stats = new Stats();
            days.put(key, stats);
            while (days.size() > MAX_DAYS) {
                Iterator<String> it = days.keySet().iterator();
                it.next();
                it.remove();
            }
        }
        return stats;
    }

    private long sessionActive(long now) {
        return sessionActive + (sessionRunningSince > 0 ? Math.max(0, now - sessionRunningSince) : 0);
    }

    // ---- Session and resets ----

    /** Stops counting into the session; returns false when it was already paused. */
    public boolean pauseSession() {
        synchronized (this) {
            if (sessionRunningSince == 0) {
                return false;
            }
            long now = clock.getAsLong();
            sessionActive = sessionActive(now);
            sessionRunningSince = 0;
            dirty = true;
        }
        async.accept(this::save);
        return true;
    }

    /** Counts into the session again; returns false when it was not paused. */
    public boolean resumeSession() {
        synchronized (this) {
            if (sessionRunningSince != 0) {
                return false;
            }
            sessionRunningSince = clock.getAsLong();
            dirty = true;
        }
        async.accept(this::save);
        return true;
    }

    /** Moves the current session to the history (unless it is empty) and starts a new one. */
    public void resetSession() {
        synchronized (this) {
            long now = clock.getAsLong();
            long active = sessionActive(now);
            if (session.bought > 0 || session.sold > 0 || active >= 60_000L) {
                history.addFirst(new PastSession(sessionStartedAt, now, active, session.copy()));
                while (history.size() > MAX_HISTORY) {
                    history.removeLast();
                }
            }
            session = new Stats();
            sessionStartedAt = now;
            sessionActive = 0;
            sessionRunningSince = now;
            dirty = true;
        }
        async.accept(this::save);
    }

    /** Clears the totals, the days, the past sessions and the flip list. The session and unsold items stay. */
    public void resetTotal() {
        synchronized (this) {
            total = new Stats();
            days.clear();
            history.clear();
            flips.clear();
            since = clock.getAsLong();
            dirty = true;
        }
        async.accept(this::save);
    }

    /** Forgets the unsold purchases; returns how many there were. */
    public int clearLots() {
        int count = 0;
        synchronized (this) {
            for (Deque<Lot> queue : lots.values()) {
                count += queue.size();
            }
            lots.clear();
            dirty = true;
        }
        async.accept(this::save);
        return count;
    }

    // ---- Queries (copies) ----

    public synchronized long since() {
        return since;
    }

    public synchronized Stats total() {
        return total.copy();
    }

    public synchronized Stats today() {
        Stats stats = days.get(dayKey(clock.getAsLong()));
        return stats == null ? new Stats() : stats.copy();
    }

    /** Day ("2026-10-29") -> totals, oldest first. */
    public synchronized LinkedHashMap<String, Stats> days() {
        LinkedHashMap<String, Stats> out = new LinkedHashMap<String, Stats>();
        for (Map.Entry<String, Stats> entry : days.entrySet()) {
            out.put(entry.getKey(), entry.getValue().copy());
        }
        return out;
    }

    public synchronized Session session() {
        return new Session(session.copy(), sessionStartedAt, sessionActive(clock.getAsLong()), sessionRunningSince == 0);
    }

    /** Past sessions, most recent first. */
    public synchronized List<PastSession> history() {
        return new ArrayList<PastSession>(history);
    }

    /** Flips, most recent first. */
    public synchronized List<Flip> flips() {
        return new ArrayList<Flip>(flips);
    }

    /** Unsold purchases, oldest item first. */
    public synchronized List<Lot> lots() {
        List<Lot> out = new ArrayList<Lot>();
        for (Deque<Lot> queue : lots.values()) {
            out.addAll(queue);
        }
        return out;
    }

    public synchronized List<Unclaimed> unclaimed() {
        return new ArrayList<Unclaimed>(unclaimed);
    }

    // ---- Persistence ----

    public void load() {
        synchronized (this) {
            if (!file.isFile()) {
                return;
            }
            long now = clock.getAsLong();
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                Map<String, Object> root = JsonTree.obj(JsonTree.parse(reader));
                since = (long) JsonTree.num(root.get("since"), now);
                total = readStats(root.get("total"));
                for (Map.Entry<String, Object> entry : JsonTree.obj(root.get("days")).entrySet()) {
                    days.put(entry.getKey(), readStats(entry.getValue()));
                }
                Map<String, Object> s = JsonTree.obj(root.get("session"));
                if (!s.isEmpty()) {
                    session = readStats(s.get("stats"));
                    sessionStartedAt = (long) JsonTree.num(s.get("startedAt"), now);
                    sessionActive = (long) JsonTree.num(s.get("activeMillis"), 0);
                    // Time with the game closed does not count: a running session goes on from now.
                    sessionRunningSince = Boolean.FALSE.equals(s.get("running")) ? 0 : now;
                }
                for (Object o : JsonTree.arr(root.get("history"))) {
                    Map<String, Object> m = JsonTree.obj(o);
                    history.addLast(new PastSession((long) JsonTree.num(m.get("startedAt"), 0),
                        (long) JsonTree.num(m.get("endedAt"), 0), (long) JsonTree.num(m.get("activeMillis"), 0),
                        readStats(m.get("stats"))));
                }
                for (Object o : JsonTree.arr(root.get("flips"))) {
                    Map<String, Object> m = JsonTree.obj(o);
                    flips.addLast(new Flip(JsonTree.str(m.get("item")), (int) JsonTree.num(m.get("amount"), 1),
                        JsonTree.num(m.get("cost"), 0), JsonTree.num(m.get("revenue"), 0),
                        JsonTree.num(m.get("profit"), 0), (long) JsonTree.num(m.get("boughtAt"), 0),
                        (long) JsonTree.num(m.get("soldAt"), 0)));
                }
                for (Object o : JsonTree.arr(root.get("lots"))) {
                    Map<String, Object> m = JsonTree.obj(o);
                    String item = JsonTree.str(m.get("item"));
                    if (item == null) {
                        continue;
                    }
                    String norm = normalize(item);
                    Deque<Lot> queue = lots.get(norm);
                    if (queue == null) {
                        queue = new ArrayDeque<Lot>();
                        lots.put(norm, queue);
                    }
                    queue.addLast(new Lot(item, (int) JsonTree.num(m.get("amount"), 1),
                        JsonTree.num(m.get("unitCost"), 0), (long) JsonTree.num(m.get("time"), 0)));
                }
                for (Object o : JsonTree.arr(root.get("unclaimed"))) {
                    Map<String, Object> m = JsonTree.obj(o);
                    String item = JsonTree.str(m.get("item"));
                    if (item != null) {
                        unclaimed.add(new Unclaimed(item, String.valueOf(JsonTree.str(m.get("buyer"))),
                            JsonTree.num(m.get("price"), 0), (long) JsonTree.num(m.get("time"), 0)));
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.warn("File dei guadagni illeggibile, riparto da zero: " + file, e);
                since = now;
                total = new Stats();
                days.clear();
                session = new Stats();
                sessionStartedAt = now;
                sessionActive = 0;
                sessionRunningSince = now;
                history.clear();
                flips.clear();
                lots.clear();
                unclaimed.clear();
            }
        }
    }

    /** Saves on the calling thread when something changed. */
    public void save() {
        synchronized (fileLock) {
            String json;
            synchronized (this) {
                if (!dirty) {
                    return;
                }
                json = toJson(clock.getAsLong());
                dirty = false;
            }
            try {
                write(json);
            } catch (IOException e) {
                synchronized (this) {
                    dirty = true;
                }
                Log.warn("Impossibile salvare " + file, e);
            }
        }
    }

    /** Saves before the game closes, including the time played in a running session. */
    public void stop() {
        synchronized (this) {
            if (sessionRunningSince > 0) {
                dirty = true;
            }
        }
        save();
    }

    private String toJson(long now) {
        try {
            StringWriter out = new StringWriter();
            JsonWriter w = new JsonWriter(out);
            w.setIndent("  ");
            w.beginObject();
            w.name("version").value(1);
            w.name("since").value(since);
            w.name("total");
            writeStats(w, total);
            w.name("days").beginObject();
            for (Map.Entry<String, Stats> entry : days.entrySet()) {
                w.name(entry.getKey());
                writeStats(w, entry.getValue());
            }
            w.endObject();
            w.name("session").beginObject();
            w.name("startedAt").value(sessionStartedAt);
            w.name("activeMillis").value(sessionActive(now));
            w.name("running").value(sessionRunningSince > 0);
            w.name("stats");
            writeStats(w, session);
            w.endObject();
            w.name("history").beginArray();
            for (PastSession past : history) {
                w.beginObject();
                w.name("startedAt").value(past.startedAt);
                w.name("endedAt").value(past.endedAt);
                w.name("activeMillis").value(past.activeMillis);
                w.name("stats");
                writeStats(w, past.stats);
                w.endObject();
            }
            w.endArray();
            w.name("flips").beginArray();
            for (Flip flip : flips) {
                w.beginObject();
                w.name("item").value(flip.item);
                w.name("amount").value(flip.amount);
                w.name("cost").value(flip.cost);
                w.name("revenue").value(flip.revenue);
                w.name("profit").value(flip.profit);
                w.name("boughtAt").value(flip.boughtAt);
                w.name("soldAt").value(flip.soldAt);
                w.endObject();
            }
            w.endArray();
            w.name("lots").beginArray();
            for (Deque<Lot> queue : lots.values()) {
                for (Lot lot : queue) {
                    w.beginObject();
                    w.name("item").value(lot.item);
                    w.name("amount").value(lot.amount);
                    w.name("unitCost").value(lot.unitCost);
                    w.name("time").value(lot.time);
                    w.endObject();
                }
            }
            w.endArray();
            w.name("unclaimed").beginArray();
            for (Unclaimed u : unclaimed) {
                w.beginObject();
                w.name("item").value(u.item);
                w.name("buyer").value(u.buyer);
                w.name("price").value(u.price);
                w.name("time").value(u.time);
                w.endObject();
            }
            w.endArray();
            w.endObject();
            w.close();
            return out.toString();
        } catch (IOException e) {
            // A StringWriter does not fail.
            throw new IllegalStateException(e);
        }
    }

    private static void writeStats(JsonWriter w, Stats s) throws IOException {
        w.beginObject();
        w.name("profit").value(s.profit);
        w.name("flips").value(s.flips);
        w.name("bought").value(s.bought);
        w.name("spent").value(s.spent);
        w.name("sold").value(s.sold);
        w.name("earned").value(s.earned);
        w.name("unmatched").value(s.unmatched);
        if (s.bestItem != null) {
            w.name("bestItem").value(s.bestItem);
            w.name("bestProfit").value(s.bestProfit);
        }
        w.endObject();
    }

    private static Stats readStats(Object o) {
        Map<String, Object> m = JsonTree.obj(o);
        Stats s = new Stats();
        s.profit = JsonTree.num(m.get("profit"), 0);
        s.flips = (int) JsonTree.num(m.get("flips"), 0);
        s.bought = (int) JsonTree.num(m.get("bought"), 0);
        s.spent = JsonTree.num(m.get("spent"), 0);
        s.sold = (int) JsonTree.num(m.get("sold"), 0);
        s.earned = JsonTree.num(m.get("earned"), 0);
        s.unmatched = (int) JsonTree.num(m.get("unmatched"), 0);
        s.bestItem = JsonTree.str(m.get("bestItem"));
        s.bestProfit = JsonTree.num(m.get("bestProfit"), 0);
        return s;
    }

    private void write(String json) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Impossibile creare " + parent);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            out.write(json);
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- Helpers ----

    /** "+1.2M" in green or "-300k" in red. */
    public static String signed(double coins) {
        return coins >= 0 ? "§2+" + Coins.compact(coins) : "§c" + Coins.compact(coins);
    }

    static String dayKey(long time) {
        return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    /** Lowercase letters, digits and a few signs: stars and other symbols do not change the item. */
    static String normalize(String name) {
        String s = Text.stripCodes(name).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        boolean gap = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '\'' || c == '-' || c == '['
                || c == ']';
            if (!keep) {
                gap = true;
                continue;
            }
            if (gap && sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(c);
            gap = false;
        }
        return sb.toString();
    }

    /**
     * Same item, possibly reforged in between: equal names, a reforge added in front, or a reforge
     * replaced by another one. Normalised names only.
     */
    static boolean sameItem(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int spaceA = a.indexOf(' ');
        int spaceB = b.indexOf(' ');
        boolean reforgedA = spaceA > 0 && REFORGES.contains(a.substring(0, spaceA));
        boolean reforgedB = spaceB > 0 && REFORGES.contains(b.substring(0, spaceB));
        String restA = reforgedA ? a.substring(spaceA + 1) : null;
        String restB = reforgedB ? b.substring(spaceB + 1) : null;
        return reforgedA && restA.equals(b) || reforgedB && restB.equals(a)
            || reforgedA && reforgedB && restA.equals(restB);
    }

    private static String playerName(String raw) {
        return raw == null ? "" : raw.replaceFirst("^\\[[^\\]]+\\] ", "").trim();
    }
}
