package dev.flipscout.core.extra;

import dev.flipscout.core.Msg;
import dev.flipscout.core.api.ApiException;
import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.util.Coins;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Price history of an item (SkyCofl): a bar chart in chat and a few numbers. */
public final class History {
    public static final List<String> RANGES = java.util.Arrays.asList("day", "week", "month", "all");
    private static final String BARS = "▁▂▃▄▅▆▇█";
    private static final int MAX_BARS = 30;
    private static final long DAY_MS = 86_400_000L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    /** One point of the history: when, and the average, lowest and highest price then. */
    static final class Point {
        final long time;
        final double avg;
        final double min;
        final double max;
        /** Sales (auctions) or items traded (bazaar) in the point's span, NaN when unknown. */
        final double volume;

        Point(long time, double avg, double min, double max, double volume) {
            this.time = time;
            this.avg = avg;
            this.min = min;
            this.max = max;
            this.volume = volume;
        }
    }

    private final HttpFetcher http;
    private final Supplier<String> baseUrl;
    private final LongSupplier clock;

    public History(HttpFetcher http, Supplier<String> baseUrl, LongSupplier clock) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    /** Chat lines for an item's history over {@code range} (day, week, month or all). */
    public List<Msg> show(String id, String name, boolean bazaar, String range) throws IOException {
        List<Point> points = bazaar ? bazaar(id, range) : auctions(id, range);
        return lines(name, bazaar, range, points);
    }

    List<Point> auctions(String id, String range) throws IOException {
        String span = "all".equals(range) ? "full" : range;
        List<Object> list = JsonTree.arr(get("/api/item/price/" + URLEncoder.encode(id, "UTF-8") + "/history/" + span));
        List<Point> out = new ArrayList<Point>();
        for (Object o : list) {
            Map<String, Object> e = JsonTree.obj(o);
            long time = Mayors.parseIso(JsonTree.str(e.get("time")));
            double avg = JsonTree.num(e.get("avg"), Double.NaN);
            if (time > 0 && avg > 0) {
                out.add(new Point(time, avg, JsonTree.num(e.get("min"), avg), JsonTree.num(e.get("max"), avg),
                    JsonTree.num(e.get("volume"), Double.NaN)));
            }
        }
        return sorted(out);
    }

    List<Point> bazaar(String id, String range) throws IOException {
        String path = "/api/bazaar/" + URLEncoder.encode(id, "UTF-8") + "/history";
        long now = clock.getAsLong();
        if ("day".equals(range) || "week".equals(range)) {
            path += "/" + range;
        } else {
            long days = "month".equals(range) ? 30 : 365;
            path += "?start=" + Instant.ofEpochMilli(now - days * DAY_MS) + "&end=" + Instant.ofEpochMilli(now);
        }
        List<Point> out = new ArrayList<Point>();
        for (Object o : JsonTree.arr(get(path))) {
            Map<String, Object> e = JsonTree.obj(o);
            long time = Mayors.parseIso(JsonTree.str(e.get("timestamp")));
            // The instant-buy price: what it costs to get one.
            double buy = JsonTree.num(e.get("buy"), Double.NaN);
            if (time > 0 && buy > 0) {
                out.add(new Point(time, buy, JsonTree.num(e.get("minBuy"), buy), JsonTree.num(e.get("maxBuy"), buy),
                    JsonTree.num(e.get("buyVolume"), Double.NaN)));
            }
        }
        return sorted(out);
    }

