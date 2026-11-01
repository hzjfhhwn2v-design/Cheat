package dev.flipscout.core.extra;

import dev.flipscout.core.Config;
import dev.flipscout.core.api.ApiException;
import dev.flipscout.core.api.BazaarProduct;
import dev.flipscout.core.flip.Fees;
import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.util.Coins;
import dev.flipscout.core.util.Text;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * SkyCofl's public lists (sky.coflnet.com): which Kat upgrades, crafts, forges, fusions, NPC
 * purchases and book combinations pay. Each entry is priced again with our live data where we
 * have it (bazaar prices, auctions still listed), so a list a few minutes old is still right.
 */
public final class CoflLists {
    private static final String COINS = "SKYBLOCK_COINS";

    private final HttpFetcher http;
    private final Supplier<String> baseUrl;

    public CoflLists(HttpFetcher http, Supplier<String> baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public List<Opportunity> fetch(OpportunityKind kind, Market market) throws IOException {
        return parse(kind, download(kind.path), market);
    }

    public List<SupplyItem> fetchLowSupply(Market market) throws IOException {
        return parseLowSupply(download("/api/auctions/supply/low"), market);
    }

    /** GETs a SkyCofl path that answers with a JSON array. */
    public List<Object> download(String path) throws IOException {
        String base = baseUrl.get();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpFetcher.Response response = http.get(base + path);
        try {
            if (response.status >= 400) {
                throw new ApiException("SkyCofl: HTTP " + response.status, response.status, response.retryAfterMs);
            }
            return JsonTree.arr(JsonTree.parse(new InputStreamReader(response.body, StandardCharsets.UTF_8)));
        } finally {
            response.close();
        }
    }

    // ---- Parsing ----

    /** The entries of a list that make a profit, best first. */
    static List<Opportunity> parse(OpportunityKind kind, List<Object> entries, Market market) {
        List<Opportunity> out = new ArrayList<Opportunity>();
        for (Object entry : entries) {
            Map<String, Object> e = JsonTree.obj(entry);
            Opportunity o;
            try {
                switch (kind) {
                    case KAT:
                        o = kat(e, market);
                        break;
                    case CRAFT:
                        o = craft(e, market);
                        break;
                    case FORGE:
                        o = forge(e, market);
                        break;
                    case FUSION:
                        o = fusion(e, market);
                        break;
                    case NPC:
                        o = npc(e, market);
                        break;
                    default:
                        o = books(e, market);
                        break;
                }
            } catch (RuntimeException ex) {
                // One odd entry never costs the whole list.
                o = null;
            }
            if (o != null && o.profit > 0) {
                out.add(o);
            }
        }
        if (kind == OpportunityKind.KAT) {
            // Many auctions of the same pet: only the best one per upgrade.
            Map<String, Opportunity> best = new LinkedHashMap<String, Opportunity>();
            for (Opportunity o : out) {
                Opportunity old = best.get(o.key);
                if (old == null || o.profit > old.profit) {
                    best.put(o.key, o);
                }
            }
            out = new ArrayList<Opportunity>(best.values());
        }
        sort(out);
        return out;
    }

    static void sort(List<Opportunity> list) {
        Collections.sort(list, new Comparator<Opportunity>() {
            @Override
            public int compare(Opportunity a, Opportunity b) {
                return Double.compare(b.score(), a.score());
            }
        });
    }

    static Opportunity kat(Map<String, Object> e, Market m) {
        Map<String, Object> core = JsonTree.obj(e.get("coreData"));
        String pet = JsonTree.str(core.get("itemTag"));
        String base = upper(JsonTree.str(core.get("baseRarity")));
        String target = upper(JsonTree.str(e.get("targetRarity")));
        String origin = JsonTree.str(e.get("originAuction"));
        if (pet == null || target == null || origin == null) {
            return null;
        }
        double purchase = num(e.get("purchaseCost"));
        if (m.auctionsIndexed()) {
            long live = m.auctionPrice(origin);
            if (live < 0) {
                // Bought or cancelled since SkyCofl looked.
                return null;
            }
            purchase = live;
        }
        double upgrade = Math.max(0, num0(e.get("upgradeCost")));
        double materials = Math.max(0, num0(e.get("materialCost")));
        double median = num(e.get("median"));
        if (!(median > 0) || !(purchase > 0)) {
            return null;
        }
        Config c = m.config();
        double fees = Fees.auctionSellFees(median, c);
        double hours = num(core.get("hours"));
        double perDay = num(e.get("volume"));
        String petName = Text.stripCodes(JsonTree.str(e.get("originAuctionName")));
        if (petName == null || petName.isEmpty()) {
            petName = Text.titleCase(pet.startsWith("PET_") ? pet.substring(4) : pet);
        }
        String name = Text.rarityColor(base) + petName;
        String note = Text.rarityColor(base) + Text.titleCase(base) + "§7→" + Text.rarityColor(target) + Text.titleCase(target);
        List<String> details = new ArrayList<String>();
        details.add("§7Buy the pet: §6" + Coins.full(purchase));
        StringBuilder kat = new StringBuilder("§7Kat: §6" + Coins.compact(upgrade));
        if (materials > 0) {
            kat.append(" §7+ materials §6").append(Coins.compact(materials));
            String list = materialList(JsonTree.obj(core.get("materials")), m);
            if (!list.isEmpty()) {
                kat.append(" §8(").append(list).append(')');
            }
        }
        details.add(kat.toString());
        if (hours > 0) {
            details.add("§7Upgrade time: §f" + duration(hours));
        }
        details.add("§7Sells as " + Text.rarityColor(target) + Text.titleCase(target) + " §7for §a" + Coins.full(median)
            + (perDay > 0 ? " §8(" + perDay(perDay) + " sold a day)" : ""));
        details.add("§7Selling fees: §c-" + Coins.compact(fees));
        return new Opportunity(OpportunityKind.KAT, "KAT:" + pet + ":" + base + ":" + target, name, note,
            purchase + upgrade + materials, median - fees, hours > 0 ? hours : Double.NaN, positive(perDay), details,
            "/viewauction " + origin, false, "OPEN");
    }

    static Opportunity craft(Map<String, Object> e, Market m) {
        List<String> extra = new ArrayList<String>();
        Map<String, Object> collection = JsonTree.obj(e.get("reqCollection"));
        String note = "";
        String collectionName = JsonTree.str(collection.get("name"));
        int level = (int) num0(collection.get("level"));
        if (collectionName != null && !collectionName.isEmpty()) {
            extra.add("§7Requires: §f" + collectionName + " collection " + (level > 0 ? Text.roman(level) : ""));
            note = "§8" + collectionName + (level > 0 ? " " + Text.roman(level) : "");
        }
        return crafted(OpportunityKind.CRAFT, e, m, Double.NaN, note, extra);
    }

    static Opportunity forge(Map<String, Object> e, Market m) {
        int hotm = (int) num0(e.get("requiredHotMLevel"));
        int mine = m.config().otherForgeHotm;
        if (mine > 0 && hotm > mine) {
            return null;
        }
        double seconds = num(e.get("duration"));
        // A few entries say 30 for half-hour forges: anything under a minute is not a real time.
        double hours = seconds >= 60 ? seconds / 3600.0 : Double.NaN;
        List<String> extra = new ArrayList<String>();
        if (hours > 0) {
            extra.add("§7Forge time: §f" + duration(hours));
        }
        StringBuilder requires = new StringBuilder();
        for (Map.Entry<String, Object> r : new TreeMap<String, Object>(JsonTree.obj(e.get("requirements"))).entrySet()) {
            requires.append(requires.length() > 0 ? ", " : "").append(r.getKey()).append(' ').append((int) num0(r.getValue()));
        }
        if (requires.length() > 0) {
            extra.add("§7Requires: §f" + requires);
        }
        return crafted(OpportunityKind.FORGE, JsonTree.obj(e.get("craftData")), m, hours, hotm > 0 ? "§8HotM " + hotm : "", extra);
    }

    /** A craft or forge: the ingredients bought instantly, the result sold after fees. */
    private static Opportunity crafted(OpportunityKind kind, Map<String, Object> data, Market m, double hours, String note,
                                       List<String> extra) {
        String id = JsonTree.str(data.get("itemId"));
        if (id == null || id.isEmpty()) {
            return null;
        }
        List<String> details = new ArrayList<String>();
        details.add("§7Ingredients:");
        double cost = 0;
        for (Object o : JsonTree.arr(data.get("ingredients"))) {
            Map<String, Object> ingredient = JsonTree.obj(o);
            String ingredientId = JsonTree.str(ingredient.get("itemId"));
            double count = num0(ingredient.get("count"));
            double price = buyCost(m, ingredientId, count, num(ingredient.get("cost")));
            if (!(price >= 0)) {
                return null;
            }
            cost += price;
            details.add("§7  " + amount(count) + "x " + m.name(ingredientId) + ": §6" + Coins.compact(price));
        }
        if (!(cost > 0)) {
            cost = num(data.get("craftCost"));
        }
        double value = sellValue(m, id, 1, num(data.get("sellPrice")), num(data.get("median")));
        if (!(cost > 0) || !(value > 0)) {
            return null;
        }
        double perDay = num(data.get("volume"));
        details.add("§7Sells for §a" + Coins.compact(value) + " §8after fees"
            + (perDay > 0 ? ", " + perDay(perDay) + " sold a day" : ""));
        details.addAll(extra);
        String name = JsonTree.str(data.get("itemName"));
        if (name == null || Text.stripCodes(name).trim().isEmpty()) {
            name = Text.rarityColor(m.tier(id)) + m.name(id);
        }
        return new Opportunity(kind, kind.name() + ":" + id, name, note, cost, value, hours, positive(perDay), details,
            "/viewrecipe " + id, true, "RECIPE");
    }

    static Opportunity fusion(Map<String, Object> e, Market m) {
        String output = JsonTree.str(e.get("output"));
        Map<String, Object> inputs = new TreeMap<String, Object>(JsonTree.obj(e.get("inputs")));
        if (output == null || inputs.isEmpty()) {
            return null;
        }
        double outputCount = Math.max(1, num0(e.get("outputCount")));
        List<String> details = new ArrayList<String>();
        details.add("§7Fuse:");
        double cost = 0;
        String first = null;
        StringBuilder key = new StringBuilder("FUSION:").append(output);
        for (Map.Entry<String, Object> input : inputs.entrySet()) {
            String id = input.getKey();
            double count = num0(input.getValue());
            BazaarProduct product = m.bazaar(id);
            double price = product != null && product.instaBuy() > 0 ? product.instaBuy() * count : Double.NaN;
            if (Double.isNaN(price)) {
                cost = Double.NaN;
            } else if (!Double.isNaN(cost)) {
                cost += price;
            }
            details.add("§7  " + amount(count) + "x " + m.name(id) + (Double.isNaN(price) ? "" : ": §6" + Coins.compact(price)));
            if (first == null) {
                first = id;
            }
            key.append(':').append(id).append('x').append(amount(count));
        }
        if (Double.isNaN(cost)) {
            cost = num(e.get("inputCost"));
        }
        Config c = m.config();
        BazaarProduct out = m.bazaar(output);
        double gross = out != null && out.instaSell() > 0 ? out.instaSell() * outputCount : num(e.get("outputValue"));
        if (!(cost > 0) || !(gross > 0)) {
            return null;
        }
        double value = gross - Fees.bazaarSellTax(gross, c);
        double perDay = num(e.get("volume"));
        details.add("§7Get §f" + amount(outputCount) + "x " + m.name(output) + "§7, sold for §a" + Coins.compact(value)
            + " §8after tax");
        return new Opportunity(OpportunityKind.FUSION, key.toString(), "§f" + m.name(output),
            outputCount > 1 ? "§7x" + amount(outputCount) : "", cost, value, Double.NaN, positive(perDay), details,
            "/bz " + m.bazaarSearch(first), false, "BAZAAR");
    }

    static Opportunity npc(Map<String, Object> e, Market m) {
        String npc = Text.stripCodes(JsonTree.str(e.get("npcName")));
        String id = JsonTree.str(e.get("itemId"));
        if (id == null || npc == null) {
            return null;
        }
        List<String> details = new ArrayList<String>();
        details.add("§7Buy from §f" + npc + "§7:");
        double cost = 0;
        for (Object o : JsonTree.arr(e.get("costs"))) {
            Map<String, Object> part = JsonTree.obj(o);
            String tag = JsonTree.str(part.get("itemTag"));
            double amount = num0(part.get("amount"));
            double price = num(part.get("price"));
            if (COINS.equals(tag)) {
                cost += price;
                details.add("§7  §6" + Coins.compact(price) + " coins");
            } else {
                double paid = buyCost(m, tag, amount, price);
                if (!(paid >= 0)) {
                    return null;
                }
                cost += paid;
                details.add("§7  " + amount(amount) + "x " + m.name(tag) + ": §6" + Coins.compact(paid));
            }
        }
        if (!(cost > 0)) {
            cost = num(e.get("npcBuyPrice"));
        }
        double sell = num(e.get("sellPrice"));
        double value = sellValue(m, id, 1, sell, sell);
        if (!(cost > 0) || !(value > 0)) {
            return null;
        }
        double perDay = num(e.get("volume"));
        details.add("§7Sells for §a" + Coins.compact(value) + " §8after fees" + (perDay > 0 ? ", " + perDay(perDay) + " sold a day" : ""));
        String itemName = Text.stripCodes(JsonTree.str(e.get("itemName")));
        if (itemName == null || itemName.isEmpty()) {
            itemName = m.name(id);
        }
        boolean bazaar = m.bazaar(id) != null;
        return new Opportunity(OpportunityKind.NPC, "NPC:" + npc + ":" + id, Text.rarityColor(m.tier(id)) + itemName,
            "§8from " + npc, cost, value, Double.NaN, positive(perDay), details,
            bazaar ? "/bz " + m.bazaarSearch(id) : "/ahs " + itemName, !bazaar, bazaar ? "BAZAAR" : "AH");
    }

    static Opportunity books(Map<String, Object> e, Market m) {
        String start = JsonTree.str(e.get("startTag"));
        String end = JsonTree.str(e.get("endTag"));
        if (start == null || end == null) {
            return null;
        }
        int from = level(start);
        int to = level(end);
        int steps = to - from;
        if (from <= 0 || steps < 1 || steps > 6) {
            return null;
        }
        int books = 1 << steps;
        BazaarProduct buy = m.bazaar(start);
        BazaarProduct sell = m.bazaar(end);
        if (buy == null || sell == null || !(buy.instaBuy() > 0) || !(sell.instaSell() > 0)) {
            return null;
        }
        double cost = books * buy.instaBuy();
        double value = sell.instaSell() - Fees.bazaarSellTax(sell.instaSell(), m.config());
        double perHour = num(e.get("hourlyVolume"));
        List<String> details = new ArrayList<String>();
        details.add("§7Buy §f" + books + "x " + m.name(start) + " §7for §6" + Coins.compact(cost));
        details.add("§7Combine them in an anvil into §f" + m.name(end));
        details.add("§7Sell it for §a" + Coins.compact(value) + " §8after tax"
            + (perHour > 0 ? ", " + perDay(perHour) + " sold an hour" : ""));
        details.add("§8The anvil also costs experience levels.");
        return new Opportunity(OpportunityKind.BOOKS, "BOOKS:" + start + ">" + end, "§f" + m.name(end),
            "§8from " + books + "x " + Text.roman(from), cost, value, Double.NaN, perHour > 0 ? perHour * 24 : Double.NaN,
            details, "/bz " + m.bazaarSearch(start), false, "BAZAAR");
    }

    /** Items with few auctions up for sale, by how much of them trades in a day. */
    static List<SupplyItem> parseLowSupply(List<Object> entries, Market market) {
        List<SupplyItem> out = new ArrayList<SupplyItem>();
        for (Object entry : entries) {
            Map<String, Object> e = JsonTree.obj(entry);
            String tag = JsonTree.str(e.get("tag"));
            if (tag == null) {
                continue;
            }
            Map<String, Object> lbin = JsonTree.obj(e.get("lbinData"));
            out.add(new SupplyItem(tag, market.name(tag), market.tier(tag), (int) num0(e.get("supply")), num(e.get("median")),
                num(lbin.get("lowest")), JsonTree.str(lbin.get("uuid")), num(e.get("volume"))));
        }
        Collections.sort(out, new Comparator<SupplyItem>() {
            @Override
            public int compare(SupplyItem a, SupplyItem b) {
                return Double.compare(b.dailyValue(), a.dailyValue());
            }
        });
        return out;
    }

    // ---- Pricing ----

    /**
     * Coins received selling {@code count} of an item: the bazaar's instant-sell price after tax
     * when it is a bazaar product, else SkyCofl's auction prices (read as the valuation strategy
     * says) after the auction fees. NaN when there is no price.
     */
    static double sellValue(Market m, String id, double count, double lowest, double median) {
        Config c = m.config();
        BazaarProduct product = id == null ? null : m.bazaar(id);
        if (product != null && product.instaSell() > 0) {
            double gross = product.instaSell() * count;
            return gross - Fees.bazaarSellTax(gross, c);
        }
        double unit = auctionPrice(c.ahStrategy, lowest, median);
        if (!(unit > 0)) {
            return Double.NaN;
        }
        double gross = unit * count;
        return gross - Fees.auctionSellFees(gross, c);
    }

    static double auctionPrice(String strategy, double lowest, double median) {
        boolean hasLowest = lowest > 0;
        boolean hasMedian = median > 0;
        if ("lbin".equals(strategy)) {
            return hasLowest ? lowest : median;
        }
        if ("sales".equals(strategy)) {
            return hasMedian ? median : lowest;
        }
        return hasLowest && hasMedian ? Math.min(lowest, median) : hasLowest ? lowest : median;
    }

    /** Coins to buy {@code count} of an item instantly: the bazaar's price when we have it, else SkyCofl's. */
    static double buyCost(Market m, String id, double count, double coflCost) {
        BazaarProduct product = id == null ? null : m.bazaar(id);
        if (product != null && product.instaBuy() > 0) {
            return product.instaBuy() * count;
        }
        return coflCost;
    }

    // ---- Helpers ----

    private static String materialList(Map<String, Object> materials, Market m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : materials.entrySet()) {
            sb.append(sb.length() > 0 ? ", " : "").append(amount(num0(entry.getValue()))).append("x ").append(m.name(entry.getKey()));
        }
        return sb.toString();
    }

    /** "ENCHANTMENT_GREEN_THUMB_4" -> 4, 0 when there is no level. */
    static int level(String tag) {
        int underscore = tag.lastIndexOf('_');
        if (underscore < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(tag.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String duration(double hours) {
        return Text.duration((long) (hours * 3_600_000L));
    }

    static String perDay(double count) {
        return count >= 1000 ? Coins.compact(count) : Coins.decimal(count, count < 10 ? 1 : 0);
    }

    private static String amount(double count) {
        return count == Math.rint(count) ? String.valueOf((long) count) : Coins.decimal(count, 1);
    }

    private static double num(Object o) {
        return JsonTree.num(o, Double.NaN);
    }

    private static double num0(Object o) {
        double v = JsonTree.num(o, 0);
        return Double.isNaN(v) ? 0 : v;
    }

    private static double positive(double v) {
        return v > 0 ? v : Double.NaN;
    }

    private static String upper(String s) {
        return s == null || s.isEmpty() ? null : s.toUpperCase(Locale.ROOT);
    }
}
