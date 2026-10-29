package dev.flipscout.core.item;

import dev.flipscout.core.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Valuable things applied to an item (recombobulator, potato books, master stars, gems, ...), as
 * the items they are made of. Prices are looked up when a listing is judged, not stored here.
 */
public final class Upgrades {
    /** Kinds of upgrade, with the share of the part's price it adds to the item once applied. */
    public enum Kind {
        RECOMBOBULATOR(0.8),
        HOT_POTATO_BOOK(1.0),
        FUMING_POTATO_BOOK(0.6),
        ENCHANTMENT(0.85),
        /**
         * Stars, as the part's count with the item's own id: what they cost depends on the item
         * (essence for the first ones, master stars after the fifth on dungeon items).
         */
        STARS(1.0),
        GEMSTONE(1.0),
        ABILITY_SCROLL(1.0),
        ART_OF_WAR(0.6),
        ART_OF_PEACE(0.8),
        WOOD_SINGULARITY(0.5),
        FARMING_FOR_DUMMIES(0.5),
        TRANSMISSION_TUNER(0.7),
        MANA_DISINTEGRATOR(0.8),
        JALAPENO_BOOK(0.8),
        POLARVOID_BOOK(1.0),
        ETHERWARP(1.0),
        POWER_SCROLL(0.5),
        DYE(0.9),
        DRILL_PART(1.0),
        ROD_PART(1.0),
        SKIN(0.9),
        DIVAN_POWDER_COATING(0.8),
        TALISMAN_ENRICHMENT(0.5),
        WET_BOOK(0.8),
        PET_ITEM(1.0);

        public final double weight;

        Kind(double weight) {
            this.weight = weight;
        }
    }

    /** {@code count} of item {@code id} applied as {@code kind}. */
    public static final class Part {
        public final Kind kind;
        public final String id;
        public final int count;

        Part(Kind kind, String id, int count) {
            this.kind = kind;
            this.id = id;
            this.count = count;
        }

        /** "Recombobulator 3000", "10x Hot Potato Book", "Ultimate Wise V", "8 stelle". */
        public String label() {
            if (kind == Kind.STARS) {
                return count == 1 ? "1 stella" : count + " stelle";
            }
            String name;
            if (kind == Kind.ENCHANTMENT && id.startsWith("ENCHANTMENT_")) {
                String rest = id.substring("ENCHANTMENT_".length());
                int underscore = rest.lastIndexOf('_');
                name = underscore > 0 && isNumber(rest.substring(underscore + 1))
                    ? Text.titleCase(rest.substring(0, underscore)) + " "
                        + Text.roman(Integer.parseInt(rest.substring(underscore + 1)))
                    : Text.titleCase(rest);
            } else {
                name = Text.titleCase(id);
            }
            return count > 1 ? count + "x " + name : name;
        }
    }

    /** Master stars in the order they are applied, after the fifth star of a dungeon item. */
    public static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR",
        "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};
    /** Share of the essence and items spent on stars that a starred item is worth more. */
    public static final double STAR_COST_WEIGHT = 0.75;

    private static final Part[] NO_PARTS = new Part[0];
    /** Listings share part objects: the same few upgrades appear on thousands of them. */
    private static final ConcurrentHashMap<String, Part> PARTS = new ConcurrentHashMap<String, Part>();

    private final Part[] parts;

    private Upgrades(Part[] parts) {
        this.parts = parts;
    }

    public List<Part> parts() {
        return Collections.unmodifiableList(Arrays.asList(parts));
    }

    /** Collects parts; {@link #build} returns null when nothing was added. */
    static final class Builder {
        private final List<Part> parts = new ArrayList<Part>();

        Builder add(Kind kind, String id, int count) {
            if (id == null || id.isEmpty() || count <= 0) {
                return this;
            }
            String upper = id.toUpperCase(Locale.ROOT);
            String cacheKey = kind.name() + ':' + upper + ':' + count;
            Part part = PARTS.get(cacheKey);
            if (part == null) {
                Part created = new Part(kind, upper.intern(), count);
                part = PARTS.putIfAbsent(cacheKey, created);
                if (part == null) {
                    part = created;
                }
            }
            parts.add(part);
            return this;
        }

        Upgrades build() {
            return parts.isEmpty() ? null : new Upgrades(parts.toArray(NO_PARTS));
        }
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) {
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
