package dev.flipscout.core.skins;

import dev.flipscout.core.Msg;
import dev.flipscout.core.util.Text;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinTextTest {
    static SkinAdvisor.Pick pick(String id, String name) {
        SkinAdvisor.Pick p = new SkinAdvisor.Pick(id, name);
        p.history = SkinAdvisorTest.steady(id, 100, 9_900_000, 0.02, 5);
        p.listings = 12;
        p.lowestBin = 8_500_000;
        p.lowestUuid = "abc";
        p.sellValue = 9_860_000;
        p.sellSource = "this week's sales";
        p.expectedSell = 9_860_000;
        p.fees = 197_100;
        p.profit = 1_157_900;
        p.expectedReturn = 0.1362;
        p.change7 = 0.012;
        p.change30 = -0.034;
        p.change90 = 0.25;
        p.median30 = 9_880_000;
        p.volatility = 0.081;
        p.drawdown90 = 0.05;
        p.salesPerDay = 5;
        p.ageDays = 99;
        p.confidence = 0.64;
        p.confidenceSamples = 40;
        p.ownHistory = true;
        p.risk = SkinAdvisor.Risk.LOW;
        return p;
    }

    private static String plain(List<Msg> lines) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : lines) {
            sb.append(m.toPlainString()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void theHoverHasEverythingAskedFor() {
        String hover = Text.stripCodes(SkinText.hover(pick("PET_SKIN_CAT", "Catgirl Black Cat Skin"), 30, true));
        assertTrue(hover.startsWith("Catgirl Black Cat Skin\n"), hover);
        assertTrue(hover.contains("Price now: 8.5M (cheapest BIN)"), hover);
        assertTrue(hover.contains("Expected profit: +1.16M (sold in 30 days, after fees)"), hover);
        assertTrue(hover.contains("Expected return: +14%"), hover);
        assertTrue(hover.contains("Risk: Low"), hover);
        assertTrue(hover.contains("7 days: +1.2% 30 days: -3.4%"), hover);
        assertTrue(hover.contains("Volatility (30 days): ±8.1%"), hover);
        assertTrue(hover.contains("Listings: 12 | sales: 5 a day"), hover);
        assertTrue(hover.contains("Confidence: 64%"), hover);
        assertTrue(hover.endsWith("Click to open the cheapest BIN"), hover);
        assertFalse(hover.contains("Fire sale"));
    }

    @Test
    void lossesAreRedWithoutAPlus() {
        SkinAdvisor.Pick p = pick("X", "X Skin");
        p.profit = -250_000;
        p.expectedReturn = -0.0294;
        String hover = SkinText.hover(p, 30, false);
        assertTrue(hover.contains("Expected profit: §c-250k"), hover);
        assertTrue(hover.contains("Expected return: §c-2.9%"), hover);
        assertFalse(hover.contains("+-"));
        assertFalse(hover.contains("Click"));
        assertEquals("§a+0%", SkinText.percent(0));
        assertEquals("§8?", SkinText.percent(Double.NaN));
        assertEquals("§a+25%", SkinText.percent(0.25));
    }

    @Test
    void theListIsOneLinePerSkinThatOpensItsCheapestBin() {
        Map<String, Integer> hidden = new LinkedHashMap<String, Integer>();
        hidden.put("rarely sold", 108);
        List<SkinAdvisor.Pick> picks = new ArrayList<SkinAdvisor.Pick>();
        picks.add(pick("A", "Alpha Skin"));
        picks.add(pick("B", "Beta Skin"));
        SkinAdvisor.Ranking r = new SkinAdvisor.Ranking(picks, 121, 307, hidden, 105);
        List<Msg> lines = SkinText.ranking(r, 30, new SkinText.Freshness(12_000, 304, 287, true));
        String text = plain(lines);
        assertTrue(text.contains("[FlipScout] Skin investments - buy now, sell in 30 days, after fees [SkyCofl]"), text);
        assertTrue(text.contains("Auctions read 12s ago | price history of 287/304 skins (still reading)"), text);
        assertTrue(text.contains(" 1. Alpha Skin\n 2. Beta Skin\n"), text);
        Msg first = lines.get(2);
        Msg.Part name = first.parts().get(1);
        assertEquals(Msg.Click.RUN, name.click);
        assertEquals("/viewauction abc", name.command);
        assertTrue(name.hover.contains("Expected profit"));
        // The freshness line says what was left out.
        Msg.Part fresh = lines.get(1).parts().get(1);
        assertTrue(fresh.hover.contains("Left out, rarely sold: §f108"), fresh.hover);
        assertTrue(fresh.hover.contains("No profit expected after fees: §f105"), fresh.hover);
        assertTrue(fresh.text.contains("§a12s"), fresh.text);
    }

    @Test
    void anEmptyListSaysWhy() {
        SkinAdvisor.Ranking r = new SkinAdvisor.Ranking(Collections.<SkinAdvisor.Pick>emptyList(), 121, 307,
            new LinkedHashMap<String, Integer>(), 121);
        String text = plain(SkinText.ranking(r, 30, new SkinText.Freshness(400_000, 10, 10, false)));
        assertTrue(text.contains("No skin is cheap enough right now to expect a profit after fees in 30 days (121 analysed)"),
            text);
        assertFalse(text.contains("still reading"));
        assertTrue(SkinText.ranking(r, 30, new SkinText.Freshness(400_000, 10, 10, false)).get(1).parts().get(1).text
            .contains("§c"));
    }

    @Test
    void theDetailHasTheNumbersAChartAndALink() {
        SkinAdvisor.Pick p = pick("PET_SKIN_CAT", "Catgirl Black Cat Skin");
        List<Msg> lines = SkinText.detail(p, 30, SkinHistoryTest.NOW);
        String text = plain(lines);
        assertTrue(text.contains("[FlipScout] Catgirl Black Cat Skin - skin investment over 30 days [chart]"), text);
        assertEquals(SkinText.ITEM_URL + "PET_SKIN_CAT", lines.get(0).parts().get(1).command);
        assertEquals(Msg.Click.URL, lines.get(0).parts().get(1).click);
        assertTrue(text.contains("Price now 8.5M (cheapest of 12 BIN) [open]"), text);
        assertEquals("/viewauction abc", lines.get(1).firstCommand());
        assertTrue(text.contains("Sells for 9.86M (this week's sales) expected in 30 days 9.86M (197k fees)"), text);
        assertTrue(text.contains("Expected profit +1.16M (+14%) | risk Low | confidence 64%"), text);
        assertTrue(text.contains("7 days +1.2% | 30 days -3.4% | 90 days +25% | 30-day median 9.88M"), text);
        assertTrue(text.contains("Advised: a profit is expected after fees."), text);
        assertTrue(text.contains("Last 30 days "), text);
        assertTrue(text.contains("Lowest "), text);
        Msg chart = null;
        for (Msg m : lines) {
            if (m.toPlainString().startsWith("  Last 30 days")) {
                chart = m;
            }
        }
        assertEquals(31, chart.parts().size());
        assertTrue(chart.parts().get(1).hover.contains("Price: "), chart.parts().get(1).hover);
    }

    @Test
    void theDetailSaysWhyASkinIsNotAdvised() {
        SkinAdvisor.Pick p = pick("X", "X Skin");
        p.exclude("falling", "fell 76% from its high of the last 90 days");
        String text = plain(SkinText.detail(p, 30, SkinHistoryTest.NOW));
        assertTrue(text.contains("Not advised: fell 76% from its high of the last 90 days"), text);
        p = pick("Y", "Y Skin");
        p.profit = -1;
        assertTrue(plain(SkinText.detail(p, 30, SkinHistoryTest.NOW)).contains("No profit expected after fees"));
        // Nothing to show without auctions or history.
        SkinAdvisor.Pick bare = new SkinAdvisor.Pick("Z", "Z Skin");
        bare.exclude("no history", "its price history has not been read yet");
        List<Msg> lines = SkinText.detail(bare, 30, SkinHistoryTest.NOW);
        assertEquals(2, lines.size());
        assertNull(lines.get(1).firstCommand());
    }

    @Test
    void aFireSaleAlertSaysHowManyAndWhen() {
        FireSales.Sale sale = FireSales.parse(dev.flipscout.core.json.JsonTree.obj(
            dev.flipscout.core.json.JsonTree.parseQuiet(FireSalesTest.REAL))).get(1);
        long now = 1793462400000L - 2 * 3_600_000L;
        String text = SkinText.fireSaleAlert(sale, "Fallen King Skeleton Skin", now).toPlainString();
        assertEquals("[FlipScout] Fire sale: Fallen King Skeleton Skin - 1,450 for 950 gems each (starts in 2 h)", text);
        String during = SkinText.fireSaleAlert(sale, "Fallen King Skeleton Skin", 1793462400000L + 1000).toPlainString();
        assertTrue(during.contains("on now, ends in 4 d"), during);
        assertTrue(Text.stripCodes(SkinText.fireSale(sale)).startsWith("1,450 sold for 950 gems (31 Oct - "),
            SkinText.fireSale(sale));
    }
}
