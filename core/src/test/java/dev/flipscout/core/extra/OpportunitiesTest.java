package dev.flipscout.core.extra;

import dev.flipscout.core.FakeFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunitiesTest {
    private static final String CRAFTS = "https://sky.coflnet.com/api/craft/profit";
    private static final long T0 = 1_800_000_000_000L;

    private final FakeMarket market = new FakeMarket();
    private final FakeFetcher http = new FakeFetcher();
    private final AtomicLong clock = new AtomicLong(T0);
    private Opportunities opportunities;

    @BeforeEach
    void setUp() {
        opportunities = new Opportunities(market.config, new CoflLists(http, () -> "https://sky.coflnet.com"), market, clock::get);
    }

    private static String craft(String id, double sell) {
        return "{\"itemId\":\"" + id + "\",\"sellPrice\":" + sell + ",\"median\":" + sell + ",\"ingredients\":[{\"itemId\":\"I\","
            + "\"count\":1,\"cost\":1000000}],\"volume\":20.0}";
    }

    private void crafts(String... entries) {
        http.put(CRAFTS, "[" + String.join(",", entries) + "]");
    }

    @Test
    void theFirstDownloadIsOnlySummedUpThenNewFlipsAreAlerted() throws IOException {
        crafts(craft("OLD", 10_000_000), craft("SMALL", 1_500_000));
        assertTrue(opportunities.refresh(OpportunityKind.CRAFT).isEmpty());

        crafts(craft("OLD", 10_000_000), craft("NEW", 8_000_000));
        clock.addAndGet(5 * 60_000L);
        List<Opportunity> alerts = opportunities.refresh(OpportunityKind.CRAFT);
        assertEquals(1, alerts.size());
        assertEquals("CRAFT:NEW", alerts.get(0).key);
        // Not again while it lasts...
        clock.addAndGet(5 * 60_000L);
        assertTrue(opportunities.refresh(OpportunityKind.CRAFT).isEmpty());
        // ...until the cooldown is over.
        clock.addAndGet(6 * 60 * 60_000L);
        assertEquals(2, opportunities.refresh(OpportunityKind.CRAFT).size());
    }

    @Test
    void alertsPerRefreshAreCapped() throws IOException {
        crafts();
        opportunities.refresh(OpportunityKind.CRAFT);
        crafts(craft("A", 9e6), craft("B", 8e6), craft("C", 7e6), craft("D", 6e6), craft("E", 5e6));
        List<Opportunity> alerts = opportunities.refresh(OpportunityKind.CRAFT);
        assertEquals(Opportunities.MAX_ALERTS_PER_REFRESH, alerts.size());
        assertEquals("CRAFT:A", alerts.get(0).key);
        // The rest come next time.
        assertEquals(2, opportunities.refresh(OpportunityKind.CRAFT).size());
    }

    @Test
    void thresholdsAreThoseOfEachKind() throws IOException {
        crafts(craft("A", 2_500_000));
        Opportunity small = opportunities.list(OpportunityKind.CRAFT, 10).get(0);
        assertTrue(small.profit < 2_000_000, String.valueOf(small.profit));
        assertTrue(!opportunities.passes(small));
        market.config.otherCraftMinProfit = 1_000_000;
        assertTrue(opportunities.passes(small));
        market.config.otherCraftMinVolume = 50;
        assertTrue(!opportunities.passes(small));
    }

    @Test
    void theSummaryWaitsForEveryListWithAlertsOn() throws IOException {
        market.config.otherKatNotify = false;
        market.config.otherForgeNotify = false;
        market.config.otherFusionNotify = false;
        market.config.otherNpcNotify = false;
        crafts(craft("A", 9e6));
        http.put("https://sky.coflnet.com/api/flip/bazaar/books", "[]");
        opportunities.refresh(OpportunityKind.CRAFT);
        assertNull(opportunities.startupSummary());
        opportunities.refresh(OpportunityKind.BOOKS);
        Map<OpportunityKind, Integer> summary = opportunities.startupSummary();
        assertEquals(Integer.valueOf(1), summary.get(OpportunityKind.CRAFT));
        assertEquals(Integer.valueOf(0), summary.get(OpportunityKind.BOOKS));
        assertNull(opportunities.startupSummary());
    }

    @Test
    void dueListsTakeTurnsAndKatWaitsForTheAuctions() {
        assertEquals(OpportunityKind.CRAFT, opportunities.due(T0));
        assertEquals(OpportunityKind.FORGE, opportunities.due(T0));
        assertEquals(OpportunityKind.FUSION, opportunities.due(T0));
        assertEquals(OpportunityKind.NPC, opportunities.due(T0));
        assertEquals(OpportunityKind.BOOKS, opportunities.due(T0));
        assertNull(opportunities.due(T0));
        market.indexed = true;
        assertEquals(OpportunityKind.KAT, opportunities.due(T0));
        assertNull(opportunities.due(T0 + 60_000L));
        assertEquals(OpportunityKind.KAT, opportunities.due(T0 + 5 * 60_000L));

        opportunities.failed(OpportunityKind.CRAFT, new IOException("down"));
        assertEquals("down", opportunities.lastError());
        assertEquals(OpportunityKind.CRAFT, opportunities.due(T0 + 2 * 60_000L));
    }

    @Test
    void listsAreKeptForTheRefreshInterval() throws IOException {
        crafts(craft("A", 9e6), craft("B", 8e6));
        assertEquals(1, opportunities.list(OpportunityKind.CRAFT, 1).size());
        assertEquals(2, opportunities.list(OpportunityKind.CRAFT, 10).size());
        assertEquals(1, http.count(CRAFTS));
        clock.addAndGet(5 * 60_000L);
        opportunities.list(OpportunityKind.CRAFT, 10);
        assertEquals(2, http.count(CRAFTS));
        assertEquals(1, opportunities.listsLoaded());
    }
}
