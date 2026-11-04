package dev.flipscout.core.skins;

import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.api.ApiException;
import dev.flipscout.core.extra.Mayors;
import dev.flipscout.core.json.JsonTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinHistoryTest {
    /** 3 November 2026, 12:00 UTC: day 20760. */
    static final long NOW = 1_793_707_200_000L;
    static final int TODAY = 20760;
    static final String COFL = "https://sky.coflnet.com";

    @TempDir
    File dir;

    /** Real days of the Ancient Turtle Skin (SkyCofl, October 2026): coins moved by "selling" it for 250M-510M. */
    static final String TURTLE = "["
        + "{\"min\":100000,\"max\":325350,\"avg\":167558,\"volume\":6,\"time\":\"2026-10-22T00:00:00\"},"
        + "{\"min\":99000,\"max\":580000,\"avg\":200538,\"volume\":26,\"time\":\"2026-10-23T00:00:00\"},"
        + "{\"min\":125000,\"max\":250000000,\"avg\":11757200,\"volume\":22,\"time\":\"2026-10-24T00:00:00\"},"
        + "{\"min\":169999,\"max\":350000,\"avg\":290817,\"volume\":11,\"time\":\"2026-10-25T00:00:00\"},"
        + "{\"min\":149000,\"max\":505000000,\"avg\":20536400,\"volume\":25,\"time\":\"2026-10-26T00:00:00\"},"
        + "{\"min\":200000,\"max\":900000,\"avg\":578846,\"volume\":13,\"time\":\"2026-10-27T00:00:00\"},"
        + "{\"min\":250000,\"max\":700000,\"avg\":429690,\"volume\":17,\"time\":\"2026-10-28T00:00:00\"}]";

    static String day(int day, double avg, double min, double max, int volume) {
        String date = java.time.LocalDate.ofEpochDay(day).toString();
        return "{\"min\":" + min + ",\"max\":" + max + ",\"avg\":" + avg + ",\"volume\":" + volume + ",\"time\":\""
            + date + "T00:00:00\"}";
    }

    static List<Object> json(String text) throws IOException {
        return JsonTree.arr(JsonTree.parse(text));
    }

    @Test
    void aDayIsPricedWithoutItsCheapestAndDearestSale() throws IOException {
        SkinHistory.Series s = SkinHistory.parse("PET_SKIN_TURTLE_ANCIENT", json(TURTLE), NOW);
        assertEquals(7, s.days.size());
        // 24 October: 22 sales averaging 11.76M because one went for 250M; without it and the
        // cheapest one the day is worth 426,670.
        SkinHistory.Day oct24 = s.days.get(2);
        assertEquals(20750, oct24.day);
        assertEquals((11_757_200.0 * 22 - 125_000 - 250_000_000) / 20, oct24.price, 1e-6);
        assertEquals(22, oct24.sales);
        // The median of a week of days then ignores what is left of it.
        double[] typical = SkinStats.typical(s.days);
        assertTrue(typical[6] > 250_000 && typical[6] < 600_000, "typical " + typical[6]);
    }

    @Test
    void twoSalesFarApartDoNotMakeADay() {
        assertTrue(Double.isNaN(SkinHistory.dayPrice(10_000.5, 1, 20_000, 2)));
        assertEquals(15_000, SkinHistory.dayPrice(15_000, 14_000, 16_000, 2), 1e-9);
        assertEquals(500, SkinHistory.dayPrice(500, 500, 500, 1), 1e-9);
        // An average outside the sales it comes from is kept as it is.
        assertEquals(100, SkinHistory.dayPrice(100, 90, 95, 5), 1e-9);
    }

    @Test
    void keepsFourHundredDaysButRemembersTheFirstSale() throws IOException {
        String text = "[" + day(TODAY - 500, 10, 10, 10, 1) + "," + day(TODAY - 10, 20, 20, 20, 1) + ","
            + day(TODAY - 20, 30, 30, 30, 1) + ",{\"avg\":0,\"time\":\"2026-10-01T00:00:00\"},{\"avg\":5}]";
        SkinHistory.Series s = SkinHistory.parse("X", json(text), NOW);
        assertEquals(TODAY - 500, s.firstDay);
        assertEquals(2, s.days.size());
        assertEquals(TODAY - 20, s.days.get(0).day);
        assertEquals(TODAY - 10, s.last().day);
        assertEquals(NOW, s.fetchedAt);
    }

    private SkinHistory history(FakeFetcher http, AtomicLong clock, List<Long> pauses) {
        return new SkinHistory(http, () -> COFL + "/", clock::get, new File(dir, "skins.json.gz"), new Mayors.Pause() {
            @Override
            public boolean pause(long millis) {
                pauses.add(millis);
                return true;
            }
        });
    }

    private static String url(String id) {
        return COFL + "/api/item/price/" + id + "/history/full";
    }

    @Test
    void readsMissingSkinsFirstAFewAtATimeWithPauses() throws IOException {
        FakeFetcher http = new FakeFetcher();
        List<String> wanted = new ArrayList<String>();
        for (int i = 0; i < 25; i++) {
            wanted.add("SKIN_" + i);
            http.put(url("SKIN_" + i), "[" + day(TODAY - 1, 100 + i, 100 + i, 100 + i, 1) + "]");
        }
        AtomicLong clock = new AtomicLong(NOW);
        List<Long> pauses = new ArrayList<Long>();
        SkinHistory h = history(http, clock, pauses);
        assertTrue(h.due(wanted));
        assertEquals(10, h.update(wanted));
        assertEquals(9, pauses.size());
        assertEquals(SkinHistory.REQUEST_GAP_MS, (long) pauses.get(0));
        assertEquals(1, http.count(url("SKIN_0")));
        assertEquals(0, http.count(url("SKIN_24")));
        assertEquals(10, h.fresh(wanted));
        assertEquals(10, h.update(wanted));
        assertEquals(5, h.update(wanted));
        assertFalse(h.due(wanted));
        assertEquals(0, h.update(wanted));
        // A day later they are read again, the oldest first.
        clock.addAndGet(SkinHistory.REFRESH_MS);
        assertTrue(h.due(wanted));
        assertTrue(h.stale("SKIN_3"));
        assertEquals(10, h.update(wanted));
        assertEquals(2, http.count(url("SKIN_0")));
        assertEquals(1, http.count(url("SKIN_24")));
    }

    @Test
    void anUnknownSkinIsNotAskedForAgainForADay() throws IOException {
        FakeFetcher http = new FakeFetcher();
        AtomicLong clock = new AtomicLong(NOW);
        SkinHistory h = history(http, clock, new ArrayList<Long>());
        List<String> wanted = Arrays.asList("NOT_A_SKIN");
        assertEquals(1, h.update(wanted));
        assertNotNull(h.get("NOT_A_SKIN"));
        assertTrue(h.get("NOT_A_SKIN").isEmpty());
        assertFalse(h.due(wanted));
    }

    @Test
    void tooManyRequestsStopsTheReadsForTenMinutes() throws IOException {
        FakeFetcher http = new FakeFetcher();
        http.put(url("A"), 429, "{}");
        AtomicLong clock = new AtomicLong(NOW);
        SkinHistory h = history(http, clock, new ArrayList<Long>());
        List<String> wanted = Arrays.asList("A", "B");
        ApiException e = assertThrows(ApiException.class, () -> h.update(wanted));
        assertEquals(429, e.status);
        assertFalse(h.due(wanted));
        assertEquals(0, h.update(wanted));
        assertEquals(0, http.count(url("B")));
        clock.addAndGet(10 * 60_000L);
        assertTrue(h.due(wanted));
    }

    @Test
    void noAnswerAtAllWaitsAMinute() {
        HttpFetcherThatFails http = new HttpFetcherThatFails();
        AtomicLong clock = new AtomicLong(NOW);
        SkinHistory h = new SkinHistory(http, () -> COFL, clock::get, null, new Mayors.Pause() {
            @Override
            public boolean pause(long millis) {
                return true;
            }
        });
        List<String> wanted = Arrays.asList("A");
        assertThrows(IOException.class, () -> h.update(wanted));
        assertFalse(h.due(wanted));
        clock.addAndGet(60_000L);
        assertTrue(h.due(wanted));
    }

    static final class HttpFetcherThatFails implements dev.flipscout.core.http.HttpFetcher {
        @Override
        public Response get(String url) throws IOException {
            throw new java.net.SocketTimeoutException("Read timed out");
        }
    }

    @Test
    void historiesAndNamesSurviveARestart() throws IOException {
        FakeFetcher http = new FakeFetcher();
        http.put(url("PET_SKIN_TURTLE_ANCIENT"), TURTLE);
        AtomicLong clock = new AtomicLong(NOW);
        SkinHistory h = history(http, clock, new ArrayList<Long>());
        h.learn("PET_SKIN_TURTLE_ANCIENT", "Ancient Turtle Skin ");
        h.update(Arrays.asList("PET_SKIN_TURTLE_ANCIENT"));
        assertTrue(new File(dir, "skins.json.gz").isFile());

        SkinHistory again = history(new FakeFetcher(), clock, new ArrayList<Long>());
        assertEquals("Ancient Turtle Skin", again.name("PET_SKIN_TURTLE_ANCIENT"));
        SkinHistory.Series s = again.get("PET_SKIN_TURTLE_ANCIENT");
        assertEquals(7, s.days.size());
        assertEquals(Math.round((11_757_200.0 * 22 - 125_000 - 250_000_000) / 20), s.days.get(2).price, 1e-9);
        assertEquals(NOW, s.fetchedAt);
        assertEquals(NOW, again.lastRead());
        assertFalse(again.stale("PET_SKIN_TURTLE_ANCIENT"));
    }

    @Test
    void aBrokenFileIsReadAgainFromSkyCofl() throws IOException {
        java.nio.file.Files.write(new File(dir, "skins.json.gz").toPath(), new byte[] {1, 2, 3});
        SkinHistory h = history(new FakeFetcher(), new AtomicLong(NOW), new ArrayList<Long>());
        assertEquals(0, h.size());
        assertTrue(h.stale("ANY"));
    }
}
