package dev.flipscout.core.nbt;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads binary NBT (as found base64+gzip encoded in the Hypixel API's {@code item_bytes}) into
 * plain Java values: compounds become maps, lists become lists, numbers stay boxed primitives.
 */
public final class NbtReader {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_LENGTH = 1 << 22;

    private NbtReader() {
    }

    public static Map<String, Object> readBase64(String base64) throws IOException {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            try {
                raw = Base64.getMimeDecoder().decode(base64);
            } catch (IllegalArgumentException e2) {
                throw new IOException("item_bytes non è base64 valido", e2);
            }
        }
        InputStream in = new ByteArrayInputStream(raw);
        if (raw.length >= 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) {
            in = new GZIPInputStream(in);
        }
        DataInputStream data = new DataInputStream(new BufferedInputStream(in));
        try {
            return readRoot(data);
        } finally {
            data.close();
        }
    }

    public static Map<String, Object> readRoot(DataInput in) throws IOException {
        int type = in.readByte();
        if (type != 10) {
            throw new IOException("La radice NBT non è un compound (tipo " + type + ")");
        }
        in.readUTF();
        return readCompound(in, 0);
    }

    private static Map<String, Object> readCompound(DataInput in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT troppo annidato");
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        while (true) {
            int type = in.readByte();
            if (type == 0) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInput in, int type, int depth) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] bytes = new byte[length(in)];
                in.readFully(bytes);
                return bytes;
            }
            case 8:
                return in.readUTF();
            case 9: {
                int elementType = in.readByte();
                int size = length(in);
                if (elementType == 0) {
                    if (size > 0) {
                        throw new IOException("Lista NBT senza tipo");
                    }
                    return Collections.emptyList();
                }
                if (depth > MAX_DEPTH) {
                    throw new IOException("NBT troppo annidato");
                }
                List<Object> list = new ArrayList<Object>(Math.min(size, 1024));
                for (int i = 0; i < size; i++) {
                    list.add(readPayload(in, elementType, depth + 1));
                }
                return list;
            }
            case 10:
                return readCompound(in, depth);
            case 11: {
                int[] ints = new int[length(in)];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case 12: {
                long[] longs = new long[length(in)];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("Tipo NBT sconosciuto: " + type);
        }
    }

    private static int length(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_LENGTH) {
            throw new IOException("Lunghezza NBT non valida: " + length);
        }
        return length;
    }

    // Accessors that tolerate missing or differently typed values.

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.emptyList();
    }

    public static String string(Object o) {
        return o instanceof String ? (String) o : null;
    }

    public static int intValue(Object o, int fallback) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean truthy(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue() != 0;
        }
        if (o instanceof String) {
            return "true".equalsIgnoreCase((String) o) || "1".equals(o);
        }
        return o instanceof Boolean && (Boolean) o;
    }
}
