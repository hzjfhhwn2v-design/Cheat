package dev.flipscout.core.skins;

import dev.flipscout.core.Config;
import dev.flipscout.core.flip.Fees;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.Valuation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Which skins are worth buying now to sell again in a few weeks.
 *
 * <p>For each skin with BIN auctions: what it costs now (the cheapest BIN), what it sells for now
 * (the next cheapest BIN, or recent sales when lower), how that usually changes over the horizon
 * for skins of the same age, and the auction house fees. The confidence is how often, over the
 * past year of this skin's prices, a purchase at this price would have been in profit after the
 * horizon. Skins that are hard to sell, too new, on a fire sale or falling hard are left out.
 */
public final class SkinAdvisor {
    static final int MIN_AGE_DAYS = 30;
    /** Below this a spread is treated as this, so a flat history does not look riskless. */
    static final double MIN_SPREAD = 0.03;
    /** Own changes needed to judge a skin by its own history rather than skins of its age. */
    static final int MIN_OWN_CHANGES = 20;

    public enum Risk {
        LOW("§aLow"), MEDIUM("§eMedium"), HIGH("§cHigh");

        public final String label;

        Risk(String label) {
            this.label = label;
        }
    }

    /** The live auction data the advice is based on. */
    public interface Live {
        /** The cheapest BIN listing of a skin, or null when none is up. */
        AuctionIndex.Listing lowest(String id);

        int listings(String id);

        /** What the skin is worth, ignoring the listing {@code lowest} itself. */
        Valuation valuate(AuctionIndex.Listing lowest);
    }

    /** One skin, analysed. */
    public static final class Pick {
        public final String id;
        public final String name;
        public SkinHistory.Series history;
        public int listings;
        public long lowestBin = -1;
        public String lowestUuid;
        /** What it sells for now, before fees, and where that comes from. */
        public double sellValue = Double.NaN;
        public String sellSource;
        /** Expected sale price after the horizon, before fees. */
        public double expectedSell = Double.NaN;
        public double fees = Double.NaN;
        /** Expected profit after fees, and as a share of the price paid. */
        public double profit = Double.NaN;
        public double expectedReturn = Double.NaN;
        public double score = Double.NaN;
        public double typical = Double.NaN;
        public double median30 = Double.NaN;
        public double change7 = Double.NaN;
        public double change30 = Double.NaN;
        public double change90 = Double.NaN;
        /** Usual size of a price move over the horizon (0.08 = 8%). */
        public double volatility = Double.NaN;
        public double drawdown90 = Double.NaN;
        public double salesPerDay = Double.NaN;
        public int daysWithSales30;
        /** Days since SkyCofl first recorded a sale, -1 when unknown. */
        public int ageDays = -1;
        public double drift;
        public double ageRise = Double.NaN;
        public int ageSamples;
        /** Share of past cases where a purchase at this price would have been in profit. */
        public double confidence = Double.NaN;
        public int confidenceSamples;
        /** The confidence comes from this skin's own history rather than skins of its age. */
        public boolean ownHistory;
        public Risk risk = Risk.HIGH;
        public FireSales.Sale fireSale;
        /** Why it is not advised, or null. */
        public String excluded;
        /** Short group of {@link #excluded}, for counts. */
        public String excludedGroup;

        Pick(String id, String name) {
            this.id = id;
            this.name = name;
        }

        /** Advised: nothing rules it out and a profit is expected. */
        public boolean buy() {
            return excluded == null && profit > 0;
        }

        void exclude(String group, String reason) {
            if (excluded == null) {
                excludedGroup = group;
                excluded = reason;
            }
        }
    }

    /** The advised skins and why others were left out. */
    public static final class Ranking {
        /** Best first. */
        public final List<Pick> picks;
        /** Skins with a BIN listing and a price history. */
        public final int analysed;
        /** Skins with a BIN listing up. */
        public final int listed;
        /** Of the analysed skins, how many each reason left out. */
        public final Map<String, Integer> hidden;
        public final int notProfitable;

        Ranking(List<Pick> picks, int analysed, int listed, Map<String, Integer> hidden, int notProfitable) {
            this.picks = picks;
            this.analysed = analysed;
            this.listed = listed;
            this.hidden = hidden;
            this.notProfitable = notProfitable;
        }
    }

    /** What does not change until a skin's history is read again. */
    private static final class Fixed {
        final SkinHistory.Series series;
        final int horizon;
        final double[] typical;
        final double[] own;

        Fixed(SkinHistory.Series series, int horizon, double[] typical, double[] own) {
            this.series = series;
            this.horizon = horizon;
            this.typical = typical;
            this.own = own;
        }
    }

