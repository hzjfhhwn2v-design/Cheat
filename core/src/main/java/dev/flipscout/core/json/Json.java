package dev.flipscout.core.json;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Tolerant readers on top of Gson's streaming {@link JsonReader}, whose API is identical from
 * Gson 2.2.4 (Minecraft 1.8.9) to current releases.
 */
public final class Json {
    private Json() {
    }

    // setLenient is deprecated in recent Gson, but Gson 2.2.4 (Minecraft 1.8.9) has nothing else.
    @SuppressWarnings("deprecation")
    public static JsonReader reader(InputStream in) {
        JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.setLenient(true);
        return reader;
    }

    public static String readString(JsonReader in) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) {
            return in.nextString();
        }
        if (token == JsonToken.BOOLEAN) {
            return String.valueOf(in.nextBoolean());
        }
        if (token == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        in.skipValue();
        return null;
    }

    public static long readLong(JsonReader in, long fallback) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NUMBER || token == JsonToken.STRING) {
            String text = in.nextString();
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                try {
                    double d = Double.parseDouble(text);
                    return Double.isNaN(d) ? fallback : (long) d;
                } catch (NumberFormatException e2) {
                    return fallback;
                }
            }
        }
        if (token == JsonToken.NULL) {
            in.nextNull();
            return fallback;
        }
        in.skipValue();
        return fallback;
    }

    public static double readDouble(JsonReader in, double fallback) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NUMBER || token == JsonToken.STRING) {
            String text = in.nextString();
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        if (token == JsonToken.NULL) {
            in.nextNull();
            return fallback;
        }
        in.skipValue();
        return fallback;
    }

    public static boolean readBoolean(JsonReader in, boolean fallback) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.BOOLEAN) {
            return in.nextBoolean();
        }
        if (token == JsonToken.STRING) {
            return Boolean.parseBoolean(in.nextString());
        }
        if (token == JsonToken.NUMBER) {
            return readDouble(in, 0) != 0;
        }
        if (token == JsonToken.NULL) {
            in.nextNull();
            return fallback;
        }
        in.skipValue();
        return fallback;
    }
}
