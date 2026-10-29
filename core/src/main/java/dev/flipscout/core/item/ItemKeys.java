package dev.flipscout.core.item;

import dev.flipscout.core.Log;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.nbt.NbtReader;
import dev.flipscout.core.util.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Works out the {@link ItemKey} of an item from its NBT. */
public final class ItemKeys {
    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl (\\d+)]");
    private static final String[] TIERS = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};

    private ItemKeys() {
    }

    /** Result of decoding an auction's item bytes. */
    public static final class Decoded {
        public final ItemKey key;
        public final int count;
        /** Display name without colour codes (from the API, else from the NBT). */
        public final String name;

        public Decoded(ItemKey key, int count, String name) {
            this.key = key;
            this.count = count;
            this.name = name;
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
        ItemKey key = fromExtraAttributes(NbtReader.compound(tag.get("ExtraAttributes")), name);
        return key == null ? null : new Decoded(key, count, name);
    }

    /** {@link #decode} that logs and returns null on malformed data. */
    public static Decoded decodeQuiet(String itemBytes, String itemName) {
        if (itemBytes == null || itemBytes.isEmpty()) {
            return null;
        }
        try {
            return decode(itemBytes, itemName);
        } catch (IOException e) {
            Log.debug("item_bytes illeggibile per " + itemName + ": " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            Log.debug("item_bytes illeggibile per " + itemName + ": " + e);
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
                return pet(extra, itemName);
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

    private static ItemKey pet(Map<String, Object> extra, String itemName) {
        Map<String, Object> info = JsonTree.obj(JsonTree.parseQuiet(NbtReader.string(extra.get("petInfo"))));
        String type = upper(JsonTree.str(info.get("type")));
        if (type == null) {
            return new ItemKey("PET", "PET_UNKNOWN", null, false, null, null);
        }
        String tier = upper(JsonTree.str(info.get("tier")));
        StringBuilder key = new StringBuilder("PET_").append(type);
        if (tier != null) {
            key.append(';').append(tier);
        }
        // Level matters a lot for value, but exact levels would leave too few comparables.
        key.append(';').append(levelBucket(petLevel(itemName)));
        String skin = upper(JsonTree.str(info.get("skin")));
        if (skin != null) {
            key.append(";SKIN_").append(skin);
        }
        int tierIndex = indexOf(TIERS, tier);
        String neu = tierIndex >= 0 ? type + ";" + tierIndex : null;
        return new ItemKey("PET", key.toString(), neu, false, null, null);
    }

    private static ItemKey book(Map<String, Object> extra) {
        Map<String, Object> enchantments = NbtReader.compound(extra.get("enchantments"));
        if (enchantments.size() == 1) {
            Map.Entry<String, Object> entry = enchantments.entrySet().iterator().next();
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            int level = NbtReader.intValue(entry.getValue(), 0);
            String key = "ENCHANTMENT_" + name + "_" + level;
            return new ItemKey("ENCHANTED_BOOK", key, name + ";" + level, true, key, key);
        }
        if (enchantments.isEmpty()) {
            return new ItemKey("ENCHANTED_BOOK", "ENCHANTED_BOOK", null, false, null, null);
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : enchantments.entrySet()) {
            parts.add(entry.getKey().toUpperCase(Locale.ROOT) + "_" + NbtReader.intValue(entry.getValue(), 0));
        }
        Collections.sort(parts);
        return new ItemKey("ENCHANTED_BOOK", "ENCHANTED_BOOK[" + join(parts) + "]", null, false, null, null);
    }

    private static ItemKey rune(String id, Map<String, Object> extra) {
        Map<String, Object> runes = NbtReader.compound(extra.get("runes"));
        if (runes.size() != 1) {
            return new ItemKey(id, id + "_UNKNOWN", null, false, null, null);
        }
        Map.Entry<String, Object> entry = runes.entrySet().iterator().next();
        String type = entry.getKey().toUpperCase(Locale.ROOT);
        int level = NbtReader.intValue(entry.getValue(), 0);
        String neu = "RUNE".equals(id) ? type + "_RUNE;" + level : null;
        return new ItemKey(id, id + "_" + type + "_" + level, neu, neu != null, null, null);
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
        return new ItemKey("POTION", key.toString(), null, false, null, null);
    }

    private static ItemKey attributeShard(Map<String, Object> extra) {
        Map<String, Object> attributes = NbtReader.compound(extra.get("attributes"));
        if (attributes.size() == 1) {
            Map.Entry<String, Object> entry = attributes.entrySet().iterator().next();
            String key = "ATTRIBUTE_SHARD_" + entry.getKey().toUpperCase(Locale.ROOT) + "_"
                + NbtReader.intValue(entry.getValue(), 0);
            return new ItemKey("ATTRIBUTE_SHARD", key, null, false, null, null);
        }
        return new ItemKey("ATTRIBUTE_SHARD", "ATTRIBUTE_SHARD" + attributeSuffix(attributes), null, false, null, null);
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
        String color = upper(NbtReader.string(extra.get("party_hat_color")));
        if (color != null) {
            key.append('[').append(color).append(']');
            exact = false;
        }
        // NEU's lowest BIN for the bare id is still a valid lower bound, so neuKey stays set.
        return new ItemKey(id, key.toString(), id, exact, exact ? id : null, exact ? id : null);
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