    static List<Msg> lines(String name, boolean bazaar, String range, List<Point> points) {
        List<Msg> out = new ArrayList<Msg>();
        String what = bazaar ? "instant-buy price" : "auction price";
        out.add(new Msg().add(Msg.PREFIX + "§f" + name + " §8- " + what + ", " + rangeName(range) + " ")
            .link("§8[SkyCofl]", OpportunityText.DATA_URL, "§7Price history: SkyCofl (sky.coflnet.com)"));
        if (points.isEmpty()) {
            out.add(Msg.plain("§7  No prices recorded in this span."));
            return out;
        }
        List<Point> bars = buckets(points, MAX_BARS);
        double low = Double.MAX_VALUE;
        double high = 0;
        double sum = 0;
        for (Point p : points) {
            low = Math.min(low, p.min > 0 ? p.min : p.avg);
            high = Math.max(high, p.max > 0 ? p.max : p.avg);
            sum += p.avg;
        }
        double barLow = Double.MAX_VALUE;
        double barHigh = 0;
        for (Point p : bars) {
            barLow = Math.min(barLow, p.avg);
            barHigh = Math.max(barHigh, p.avg);
        }
        boolean hours = "day".equals(range) || "week".equals(range);
        Msg chart = new Msg().add("§7  ");
        for (Point p : bars) {
            int level = barHigh > barLow ? (int) Math.round((p.avg - barLow) / (barHigh - barLow) * (BARS.length() - 1)) : 3;
            String date = (hours ? HOUR : DAY).format(Instant.ofEpochMilli(p.time).atZone(ZoneId.systemDefault()));
            chart.add("§e" + BARS.charAt(level), "§f" + date + "\n§7Average: §6" + Coins.compact(p.avg)
                + "\n§7Lowest: §c" + Coins.compact(p.min) + " §7Highest: §a" + Coins.compact(p.max)
                + (p.volume >= 0 ? "\n§7" + (bazaar ? "Bought: §f" : "Sales: §f") + Coins.compact(p.volume) : ""));
        }
        out.add(chart);
        Point first = points.get(0);
        Point last = points.get(points.size() - 1);
        double change = first.avg > 0 ? (last.avg / first.avg - 1) * 100 : 0;
        StringBuilder stats = new StringBuilder("§7  Now §6").append(Coins.compact(last.avg))
            .append(" §8| §7average §f").append(Coins.compact(sum / points.size()))
            .append(" §8| §7lowest §c").append(Coins.compact(low))
            .append(" §8| §7highest §a").append(Coins.compact(high))
            .append(" §8| ").append(change >= 0 ? "§a+" : "§c").append(Coins.decimal(change, 1)).append('%');
        double perDay = salesPerDay(points);
        if (!bazaar && perDay > 0) {
            stats.append(" §8| §7").append(Coins.decimal(perDay, perDay < 10 ? 1 : 0)).append(" sold a day");
        }
        out.add(Msg.plain(stats.toString()));
        return out;
    }

    /** Averages consecutive points so at most {@code max} remain. */
    static List<Point> buckets(List<Point> points, int max) {
        if (points.size() <= max) {
            return points;
        }
        List<Point> out = new ArrayList<Point>();
        double size = points.size() / (double) max;
        for (int b = 0; b < max; b++) {
            int from = (int) Math.round(b * size);
            int to = (int) Math.round((b + 1) * size);
            double sum = 0;
            double min = Double.MAX_VALUE;
            double maxPrice = 0;
            double volume = 0;
            boolean hasVolume = false;
            for (int i = from; i < to; i++) {
                Point p = points.get(i);
                sum += p.avg;
                min = Math.min(min, p.min);
                maxPrice = Math.max(maxPrice, p.max);
                if (p.volume >= 0) {
                    volume += p.volume;
                    hasVolume = true;
                }
            }
            int n = Math.max(1, to - from);
            out.add(new Point(points.get(from).time, sum / n, min, maxPrice, hasVolume ? volume : Double.NaN));
        }
        return out;
    }

    /** Auction sales per day over the span, NaN when the history has no volumes. */
    static double salesPerDay(List<Point> points) {
        double total = 0;
        boolean any = false;
        for (Point p : points) {
            if (p.volume >= 0) {
                total += p.volume;
                any = true;
            }
        }
        if (!any || points.size() < 2) {
            return Double.NaN;
        }
        double days = (points.get(points.size() - 1).time - points.get(0).time) / (double) DAY_MS;
        return days >= 1 ? total / days : Double.NaN;
    }

    private static String rangeName(String range) {
        switch (range) {
            case "day":
                return "last 24 hours";
            case "week":
                return "last 7 days";
            case "month":
                return "last 30 days";
            default:
                return "all time";
        }
    }

    private static List<Point> sorted(List<Point> points) {
        Collections.sort(points, new Comparator<Point>() {
            @Override
            public int compare(Point a, Point b) {
                return Long.compare(a.time, b.time);
            }
        });
        return points;
    }

    private Object get(String path) throws IOException {
        String base = baseUrl.get();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpFetcher.Response response = http.get(base + path);
        try {
            if (response.status == 404 || response.status == 400 || response.status == 204) {
                return Collections.emptyList();
            }
            if (response.status >= 400) {
                throw new ApiException("SkyCofl: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            return JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8));
        } finally {
            response.close();
        }
    }
}
