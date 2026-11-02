package dev.flipscout.core.extra;

import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.Msg;
import dev.flipscout.core.json.JsonTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MayorsTest {
    private static final String COFL = "https://sky.coflnet.com";
    private static final String ELECTION = "https://api.hypixel.net/v2/resources/skyblock/election";
    private static final DateTimeFormatter COFL_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss", Locale.ROOT)
        .withZone(ZoneOffset.UTC);
    /** Midnight UTC, so daily prices line up with whole days. */
    private static final long NOW = Instant.parse("2027-01-15T00:00:00Z").toEpochMilli();

    @TempDir
    File dir;

    private final FakeMarket market = new FakeMarket();
    private final FakeFetcher http = new FakeFetcher();
    private final AtomicLong clock = new AtomicLong(NOW);
    private List<String> auctionItems = new ArrayList<String>();
    private Mayors mayors;

    @BeforeEach
    void setUp() {
        mayors = newMayors();
    }

    private Mayors newMayors() {
        return new Mayors(market.config, http, market, clock::get, new File(dir, "mayor.json"), () -> auctionItems,
            millis -> true);
    }

    /** Terms of 124 hours over the last year: Paul every fourth term (three weeks apart), Cole in between. */
    private static List<long[]> terms() {
        List<long[]> out = new ArrayList<long[]>();
        long start = NOW - 300 * Mayors.DAY_MS;
        int i = 0;
        while (start + Mayors.YEAR_MS < NOW) {
            out.add(new long[] {start, start + Mayors.YEAR_MS, i++ % 4 == 0 ? 0 : 1});
            start += Mayors.YEAR_MS;
        }
        return out;
    }

    private static String periodsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (long[] t : terms()) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            boolean paul = t[2] == 0;
            sb.append("{\"start\":\"").append(COFL_DATE.format(Instant.ofEpochMilli(t[0]))).append(" +00:00\",\"end\":\"")
                .append(COFL_DATE.format(Instant.ofEpochMilli(t[1]))).append("\",\"winner\":{\"key\":\"")
                .append(paul ? "dungeons" : "mining").append("\",\"name\":\"").append(paul ? "Paul" : "Cole").append("\"}}");
        }
        return sb.append(']').toString();
    }

    /** Daily prices: 100, or {@code paulPrice} on days when Paul is mayor. */
    private static String historyJson(double paulPrice, boolean bazaar) {
        StringBuilder sb = new StringBuilder("[");
        List<long[]> terms = terms();
        for (long day = NOW - 320 * Mayors.DAY_MS; day < NOW; day += Mayors.DAY_MS) {
            double price = 100;
            for (long[] t : terms) {
                if (t[2] == 0 && day >= t[0] && day < t[1]) {
                    price = paulPrice;
                }
            }
            if (sb.length() > 1) {
                sb.append(',');
            }
            String time = Instant.ofEpochMilli(day).toString().replace("Z", "");
            sb.append(bazaar ? "{\"buy\":" + (price + 1) + ",\"sell\":" + (price - 1) + ",\"timestamp\":\"" + time + "\"}"
                : "{\"avg\":" + price + ",\"volume\":5,\"time\":\"" + time + "\"}");
        }
        return sb.append(']').toString();
    }

    private void serveAnalysis() {
        String from = Instant.ofEpochMilli(NOW - Mayors.HISTORY_MS).toString();
        String to = Instant.ofEpochMilli(NOW).toString();
        http.put(COFL + "/api/mayor?from=" + from + "&to=" + to, periodsJson());
        http.put(COFL + "/api/bazaar/ENCHANTED_DIAMOND/history?start=" + from + "&end=" + to, historyJson(120, true));
        http.put(COFL + "/api/item/price/HYPERION/history/full", historyJson(80, false));
        market.product("ENCHANTED_DIAMOND", 1300, 1250);
        market.bazaar.get("ENCHANTED_DIAMOND").sellMovingWeek = 1_000_000;
        market.product("CHEAP_THING", 0, 0);
        auctionItems = new ArrayList<String>(Arrays.asList("HYPERION", "ENCHANTED_DIAMOND"));
    }

    private void serveElection(int paulVotes, int coleVotes) {
        http.put(ELECTION, "{\"success\":true,\"mayor\":{\"key\":\"slayer\",\"name\":\"Aatrox\",\"perks\":[{\"name\":"
            + "\"Pathfinder\"},{\"name\":\"Slayer XP Buff\"}],\"minister\":{\"key\":\"events\",\"name\":\"Foxy\",\"perk\":"
            + "{\"name\":\"Extra Event\"}}},\"current\":{\"year\":523,\"candidates\":[{\"key\":\"mining\",\"name\":\"Cole\","
            + "\"votes\":" + coleVotes + "},{\"key\":\"dungeons\",\"name\":\"Paul\",\"votes\":" + paulVotes + ",\"perks\":[{\"name\":"
            + "\"EZPZ\"}]}]}}");
    }

    @Test
    void datesInBothFormatsAreRead() {
        assertEquals(Instant.parse("2025-12-30T03:15:00Z").toEpochMilli(), Mayors.parseCoflDate("12/30/2025 03:15:00 +00:00"));
        assertEquals(Instant.parse("2026-01-04T07:15:00Z").toEpochMilli(), Mayors.parseCoflDate("01/04/2026 07:15:00"));
        assertEquals(Instant.parse("2026-10-31T00:00:00Z").toEpochMilli(), Mayors.parseIso("2026-10-31T00:00:00"));
        assertEquals(Instant.parse("2026-10-31T02:00:00Z").toEpochMilli(), Mayors.parseIso("2026-10-31T02:00:00.123Z"));
        assertEquals(0, Mayors.parseCoflDate("soon"));
        assertEquals(0, Mayors.parseIso(null));
    }

    @Test
    void theElectionNamesTheMayorAndTheFavourite() throws IOException {
        serveElection(5000, 2000);
        mayors.refreshElection();
        Mayors.Election e = mayors.election();
        assertEquals("Aatrox", e.mayorName);
        assertEquals("slayer", e.mayorKey);
        assertEquals(Arrays.asList("Pathfinder", "Slayer XP Buff"), e.mayorPerks);
        assertEquals("Foxy", e.ministerName);
        assertEquals("Extra Event", e.ministerPerk);
        assertEquals(523, e.year);
        assertEquals("Paul", e.favourite().name);
        assertEquals(7000, e.totalVotes());

        Mayors.Election closed = Mayors.parseElection(JsonTree.obj(JsonTree.parseQuiet("{\"mayor\":{\"key\":\"pets\",\"name\":\"Diana\"}}")));
        assertEquals(0, closed.year);
        assertNull(closed.favourite());
    }

    @Test
    void termsAreComparedWithTheDaysAroundThem() throws IOException {
        List<Mayors.Period> periods = Mayors.parsePeriods(JsonTree.arr(JsonTree.parse(periodsJson())));
        assertTrue(periods.size() > 50, String.valueOf(periods.size()));
        List<Mayors.Point> points = Mayors.points(JsonTree.arr(JsonTree.parse(historyJson(120, false))), "time", NOW);
        Map<String, List<Double>> changes = Mayors.termChanges(points, periods);
        double paul = Mayors.median(changes.get("dungeons"));
        double cole = Mayors.median(changes.get("mining"));
        assertTrue(paul > 0.15, String.valueOf(paul));
        // Cole's terms sit between Paul's: about the same price as the days around them.
        assertTrue(Math.abs(cole) < 0.05, String.valueOf(cole));

        Mayors.Effect effect = Mayors.summarize("X", "X", false, changes.get("dungeons"));
        assertEquals(changes.get("dungeons").size(), effect.terms);
        assertTrue(effect.agreeing >= effect.terms - 1);
        assertNull(Mayors.summarize("X", "X", false, Collections.singletonList(0.3)));
        assertEquals(2.5, Mayors.median(Arrays.asList(4.0, 1.0, 2.0, 3.0)), 1e-9);
    }

    @Test
    void theAnalysisFindsWhatMovesUnderEachMayorAndIsKept() throws IOException {
        serveAnalysis();
        serveElection(5000, 2000);
        assertTrue(mayors.due(NOW));
        mayors.update();
        assertEquals(2, mayors.analyzedItems());
        assertTrue(mayors.knows("dungeons"));

        List<Mayors.Effect> up = mayors.movers("dungeons", true, 5);
        assertEquals(1, up.size());
        assertEquals("ENCHANTED_DIAMOND", up.get(0).id);
        assertTrue(up.get(0).bazaar);
        List<Mayors.Effect> down = mayors.movers("dungeons", false, 5);
        assertEquals("HYPERION", down.get(0).id);
        assertFalse(down.get(0).bazaar);
        // Nothing to do again for a few days.
        assertFalse(mayors.due(NOW + 60_000L));

        // Once per election, and only with a clear favourite.
        assertEquals("Paul", mayors.alertFavourite().name);
        assertNull(mayors.alertFavourite());

        // Saved: a restart reads it back instead of downloading it all again.
        Mayors restarted = newMayors();
        restarted.load();
        assertEquals(2, restarted.analyzedItems());
        assertEquals("ENCHANTED_DIAMOND", restarted.movers("dungeons", true, 5).get(0).id);
        assertTrue(restarted.nextMayorAt(NOW) > NOW);
        serveElection(5000, 2000);
        restarted.refreshElection();
        assertNull(restarted.alertFavourite());
    }

    @Test
    void noAlertWithoutAClearFavourite() throws IOException {
        serveAnalysis();
        serveElection(5000, 4900);
        mayors.update();
        assertNull(mayors.alertFavourite());
        market.config.mayorNotify = false;
        serveElection(5000, 1000);
        mayors.refreshElection();
        assertNull(mayors.alertFavourite());
    }

    @Test
    void theViewShowsTheElectionAndTheMovers() throws IOException {
        serveAnalysis();
        serveElection(5000, 2000);
        mayors.update();
        StringBuilder text = new StringBuilder();
        for (Msg line : MayorText.view(mayors, market, NOW)) {
            text.append(line.toPlainString()).append('\n');
        }
        String all = text.toString();
        assertTrue(all.contains("Mayor: Aatrox (Pathfinder, Slayer XP Buff) + minister Foxy (Extra Event)"), all);
        assertTrue(all.contains("Election (year 523): ▸ Paul 71.4% Cole 28.6%"), all);
        assertTrue(all.contains("If Paul wins, prices usually move like this:"), all);
        assertTrue(all.contains("▲ Enchanted Diamond +"), all);
        assertTrue(all.contains("▼ Hyperion -"), all);
        assertTrue(all.contains("Under Aatrox (now):"), all);
        assertTrue(all.contains("No term of Aatrox in the last year"), all);

        Msg alert = MayorText.alert(mayors, market, mayors.election().favourite());
        assertTrue(alert.toPlainString().contains("Election: Paul leads with 71%. Under Paul usually: ▲ Enchanted Diamond +"),
            alert.toPlainString());
        assertEquals("/bz Enchanted Diamond", alert.parts().get(1).command);
    }

    @Test
    void candidatesMixTheMostTradedBazaarAndAuctionItems() {
        market.product("A", 10, 9).product("B", 100, 90).product("C", 1, 1);
        market.bazaar.get("A").sellMovingWeek = 1000;
        market.bazaar.get("B").sellMovingWeek = 1000;
        auctionItems = new ArrayList<String>(Arrays.asList("HYPERION", "A", "TERMINATOR"));
        market.config.mayorItems = 4;
        List<String[]> items = newMayors().candidates();
        assertEquals(4, items.size());
        assertEquals("B", items.get(0)[0]);
        assertEquals("A", items.get(1)[0]);
        assertEquals("HYPERION", items.get(2)[0]);
        assertEquals("ah", items.get(2)[1]);
        assertEquals("TERMINATOR", items.get(3)[0]);
    }
}
