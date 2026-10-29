package dev.flipscout.core.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesHistoryTest {
    private static final int NOW = 30_000_000;

    @TempDir
    File dir;

    @Test
    void medianAndVolume() {
        SalesHistory history = new SalesHistory();
        for (int m = NOW - 599; m <= NOW; m++) {
            history.markObserved(m);
        }
        history.add("X", NOW - 10, 100);
        history.add("X", NOW - 9, 300);
        history.add("X", NOW - 8, 200);
        SalesHistory.Stats stats = history.stats("X", NOW, 3);
        assertEquals(200, stats.median, 1e-9);
        assertEquals(3, stats.samples);
        // 3 sales in 600 watched minutes -> 7.2 per day, not yet a full day of data.
        assertEquals(7.2, stats.volumePerDay, 1e-9);
        assertFalse(stats.volumeReliable);

        SalesHistory.Stats unknown = history.stats("Y", NOW, 3);
        assertTrue(Double.isNaN(unknown.median));
        assertEquals(0.0, unknown.volumePerDay, 1e-9);
    }

    @Test
    void shortWatchGivesNoVolume() {
        SalesHistory history = new SalesHistory();
        for (int m = NOW - 89; m <= NOW; m++) {
            history.markObserved(m);
        }
        history.add("X", NOW - 1, 50);
        assertTrue(Double.isNaN(history.stats("X", NOW, 1).volumePerDay));
        assertTrue(Double.isNaN(history.stats("Y", NOW, 1).volumePerDay));
    }

    @Test
    void rareItemsUseOlderSales() {
        SalesHistory history = new SalesHistory();
        history.add("RARE", NOW - 3 * 1440, 10);
        history.add("RARE", NOW - 2 * 1440, 20);
        history.add("RARE", NOW - 2000, 30);
        SalesHistory.Stats stats = history.stats("RARE", NOW, 3);
        assertEquals(3, stats.samples);
        assertEquals(20, stats.median, 1e-9);
    }

    @Test
    void keepsTheMostRecentSalesPerKey() {
        SalesHistory history = new SalesHistory();
        for (int i = 0; i < 500; i++) {
            history.add("X", NOW - 500 + i, i);
        }
        assertEquals(120, history.sampleCount());
        // Median of the 50 newest (450..499).
        assertEquals(474.5, history.stats("X", NOW, 3).median, 1e-9);
    }

    @Test
    void saveLoadAndPrune() throws IOException {
        SalesHistory history = new SalesHistory();
        history.markObserved(NOW - 1);
        history.markObserved(NOW);
        history.add("A", NOW - 5 * 1440, 1);
        history.add("A", NOW, 2);
        history.add("B", NOW, 3);
        File file = new File(dir, "sales.bin");
        history.save(file);

        SalesHistory loaded = new SalesHistory();
        loaded.load(file);
        assertEquals(3, loaded.sampleCount());
        assertEquals(2, loaded.keyCount());
        assertEquals(2, loaded.observedMinutes());

        loaded.prune(NOW, 3);
        assertEquals(2, loaded.sampleCount());
        assertEquals(2.0, loaded.stats("A", NOW, 1).median, 1e-9);
    }

    @Test
    void corruptFileIsIgnored() throws IOException {
        File file = new File(dir, "sales.bin");
        java.nio.file.Files.write(file.toPath(), new byte[] {1, 2, 3});
        SalesHistory history = new SalesHistory();
        history.load(file);
        assertEquals(0, history.sampleCount());
    }
}
