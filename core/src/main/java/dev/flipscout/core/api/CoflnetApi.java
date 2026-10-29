package dev.flipscout.core.api;

import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.JsonTree;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/** Public price summary of sky.coflnet.com (sales of roughly the last day). */
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
        HttpFetcher.Response response = http.get(base + "/api/item/price/" + URLEncoder.encode(tag, "UTF-8"));
        try {
            if (response.status == 404 || response.status == 204) {
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
}
