package dev.flipscout.core;

import dev.flipscout.core.http.HttpFetcher;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Serves canned responses by URL; anything else is a 404. */
public final class FakeFetcher implements HttpFetcher {
    private static final class Route {
        final int status;
        final String body;

        Route(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final Map<String, Route> routes = new HashMap<String, Route>();
    public final List<String> requests = new ArrayList<String>();
    public long serverDate;

    public FakeFetcher put(String url, String body) {
        routes.put(url, new Route(200, body));
        return this;
    }

    public FakeFetcher put(String url, int status, String body) {
        routes.put(url, new Route(status, body));
        return this;
    }

    public int count(String url) {
        int n = 0;
        for (String request : requests) {
            if (request.equals(url)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized Response get(String url) {
        requests.add(url);
        Route route = routes.get(url);
        if (route == null) {
            return new Response(404, new ByteArrayInputStream("{\"success\":false,\"cause\":\"Not found\"}"
                .getBytes(StandardCharsets.UTF_8)), serverDate, 0);
        }
        return new Response(route.status, new ByteArrayInputStream(route.body.getBytes(StandardCharsets.UTF_8)),
            serverDate, 0);
    }
}
