package dev.flipscout.core.item;

import dev.flipscout.core.Log;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.nbt.NbtReader;
import dev.flipscout.core.util.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Works out the {@link ItemKey} of an item from its NBT. */
public final class ItemKeys {
    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl (\\d+)]");
    /**
     * Pets that level past 100. Coflnet reads "level 100" as "100 or more" for them, so their level
     * 100 bucket gets no Coflnet price.
     */
    private static final Set<String> LEVEL_200_PETS = new HashSet<String>(Arrays.asList(
        "GOLDEN_DRAGON", "JADE_DRAGON", "ROSE_DRAGON"));
    private static final String[] GEM_QUALITIES = {"ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT"};
    /**
     * Enchantments the enchanting table gives up to level 5: at those levels their books are worth
     * next to nothing, so they are not even stored.
     */
    private static final Set<String> TABLE_ENCHANTS = new HashSet<String>(Arrays.asList(
        "SHARPNESS", "SMITE", "BANE_OF_ARTHROPODS", "CRITICAL", "CUBISM", "ENDER_SLAYER", "EXECUTE", "EXPERIENCE",
        "FIRE_ASPECT", "FIRST_STRIKE", "GIANT_KILLER", "IMPALING", "KNOCKBACK", "LETHALITY", "LIFE_STEAL", "LOOTING",
        "LUCK", "SCAVENGER", "THUNDERLORD", "VAMPIRISM", "VENOMOUS", "SYPHON", "TITAN_KILLER", "TRIPLE_STRIKE",
        "CLEAVE", "POWER", "PUNCH", "FLAME", "INFINITE_QUIVER", "SNIPE", "AIMING", "PIERCING", "THUNDERBOLT",
        "PROTECTION", "GROWTH", "THORNS", "RESPIRATION", "AQUA_AFFINITY", "DEPTH_STRIDER", "FEATHER_FALLING",
        "FIRE_PROTECTION", "BLAST_PROTECTION", "PROJECTILE_PROTECTION", "REJUVENATE", "SUGAR_RUSH", "EFFICIENCY",
        "FORTUNE", "SILK_TOUCH", "SMELTING_TOUCH", "HARVESTING", "RAINBOW", "ANGLER", "BLESSING", "CASTER", "FRAIL",
        "LUCK_OF_THE_SEA", "LURE", "MAGNET", "SPIKED_HOOK"));

    private ItemKeys() {
    }

    /** Result of decoding an auction's item bytes. */
    public static final class Decoded {
        public final ItemKey key;
        public final int count;
        /** Display name without colour codes (from the API, else from the NBT). */
        public final String name;
        /** Valuable upgrades applied to the item, or null. */
        public final Upgrades upgrades;
        /** Pet candy used on a pet (0 when none); -1 for other items and pets that do not say. */
        public final int petCandy;

        public Decoded(ItemKey key, int count, String name) {
            this(key, count, name, null);
        }

        public Decoded(ItemKey key, int count, String name, Upgrades upgrades) {
            this(key, count, name, upgrades, -1);
        }

        public Decoded(ItemKey key, int count, String name, Upgrades upgrades, int petCandy) {
            this.key = key;
            this.count = count;
            this.name = name;
            this.upgrades = upgrades;
            this.petCandy = petCandy;
        }
    }

    public static Decoded decode(String itemBytes, String itemName) throws IOException {
        Map<String, Object> root = NbtReader.readBase64(itemBytes);
        List<Object> items = NbtReader.list(root.get("i"));
        if (items.isEmpty()) {
            return null;
        }
        Map<String, Object> item = NbtReader.compound(items.get(0));
        int count = Math.max(1, NbtReader.intValue(item.get("Count"), 1));
        Map<String, Object> tag = NbtReader.compound(item.get("tag"));
        String name = Text.stripCodes(itemName);
        if (name == null || name.isEmpty()) {
            name = Text.stripCodes(NbtReader.string(NbtReader.compound(tag.get("display")).get("Name")));
        }
        Map<String, Object> extra = NbtReader.compound(tag.get("ExtraAttributes"));
        if ("PET".equals(upper(NbtReader.string(extra.get("id"))))) {
            // One read of petInfo for the key, the held item and the candy.
            Map<String, Object> info = petInfo(extra);
            return new Decoded(pet(info, name), count, name, count == 1 ? petUpgrades(info) : null, petCandy(info));
        }
        ItemKey key = fromExtraAttributes(extra, name);
        if (key == null) {
            return null;
        }
        return new Decoded(key, count, name, count == 1 ? upgrades(key.id, extra) : null);
    }

    /** {@link #decode} that logs and returns null on malformed data. */
    public static Decoded decodeQuiet(String itemBytes, String itemName) {
        if (itemBytes == null || itemBytes.isEmpty()) {
            return null;
        }
        try {
            return decode(itemBytes, itemName);
        } catch (IOException e) {
            Log.debug("unreadable item_bytes for " + itemName + ": " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            Log.debug("unreadable item_bytes for " + itemName + ": " + e);
            return null;
        }
    }

    public static ItemKey fromExtraAttributes(Map<String, Object> extra, String itemName) {
        String id = upper(NbtReader.string(extra.get("id")));
        if (id == null || id.isEmpty()) {
            return null;
        }
        switch (id) {
            case "PET":
                return pet(petInfo(extra), itemName);
            case "ENCHANTED_BOOK":
                return book(extra);
            case "RUNE":
            case "UNIQUE_RUNE":
                return rune(id, extra);
            case "POTION":
                return potion(extra);
            case "ATTRIBUTE_SHARD":
                return attributeShard(extra);
            default:
                return plain(id, extra);
        }
    }

    /**
     * Valuable upgrades applied to an item, or null. Books, runes, potions and shards are fully
     * described by their key already.
     */
    public static Upgrades upgrades(String id, Map<String, Object> extra) {
        if ("PET".equals(id)) {
            return petUpgrades(petInfo(extra));
        }
        Upgrades.Builder b = new Upgrades.Builder();
        if ("ENCHANTED_BOOK".equals(id) || "RUNE".equals(id) || "UNIQUE_RUNE".equals(id) || "POTION".equals(id)
            || "ATTRIBUTE_SHARD".equals(id)) {
            return null;
        }
        if (NbtReader.intValue(extra.get("rarity_upgrades"), 0) > 0) {
            b.add(Upgrades.Kind.RECOMBOBULATOR, "RECOMBOBULATOR_3000", 1);
        }
        int potatoBooks = NbtReader.intValue(extra.get("hot_potato_count"), 0);
        b.add(Upgrades.Kind.HOT_POTATO_BOOK, "HOT_POTATO_BOOK", Math.min(potatoBooks, 10));
        b.add(Upgrades.Kind.FUMING_POTATO_BOOK, "FUMING_POTATO_BOOK", Math.min(potatoBooks - 10, 5));
        for (Map.Entry<String, Object> entry : NbtReader.compound(extra.get("enchantments")).entrySet()) {
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            int level = NbtReader.intValue(entry.getValue(), 0);
            if (level > 0 && !(level <= 5 && TABLE_ENCHANTS.contains(name))) {
                b.add(Upgrades.Kind.ENCHANTMENT, "ENCHANTMENT_" + name + "_" + level, 1);
            }
        }
        // What stars cost depends on the item: priced later from its upgrade costs.
        int stars = Math.max(NbtReader.intValue(extra.get("upgrade_level"), 0),
            NbtReader.intValue(extra.get("dungeon_item_level"), 0));
        b.add(Upgrades.Kind.STARS, id, stars);
        gems(b, NbtReader.compound(extra.get("gems")));
        for (Object scroll : NbtReader.list(extra.get("ability_scroll"))) {
            b.add(Upgrades.Kind.ABILITY_SCROLL, NbtReader.string(scroll), 1);
        }
        b.add(Upgrades.Kind.ART_OF_WAR, "THE_ART_OF_WAR", NbtReader.intValue(extra.get("art_of_war_count"), 0));
        b.add(Upgrades.Kind.ART_OF_PEACE, "THE_ART_OF_PEACE", NbtReader.intValue(extra.get("artOfPeaceApplied"), 0));
        b.add(Upgrades.Kind.WOOD_SINGULARITY, "WOOD_SINGULARITY",
            NbtReader.intValue(extra.get("wood_singularity_count"), 0));
        b.add(Upgrades.Kind.FARMING_FOR_DUMMIES, "FARMING_FOR_DUMMIES",
            NbtReader.intValue(extra.get("farming_for_dummies_count"), 0));
        b.add(Upgrades.Kind.TRANSMISSION_TUNER, "TRANSMISSION_TUNER",
            NbtReader.intValue(extra.get("tuned_transmission"), 0));
        b.add(Upgrades.Kind.MANA_DISINTEGRATOR, "MANA_DISINTEGRATOR",
            NbtReader.intValue(extra.get("mana_disintegrator_count"), 0));
        b.add(Upgrades.Kind.JALAPENO_BOOK, "JALAPENO_BOOK", NbtReader.intValue(extra.get("jalapeno_count"), 0));
        b.add(Upgrades.Kind.POLARVOID_BOOK, "POLARVOID_BOOK", NbtReader.intValue(extra.get("polarvoid"), 0));
        if (NbtReader.intValue(extra.get("ethermerge"), 0) > 0) {
            b.add(Upgrades.Kind.ETHERWARP, "ETHERWARP_MERGER", 1).add(Upgrades.Kind.ETHERWARP, "ETHERWARP_CONDUIT", 1);
        }
        b.add(Upgrades.Kind.POWER_SCROLL, NbtReader.string(extra.get("power_ability_scroll")), 1);
        b.add(Upgrades.Kind.DYE, NbtReader.string(extra.get("dye_item")), 1);
        // Drill parts: newer "engine": {"id": "..."} or older "drill_part_engine": "titanium_drill_engine". Most
        // drills carry both for the same part, so each slot is counted once.
        for (String slot : new String[] {"upgrade_module", "fuel_tank", "engine"}) {
            String part = NbtReader.string(NbtReader.compound(extra.get(slot)).get("id"));
            if (part == null || part.isEmpty()) {
                part = NbtReader.string(extra.get("drill_part_" + slot));
            }
            b.add(Upgrades.Kind.DRILL_PART, part, 1);
        }
        // Fishing rod parts: "line": {"part": "speedy_line", ...}.
        for (String part : new String[] {"line", "sinker", "hook"}) {
            b.add(Upgrades.Kind.ROD_PART, NbtReader.string(NbtReader.compound(extra.get(part)).get("part")), 1);
        }
        b.add(Upgrades.Kind.SKIN, NbtReader.string(extra.get("skin")), 1);
        b.add(Upgrades.Kind.DIVAN_POWDER_COATING, "DIVAN_POWDER_COATING",
            NbtReader.intValue(extra.get("divan_powder_coating"), 0));
        String enrichment = NbtReader.string(extra.get("talisman_enrichment"));
        if (enrichment != null) {
            b.add(Upgrades.Kind.TALISMAN_ENRICHMENT, "TALISMAN_ENRICHMENT_" + enrichment, 1);
        }
        b.add(Upgrades.Kind.WET_BOOK, "WET_BOOK", NbtReader.intValue(extra.get("wet_book_count"), 0));
        return b.build();
    }

    /**
     * Gemstones: {"RUBY_0": "FINE", "COMBAT_0": "PERFECT", "COMBAT_0_gem": "SAPPHIRE"}, where a
     * quality may also be a compound with a "quality" field.
     */
    private static void gems(Upgrades.Builder b, Map<String, Object> gems) {
        for (Map.Entry<String, Object> entry : gems.entrySet()) {
            String slot = entry.getKey();
            if (slot.endsWith("_gem") || "unlocked_slots".equals(slot)) {
                continue;
            }
            Object value = entry.getValue();
            String quality = value instanceof Map
                ? NbtReader.string(NbtReader.compound(value).get("quality")) : NbtReader.string(value);
            quality = upper(quality);
            if (quality == null || indexOf(GEM_QUALITIES, quality) < 0) {
                continue;
            }
            int underscore = slot.lastIndexOf('_');
            String type = underscore > 0 ? slot.substring(0, underscore) : slot;
            String chosen = NbtReader.string(gems.get(slot + "_gem"));
            if (chosen != null) {
                // Universal slots (COMBAT_0, ...) name the gemstone in a sibling entry.
                type = chosen;
            }
            b.add(Upgrades.Kind.GEMSTONE, quality + "_" + upper(type) + "_GEM", 1);
        }
    }

    /** Key to price an upgrade part by: its book key for ENCHANTMENT_X_N, else the plain item. */
    public static ItemKey partKey(String id) {
        if (id.startsWith("ENCHANTMENT_")) {
            String rest = id.substring("ENCHANTMENT_".length());
            int underscore = rest.lastIndexOf('_');
            if (underscore > 0) {
                return new ItemKey("ENCHANTED_BOOK", id, id, id);
            }
        }
        return new ItemKey(id, id, id, id);
    }

    /** A pet's petInfo: a JSON string inside the NBT ({"type": "ENDER_DRAGON", "candyUsed": 0, ...}). */
    private static Map<String, Object> petInfo(Map<String, Object> extra) {
        return JsonTree.obj(JsonTree.parseQuiet(NbtReader.string(extra.get("petInfo"))));
    }

    private static Upgrades petUpgrades(Map<String, Object> info) {
        return new Upgrades.Builder().add(Upgrades.Kind.PET_ITEM, JsonTree.str(info.get("heldItem")), 1).build();
    }

    /** How many pet candies were used on a pet (0 when none), or -1 when its petInfo does not say. */
    static int petCandy(Map<String, Object> info) {
        double candy = JsonTree.num(info.get("candyUsed"), Double.NaN);
        return Double.isNaN(candy) ? -1 : (int) Math.max(0, candy);
    }

    private static ItemKey pet(Map<String, Object> info, String itemName) {
        String type = upper(JsonTree.str(info.get("type")));
        if (type == null) {
            return new ItemKey("PET", "PET_UNKNOWN", null, null);
        }
        String tier = upper(JsonTree.str(info.get("tier")));
        StringBuilder key = new StringBuilder("PET_").append(type);
        if (tier != null) {
            key.append(';').append(tier);
        }
        // Level matters a lot for value, but exact levels would leave too few comparables.
        int level = petLevel(itemName);
        String bucket = levelBucket(level);
        key.append(';').append(bucket);
        String skin = upper(JsonTree.str(info.get("skin")));
        if (skin != null) {
            key.append(";SKIN_").append(skin);
        }
        // The same rarity, levels and skin on Coflnet: "PET_ENDER_DRAGON?Rarity=LEGENDARY&PetLevel=90-99&PetSkin=None".
        String cofl = null;
        if (tier != null && level > 0 && !(level == 100 && LEVEL_200_PETS.contains(type))) {
            cofl = "PET_" + type + "?Rarity=" + tier + "&PetLevel=" + bucket.substring(1) + "&PetSkin="
                + (skin == null ? "None" : skin);
        }
        return new ItemKey("PET", key.toString(), cofl, null);
    }

    private static ItemKey book(Map<String, Object> extra) {
        Map<String, Object> enchantments = NbtReader.compound(extra.get("enchantments"));
        if (enchantments.size() == 1) {
            Map.Entry<String, Object> entry = enchantments.entrySet().iterator().next();
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            int level = NbtReader.intValue(entry.getValue(), 0);
            String key = "ENCHANTMENT_" + name + "_" + level;
            return new ItemKey("ENCHANTED_BOOK", key, key, key);
        }
        if (enchantments.isEmpty()) {
            return new ItemKey("ENCHANTED_BOOK", "ENCHANTED_BOOK", null, null);
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : enchantments.entrySet()) {
            parts.add(entry.getKey().toUpperCase(Locale.ROOT) + "_" + NbtReader.intValue(entry.getValue(), 0));
        }
        Collections.sort(parts);
        return new ItemKey("ENCHANTED_BOOK", "ENCHANTED_BOOK[" + join(parts) + "]", null, null);
    }

    private static ItemKey rune(String id, Map<String, Object> extra) {
        Map<String, Object> runes = NbtReader.compound(extra.get("runes"));
        if (runes.size() != 1) {
            return new ItemKey(id, id + "_UNKNOWN", null, null);
        }
        Map.Entry<String, Object> entry = runes.entrySet().iterator().next();
        String type = entry.getKey().toUpperCase(Locale.ROOT);
        int level = NbtReader.intValue(entry.getValue(), 0);
        // Coflnet: "RUNE_MUSIC?MusicRune=3".
        String cofl = "RUNE".equals(id) && level > 0
            ? "RUNE_" + type + "?" + Text.titleCase(type).replace(" ", "") + "Rune=" + level : null;
        return new ItemKey(id, id + "_" + type + "_" + level, cofl, null);
    }

    private static ItemKey potion(Map<String, Object> extra) {
        String potion = upper(NbtReader.string(extra.get("potion")));
        StringBuilder key = new StringBuilder("POTION_").append(potion == null ? "UNKNOWN" : potion)
            .append('_').append(NbtReader.intValue(extra.get("potion_level"), 0));
        if (NbtReader.truthy(extra.get("enhanced"))) {
            key.append("_ENHANCED");
        }
        if (NbtReader.truthy(extra.get("extended"))) {
            key.append("_EXTENDED");
        }
        if (NbtReader.truthy(extra.get("splash"))) {
            key.append("_SPLASH");
        }
        return new ItemKey("POTION", key.toString(), null, null);
    }

    private static ItemKey attributeShard(Map<String, Object> extra) {
        Map<String, Object> attributes = NbtReader.compound(extra.get("attributes"));
        if (attributes.size() == 1) {
            Map.Entry<String, Object> entry = attributes.entrySet().iterator().next();
            String key = "ATTRIBUTE_SHARD_" + entry.getKey().toUpperCase(Locale.ROOT) + "_"
                + NbtReader.intValue(entry.getValue(), 0);
            return new ItemKey("ATTRIBUTE_SHARD", key, null, null);
        }
        return new ItemKey("ATTRIBUTE_SHARD", "ATTRIBUTE_SHARD" + attributeSuffix(attributes), null, null);
    }

    private static ItemKey plain(String id, Map<String, Object> extra) {
        StringBuilder key = new StringBuilder(id);
        boolean exact = true;
        Map<String, Object> attributes = NbtReader.compound(extra.get("attributes"));
        if (!attributes.isEmpty()) {
            // Armour with rolled attributes: the roll decides the price, so keep rolls apart.
            key.append(attributeSuffix(attributes));
            exact = false;
        }
        if ("NEW_YEAR_CAKE".equals(id)) {
            key.append('[').append(NbtReader.intValue(extra.get("new_years_cake"), 0)).append(']');
            exact = false;
        }
        // A Midas weapon is worth what was bid for it in the Dark Auction.
        int bid = NbtReader.intValue(extra.get("winning_bid"), 0);
        if (bid > 0 && id.contains("MIDAS")) {
            key.append("[BID ").append(bid < 10_000_000 ? "<10M" : bid < 25_000_000 ? "10-25M"
                : bid < 50_000_000 ? "25-50M" : bid < 100_000_000 ? "50-100M" : "100M+").append(']');
            exact = false;
        }
        // Party hats differ by colour, emoji and year.
        for (String field : new String[] {"party_hat_color", "party_hat_emoji", "party_hat_year"}) {
            Object value = extra.get(field);
            String text = value instanceof Number ? String.valueOf(((Number) value).intValue()) : upper(NbtReader.string(value));
            if (text != null) {
                key.append('[').append(text).append(']');
                exact = false;
            }
        }
        return new ItemKey(id, key.toString(), exact ? id : null, exact ? id : null);
    }

    static int petLevel(String itemName) {
        if (itemName == null) {
            return 0;
        }
        Matcher m = PET_LEVEL.matcher(itemName);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String levelBucket(int level) {
        if (level <= 0) {
            return "L?";
        }
        if (level == 1) {
            return "L1";
        }
        if (level < 30) {
            return "L2-29";
        }
        if (level < 60) {
            return "L30-59";
        }
        if (level < 80) {
            return "L60-79";
        }
        if (level < 90) {
            return "L80-89";
        }
        if (level < 100) {
            return "L90-99";
        }
        if (level == 100) {
            return "L100";
        }
        if (level < 150) {
            return "L101-149";
        }
        if (level < 200) {
            return "L150-199";
        }
        return "L200";
    }

    private static String attributeSuffix(Map<String, Object> attributes) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            parts.add(entry.getKey().toUpperCase(Locale.ROOT) + ":" + NbtReader.intValue(entry.getValue(), 0));
        }
        Collections.sort(parts);
        return "[" + join(parts) + "]";
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static String upper(String s) {
        return s == null || s.isEmpty() ? null : s.toUpperCase(Locale.ROOT);
    }

    private static int indexOf(String[] values, String value) {
        if (value == null) {
            return -1;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }
}
