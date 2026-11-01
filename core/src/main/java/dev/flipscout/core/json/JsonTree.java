package dev.flipscout.core.json;

import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses small JSON documents into plain maps, lists, strings, doubles and booleans. */
public final class JsonTree {
    private JsonTree() {
    }

    @SuppressWarnings("deprecation") // setLenient: see Json.reader
    public static Object parse(Reader reader) throws IOException {
        JsonReader in = new JsonReader(reader);
        in.setLenient(true);
        return read(in);
    }

    public static Object parse(String text) throws IOException {
        return parse(new StringReader(text));
    }

    /** Like {@link #parse(String)} but returns null instead of failing. */
    public static Object parseQuiet(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return parse(text);
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Object read(JsonReader in) throws IOException {
        switch (in.peek()) {
            case BEGIN_OBJECT: {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                in.beginObject();
                while (in.hasNext()) {
                    String name = in.nextName();
                    map.put(name, read(in));
                }
                in.endObject();
                return map;
            }
            case BEGIN_ARRAY: {
                List<Object> list = new ArrayList<Object>();
                in.beginArray();
                while (in.hasNext()) {
                    list.add(read(in));
                }
                in.endArray();
                return list;
            }
            case STRING:
                return in.nextString();
            case NUMBER:
                return Json.readDouble(in, Double.NaN);
            case BOOLEAN:
                return in.nextBoolean();
            case NULL:
                in.nextNull();
                return null;
            default:
                throw new IOException("Unexpected JSON: " + in.peek());
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.emptyList();
    }

    public static double num(Object o, double fallback) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public static String str(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof String ? (String) o : String.valueOf(o);
    }

    /** Case-insensitive lookup of the first of {@code names} present in {@code map}. */
    public static Object field(Map<String, Object> map, String... names) {
        for (String name : names) {
            if (map.containsKey(name)) {
                return map.get(name);
            }
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (key.equals(name.toLowerCase(Locale.ROOT))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
}
