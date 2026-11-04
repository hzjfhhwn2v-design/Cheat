package dev.flipscout.core.skins;

import dev.flipscout.core.Msg;
import dev.flipscout.core.util.Coins;
import dev.flipscout.core.util.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chat lines for skin investments. */
public final class SkinText {
    public static final String ITEM_URL = "https://sky.coflnet.com/item/";
    private static final String BARS = "▁▂▃▄▅▆▇█";
    private static final String RULE = "§8━━━━━━━━━━━━━━━━━━━━";
    private static final int CHART_DAYS = 30;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** How fresh the data behind a list is. */
    public static final class Freshness {
        /** Age of the auction data in milliseconds, -1 when not read yet. */
        public final long auctionsAge;
        /** Skins with enough listings, and how many of them have a recent price history. */
        public final int wanted;
        public final int histories;
        public final boolean reading;

        public Freshness(long auctionsAge, int wanted, int histories, boolean reading) {
            this.auctionsAge = auctionsAge;
            this.wanted = wanted;
            this.histories = histories;
            this.reading = reading;
        }
    }

    private SkinText() {
    }

    /** The ranking: a header, one line per skin (hover for the numbers, click for the auction). */
    public static List<Msg> ranking(SkinAdvisor.Ranking r, int horizon, Freshness f) {
        List<Msg> out = new ArrayList<Msg>();
        out.add(new Msg().add(Msg.PREFIX + "§fSkin investments §8- §7buy now, sell in " + days(horizon) + ", after fees ")
            .link("§8[SkyCofl]", "https://sky.coflnet.com/data", "§7Price history: SkyCofl (sky.coflnet.com)"));
        out.add(freshness(f, r));
        if (r.picks.isEmpty()) {
            out.add(Msg.plain("§7  No skin is cheap enough right now to expect a profit after fees in " + days(horizon)
                + " §8(" + r.analysed + " analysed)§7. Skins rarely gain more than the fees in a month."));
            return out;
        }
        for (int i = 0; i < r.picks.size(); i++) {
            SkinAdvisor.Pick p = r.picks.get(i);
            Msg line = new Msg().add("§8 " + (i + 1) + ". ");
            String name = "§b" + p.name;
            if (p.lowestUuid != null) {
                line.run(name, "/viewauction " + p.lowestUuid, hover(p, horizon, true));
            } else {
                line.add(name, hover(p, horizon, false));
            }
            out.add(line);
        }
        out.add(new Msg().suggest("§8  Hover a skin for its numbers, click to open its cheapest BIN. "
            + "§7/flipscout skin <name> §8for any skin.", "/flipscout skin ", "§eClick to type it"));
        return out;
    }

    static Msg freshness(Freshness f, SkinAdvisor.Ranking r) {
        String auctions;
        if (f.auctionsAge < 0) {
            auctions = "§cauctions not read yet";
        } else {
            String color = f.auctionsAge < 120_000 ? "§a" : f.auctionsAge < 300_000 ? "§e" : "§c";
            auctions = "§7Auctions read " + color + Text.shortDuration(f.auctionsAge) + " ago";
        }
        String histories = "§7price history of §f" + f.histories + "§7/§f" + f.wanted + " §7skins"
            + (f.reading || f.histories < f.wanted ? " §8(still reading)" : "");
        StringBuilder hover = new StringBuilder("§f" + r.listed + " §7skins up for sale, §f" + r.analysed + " §7analysed");
        for (Map.Entry<String, Integer> e : r.hidden.entrySet()) {
            hover.append("\n§7Left out, ").append(e.getKey()).append(": §f").append(e.getValue());
        }
        hover.append("\n§7No profit expected after fees: §f").append(r.notProfitable);
        return new Msg().add("§8  ").add(auctions + " §8| " + histories, hover.toString());
    }

