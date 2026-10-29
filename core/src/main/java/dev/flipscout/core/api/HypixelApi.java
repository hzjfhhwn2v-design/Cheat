package dev.flipscout.core.api;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client for the public (keyless) SkyBlock endpoints of api.hypixel.net. Responses are parsed in
 * streaming mode: auction pages are a few MB each and most fields are not needed.
 */
public final class HypixelApi {
    private final HttpFetcher http;
    private final Supplier<String> baseUrl;

    public HypixelApi(HttpFetcher http, Supplier<String> baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public AuctionPage fetchAuctionPage(int page) throws IOException {
        HttpFetcher.Response response = get("/v2/skyblock/auctions?page=" + page);
        try {
            JsonReader in = Json.reader(response.body);
            AuctionPage result = new AuctionPage();
            result.page = page;
            result.serverDate = response.serverDate;
            boolean success = true;
            String cause = null;
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "success":
                        success = Json.readBoolean(in, true);
                        break;
                    case "cause":
                        cause = Json.readString(in);
                        break;
                    case "page":
                        result.page = (int) Json.readLong(in, page);
                        break;
                    case "totalPages":
                        result.totalPages = (int) Json.readLong(in, 0);
                        break;
                    case "totalAuctions":
                        result.totalAuctions = (int) Json.readLong(in, 0);
                        break;
                    case "lastUpdated":
                        result.lastUpdated = Json.readLong(in, 0);
                        break;
                    case "auctions":
                        if (in.peek() != JsonToken.BEGIN_ARRAY) {
                            in.skipValue();
                            break;
                        }
                        in.beginArray();
                        while (in.hasNext()) {
                            Auction auction = readAuction(in);
                            if (auction != null) {
                                result.auctions.add(auction);
                            }
                        }
                        in.endArray();
                        break;
                    default:
                        in.skipValue();
                }
            }
            in.endObject();
            if (!success) {
                throw new ApiException("Hypixel: " + (cause != null ? cause : "risposta non valida"), response.status, 0);
            }
            return result;
        } finally {
            response.close();
        }
    }

    private static Auction readAuction(JsonReader in) throws IOException {
        if (in.peek() != JsonToken.BEGIN_OBJECT) {
            in.skipValue();
            return null;
        }
        Auction a = new Auction();
        in.beginObject();
        while (in.hasNext()) {
            String name = in.nextName();
            switch (name) {
                case "uuid":
                    a.uuid = Json.readString(in);
                    break;
                case "auctioneer":
                    a.seller = Json.readString(in);
                    break;
                case "item_name":
                    a.itemName = Json.readString(in);
                    break;
                case "tier":
                    a.tier = Json.readString(in);
                    break;
                case "category":
                    a.category = Json.readString(in);
                    break;
                case "start":
                    a.start = Json.readLong(in, 0);
                    break;
                case "end":
                    a.end = Json.readLong(in, 0);
                    break;
                case "starting_bid":
                    a.startingBid = Json.readLong(in, 0);
                    break;
                case "highest_bid_amount":
                    a.highestBid = Json.readLong(in, 0);
                    break;
                case "bin":
                    a.bin = Json.readBoolean(in, false);
                    break;
                case "item_bytes":
                    a.itemBytes = readItemBytes(in);
                    break;
                default:
                    in.skipValue();
            }
        }
        in.endObject();
        return a.uuid == null ? null : a;
    }

    public EndedPage fetchEnded() throws IOException {
        HttpFetcher.Response response = get("/v2/skyblock/auctions_ended");
        try {
            JsonReader in = Json.reader(response.body);
            EndedPage result = new EndedPage();
            result.serverDate = response.serverDate;
            boolean success = true;
            String cause = null;
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                if ("success".equals(name)) {
                    success = Json.readBoolean(in, true);
                } else if ("cause".equals(name)) {
                    cause = Json.readString(in);
                } else if ("lastUpdated".equals(name)) {
                    result.lastUpdated = Json.readLong(in, 0);
                } else if ("auctions".equals(name) && in.peek() == JsonToken.BEGIN_ARRAY) {
                    in.beginArray();
                    while (in.hasNext()) {
                        EndedAuction ended = readEnded(in);
                        if (ended != null) {
                            result.auctions.add(ended);
                        }
                    }
                    in.endArray();
                } else {
                    in.skipValue();
                }
            }
            in.endObject();
            if (!success) {
                throw new ApiException("Hypixel: " + (cause != null ? cause : "risposta non valida"), response.status, 0);
            }
            return result;
        } finally {
            response.close();
        }
    }

    private static EndedAuction readEnded(JsonReader in) throws IOException {
        if (in.peek() != JsonToken.BEGIN_OBJECT) {
            in.skipValue();
            return null;
        }
        EndedAuction e = new EndedAuction();
        in.beginObject();
        while (in.hasNext()) {
            String name = in.nextName();
            switch (name) {
                case "auction_id":
                    e.auctionId = Json.readString(in);
                    break;
                case "seller":
                    e.seller = Json.readString(in);
                    break;
                case "buyer":
                    e.buyer = Json.readString(in);
                    break;
                case "timestamp":
                    e.timestamp = Json.readLong(in, 0);
                    break;
                case "price":
                    e.price = Json.readLong(in, 0);
                    break;
                case "bin":
                    e.bin = Json.readBoolean(in, false);
                    break;
                case "item_bytes":
                    e.itemBytes = readItemBytes(in);
                    break;
                default:
                    in.skipValue();
            }
        }
        in.endObject();
        return e.auctionId == null ? null : e;
    }

    /** item_bytes is a plain string here, but some endpoints wrap it as {"type":0,"data":"..."}. */
    private static String readItemBytes(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.BEGIN_OBJECT) {
            String data = null;
            in.beginObject();
            while (in.hasNext()) {
                if ("data".equals(in.nextName())) {
                    data = Json.readString(in);
                } else {
                    in.skipValue();
                }
            }
            in.endObject();
            return data;
        }
        return Json.readString(in);
    }

    public BazaarSnapshot fetchBazaar() throws IOException {
        HttpFetcher.Response response = get("/v2/skyblock/bazaar");
        try {
            JsonReader in = Json.reader(response.body);
            Map<String, BazaarProduct> products = new HashMap<String, BazaarProduct>();
            long lastUpdated = 0;
            boolean success = true;
            String cause = null;
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                if ("success".equals(name)) {
                    success = Json.readBoolean(in, true);
                } else if ("cause".equals(name)) {
                    cause = Json.readString(in);
                } else if ("lastUpdated".equals(name)) {
                    lastUpdated = Json.readLong(in, 0);
                } else if ("products".equals(name) && in.peek() == JsonToken.BEGIN_OBJECT) {
                    in.beginObject();
                    while (in.hasNext()) {
                        String id = in.nextName();
                        BazaarProduct product = readProduct(in, id);
                        if (product != null) {
                            products.put(id, product);
                        }
                    }
                    in.endObject();
                } else {
                    in.skipValue();
                }
            }
            in.endObject();
            if (!success) {
                throw new ApiException("Hypixel: " + (cause != null ? cause : "risposta non valida"), response.status, 0);
            }
            return new BazaarSnapshot(lastUpdated, products);
        } finally {
            response.close();
        }
    }

    private static BazaarProduct readProduct(JsonReader in, String id) throws IOException {
        if (in.peek() != JsonToken.BEGIN_OBJECT) {
            in.skipValue();
            return null;
        }
        BazaarProduct p = new BazaarProduct(id);
        List<double[]> offers = null;
        List<double[]> orders = null;
        in.beginObject();
        while (in.hasNext()) {
            String name = in.nextName();
            if ("buy_summary".equals(name)) {
                offers = readSummary(in);
            } else if ("sell_summary".equals(name)) {
                orders = readSummary(in);
            } else if ("quick_status".equals(name) && in.peek() == JsonToken.BEGIN_OBJECT) {
                in.beginObject();
                while (in.hasNext()) {
                    String field = in.nextName();
                    if ("buyMovingWeek".equals(field)) {
                        p.buyMovingWeek = Json.readDouble(in, 0);
                    } else if ("sellMovingWeek".equals(field)) {
                        p.sellMovingWeek = Json.readDouble(in, 0);
                    } else {
                        in.skipValue();
                    }
                }
                in.endObject();
            } else {
                in.skipValue();
            }
        }
        in.endObject();
        if (offers != null) {
            p.offerPrices = column(offers, 0);
            p.offerAmounts = column(offers, 1);
        }
        if (orders != null) {
            p.orderPrices = column(orders, 0);
            p.orderAmounts = column(orders, 1);
        }
        p.sortBook();
        return p;
    }

    /** Reads [{amount, pricePerUnit, orders}, ...] into {price, amount} pairs. */
    private static List<double[]> readSummary(JsonReader in) throws IOException {
        List<double[]> rows = new ArrayList<double[]>();
        if (in.peek() != JsonToken.BEGIN_ARRAY) {
            in.skipValue();
            return rows;
        }
        in.beginArray();
        while (in.hasNext()) {
            if (in.peek() != JsonToken.BEGIN_OBJECT) {
                in.skipValue();
                continue;
            }
            double price = Double.NaN;
            double amount = 0;
            in.beginObject();
            while (in.hasNext()) {
                String field = in.nextName();
                if ("pricePerUnit".equals(field)) {
                    price = Json.readDouble(in, Double.NaN);
                } else if ("amount".equals(field)) {
                    amount = Json.readDouble(in, 0);
                } else {
                    in.skipValue();
                }
            }
            in.endObject();
            if (price > 0 && amount > 0) {
                rows.add(new double[] {price, amount});
            }
        }
        in.endArray();
        return rows;
    }

    private static double[] column(List<double[]> rows, int index) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = rows.get(i)[index];
        }
        return out;
    }

    public ItemCatalog fetchItems() throws IOException {
        HttpFetcher.Response response = get("/v2/resources/skyblock/items");
        try {
            JsonReader in = Json.reader(response.body);
            Map<String, ItemCatalog.Info> items = new HashMap<String, ItemCatalog.Info>();
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                if ("items".equals(name) && in.peek() == JsonToken.BEGIN_ARRAY) {
                    in.beginArray();
                    while (in.hasNext()) {
                        ItemCatalog.Info info = readItem(in);
                        if (info != null) {
                            items.put(info.id, info);
                        }
                    }
                    in.endArray();
                } else {
                    in.skipValue();
                }
            }
            in.endObject();
            return new ItemCatalog(items);
        } finally {
            response.close();
        }
    }

    private static ItemCatalog.Info readItem(JsonReader in) throws IOException {
        if (in.peek() != JsonToken.BEGIN_OBJECT) {
            in.skipValue();
            return null;
        }
        String id = null;
        String name = null;
        String tier = null;
        double npc = 0;
        in.beginObject();
        while (in.hasNext()) {
            String field = in.nextName();
            if ("id".equals(field)) {
                id = Json.readString(in);
            } else if ("name".equals(field)) {
                name = Json.readString(in);
            } else if ("tier".equals(field)) {
                tier = Json.readString(in);
            } else if ("npc_sell_price".equals(field)) {
                npc = Json.readDouble(in, 0);
            } else {
                in.skipValue();
            }
        }
        in.endObject();
        return id == null ? null : new ItemCatalog.Info(id, name, tier, npc);
    }

    private HttpFetcher.Response get(String path) throws IOException {
        String base = baseUrl.get();
        HttpFetcher.Response response = http.get(stripSlash(base) + path);
        if (response.status >= 400) {
            response.close();
            String reason = response.status == 429 ? "troppe richieste" : "HTTP " + response.status;
            throw new ApiException("Hypixel: " + reason, response.status, response.retryAfterMs);
        }
        return response;
    }

    static String stripSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
