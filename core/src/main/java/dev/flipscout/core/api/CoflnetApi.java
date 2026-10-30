package dev.flipscout.core.api;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.Json;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.util.Text;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Public price summaries and item names of sky.coflnet.com. */
public final class CoflnetApi {
    public static final class Price {
        public final double median;
        public final double volume;

        public Price(double median, double volume) {
            this.median = median;
            this.volume = volume;
        }
    }

    private final HttpFetcher http;
    private final Supplier<String> baseUrl;

    public CoflnetApi(HttpFetcher http, Supplier<String> baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    /** Returns null when Coflnet has no data for the tag. */
    public Price fetchPrice(String tag) throws IOException {
        String base = HypixelApi.stripSlash(baseUrl.get());
        HttpFetcher.Response response = http.get(base + pricePath(tag));
        try {
            // 400 is what an unknown tag or filter gets: no data, not an outage.
            if (response.status == 404 || response.status == 400 || response.status == 204) {
                return null;
            }
            if (response.status >= 400) {
                throw new ApiException("Coflnet: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            Map<String, Object> fields = JsonTree.obj(
                JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8)));
            double median = JsonTree.num(JsonTree.field(fields, "median", "med"), Double.NaN);
            double volume = JsonTree.num(JsonTree.field(fields, "volume"), Double.NaN);
            if (!(median > 0)) {
                return null;
            }
            return new Price(median, volume >= 0 ? volume : Double.NaN);
        } finally {
            response.close();
        }
    }

    /** "/api/item/price/TAG", plus the filters of a tag written as "TAG?Name=value&Name=value". */
    static String pricePath(String tag) throws IOException {
        int question = tag.indexOf('?');
        StringBuilder path = new StringBuilder("/api/item/price/")
            .append(URLEncoder.encode(question < 0 ? tag : tag.substring(0, question), "UTF-8"));
        if (question >= 0) {
            char separator = '?';
            for (String filter : tag.substring(question + 1).split("&")) {
                int equals = filter.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                path.append(separator).append(URLEncoder.encode(filter.substring(0, equals), "UTF-8"))
                    .append('=').append(URLEncoder.encode(filter.substring(equals + 1), "UTF-8"));
                separator = '&';
            }
        }
        return path.toString();
    }

    /**
     * Names of the items Coflnet knows, by tag, for the bazaar products missing from Hypixel's item
     * list (shards, enchantments...). Entries whose name is just the tag are left out.
     */
    public Map<String, String> fetchItemNames() throws IOException {
        String base = HypixelApi.stripSlash(baseUrl.get());
        HttpFetcher.Response response = http.get(base + "/api/items");
        try {
            if (response.status >= 400) {
                throw new ApiException("Coflnet: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            JsonReader in = Json.reader(response.body);
            Map<String, String> names = new HashMap<String, String>();
            in.beginArray();
            while (in.hasNext()) {
                if (in.peek() != JsonToken.BEGIN_OBJECT) {
                    in.skipValue();
                    continue;
                }
                String tag = null;
                String name = null;
                in.beginObject();
                while (in.hasNext()) {
                    String field = in.nextName();
                    if ("tag".equals(field)) {
                        tag = Json.readString(in);
                    } else if ("name".equals(field)) {
                        name = Json.readString(in);
                    } else {
                        in.skipValue();
                    }
                }
                in.endObject();
                name = Text.stripCodes(name);
                if (tag != null && name != null && !name.trim().isEmpty() && !name.equals(tag)) {
                    names.put(tag, name.trim());
                }
            }
            in.endArray();
            return names;
        } finally {
            response.close();
        }
    }
}
