package dev.flipscout.core.extra;

import java.util.Locale;

/** The kinds of flip that come from SkyCofl's public lists. */
public enum OpportunityKind {
    KAT("kat", "Kat", "KAT", "§d", "/api/kat/profit"),
    CRAFT("craft", "Craft", "CRAFT", "§a", "/api/craft/profit"),
    FORGE("forge", "Forge", "FORGE", "§6", "/api/flip/forge"),
    FUSION("fusion", "Fusion", "FUSION", "§b", "/api/flip/fusion"),
    NPC("npc", "NPC", "NPC", "§e", "/api/flip/npc"),
    BOOKS("books", "Book", "BOOK", "§9", "/api/flip/bazaar/books");

    /** Sub-command and settings name. */
    public final String id;
    /** Name in sentences ("Kat flips"). */
    public final String title;
    /** Tag at the start of a chat line. */
    public final String tag;
    public final String color;
    /** SkyCofl API path of the list. */
    public final String path;

    OpportunityKind(String id, String title, String tag, String color, String path) {
        this.id = id;
        this.title = title;
        this.tag = tag;
        this.color = color;
        this.path = path;
    }

    /** By sub-command name, ignoring case; null when unknown. */
    public static OpportunityKind byId(String id) {
        String lower = id == null ? "" : id.toLowerCase(Locale.ROOT);
        for (OpportunityKind kind : values()) {
            if (kind.id.equals(lower)) {
                return kind;
            }
        }
        return null;
    }
}
