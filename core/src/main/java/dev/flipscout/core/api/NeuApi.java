package dev.flipscout.core.api;

import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.JsonTree;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Price data published by NotEnoughUpdates on moulberry.codes (keys are NEU internal names). */
public final class NeuApi {
    /** Average auction price over 3 days. */
    public static final class Average {
        /** Average of "clean" sales when available, else of all sales. */
        public final double price;
        /** Sales per day, NaN when unknown. */
        public final double salesPerDay;

        public Average(double price, double salesPerDay) {
            this.price = price;
            this.salesPerDay = salesPerDay;
        }
    }

    private final HttpFetcher http;
    private final Supplier<String> baseUrl;

    public NeuApi(HttpFetcher http, Supplier<String> baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public Map<String, Double> fetchLowestBins() throws IOException {
        Map<String, Object> root = JsonTree.obj(fetch("/lowestbin.json"));
        Map<String, Double> out = new HashMap<String, Double>(root.size() * 2);
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            double value = JsonTree.num(entry.getValue(), Double.NaN);
            if (value > 0) {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    public Map<String, Average> fetchAverages() throws IOException {
        Map<String, Object> root = JsonTree.obj(fetch("/auction_averages/3day.json"));
        Map<String, Average> out = new HashMap<String, Average>(root.size() * 2);
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number) {
                out.put(entry.getKey(), new Average(((Number) value).doubleValue(), Double.NaN));
                continue;
            }
            Map<String, Object> fields = JsonTree.obj(value);
            double clean = JsonTree.num(fields.get("clean_price"), Double.NaN);
            double all = JsonTree.num(fields.get("price"), Double.NaN);
            double price = clean > 0 ? clean : all;
            if (!(price > 0)) {
                continue;
            }
            double sales = JsonTree.num(fields.get("sales"), Double.NaN);
            if (!(sales >= 0)) {
                sales = JsonTree.num(fields.get("clean_sales"), Double.NaN);
            }
            out.put(entry.getKey(), new Average(price, sales >= 0 ? sales / 3.0 : Double.NaN));
        }
        return out;
    }

    private Object fetch(String path) throws IOException {
        String base = HypixelApi.stripSlash(baseUrl.get());
        HttpFetcher.Response response = http.get(base + path);
        try {
            if (response.status >= 400) {
                throw new ApiException("NEU: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            return JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8));
        } finally {
            response.close();
        }
    }
}
