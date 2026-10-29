package dev.flipscout.core.api;

import dev.flipscout.core.util.Text;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/** Names, rarities and NPC sell prices from /v2/resources/skyblock/items. */
public final class ItemCatalog {
    public static final ItemCatalog EMPTY = new ItemCatalog(Collections.<String, Info>emptyMap());

    public static final class Info {
        public final String id;
        public final String name;
        public final String tier;
        /** Coins an NPC pays for one item, 0 when it cannot be sold to NPCs. */
        public final double npcSellPrice;

        public Info(String id, String name, String tier, double npcSellPrice) {
            this.id = id;
            this.name = name;
            this.tier = tier;
            this.npcSellPrice = npcSellPrice;
        }
    }

    private final Map<String, Info> byId;

    public ItemCatalog(Map<String, Info> byId) {
        this.byId = byId;
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
        return deriveName(id);
    }

    /** Text to type after /bz to find the product. */
    public String bazaarSearch(String id) {
        if (id.startsWith("ENCHANTMENT_")) {
            // Books are listed per level; searching the enchant name finds them all.
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
