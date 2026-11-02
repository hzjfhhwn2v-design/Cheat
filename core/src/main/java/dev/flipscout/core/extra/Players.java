package dev.flipscout.core.extra;

import dev.flipscout.core.api.ApiException;
import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.JsonTree;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Player names to UUIDs (Mojang) and the auctions a player created (SkyCofl). */
public final class Players {
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    public static final String MOJANG = "https://api.mojang.com/users/profiles/minecraft/";

    /** An auction a player created, as SkyCofl recorded it. */
    public static final class PastAuction {
        public final String uuid;
        public final String itemName;
        public final String tag;
        public final long startingBid;
        public final long highestBid;
        public final long end;
        public final boolean bin;

        PastAuction(String uuid, String itemName, String tag, long startingBid, long highestBid, long end, boolean bin) {
            this.uuid = uuid;
            this.itemName = itemName;
            this.tag = tag;
            this.startingBid = startingBid;
            this.highestBid = highestBid;
            this.end = end;
            this.bin = bin;
        }

        public boolean sold() {
            return highestBid > 0;
        }
    }

    private final HttpFetcher http;
    private final Supplier<String> coflUrl;
    /** Lowercase name to UUID; "" when there is no such player. */
    private final Map<String, String> uuids = new ConcurrentHashMap<String, String>();
    private final Map<String, String> names = new ConcurrentHashMap<String, String>();

    public Players(HttpFetcher http, Supplier<String> coflUrl) {
        this.http = http;
        this.coflUrl = coflUrl;
    }

    /** The UUID of a player (lowercase, no dashes), null when nobody has that name. */
    public String uuid(String name) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        String cached = uuids.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        HttpFetcher.Response response = http.get(MOJANG + URLEncoder.encode(name, "UTF-8"));
        try {
            if (response.status == 204 || response.status == 404) {
                uuids.put(key, "");
                return null;
            }
            if (response.status >= 400) {
                throw new ApiException("Mojang: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            Map<String, Object> profile = JsonTree.obj(JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8)));
            String id = JsonTree.str(profile.get("id"));
            if (id == null || id.isEmpty()) {
                uuids.put(key, "");
                return null;
            }
            id = id.replace("-", "").toLowerCase(Locale.ROOT);
            uuids.put(key, id);
            String exact = JsonTree.str(profile.get("name"));
            if (exact != null) {
                names.put(id, exact);
            }
            return id;
        } finally {
            response.close();
        }
    }

    /** The name as Mojang spells it, or {@code fallback}. */
    public String name(String uuid, String fallback) {
        String name = names.get(uuid);
        return name != null ? name : fallback;
    }

    /** The last auctions a player created, newest first (SkyCofl keeps ten per page). */
    public List<PastAuction> auctions(String uuid) throws IOException {
        String base = coflUrl.get();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpFetcher.Response response = http.get(base + "/api/player/" + uuid + "/auctions?page=0");
        List<Object> list;
        try {
            if (response.status == 404 || response.status == 204) {
                return new ArrayList<PastAuction>();
            }
            if (response.status >= 400) {
                throw new ApiException("SkyCofl: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            list = JsonTree.arr(JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8)));
        } finally {
            response.close();
        }
        List<PastAuction> out = new ArrayList<PastAuction>();
        for (Object o : list) {
            Map<String, Object> a = JsonTree.obj(o);
            String id = JsonTree.str(a.get("auctionId"));
            if (id == null) {
                continue;
            }
            out.add(new PastAuction(id, JsonTree.str(a.get("itemName")), JsonTree.str(a.get("tag")),
                (long) JsonTree.num(a.get("startingBid"), 0), (long) JsonTree.num(a.get("highestBid"), 0),
                Mayors.parseIso(JsonTree.str(a.get("end"))), Boolean.TRUE.equals(a.get("bin"))));
        }
        return out;
    }
}
