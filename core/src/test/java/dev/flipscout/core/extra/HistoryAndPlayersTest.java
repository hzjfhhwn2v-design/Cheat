package dev.flipscout.core.extra;

import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.Msg;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryAndPlayersTest {
    private static final String COFL = "https://sky.coflnet.com";
    private static final long NOW = Instant.parse("2027-01-15T00:00:00Z").toEpochMilli();

    private final FakeFetcher http = new FakeFetcher();
    private final AtomicLong clock = new AtomicLong(NOW);
    private final History history = new History(http, () -> COFL, clock::get);

    private static String days(int count, double from, double step) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String time = Instant.ofEpochMilli(NOW - (count - i) * 86_400_000L).toString().replace("Z", "");
            double price = from + i * step;
            sb.append("{\"min\":").append(price - 10).append(",\"max\":").append(price + 10).append(",\"avg\":").append(price)
                .append(",\"volume\":4,\"time\":\"").append(time).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String text(List<Msg> lines) {
        StringBuilder sb = new StringBuilder();
        for (Msg line : lines) {
            sb.append(line.toPlainString()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void auctionHistoryIsAChartAndAFewNumbers() throws IOException {
        http.put(COFL + "/api/item/price/HYPERION/history/month", days(31, 800_000_000, 10_000_000));
        List<Msg> lines = history.show("HYPERION", "Hyperion", false, "month");
        String text = text(lines);
        assertTrue(text.contains("Hyperion - auction price, last 30 days [SkyCofl]"), text);
        // 31 days in 30 bars, rising: the first bar is the lowest, the last the highest.
        Msg chart = lines.get(1);
        assertEquals(31, chart.parts().size());
        assertEquals("§e▁", chart.parts().get(1).text);
        assertEquals("§e█", chart.parts().get(30).text);
        assertTrue(chart.parts().get(30).hover.contains("Average: §61.1B"), chart.parts().get(30).hover);
        assertTrue(text.contains("Now 1.1B | average 950M | lowest 800M | highest 1.1B | +37.5% | 4.1 sold a day"), text);
    }

    @Test
    void bazaarHistoryUsesTheInstantBuyPrice() throws IOException {
        http.put(COFL + "/api/bazaar/ENCHANTED_DIAMOND/history/week", "[{\"maxBuy\":1400,\"minBuy\":1300,\"buy\":1355.7,"
            + "\"sell\":1271.5,\"buyVolume\":1354355,\"timestamp\":\"2027-01-14T16:00:00\"},{\"buy\":1300,\"sell\":1250,"
            + "\"timestamp\":\"2027-01-14T14:00:00\"}]");
        String text = text(history.show("ENCHANTED_DIAMOND", "Enchanted Diamond", true, "week"));
        assertTrue(text.contains("Enchanted Diamond - instant-buy price, last 7 days"), text);
        assertTrue(text.contains("Now 1.36k | average 1.33k | lowest 1.3k | highest 1.4k | +4.3%"), text);
        assertTrue(!text.contains("sold a day"), text);
    }

    @Test
    void anEmptyHistorySaysSo() throws IOException {
        String text = text(history.show("NOTHING", "Nothing", false, "day"));
        assertTrue(text.contains("No prices recorded in this span."), text);
    }

    @Test
    void bucketsAverageNeighbours() {
        List<History.Point> points = new ArrayList<History.Point>();
        for (int i = 0; i < 60; i++) {
            points.add(new History.Point(i, i, i, i, 1));
        }
        List<History.Point> bars = History.buckets(points, 30);
        assertEquals(30, bars.size());
        assertEquals(0.5, bars.get(0).avg, 1e-9);
        assertEquals(2, bars.get(0).volume, 1e-9);
        assertEquals(points, History.buckets(points, 100));
    }

    @Test
    void playersAreLookedUpOnceAndTheirAuctionsRead() throws IOException {
        Players players = new Players(http, () -> COFL);
        http.put(Players.MOJANG + "Technoblade", "{\"id\":\"B876EC32E396476BA1158438D83C67D4\",\"name\":\"Technoblade\"}");
        assertEquals("b876ec32e396476ba1158438d83c67d4", players.uuid("Technoblade"));
        assertEquals("b876ec32e396476ba1158438d83c67d4", players.uuid("technoblade"));
        assertEquals(1, http.count(Players.MOJANG + "Technoblade"));
        assertEquals("Technoblade", players.name("b876ec32e396476ba1158438d83c67d4", "x"));
        http.put(Players.MOJANG + "Nobody_Here", 204, "");
        assertNull(players.uuid("Nobody_Here"));

        http.put(COFL + "/api/player/abc/auctions?page=0", "[{\"auctionId\":\"a1\",\"highestBid\":820000000,\"itemName\":"
            + "\"§6Hyperion\",\"tag\":\"HYPERION\",\"end\":\"2027-01-14T10:00:00Z\",\"startingBid\":820000000,\"bin\":true},"
            + "{\"auctionId\":\"a2\",\"highestBid\":0,\"itemName\":\"Bee\",\"tag\":\"PET_BEE\",\"end\":\"2027-01-16T10:00:00\","
            + "\"startingBid\":1000,\"bin\":false},{\"highestBid\":5}]");
        List<Players.PastAuction> past = players.auctions("abc");
        assertEquals(2, past.size());
        assertTrue(past.get(0).sold());
        assertTrue(past.get(0).bin);
        assertEquals(Instant.parse("2027-01-14T10:00:00Z").toEpochMilli(), past.get(0).end);
        assertTrue(!past.get(1).sold());
        assertTrue(players.auctions("unknown").isEmpty());
        assertTrue(Players.NAME.matcher("baldih").matches());
        assertTrue(!Players.NAME.matcher("bad name").matches());
    }
}
