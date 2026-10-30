package dev.flipscout.core;

import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.JsonTree;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Who plays on your profiles, learned from the auction house: every auction lists the members of
 * the profile it was created on, so the auctions of your own profiles (and their members) show up
 * as soon as anyone on them sells something. Nothing to configure. Thread-safe.
 */
final class Coop {
    /** A name that could not be looked up is tried again after this long. */
    private static final long RETRY_MS = 10 * 60_000L;

    private final Platform platform;
    private final LongSupplier clock;
    /** Other members of your profiles (UUIDs without dashes, lowercase). */
    private final Set<String> members = ConcurrentHashMap.newKeySet();
    private final Set<String> profiles = ConcurrentHashMap.newKeySet();
    private final Map<String, String> names = new ConcurrentHashMap<String, String>();
    /** When a name lookup last failed, by UUID. */
    private final Map<String, Long> failedLookups = new ConcurrentHashMap<String, Long>();

    Coop(Platform platform, LongSupplier clock) {
        this.platform = platform;
        this.clock = clock;
    }

    /** Learns from one auction: {@code coop} lists the members of {@code profileId}. */
    void learn(String profileId, String[] coop) {
        String me = platform.playerUuid();
        if (me == null || coop == null || coop.length < 2) {
            return;
        }
        boolean mine = false;
        for (String uuid : coop) {
            if (me.equals(normalize(uuid))) {
                mine = true;
                break;
            }
        }
        if (!mine) {
            return;
        }
        if (profileId != null) {
            profiles.add(normalize(profileId));
        }
        for (String uuid : coop) {
            String member = normalize(uuid);
            if (member != null && !member.equals(me)) {
                members.add(member);
            }
        }
    }

    /** True for you and the members of your profiles. */
    boolean isUs(String uuid) {
        String id = normalize(uuid);
        if (id == null) {
            return false;
        }
        return id.equals(platform.playerUuid()) || members.contains(id);
    }

    boolean isMember(String uuid) {
        String id = normalize(uuid);
        return id != null && members.contains(id);
    }

    boolean isOurProfile(String profileId) {
        String id = normalize(profileId);
        return id != null && profiles.contains(id);
    }

    /** The member's name once looked up, else null. */
    String nameOf(String uuid) {
        String id = normalize(uuid);
        return id == null ? null : names.get(id);
    }

    /** Members found so far, by name where known. */
    List<String> memberNames() {
        List<String> out = new ArrayList<String>();
        for (String member : members) {
            String name = names.get(member);
            out.add(name != null ? name : member.substring(0, 8) + "…");
        }
        return out;
    }

    /** True when some member's name still has to be looked up (and may be tried now). */
    boolean namesMissing() {
        long now = clock.getAsLong();
        for (String member : members) {
            if (due(member, now)) {
                return true;
            }
        }
        return false;
    }

    /** Looks up the names of the members from Mojang (slow: run it in the background). */
    void lookUpNames(HttpFetcher http) {
        long now = clock.getAsLong();
        for (String member : members) {
            if (!due(member, now)) {
                continue;
            }
            try {
                String name = fetchName(http, member);
                if (name != null) {
                    names.put(member, name);
                    failedLookups.remove(member);
                    continue;
                }
            } catch (IOException | RuntimeException e) {
                Log.debug("Nome del membro della coop " + member + " non trovato: " + e);
            }
            failedLookups.put(member, now);
        }
    }

    private boolean due(String member, long now) {
        Long failed = failedLookups.get(member);
        return !names.containsKey(member) && (failed == null || now - failed >= RETRY_MS);
    }

    private static String fetchName(HttpFetcher http, String uuid) throws IOException {
        HttpFetcher.Response response = http.get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
        try {
            if (response.status != 200) {
                return null;
            }
            Map<String, Object> profile = JsonTree.obj(
                JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8)));
            return JsonTree.str(profile.get("name"));
        } finally {
            response.close();
        }
    }

    static String normalize(String uuid) {
        return uuid == null || uuid.isEmpty() ? null : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }
}
