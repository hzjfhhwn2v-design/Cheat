package dev.flipscout.core.profit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognises Hypixel's chat messages about buying and selling (colour codes already removed). */
final class TradeParser {
    enum Kind {
        /** You bought a BIN auction. */
        AUCTION_BOUGHT,
        /** Someone bought your auction; only shown while you are online. */
        AUCTION_SOLD,
        /** You collected the coins of a sold auction, including sales made while you were away. */
        AUCTION_COLLECTED,
        /** Instant buy, or a filled buy order claimed. */
        BAZAAR_BOUGHT,
        /** Instant sell, or a filled sell offer claimed. */
        BAZAAR_SOLD
    }

    static final class Trade {
        final Kind kind;
        final String item;
        final int amount;
        /** Coins paid or received in total. */
        final double coins;
        /** The other player of an auction sale, or null. */
        final String player;

        Trade(Kind kind, String item, int amount, double coins, String player) {
            this.kind = kind;
            this.item = item;
            this.amount = Math.max(1, amount);
            this.coins = coins;
            this.player = player;
        }
    }

    private static final String NUMBER = "([0-9][0-9,]*(?:\\.[0-9]+)?)";
    private static final Pattern AUCTION_BOUGHT = Pattern.compile("You purchased (.+) for " + NUMBER + " coins!");
    private static final Pattern AUCTION_SOLD =
        Pattern.compile("\\[Auction\\] (.+?) bought (.+) for " + NUMBER + " coins(?: CLICK)?");
    private static final Pattern AUCTION_COLLECTED = Pattern.compile(
        "You collected " + NUMBER + " coins from selling (.+) to ((?:\\[[^\\]]+\\] )?\\S+) in an auction!");
    private static final Pattern BAZAAR_BOUGHT =
        Pattern.compile("\\[Bazaar\\] Bought " + NUMBER + "x (.+) for " + NUMBER + " coins!");
    private static final Pattern BAZAAR_SOLD =
        Pattern.compile("\\[Bazaar\\] Sold " + NUMBER + "x (.+) for " + NUMBER + " coins!");
    private static final Pattern BAZAAR_ORDER_CLAIMED = Pattern.compile(
        "\\[Bazaar\\] Claimed " + NUMBER + "x (.+) worth " + NUMBER + " coins bought for " + NUMBER + " each!");
    private static final Pattern BAZAAR_OFFER_CLAIMED = Pattern.compile(
        "\\[Bazaar\\] Claimed " + NUMBER + " coins from selling " + NUMBER + "x (.+) at " + NUMBER + " each!");
    private static final Pattern COUNT_BEFORE = Pattern.compile("([0-9][0-9,]*)x (.+)");
    private static final Pattern COUNT_AFTER = Pattern.compile("(.+) x([0-9][0-9,]*)");

    private TradeParser() {
    }

    /**
     * The trade a chat line reports, or null. Only whole lines match, so a player cannot fake one:
     * their messages always start with their name.
     */
    static Trade parse(String line) {
        Matcher m;
        if ((m = AUCTION_BOUGHT.matcher(line)).matches()) {
            return auction(Kind.AUCTION_BOUGHT, m.group(1), m.group(2), null);
        }
        if ((m = AUCTION_SOLD.matcher(line)).matches()) {
            return auction(Kind.AUCTION_SOLD, m.group(2), m.group(3), m.group(1));
        }
        if ((m = AUCTION_COLLECTED.matcher(line)).matches()) {
            return auction(Kind.AUCTION_COLLECTED, m.group(2), m.group(1), m.group(3));
        }
        if ((m = BAZAAR_BOUGHT.matcher(line)).matches()) {
            return new Trade(Kind.BAZAAR_BOUGHT, m.group(2), count(m.group(1)), number(m.group(3)), null);
        }
        if ((m = BAZAAR_ORDER_CLAIMED.matcher(line)).matches()) {
            return new Trade(Kind.BAZAAR_BOUGHT, m.group(2), count(m.group(1)), number(m.group(3)), null);
        }
        if ((m = BAZAAR_SOLD.matcher(line)).matches()) {
            return new Trade(Kind.BAZAAR_SOLD, m.group(2), count(m.group(1)), number(m.group(3)), null);
        }
        if ((m = BAZAAR_OFFER_CLAIMED.matcher(line)).matches()) {
            return new Trade(Kind.BAZAAR_SOLD, m.group(3), count(m.group(2)), number(m.group(1)), null);
        }
        return null;
    }

    /** Auction item names may carry the stack size: "64x Enchanted Diamond" or "Enchanted Diamond x64". */
    private static Trade auction(Kind kind, String item, String coins, String player) {
        Matcher m;
        if ((m = COUNT_BEFORE.matcher(item)).matches()) {
            return new Trade(kind, m.group(2), count(m.group(1)), number(coins), player);
        }
        if ((m = COUNT_AFTER.matcher(item)).matches()) {
            return new Trade(kind, m.group(1), count(m.group(2)), number(coins), player);
        }
        return new Trade(kind, item, 1, number(coins), player);
    }

    private static double number(String text) {
        return Double.parseDouble(text.replace(",", ""));
    }

    private static int count(String text) {
        try {
            return Integer.parseInt(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
