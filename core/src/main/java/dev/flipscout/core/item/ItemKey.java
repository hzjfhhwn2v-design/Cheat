package dev.flipscout.core.item;

/**
 * Identity of an item type for pricing. Two auctions with the same {@link #key} are considered
 * comparable; the other fields say how to look the item up in each external source.
 */
public final class ItemKey {
    /** Skyblock id from ExtraAttributes (HYPERION, PET, ENCHANTED_BOOK, ...). */
    public final String id;
    /** Grouping key for our own data: more specific than {@link #id} for pets, books, runes... */
    public final String key;
    /**
     * Coflnet item tag, with filters when the tag alone is too broad ("PET_X?Rarity=...&PetLevel=..."),
     * or null when Coflnet prices would not be comparable.
     */
    public final String coflTag;
    /** Bazaar product id for this item, or null. */
    public final String bazaarId;

    public ItemKey(String id, String key, String coflTag, String bazaarId) {
        this.id = id;
        this.key = key;
        this.coflTag = coflTag;
        this.bazaarId = bazaarId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemKey)) {
            return false;
        }
        ItemKey other = (ItemKey) o;
        return key.equals(other.key) && eq(coflTag, other.coflTag) && eq(bazaarId, other.bazaarId) && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
