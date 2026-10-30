package dev.flipscout.core;

import dev.flipscout.core.http.HttpFetcher;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** Responses dropped before the end of the body. */
    public final AtomicInteger aborts = new AtomicInteger();
    private final Runnable abort = new Runnable() {
        @Override
        public void run() {
            aborts.incrementAndGet();
        }
    };

    public FakeFetcher put(String url, String body) {
        routes.put(url, new Route(200, body));
        return this;
    }

    public FakeFetcher put(String url, int status, String body) {
        routes.put(url, new Route(status, body));
        return this;
    }

    public synchronized int count(String url) {
        int n = 0;
        for (String request : requests) {
            if (request.equals(url)) {
                n++;
            }
        }
        return n;
    }

    /** POST requests received: url and body. */
    public final List<String[]> posts = new ArrayList<String[]>();
    /** Status answered to POST requests, one per request in order; 204 once they run out. */
    public final List<Integer> postStatuses = new ArrayList<Integer>();

    @Override
    public synchronized Response post(String url, String contentType, byte[] body) {
        posts.add(new String[] {url, new String(body, StandardCharsets.UTF_8)});
        int status = postStatuses.isEmpty() ? 204 : postStatuses.remove(0);
        return new Response(status, new ByteArrayInputStream(new byte[0]), serverDate, status == 429 ? 10 : 0);
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
            serverDate, 0, abort);
    }
}
