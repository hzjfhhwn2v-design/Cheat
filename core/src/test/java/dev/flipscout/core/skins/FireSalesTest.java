package dev.flipscout.core.skins;

import dev.flipscout.core.FakeFetcher;
import dev.flipscout.core.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireSalesTest {
    static final String URL = "https://api.hypixel.net/v2/skyblock/firesales";
    /** Hypixel's answer on 3 November 2026. */
    static final String REAL = "{\"success\":true,\"sales\":[{\"item_id\":\"UNIQUE_RUNE\",\"start\":1793462400000,"
        + "\"end\":1793811600000,\"amount\":5000,\"price\":950,\"rune\":\"PRANCING_PUMPKIN\"},{\"item_id\":"
        + "\"PET_SKIN_SKELETON_FALLEN_KING\",\"start\":1793462400000,\"end\":1793811600000,\"amount\":1450,\"price\":950}]}";

    @TempDir
    File dir;

    @Test
    void newFireSalesAreReportedOnceAndKept() throws IOException {
        FakeFetcher http = new FakeFetcher().put(URL, REAL);
        AtomicLong clock = new AtomicLong(SkinHistoryTest.NOW);
        File file = new File(dir, "firesales.json");
        FireSales sales = new FireSales(http, () -> "https://api.hypixel.net/", clock::get, file);
        assertTrue(sales.due(clock.get()));
        List<FireSales.Sale> fresh = sales.refresh();
        assertEquals(2, fresh.size());
        assertEquals("PRANCING_PUMPKIN", fresh.get(0).rune);
        FireSales.Sale king = fresh.get(1);
        assertEquals("PET_SKIN_SKELETON_FALLEN_KING", king.itemId);
        assertEquals(1450, king.amount);
        assertEquals(950, king.price);
        assertTrue(king.current(clock.get()));
        assertFalse(sales.due(clock.get()));
        assertEquals(2, sales.current().size());

        clock.addAndGet(FireSales.REFRESH_MS);
        assertTrue(sales.refresh().isEmpty());
        // Runes are not skins: latest() only gives the skin.
        assertEquals(king.start, sales.latest("PET_SKIN_SKELETON_FALLEN_KING").start);
        assertNull(sales.latest("UNIQUE_RUNE"));

        // Hypixel forgets it once over; the file does not.
        clock.set(1793811600000L + 1);
        FireSales again = new FireSales(new FakeFetcher().put(URL, "{\"success\":true,\"sales\":[]}"),
            () -> "https://api.hypixel.net", clock::get, file);
        assertTrue(again.refresh().isEmpty());
        assertEquals(1450, again.latest("PET_SKIN_SKELETON_FALLEN_KING").amount);
        assertTrue(again.current().isEmpty());
    }

    @Test
    void anErrorIsRetriedSoon() {
        FakeFetcher http = new FakeFetcher().put(URL, 503, "{}");
        AtomicLong clock = new AtomicLong(SkinHistoryTest.NOW);
        FireSales sales = new FireSales(http, () -> "https://api.hypixel.net", clock::get, null);
        assertThrows(ApiException.class, sales::refresh);
        assertFalse(sales.due(clock.get()));
        clock.addAndGet(5 * 60_000L);
        assertTrue(sales.due(clock.get()));
    }
}
