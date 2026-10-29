package dev.flipscout.core.market;

import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.api.CoflnetApi;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoflnetSourceTest {
    private static final String PRICE = "https://sky.coflnet.com/api/item/price/";

    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final FakeFetcher http = new FakeFetcher();
    private final CoflnetSource coflnet = new CoflnetSource(new CoflnetApi(http, () -> "https://sky.coflnet.com"),
        clock::get);

    @Test
    void lookupsAheadOfTimeLeaveRoomForLiveFlips() {
        for (int i = 0; i < 10; i++) {
            http.put(PRICE + "ITEM" + i, "{\"median\":1000,\"volume\":5}");
        }
        // Ahead of time: two seconds apart, at most six a minute.
        assertNotNull(coflnet.prefetch("ITEM0"));
        assertNull(coflnet.prefetch("ITEM1"));
        assertEquals(0, http.count(PRICE + "ITEM1"));
        for (int i = 1; i < 6; i++) {
            clock.addAndGet(2_000);
            assertNotNull(coflnet.prefetch("ITEM" + i));
        }
        // A live flip is looked up at once, even right after a lookup ahead of time...
        assertNotNull(coflnet.get("ITEM7"));
        assertEquals(1, http.count(PRICE + "ITEM7"));
        // ...while the share for lookups ahead of time is used up.
        clock.addAndGet(2_000);
        assertNull(coflnet.prefetch("ITEM6"));
        assertEquals(0, http.count(PRICE + "ITEM6"));
        assertEquals(7, coflnet.cachedPrices());

        // Prices stay cached for hours.
        clock.addAndGet(5 * 60 * 60_000L);
        assertTrue(coflnet.isKnown("ITEM0"));
        assertNotNull(coflnet.get("ITEM0"));
        assertEquals(1, http.count(PRICE + "ITEM0"));
    }

    @Test
    void liveLookupsMayComeTogether() {
        for (int i = 0; i < 4; i++) {
            http.put(PRICE + "LIVE" + i, "{\"median\":1000,\"volume\":5}");
            assertNotNull(coflnet.get("LIVE" + i));
        }
        for (int i = 0; i < 4; i++) {
            assertEquals(1, http.count(PRICE + "LIVE" + i));
        }
    }

    @Test
    void itemsCoflnetDoesNotKnowAreNotAskedAgainForAnHour() {
        assertNull(coflnet.prefetch("NOPE"));
        assertEquals(1, http.count(PRICE + "NOPE"));
        assertTrue(coflnet.isKnown("NOPE"));
        clock.addAndGet(30 * 60_000L);
        assertNull(coflnet.get("NOPE"));
        assertEquals(1, http.count(PRICE + "NOPE"));
        clock.addAndGet(31 * 60_000L);
        assertFalse(coflnet.isKnown("NOPE"));
    }
}
