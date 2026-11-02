package dev.flipscout.core;

import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.profit.ProfitTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExporterTest {
    @TempDir
    File dir;

    private static final long NOW = 1_800_000_000_000L;

    private static List<String> lines(File file) throws IOException {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void flipsBecomeACsvThatSpreadsheetsRead() throws IOException {
        AtomicLong clock = new AtomicLong(NOW);
        ProfitTracker tracker = new ProfitTracker(new Config(), new File(dir, "profits.json"), clock::get, new FakePlatform(),
            Runnable::run);
        tracker.onChat("[Bazaar] Bought 64x Enchanted Diamond for 10,240 coins!");
        clock.addAndGet(90 * 60_000L);
        tracker.onChat("[Bazaar] Sold 64x Enchanted Diamond for 11,000 coins!");
        Exporter exporter = new Exporter(new File(dir, "exports"), ZoneOffset.UTC);
        File file = exporter.flips(tracker.flips(), NOW);
        assertEquals("flips-20270115-080000.csv", file.getName());
        List<String> lines = lines(file);
        assertEquals("﻿sold_at,bought_at,item,amount,cost,revenue_after_taxes,profit,held_minutes", lines.get(0));
        assertEquals("2027-01-15 09:30:00,2027-01-15 08:00:00,Enchanted Diamond,64,10240,11000,760,90", lines.get(1));
        assertEquals(Integer.valueOf(6), Exporter.columns(lines.get(0)).get("profit"));
    }

    @Test
    void cellsWithCommasOrQuotesAreQuoted() {
        assertEquals("plain", Exporter.cell("plain"));
        assertEquals("\"a, b\"", Exporter.cell("a, b"));
        assertEquals("\"say \"\"hi\"\"\"", Exporter.cell("say \"hi\""));
        assertEquals("Hyperion", Exporter.cell("§6Hyperion"));
        assertEquals("", Exporter.cell(null));
    }

    @Test
    void notificationsAndTheBazaarAreExported() throws IOException {
        Exporter exporter = new Exporter(new File(dir, "exports"), ZoneOffset.UTC);
        Engine.Notification n = new Engine.Notification(NOW, Msg.prefixed("§eAH Hyperion, cheap"), "/viewauction a", "a", NOW - 5_000,
            NOW, "Hyperion", 800_000_000L, 900_000_000, 68_500_000);
        List<String> lines = lines(exporter.notifications(Collections.singletonList(n), NOW));
        assertEquals("2027-01-15 08:00:00,\"AH Hyperion, cheap\",/viewauction a,Hyperion,800000000,900000000,68500000,,",
            lines.get(1));

        BazaarProduct diamond = new BazaarProduct("ENCHANTED_DIAMOND");
        diamond.offerPrices = new double[] {170, 171};
        diamond.offerAmounts = new double[] {100, 50};
        diamond.sellMovingWeek = 1_000_000;
        Map<String, BazaarProduct> products = new LinkedHashMap<String, BazaarProduct>();
        products.put(diamond.id, diamond);
        File file = exporter.bazaar(new BazaarSnapshot(NOW - 10_000, products), ItemCatalog.EMPTY, NOW);
        Map<String, Object> root = JsonTree.obj(JsonTree.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
        Map<String, Object> product = JsonTree.obj(JsonTree.arr(root.get("products")).get(0));
        assertEquals("Enchanted Diamond", product.get("name"));
        assertEquals(170.0, product.get("instantBuy"));
        assertEquals(null, product.get("instantSell"));
        assertEquals(2, JsonTree.arr(product.get("sellOffers")).size());
        assertTrue(file.getName().startsWith("bazaar-") && file.getName().endsWith(".json"));
    }
}