    private final Config config;
    private final SkinHistory history;
    private final FireSales fireSales;
    private final Live live;
    private final LongSupplier clock;
    private final Map<String, Fixed> fixed = new ConcurrentHashMap<String, Fixed>();
    private volatile AgeModel model;
    private volatile int modelVersion = -1;
    /** IDs of the last ranking, best first: offered first by tab completion. */
    private volatile List<String> lastRanking = Collections.emptyList();

    public SkinAdvisor(Config config, SkinHistory history, FireSales fireSales, Live live, LongSupplier clock) {
        this.config = config;
        this.history = history;
        this.fireSales = fireSales;
        this.live = live;
        this.clock = clock;
    }

    public int horizon() {
        return Math.max(7, Math.min(180, config.skinsDays));
    }

    /** The best skins to buy, at most {@code limit}. */
    public Ranking rank(int limit) {
        AgeModel m = model();
        List<Pick> buys = new ArrayList<Pick>();
        Map<String, Integer> hidden = new LinkedHashMap<String, Integer>();
        int analysed = 0;
        int listed = 0;
        int notProfitable = 0;
        for (Map.Entry<String, String> skin : history.names().entrySet()) {
            AuctionIndex.Listing lowest = live.lowest(skin.getKey());
            if (lowest == null) {
                continue;
            }
            listed++;
            Pick pick = analyse(skin.getKey(), skin.getValue(), lowest, m);
            if (pick.history == null) {
                continue;
            }
            analysed++;
            if (pick.excluded != null) {
                Integer n = hidden.get(pick.excludedGroup);
                hidden.put(pick.excludedGroup, n == null ? 1 : n + 1);
            } else if (pick.buy()) {
                buys.add(pick);
            } else {
                notProfitable++;
            }
        }
        Collections.sort(buys, new Comparator<Pick>() {
            @Override
            public int compare(Pick a, Pick b) {
                return Double.compare(b.score, a.score);
            }
        });
        List<Pick> top = buys.size() > limit ? new ArrayList<Pick>(buys.subList(0, limit)) : buys;
        List<String> ids = new ArrayList<String>();
        for (Pick p : top) {
            ids.add(p.id);
        }
        lastRanking = Collections.unmodifiableList(ids);
        return new Ranking(top, analysed, listed, hidden, notProfitable);
    }

    /** IDs of the skins advised by the last ranking, best first. */
    public List<String> lastRanking() {
        return lastRanking;
    }

    /** One skin in full, also when it has no listing or no history. */
    public Pick analyse(String id) {
        String name = history.name(id);
        return analyse(id, name != null ? name : id, live.lowest(id), model());
    }

    AgeModel model() {
        int version = history.version() * 1_000 + horizon();
        AgeModel m = model;
        if (m == null || modelVersion != version) {
            m = AgeModel.build(history.all(), horizon());
            model = m;
            modelVersion = version;
        }
        return m;
    }