    /** The numbers shown when hovering a skin in the list. */
    static String hover(SkinAdvisor.Pick p, int horizon, boolean clickable) {
        StringBuilder sb = new StringBuilder();
        sb.append("§b§l").append(p.name).append('\n').append(RULE).append('\n');
        sb.append("§7Price now: §6").append(Coins.compact(p.lowestBin)).append(" §8(cheapest BIN)\n");
        sb.append("§7Expected profit: ").append(coins(p.profit)).append(" §8(sold in ").append(days(horizon))
            .append(", after fees)\n");
        sb.append("§7Expected return: ").append(percent(p.expectedReturn)).append('\n');
        sb.append("§7Risk: ").append(p.risk.label).append('\n').append(RULE).append('\n');
        sb.append("§77 days: ").append(percent(p.change7)).append(" §830 days: ").append(percent(p.change30)).append('\n');
        sb.append("§7Volatility (").append(days(horizon)).append("): §e±").append(Coins.decimal(p.volatility * 100, 1))
            .append("%\n");
        sb.append("§7Listings: §f").append(p.listings).append(" §8| §7sales: §f")
            .append(Coins.decimal(p.salesPerDay, p.salesPerDay < 10 ? 1 : 0)).append(" a day\n");
        if (p.fireSale != null) {
            sb.append("§7Fire sale: ").append(fireSale(p.fireSale)).append('\n');
        }
        sb.append("§7Confidence: §f").append(Math.round(p.confidence * 100)).append("% §8(times a buy at this price\n")
            .append("§8was in profit ").append(days(horizon)).append(" later, ")
            .append(p.ownHistory ? "over this skin's last year)" : "skins of its age)").append('\n').append(RULE);
        if (clickable) {
            sb.append("\n§eClick to open the cheapest BIN");
        }
        return sb.toString();
    }

    /** Everything about one skin, with a chart of its last month. */
    public static List<Msg> detail(SkinAdvisor.Pick p, int horizon, long now) {
        List<Msg> out = new ArrayList<Msg>();
        out.add(new Msg().add(Msg.PREFIX + "§b" + p.name + " §8- §7skin investment over " + days(horizon) + " ")
            .link("§8[chart]", ITEM_URL + p.id, "§7Full price chart on SkyCofl\n§eClick to open sky.coflnet.com"));
        if (p.lowestBin > 0) {
            Msg price = new Msg().add("§7  Price now §6" + Coins.compact(p.lowestBin) + " §8(cheapest of " + p.listings
                + " BIN) ");
            if (p.lowestUuid != null) {
                price.run("§e[open]", "/viewauction " + p.lowestUuid, "§eClick to open the cheapest BIN");
            }
            out.add(price);
        }
        if (p.sellValue > 0 && !Double.isNaN(p.profit)) {
            out.add(Msg.plain("§7  Sells for §6" + Coins.compact(p.sellValue) + " §8(" + p.sellSource + ") §7expected in "
                + days(horizon) + " §6" + Coins.compact(p.expectedSell) + " §8(" + Coins.compact(p.fees) + " fees)"));
            out.add(Msg.plain("§7  Expected profit " + coins(p.profit) + " §7(" + percent(p.expectedReturn) + "§7) §8| §7risk "
                + p.risk.label + " §8| §7confidence §f" + Math.round(p.confidence * 100) + "%"));
        }
        if (p.history != null && !p.history.isEmpty()) {
            out.add(Msg.plain("§7  7 days " + percent(p.change7) + " §8| §730 days " + percent(p.change30) + " §8| §790 days "
                + percent(p.change90) + " §8| §730-day median §f" + Coins.compact(p.median30)));
            out.add(Msg.plain("§7  Volatility §e±" + Coins.decimal(p.volatility * 100, 1) + "% §8| §7largest fall in 90 days §f"
                + Math.round(p.drawdown90 * 100) + "% §8| §7sales §f" + Coins.decimal(p.salesPerDay, 1) + " a day"));
            if (p.ageDays >= 0) {
                String usual = Double.isNaN(p.ageRise) ? "" : " §8(skins this old: usually " + percent(Math.exp(p.drift) - 1)
                    + " §8in " + days(horizon) + ", up " + Math.round(p.ageRise * 100) + "% of the time)";
                out.add(Msg.plain("§7  First sold §f" + date(p.history.firstDay) + usual));
            }
        }
        if (p.fireSale != null) {
            out.add(Msg.plain("§7  Fire sale: " + fireSale(p.fireSale)
                + (p.fireSale.current(now) ? " §c(on now)" : "")));
        }
        if (p.excluded != null) {
            out.add(Msg.plain("§c  Not advised: " + p.excluded));
        } else if (!(p.profit > 0)) {
            out.add(Msg.plain("§e  No profit expected after fees at this price."));
        } else {
            out.add(Msg.plain("§a  Advised: a profit is expected after fees."));
        }
        if (p.history != null && !p.history.isEmpty()) {
            out.addAll(chart(p.history));
        }
        return out;
    }

