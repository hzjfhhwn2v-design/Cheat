package dev.flipscout.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Builds item_bytes and API JSON in the formats Hypixel serves. */
public final class TestData {
    private TestData() {
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** Base64 gzip NBT of {i: [{id, Count, Damage, tag: {ExtraAttributes, display: {Name}}}]}. */
    public static String itemBytes(Map<String, Object> extraAttributes, String displayName, int count) {
        Map<String, Object> tag = map("ExtraAttributes", extraAttributes);
        if (displayName != null) {
            tag.put("display", map("Name", displayName));
        }
        Map<String, Object> item = map("id", (short) 1, "Count", (byte) count, "Damage", (short) 0, "tag", tag);
        List<Object> items = new ArrayList<Object>();
        items.add(item);
        return encode(map("i", items));
    }

    public static String plainItem(String id) {
        return itemBytes(map("id", id), null, 1);
    }

    public static String stack(String id, int count) {
        return itemBytes(map("id", id), null, count);
    }

    public static String pet(String type, String tier, String displayName) {
        String petInfo = "{\"type\":\"" + type + "\",\"active\":false,\"exp\":0.0,\"tier\":\"" + tier + "\",\"hideInfo\":false}";
        return itemBytes(map("id", "PET", "petInfo", petInfo), displayName, 1);
    }

    public static String book(String enchant, int level) {
        return itemBytes(map("id", "ENCHANTED_BOOK", "enchantments", map(enchant, level)), null, 1);
    }

    public static String encode(Map<String, Object> root) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes));
            out.writeByte(10);
            out.writeUTF("");
            writeCompound(out, root);
            out.close();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            out.writeByte(type(entry.getValue()));
            out.writeUTF(entry.getKey());
            writePayload(out, entry.getValue());
        }
        out.writeByte(0);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object value) throws IOException {
        if (value instanceof Byte) {
            out.writeByte((Byte) value);
        } else if (value instanceof Short) {
            out.writeShort((Short) value);
        } else if (value instanceof Integer) {
            out.writeInt((Integer) value);
        } else if (value instanceof Long) {
            out.writeLong((Long) value);
        } else if (value instanceof Float) {
            out.writeFloat((Float) value);
        } else if (value instanceof Double) {
            out.writeDouble((Double) value);
        } else if (value instanceof String) {
            out.writeUTF((String) value);
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            out.writeByte(list.isEmpty() ? 0 : type(list.get(0)));
            out.writeInt(list.size());
            for (Object element : list) {
                writePayload(out, element);
            }
        } else if (value instanceof Map) {
            writeCompound(out, (Map<String, Object>) value);
        } else {
            throw new IllegalArgumentException("Unsupported NBT value " + value);
        }
    }

    private static int type(Object value) {
        if (value instanceof Byte) {
            return 1;
        }
        if (value instanceof Short) {
            return 2;
        }
        if (value instanceof Integer) {
            return 3;
        }
        if (value instanceof Long) {
            return 4;
        }
        if (value instanceof Float) {
            return 5;
        }
        if (value instanceof Double) {
            return 6;
        }
        if (value instanceof String) {
            return 8;
        }
        if (value instanceof List) {
            return 9;
        }
        if (value instanceof Map) {
            return 10;
        }
        throw new IllegalArgumentException("Unsupported NBT value " + value);
    }

    // ---- API JSON ----

    public static String auction(String uuid, String seller, String itemName, String tier, long price, boolean bin,
                                 long start, String itemBytes) {
        return "{\"uuid\":\"" + uuid + "\",\"auctioneer\":\"" + seller + "\",\"profile_id\":\"p\",\"coop\":[\"" + seller
            + "\"],\"start\":" + start + ",\"end\":" + (start + 86_400_000L) + ",\"item_name\":\"" + itemName
            + "\",\"item_lore\":\"§7Lore\\n§6§lLEGENDARY\",\"extra\":\"x\",\"category\":\"weapon\",\"tier\":\"" + tier
            + "\",\"starting_bid\":" + price + ",\"item_bytes\":\"" + itemBytes
            + "\",\"claimed\":false,\"claimed_bidders\":[],\"highest_bid_amount\":0,\"last_updated\":" + start
            + (bin ? ",\"bin\":true" : "") + ",\"bids\":[]}";
    }

    public static String auctionPage(int page, int totalPages, long lastUpdated, String... auctions) {
        return "{\"success\":true,\"page\":" + page + ",\"totalPages\":" + totalPages + ",\"totalAuctions\":"
            + auctions.length + ",\"lastUpdated\":" + lastUpdated + ",\"auctions\":[" + join(auctions) + "]}";
    }

    public static String ended(String auctionId, long price, boolean bin, long timestamp, String itemBytes) {
        return "{\"auction_id\":\"" + auctionId + "\",\"seller\":\"s\",\"seller_profile\":\"sp\",\"buyer\":\"b\",\"timestamp\":"
            + timestamp + ",\"price\":" + price + ",\"bin\":" + bin + ",\"item_bytes\":\"" + itemBytes + "\"}";
    }

    public static String endedPage(long lastUpdated, String... auctions) {
        return "{\"success\":true,\"lastUpdated\":" + lastUpdated + ",\"auctions\":[" + join(auctions) + "]}";
    }

    /** offers/orders are {price, amount} pairs. */
    public static String product(String id, double[][] sellOffers, double[][] buyOrders, double buyMovingWeek,
                                 double sellMovingWeek) {
        return "\"" + id + "\":{\"product_id\":\"" + id + "\",\"sell_summary\":" + summary(buyOrders)
            + ",\"buy_summary\":" + summary(sellOffers) + ",\"quick_status\":{\"productId\":\"" + id
            + "\",\"sellPrice\":1.0,\"sellVolume\":1,\"sellMovingWeek\":" + sellMovingWeek
            + ",\"sellOrders\":1,\"buyPrice\":1.0,\"buyVolume\":1,\"buyMovingWeek\":" + buyMovingWeek + ",\"buyOrders\":1}}";
    }

    public static String bazaar(long lastUpdated, String... products) {
        return "{\"success\":true,\"lastUpdated\":" + lastUpdated + ",\"products\":{" + join(products) + "}}";
    }

    private static String summary(double[][] rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"amount\":").append((long) rows[i][1]).append(",\"pricePerUnit\":").append(rows[i][0])
                .append(",\"orders\":1}");
        }
        return sb.append(']').toString();
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
