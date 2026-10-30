package dev.flipscout.core.api;

import dev.flipscout.core.util.Text;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names, rarities and NPC sell prices from /v2/resources/skyblock/items, plus Coflnet's names for
 * what that list lacks (shards, enchantments and many other bazaar products).
 */
public final class ItemCatalog {
    public static final ItemCatalog EMPTY = new ItemCatalog(Collections.<String, Info>emptyMap());
    /** Coflnet names enchantments "duplex 1 enchant": the in-game name, which may differ from the id. */
    private static final Pattern COFLNET_ENCHANT = Pattern.compile("(.+?) (\\d+) enchant");

    public static final class Info {
        public final String id;
        public final String name;
        public final String tier;
        /** Coins an NPC pays for one item, 0 when it cannot be sold to NPCs. */
        public final double npcSellPrice;
        /** What each star costs (index 0 = first star), or null when the item has no stars. */
        public final StarCost[][] upgradeCosts;
        /** Dungeon items take master stars after their fifth star. */
        public final boolean dungeonItem;

        public Info(String id, String name, String tier, double npcSellPrice) {
            this(id, name, tier, npcSellPrice, null, false);
        }

        public Info(String id, String name, String tier, double npcSellPrice, StarCost[][] upgradeCosts,
                    boolean dungeonItem) {
            this.id = id;
            this.name = name;
            this.tier = tier;
            this.npcSellPrice = npcSellPrice;
            this.upgradeCosts = upgradeCosts;
            this.dungeonItem = dungeonItem;
        }
    }

    /** {@code amount} of item {@code id} (essences as ESSENCE_WITHER and so on). */
    public static final class StarCost {
        public final String id;
        public final int amount;

        public StarCost(String id, int amount) {
            this.id = id;
            this.amount = amount;
        }
    }

    private final Map<String, Info> byId;
    /** Coflnet's names by tag. */
    private final Map<String, String> otherNames;
    /** Simplified name -> id, built when first needed. */
    private volatile Map<String, String> byName;

    public ItemCatalog(Map<String, Info> byId) {
        this(byId, Collections.<String, String>emptyMap());
    }

    private ItemCatalog(Map<String, Info> byId, Map<String, String> otherNames) {
        this.byId = byId;
        this.otherNames = otherNames;
    }

    /** This catalog with Coflnet's names (replacing any it had). */
    public ItemCatalog withNames(Map<String, String> names) {
        return new ItemCatalog(byId, names);
    }

    /** This catalog's Coflnet names, to carry over to a newer item list. */
    public Map<String, String> names() {
        return otherNames;
    }

    public int nameCount() {
        return otherNames.size();
    }

    public Info get(String id) {
        return id == null ? null : byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    public Collection<String> ids() {
        return Collections.unmodifiableCollection(byId.keySet());
    }

    public String displayName(String id) {
        Info info = get(id);
        if (info != null && info.name != null && !info.name.isEmpty()) {
            return Text.stripCodes(info.name);
        }
        String name = otherNames.get(id);
        if (name != null && id.startsWith("ENCHANTMENT_")) {
            Matcher m = COFLNET_ENCHANT.matcher(name);
            if (m.matches()) {
                int level = Integer.parseInt(m.group(2));
                return Text.titleWords(m.group(1)) + (level > 0 ? " " + Text.roman(level) : "");
            }
            name = null;
        }
        return name != null ? name : deriveName(id);
    }

    /**
     * The id of an item from its name in chat ("Heroic Hyperion ✪✪✪✪✪", "[Lvl 100] Ender Dragon"),
     * or null when it is not known.
     */
    public String idForName(String name) {
        String simple = simplify(name);
        if (simple.isEmpty()) {
            return null;
        }
        if (simple.startsWith("lvl ")) {
            // Pets: "[Lvl 100] Ender Dragon" is PET_ENDER_DRAGON.
            int space = simple.indexOf(' ', 4);
            return space < 0 ? null : "PET_" + simple.substring(space + 1).replace(' ', '_').toUpperCase(Locale.ROOT);
        }
        Map<String, String> names = idsByName();
        String id = names.get(simple);
        int space = simple.indexOf(' ');
        if (id == null && space > 0) {
            // Reforged: "heroic hyperion".
            id = names.get(simple.substring(space + 1));
        }
        return id;
    }

    private Map<String, String> idsByName() {
        Map<String, String> map = byName;
        if (map == null) {
            map = new HashMap<String, String>(byId.size() + otherNames.size());
            for (Map.Entry<String, String> entry : otherNames.entrySet()) {
                if (!entry.getKey().startsWith("ENCHANTMENT_")) {
                    map.put(simplify(entry.getValue()), entry.getKey());
                }
            }
            // Hypixel's names win over Coflnet's.
            for (Info info : byId.values()) {
                if (info.name != null) {
                    map.put(simplify(info.name), info.id);
                }
            }
            map.remove("");
            byName = map;
        }
        return map;
    }

    /** Lowercase letters, digits and apostrophes, single spaces between words. */
    static String simplify(String name) {
        String plain = Text.stripCodes(name == null ? "" : name).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(plain.length());
        boolean gap = false;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '\'') {
                if (gap && sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(c);
                gap = false;
            } else {
                gap = true;
            }
        }
        return sb.toString();
    }

    /** Text to type after /bz to find the product. */
    public String bazaarSearch(String id) {
        if (id.startsWith("ENCHANTMENT_")) {
            // Books are listed per level; searching the enchant name finds them all.
            String name = otherNames.get(id);
            Matcher m = name == null ? null : COFLNET_ENCHANT.matcher(name);
            if (m != null && m.matches()) {
                return Text.titleWords(m.group(1));
            }
            String rest = id.substring("ENCHANTMENT_".length());
            int underscore = rest.lastIndexOf('_');
            if (underscore > 0 && isNumber(rest.substring(underscore + 1))) {
                rest = rest.substring(0, underscore);
            }
            return Text.titleCase(rest);
        }
        return displayName(id).replace("✪", "").trim();
    }

    public static String deriveName(String id) {
        if (id.startsWith("ENCHANTMENT_")) {
            String rest = id.substring("ENCHANTMENT_".length());
            int underscore = rest.lastIndexOf('_');
            if (underscore > 0 && isNumber(rest.substring(underscore + 1))) {
                return Text.titleCase(rest.substring(0, underscore)) + " "
                    + Text.roman(Integer.parseInt(rest.substring(underscore + 1)));
            }
            return Text.titleCase(rest);
        }
        if (id.startsWith("ESSENCE_")) {
            return Text.titleCase(id.substring("ESSENCE_".length())) + " Essence";
        }
        if (id.startsWith("SHARD_")) {
            return Text.titleCase(id.substring("SHARD_".length())) + " Shard";
        }
        return Text.titleCase(id);
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty() || s.length() > 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