    /** The typical price of each of the last 30 days, as bars, with the lowest and highest. */
    static List<Msg> chart(SkinHistory.Series s) {
        List<Msg> out = new ArrayList<Msg>();
        List<SkinHistory.Day> days = s.days;
        double[] typical = SkinStats.typical(days);
        int lastDay = days.get(days.size() - 1).day;
        int from = 0;
        while (from < days.size() && days.get(from).day <= lastDay - CHART_DAYS) {
            from++;
        }
        double low = Double.MAX_VALUE;
        double high = 0;
        for (int i = from; i < days.size(); i++) {
            low = Math.min(low, typical[i]);
            high = Math.max(high, typical[i]);
        }
        Msg bars = new Msg().add("§7  Last " + CHART_DAYS + " days ");
        for (int i = from; i < days.size(); i++) {
            int level = high > low ? (int) Math.round((typical[i] - low) / (high - low) * (BARS.length() - 1)) : 3;
            SkinHistory.Day d = days.get(i);
            bars.add("§e" + BARS.charAt(level), "§f" + day(d.day) + "\n§7Price: §6" + Coins.compact(typical[i])
                + "\n§7Sales: §f" + d.sales);
        }
        out.add(bars);
        out.add(Msg.plain("§7  Lowest §c" + Coins.compact(low) + " §8| §7highest §a" + Coins.compact(high)));
        return out;
    }

    /** One line announcing a fire sale. */
    public static Msg fireSaleAlert(FireSales.Sale sale, String name, long now) {
        String when = now < sale.start ? "starts in " + Text.duration(sale.start - now) : "on now, ends in "
            + Text.duration(sale.end - now);
        return new Msg().add(Msg.PREFIX + "§6Fire sale: §f" + name + " §7- " + Coins.full(sale.amount) + " for "
            + Coins.full(sale.price) + " gems each §8(" + when + ")",
            "§7Skins from a fire sale cannot be bought for gems again later.\n§7While it lasts, more are still being sold:"
                + "\n§7FlipScout leaves it out of §e/flipscout skins §7until it ends.");
    }

    static String fireSale(FireSales.Sale s) {
        return "§f" + Coins.full(s.amount) + " §7sold for §f" + Coins.full(s.price) + " gems §8("
            + day((int) (s.start / SkinHistory.DAY_MS)) + " - " + date((int) (s.end / SkinHistory.DAY_MS)) + ")";
    }

    static String percent(double fraction) {
        if (Double.isNaN(fraction)) {
            return "§8?";
        }
        double pct = fraction * 100;
        return (pct >= 0 ? "§a+" : "§c") + Coins.decimal(pct, Math.abs(pct) < 10 ? 1 : 0) + "%";
    }

    static String coins(double value) {
        if (Double.isNaN(value)) {
            return "§8?";
        }
        return (value >= 0 ? "§a+" : "§c") + Coins.compact(value);
    }

    static String days(int days) {
        return days + " day" + (days == 1 ? "" : "s");
    }

    private static String date(int day) {
        return DATE.format(Instant.ofEpochMilli(day * SkinHistory.DAY_MS).atZone(ZoneOffset.UTC));
    }

    private static String day(int day) {
        return DAY.format(Instant.ofEpochMilli(day * SkinHistory.DAY_MS).atZone(ZoneId.of("UTC")));
    }
}
