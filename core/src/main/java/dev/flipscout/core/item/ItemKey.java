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
    /** NEU internal name used by moulberry.codes, or null. */
    public final String neuKey;
    /** True when {@link #neuKey} means exactly the same thing as {@link #key}. */
    public final boolean neuExact;
    /** Coflnet item tag, or null when Coflnet prices would not be comparable. */
    public final String coflTag;
    /** Bazaar product id for this item, or null. */
    public final String bazaarId;

    public ItemKey(String id, String key, String neuKey, boolean neuExact, String coflTag, String bazaarId) {
        this.id = id;
        this.key = key;
        this.neuKey = neuKey;
        this.neuExact = neuExact;
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
        return key.equals(other.key) && eq(neuKey, other.neuKey) && neuExact == other.neuExact
            && eq(coflTag, other.coflTag) && eq(bazaarId, other.bazaarId) && id.equals(other.id);
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
