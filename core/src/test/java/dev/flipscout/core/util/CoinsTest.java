package dev.flipscout.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoinsTest {
    @Test
    void compactFormatsEachMagnitude() {
        assertEquals("950", Coins.compact(950));
        assertEquals("5.5", Coins.compact(5.5));
        assertEquals("12.3k", Coins.compact(12_345));
        assertEquals("999k", Coins.compact(999_499));
        assertEquals("1M", Coins.compact(999_999));
        assertEquals("1.25M", Coins.compact(1_250_000));
        assertEquals("820M", Coins.compact(820_000_000));
        assertEquals("1.23B", Coins.compact(1_234_567_890));
        assertEquals("-62.4M", Coins.compact(-62_400_000));
        assertEquals("?", Coins.compact(Double.NaN));
    }

    @Test
    void fullAndUnit() {
        assertEquals("1,234,567", Coins.full(1_234_567));
        assertEquals("4.7", Coins.unit(4.7));
        assertEquals("312.4", Coins.unit(312.44));
        assertEquals("1.23k", Coins.unit(1_234));
    }

    @Test
    void parsesShortAndLongForms() {
        assertEquals(1_500_000, Coins.parse("1.5m"));
        assertEquals(1_500_000, Coins.parse("1,5M"));
        assertEquals(500_000, Coins.parse("500k"));
        assertEquals(2_000_000_000L, Coins.parse("2b"));
        assertEquals(1_000_000, Coins.parse("1000000"));
        assertEquals(1_000_000, Coins.parse("1.000.000"));
        assertEquals(1_000_000, Coins.parse("1,000,000"));
        assertEquals(0, Coins.parse("0"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(NumberFormatException.class, () -> Coins.parse("abc"));
        assertThrows(NumberFormatException.class, () -> Coins.parse("-5"));
        assertThrows(NumberFormatException.class, () -> Coins.parse(""));
        assertThrows(NumberFormatException.class, () -> Coins.parse("1e9"));
        assertThrows(NumberFormatException.class, () -> Coins.parseDecimal("x%"));
        assertEquals(5.5, Coins.parseDecimal("5,5%"), 1e-9);
    }

    @Test
    void textHelpers() {
        assertEquals("Enchanted Diamond", Text.titleCase("ENCHANTED_DIAMOND"));
        assertEquals("Ink Sack", Text.titleCase("INK_SACK:3"));
        assertEquals("XIV", Text.roman(14));
        assertEquals("Hyperion", Text.stripCodes("§6Hyperion"));
        assertEquals("45 s", Text.duration(45_000));
        assertEquals("3 min", Text.duration(200_000));
        assertEquals("2.5 h", Text.duration(9_000_000));
    }
}