    private Pick analyse(String id, String name, AuctionIndex.Listing lowest, AgeModel m) {
        Pick p = new Pick(id, name);
        int horizon = m.horizon;
        int today = (int) (clock.getAsLong() / SkinHistory.DAY_MS);
        if (lowest != null) {
            p.lowestBin = lowest.price;
            p.lowestUuid = lowest.uuid;
            p.listings = live.listings(id);
        }
        SkinHistory.Series s = history.get(id);
        p.history = s;
        if (config.isBlacklisted(id)) {
            p.exclude("blacklisted", "on your blacklist");
        }
        if (s == null) {
            p.exclude("no history", "its price history has not been read yet");
            return p;
        }
        if (s.isEmpty()) {
            p.exclude("no history", "SkyCofl has no sales of it");
            return p;
        }
        Fixed f = fixed(s, horizon);
        List<SkinHistory.Day> days = s.days;
        int last = days.size() - 1;
        p.typical = f.typical[last];
        p.median30 = SkinStats.median(days, today, 30);
        p.change7 = SkinStats.change(days, f.typical, 7);
        p.change30 = SkinStats.change(days, f.typical, 30);
        p.change90 = SkinStats.change(days, f.typical, 90);
        p.drawdown90 = SkinStats.drawdown(days, f.typical, 90);
        p.salesPerDay = SkinStats.salesPerDay(days, today, 30);
        p.daysWithSales30 = SkinStats.daysWithSales(days, today, 30);
        p.ageDays = s.firstDay >= 0 ? today - s.firstDay : -1;
        p.drift = m.drift(p.ageDays);
        p.ageRise = m.rise(p.ageDays);
        p.ageSamples = m.samples(p.ageDays);
        double[] spread;
        if (f.own.length >= MIN_OWN_CHANGES) {
            p.volatility = SkinStats.robustStd(f.own);
            spread = SkinStats.centered(f.own);
            p.ownHistory = true;
        } else {
            p.volatility = SkinStats.dailySpread(days, today, 90, horizon);
            spread = m.spread(p.ageDays);
        }
        p.fireSale = fireSales == null ? null : fireSales.latest(id);
        boolean recent = days.get(last).day >= today - 7;

        if (lowest == null) {
            p.exclude("not listed", "no BIN auction of it is up right now");
        }
        if (p.ageDays >= 0 && p.ageDays < MIN_AGE_DAYS) {
            p.exclude("too new", "first sold " + p.ageDays + " days ago: too new to judge");
        }
        if (!recent || p.daysWithSales30 < 8) {
            p.exclude("rarely sold", !recent ? "no sales in the last week"
                : "sold on only " + p.daysWithSales30 + " of the last 30 days");
        }
        if (lowest != null && p.listings < config.skinsMinListings) {
            p.exclude("few listings", "only " + p.listings + " BIN auction" + (p.listings == 1 ? "" : "s")
                + " up (minimum " + config.skinsMinListings + ")");
        }
        if (p.salesPerDay < config.skinsMinSales) {
            p.exclude("rarely sold", String.format(java.util.Locale.ROOT, "sells %.1f a day (minimum %.1f)",
                p.salesPerDay, config.skinsMinSales));
        }
        if (p.drawdown90 * 100 >= config.skinsMaxDrawdown) {
            p.exclude("falling", "fell " + Math.round(p.drawdown90 * 100) + "% from its high of the last 90 days");
        }
        if (p.fireSale != null && p.fireSale.current(clock.getAsLong())) {
            p.exclude("fire sale", "on fire sale: more are still being sold for gems");
        }
        if (lowest == null) {
            return p;
        }

        Valuation v = live.valuate(lowest);
        double sales = !Double.isNaN(v.salesMedian) ? v.salesMedian : !Double.isNaN(v.coflMedian) ? v.coflMedian
            : recent ? p.typical : Double.NaN;
        String salesName = !Double.isNaN(v.salesMedian) ? "recent sales" : !Double.isNaN(v.coflMedian)
            ? "recent sales (SkyCofl)" : "this week's sales";
        // As for flips: the next cheapest BIN, recent sales, or the lower of the two (the default).
        double next = v.lbinOther;
        boolean useNext;
        if ("lbin".equals(config.ahStrategy)) {
            useNext = !Double.isNaN(next);
        } else if ("sales".equals(config.ahStrategy)) {
            useNext = Double.isNaN(sales);
        } else {
            useNext = !Double.isNaN(next) && (Double.isNaN(sales) || next <= sales);
        }
        if (useNext) {
            p.sellValue = next;
            p.sellSource = "the next cheapest BIN";
        } else {
            p.sellValue = sales;
            p.sellSource = salesName;
        }
        if (!(p.sellValue > 0) || Double.isNaN(p.volatility)) {
            p.exclude("no data", "not enough data to judge");
            return p;
        }
        p.expectedSell = p.sellValue * Math.exp(p.drift);
        p.fees = Fees.auctionSellFees(p.expectedSell, config);
        p.profit = p.expectedSell - p.fees - lowest.price;
        p.expectedReturn = p.profit / lowest.price;
        p.score = p.expectedReturn / Math.max(p.volatility, MIN_SPREAD);
        double keep = 1 - p.fees / p.expectedSell;
        double breakEven = Math.log(lowest.price / (p.sellValue * keep));
        p.confidence = SkinStats.shareAtLeast(spread, p.drift, breakEven);
        p.confidenceSamples = spread.length;
        p.risk = risk(p, horizon);
        return p;
    }

    /** Risk from the usual move, the recent fall and how many are up for sale. */
    static Risk risk(Pick p, int horizon) {
        double scale = Math.sqrt(horizon / 30.0);
        if (Double.isNaN(p.volatility) || p.volatility > 0.25 * scale || p.drawdown90 > 0.55 || p.listings < 5) {
            return Risk.HIGH;
        }
        if (p.volatility < 0.10 * scale && p.drawdown90 < 0.30) {
            return Risk.LOW;
        }
        return Risk.MEDIUM;
    }

    private Fixed fixed(SkinHistory.Series s, int horizon) {
        Fixed f = fixed.get(s.id);
        if (f != null && f.series == s && f.horizon == horizon) {
            return f;
        }
        double[] typical = SkinStats.typical(s.days);
        int lastDay = s.days.get(s.days.size() - 1).day;
        double[] own = SkinStats.changes(s.days, typical, horizon, lastDay - 365, AgeModel.STEP_DAYS, s.firstDay, null);
        f = new Fixed(s, horizon, typical, own);
        fixed.put(s.id, f);
        return f;
    }
}
