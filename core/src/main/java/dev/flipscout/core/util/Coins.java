package dev.flipscout.core.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Formatting and parsing of coin amounts ("1.5M", "500k", ...). */
public final class Coins {
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    private static final Pattern GROUPED = Pattern.compile("[0-9]{1,3}([.,][0-9]{3})+");

    private Coins() {
    }

    /** Short form used in chat: 950, 12.3k, 1.25M, 3.4B. */
    public static String compact(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "?";
        }
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (abs < 1_000) {
            return sign + scaled(abs);
        }
        if (abs < 999_500) {
            return sign + scaled(abs / 1_000d) + "k";
        }
        if (abs < 999_500_000) {
            return sign + scaled(abs / 1_000_000d) + "M";
        }
        return sign + scaled(abs / 1_000_000_000d) + "B";
    }

    /** Full amount with thousands separators: 1,234,567. */
    public static String full(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "?";
        }
        return String.format(Locale.ROOT, "%,d", Math.round(value));
    }

    /** Bazaar unit price: one decimal below 1k ("4.7"), compact above. */
    public static String unit(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "?";
        }
        if (Math.abs(value) < 1_000) {
            return trim(String.format(Locale.ROOT, "%.1f", value));
        }
        return compact(value);
    }

    public static String decimal(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "?";
        }
        return trim(String.format(Locale.ROOT, "%." + decimals + "f", value));
    }

    private static String scaled(double v) {
        return trim(String.format(Locale.ROOT, v < 10 ? "%.2f" : v < 100 ? "%.1f" : "%.0f", v));
    }

    private static String trim(String s) {
        if (s.indexOf('.') < 0) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        String out = s.substring(0, end);
        return "-0".equals(out) ? "0" : out;
    }

    /**
     * Parses "1.5m", "500k", "2b", "1,5m", "1000000", "1.000.000" and "1,000,000".
     *
     * @throws NumberFormatException when the text is not a non-negative amount
     */
    public static long parse(String text) {
        if (text == null) {
            throw new NumberFormatException("empty value");
        }
        String s = text.trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        if (s.isEmpty()) {
            throw new NumberFormatException("empty value");
        }
        char last = s.charAt(s.length() - 1);
        double multiplier = 1;
        if (last == 'k' || last == 'm' || last == 'b') {
            multiplier = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            s = s.substring(0, s.length() - 1).replace(',', '.');
        } else if (GROUPED.matcher(s).matches()) {
            s = s.replace(".", "").replace(",", "");
        } else {
            s = s.replace(',', '.');
        }
        if (!NUMBER.matcher(s).matches()) {
            throw new NumberFormatException("invalid amount: " + text);
        }
        double value = Double.parseDouble(s) * multiplier;
        if (value > 1e15) {
            throw new NumberFormatException("amount too large: " + text);
        }
        return Math.round(value);
    }

    /** Parses a decimal such as "5", "5.5", "5,5" or "5%". */
    public static double parseDecimal(String text) {
        if (text == null) {
            throw new NumberFormatException("empty value");
        }
        String s = text.trim().replace("%", "").replace(',', '.');
        if (!NUMBER.matcher(s).matches()) {
            throw new NumberFormatException("invalid number: " + text);
        }
        return Double.parseDouble(s);
    }
}
