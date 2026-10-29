package dev.flipscout.core.util;

import java.util.Locale;

public final class Text {
    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private Text() {
    }

    public static String stripCodes(String s) {
        if (s == null || s.indexOf('§') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String rarityColor(String tier) {
        if (tier == null) {
            return "§f";
        }
        switch (tier.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON":
                return "§a";
            case "RARE":
                return "§9";
            case "EPIC":
                return "§5";
            case "LEGENDARY":
                return "§6";
            case "MYTHIC":
                return "§d";
            case "DIVINE":
                return "§b";
            case "SPECIAL":
            case "VERY_SPECIAL":
                return "§c";
            case "ULTIMATE":
            case "ADMIN":
                return "§4";
            default:
                return "§f";
        }
    }

    /** ENCHANTED_DIAMOND -> Enchanted Diamond (anything after ':' is dropped). */
    public static String titleCase(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String base = id;
        int colon = base.indexOf(':');
        if (colon > 0) {
            base = base.substring(0, colon);
        }
        StringBuilder sb = new StringBuilder();
        for (String word : base.toLowerCase(Locale.ROOT).split("_+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    public static String roman(int n) {
        if (n <= 0 || n >= 4000) {
            return String.valueOf(n);
        }
        StringBuilder sb = new StringBuilder();
        int rest = n;
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (rest >= ROMAN_VALUES[i]) {
                rest -= ROMAN_VALUES[i];
                sb.append(ROMAN_SYMBOLS[i]);
            }
        }
        return sb.toString();
    }

    /** "12 s", "4 min", "3.5 h", "2 g" for a duration in milliseconds. */
    public static String duration(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long s = ms / 1000;
        if (s < 60) {
            return s + " s";
        }
        long min = s / 60;
        if (min < 60) {
            return min + " min";
        }
        double hours = min / 60.0;
        if (hours < 48) {
            return Coins.decimal(hours, 1) + " h";
        }
        return Coins.decimal(hours / 24.0, 1) + " g";
    }

    /** Time since {@code timestamp}, or "mai" (never) when it is 0. */
    public static String ago(long now, long timestamp) {
        return timestamp <= 0 ? "mai" : duration(now - timestamp) + " fa";
    }
}
