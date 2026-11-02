package dev.flipscout.core;

import com.google.gson.stream.JsonWriter;
import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.profit.ProfitTracker;
import dev.flipscout.core.util.Text;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Writes the closed flips and notifications as CSV, and the bazaar as JSON, for spreadsheets and scripts. */
final class Exporter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final int BOOK_DEPTH = 5;

    private final File dir;
    private final ZoneId zone;

    Exporter(File dir, ZoneId zone) {
        this.dir = dir;
        this.zone = zone;
    }

    File flips(List<ProfitTracker.Flip> flips, long now) throws IOException {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] {"sold_at", "bought_at", "item", "amount", "cost", "revenue_after_taxes", "profit", "held_minutes"});
        for (ProfitTracker.Flip f : flips) {
            rows.add(new String[] {time(f.soldAt), time(f.boughtAt), f.item, String.valueOf(f.amount), number(f.cost),
                number(f.revenue), number(f.profit), f.boughtAt > 0 ? String.valueOf((f.soldAt - f.boughtAt) / 60_000L) : ""});
        }
        return csv("flips", rows, now);
    }

    File notifications(List<Engine.Notification> notifications, long now) throws IOException {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] {"time", "message", "command", "item", "price", "expected_resale", "expected_profit", "sold",
            "bought_by"});
        for (Engine.Notification n : notifications) {
            String text = n.message.toPlainString();
            if (text.startsWith(Text.stripCodes(Msg.PREFIX))) {
                text = text.substring(Text.stripCodes(Msg.PREFIX).length());
            }
            rows.add(new String[] {time(n.time), text, n.command == null ? "" : n.command, n.itemName == null ? "" : n.itemName,
                n.price > 0 ? String.valueOf(n.price) : "", n.resale > 0 ? number(n.resale) : "",
                n.auctionUuid != null ? number(n.profit) : "", n.soldAt() > 0 ? "yes" : "", n.buyer() == null ? ""
                : n.buyer().name().toLowerCase(Locale.ROOT)});
        }
        return csv("notifications", rows, now);
    }

    File bazaar(BazaarSnapshot snapshot, ItemCatalog items, long now) throws IOException {
        File file = file("bazaar", "json", now);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            JsonWriter w = new JsonWriter(out);
            w.setIndent("  ");
            w.beginObject();
            w.name("exportedAt").value(Instant.ofEpochMilli(now).toString());
            w.name("lastUpdated").value(Instant.ofEpochMilli(snapshot.lastUpdated).toString());
            w.name("products").beginArray();
            for (BazaarProduct p : new TreeMap<String, BazaarProduct>(snapshot.products).values()) {
                w.beginObject();
                w.name("id").value(p.id);
                w.name("name").value(items.displayName(p.id));
                price(w, "instantBuy", p.instaBuy());
                price(w, "instantSell", p.instaSell());
                w.name("buyMovingWeek").value(p.buyMovingWeek);
                w.name("sellMovingWeek").value(p.sellMovingWeek);
                book(w, "sellOffers", p.offerPrices, p.offerAmounts);
                book(w, "buyOrders", p.orderPrices, p.orderAmounts);
                w.endObject();
            }
            w.endArray();
            w.endObject();
            w.flush();
        }
        return file;
    }

    private static void price(JsonWriter w, String name, double price) throws IOException {
        w.name(name);
        if (price > 0) {
            w.value(price);
        } else {
            w.nullValue();
        }
    }

    private static void book(JsonWriter w, String name, double[] prices, double[] amounts) throws IOException {
        w.name(name).beginArray();
        for (int i = 0; i < Math.min(BOOK_DEPTH, prices.length); i++) {
            w.beginObject().name("price").value(prices[i]).name("amount").value(amounts[i]).endObject();
        }
        w.endArray();
    }

    private File csv(String name, List<String[]> rows, long now) throws IOException {
        File file = file(name, "csv", now);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            // A byte order mark, so spreadsheet apps read the file as UTF-8.
            out.write('﻿');
            for (String[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) {
                        out.write(',');
                    }
                    out.write(cell(row[i]));
                }
                out.write("\r\n");
            }
        }
        return file;
    }

    private File file(String name, String extension, long now) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        return new File(dir, name + "-" + STAMP.format(Instant.ofEpochMilli(now).atZone(zone)) + "." + extension);
    }

    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = Text.stripCodes(value);
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private String time(long millis) {
        return millis > 0 ? TIME.format(Instant.ofEpochMilli(millis).atZone(zone)) : "";
    }

    private static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    /** For tests: the columns of a CSV line written by this class. */
    static Map<String, Integer> columns(String header) {
        Map<String, Integer> out = new TreeMap<String, Integer>();
        String[] names = header.replace("﻿", "").split(",");
        for (int i = 0; i < names.length; i++) {
            out.put(names[i].trim(), i);
        }
        return out;
    }
}
