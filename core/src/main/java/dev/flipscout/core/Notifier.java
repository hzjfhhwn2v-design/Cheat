package dev.flipscout.core;

import dev.flipscout.core.flip.AuctionFlip;
import dev.flipscout.core.flip.BazaarFlip;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.Valuation;
import dev.flipscout.core.util.Coins;
import dev.flipscout.core.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Builds the chat messages (in English) for flips. */
final class Notifier {
    /** Lines of the blocked-flips hover: more would not fit on screen. */
    private static final int MAX_BLOCKED_LINES = 15;

    Msg auction(AuctionFlip flip, long now) {
        AuctionIndex.Listing l = flip.listing;
        String name = itemName(l);
        StringBuilder line = new StringBuilder(Msg.PREFIX)
            .append("§eAH ").append(name)
            .append(" §8» §6").append(Coins.compact(l.price))
            .append(" §8→ §a").append(Coins.compact(flip.resale))
            .append(" §8| §2+").append(Coins.compact(flip.profit))
            .append(" §7(").append(Coins.decimal(flip.percent, 1)).append("%)")
            .append(" §8| §7").append(volume(flip.valuation.volumePerDay));
        if (l.start > 0) {
            // How long ago it was listed, so you can tell how fresh the flip is.
            line.append(" §8| §7").append(Text.shortDuration(now - l.start));
        }
        if (flip.upgradeOnly) {
            line.append(" §b+upgrades");
        }
        if (flip.weak) {
            line.append(" §c⚠");
        }
        line.append(" §b§l[OPEN]");
        return new Msg().run(line.toString(), flip.command(), auctionHover(flip, now));
    }

    String auctionHover(AuctionFlip flip, long now) {
        AuctionIndex.Listing l = flip.listing;
        Valuation v = flip.valuation;
        StringBuilder h = new StringBuilder();
        h.append(itemName(l)).append('\n');
        h.append("§8").append(l.key.key).append('\n');
        h.append("§7Price: §6").append(Coins.full(l.price));
        if (l.count > 1) {
            h.append(" §8(").append(Coins.compact(l.unitPrice)).append(" each)");
        }
        h.append('\n');
        h.append("§7Estimated resale: §a").append(Coins.full(flip.resale)).append('\n');
        if (v.bazaar) {
            h.append("§7  Bazaar instant-sell: §f").append(Coins.unit(v.bazaarInstaSell)).append(" each\n");
        } else {
            if (!Double.isNaN(v.lbinOther)) {
                h.append("§7  Lowest BIN (others): §f").append(Coins.compact(v.lbinOther))
                    .append(" §8(").append(v.otherListings).append(" listed)\n");
            }
            if (!Double.isNaN(v.salesMedian)) {
                h.append("§7  Recent sales median: §f").append(Coins.compact(v.salesMedian))
                    .append(" §8(").append(v.salesSamples).append(" sales)\n");
            }
            if (!Double.isNaN(v.coflMedian)) {
                h.append("§7  Coflnet median: §f").append(Coins.compact(v.coflMedian)).append('\n');
            }
        }
        if (v.upgradeExtra < 0) {
            h.append("§7Fewer upgrades than the reference BIN: §c").append(Coins.compact(v.upgradeExtra))
                .append(" §8(").append(Coins.decimal(v.upgradeShare * 100, 0)).append("% of the difference)\n");
        } else if (v.upgradeExtra > 0) {
            h.append("§7Upgrades counted: §b+").append(Coins.compact(v.upgradeExtra))
                .append(" §8(").append(Coins.decimal(v.upgradeShare * 100, 0)).append("% of their value)\n");
        }
        if (v.upgradeExtra != 0) {
            int shown = 0;
            for (Valuation.Part part : v.upgradeParts) {
                if (shown++ == 6) {
                    h.append("§8  ...\n");
                    break;
                }
                h.append("§7  ").append(part.part.label()).append(": §f").append(Coins.compact(part.value)).append('\n');
            }
            if (v.referenceUpgrades > 0) {
                h.append("§7  already in the reference BIN: §c-").append(Coins.compact(v.referenceUpgrades)).append('\n');
            }
        }
        h.append("§7Selling fees: §c-").append(Coins.compact(flip.fees)).append('\n');
        h.append("§7Profit: §2+").append(Coins.full(flip.profit))
            .append(" §7(").append(Coins.decimal(flip.percent, 1)).append("%)\n");
        h.append("§7Sales per day: §f").append(Double.isNaN(v.volumePerDay) ? "?" : Coins.decimal(v.volumePerDay, 1));
        if (v.volumeSource != null) {
            h.append(" §8(").append(v.volumeSource).append(')');
        }
        h.append('\n');
        if (l.start > 0) {
            h.append("§7Listed: §f").append(Text.ago(now, l.start)).append('\n');
        }
        if (flip.weak) {
            h.append("§cOnly one price reference: double-check before buying\n");
        }
        if (flip.upgradeOnly) {
            h.append("§bOnly worth it counting upgrades: reselling may take longer\n");
        }
        h.append("§eClick to open the auction");
        return h.toString();
    }

    Msg bazaar(BazaarFlip flip) {
        StringBuilder line = new StringBuilder(Msg.PREFIX);
        String hover;
        switch (flip.type) {
            case ORDER:
                line.append("§dBZ flip §f").append(flip.name)
                    .append(" §8» §7buy §e").append(Coins.unit(flip.buyOrderPrice))
                    .append(" §7sell §a").append(Coins.unit(flip.sellOfferPrice))
                    .append(" §8| §2+").append(Coins.decimal(flip.marginPercent, 1)).append('%')
                    .append(" §8| §7~").append(Coins.compact(flip.profit)).append("/h");
                hover = "§f" + flip.name + "\n"
                    + "§7Buy order at §e" + Coins.unit(flip.buyOrderPrice)
                    + " §8(instant-sell now: " + Coins.unit(flip.instaSell) + ")\n"
                    + "§7Sell offer at §a" + Coins.unit(flip.sellOfferPrice)
                    + " §8(instant-buy now: " + Coins.unit(flip.instaBuy) + ")\n"
                    + "§7Margin per item after tax: §2" + Coins.unit(flip.margin)
                    + " §7(" + Coins.decimal(flip.marginPercent, 1) + "%)\n"
                    + "§7Volume: §f" + Coins.compact(flip.buysPerHour) + " §7buys/h, §f"
                    + Coins.compact(flip.sellsPerHour) + " §7sells/h\n"
                    + "§7Potential: §2" + Coins.compact(flip.profit) + "/h §8(if you take the whole volume)\n"
                    + "§eClick to open the bazaar";
                break;
            case CRASH:
                line.append("§dBZ crash §f").append(flip.name)
                    .append(" §8» §ebuy at ").append(Coins.unit(flip.instaBuy))
                    .append(" §7(usually ").append(Coins.unit(flip.baseline))
                    .append(", §c-").append(Coins.decimal(flip.dropPercent, 0)).append("%§7)")
                    .append(" §8| §2~+").append(Coins.compact(flip.profit));
                hover = "§f" + flip.name + "\n"
                    + "§7Instant-buy price: §e" + Coins.unit(flip.instaBuy) + "\n"
                    + "§7Median of the last 90 min: §a" + Coins.unit(flip.baseline) + "\n"
                    + "§7Items below that price: §f" + Coins.compact(flip.units)
                    + " §7for §6" + Coins.compact(flip.cost) + "\n"
                    + "§7Profit reselling at the usual price: §2+" + Coins.compact(flip.profit) + "\n"
                    + "§8The price may not recover: it's a gamble.\n"
                    + "§eClick to open the bazaar";
                break;
            default:
                line.append("§dBZ→NPC §f").append(flip.name)
                    .append(" §8» §ebuy ").append(Coins.unit(flip.instaBuy))
                    .append(" §7→ NPC §a").append(Coins.unit(flip.npcPrice))
                    .append(" §8| §2+").append(Coins.compact(flip.profit))
                    .append(" §7(").append(Coins.compact(flip.units)).append(" pcs)");
                hover = "§f" + flip.name + "\n"
                    + "§7Instant-buy below §a" + Coins.unit(flip.npcPrice)
                    + " §7and sell to an NPC\n"
                    + "§7Items available: §f" + Coins.compact(flip.units)
                    + " §7for §6" + Coins.compact(flip.cost) + "\n"
                    + "§7Profit: §2+" + Coins.compact(flip.profit) + "\n"
                    + "§eClick to open the bazaar";
                break;
        }
        line.append(" §b§l[BAZAAR]");
        return new Msg().run(line.toString(), flip.command(), hover);
    }

    /**
     * "Your filters blocked N flips": one line per flip in the hover, most profitable first. A click
     * shows the blacklist.
     */
    Msg blocked(List<BlockedFlips.Entry> entries) {
        List<BlockedFlips.Entry> sorted = new ArrayList<BlockedFlips.Entry>(entries);
        Collections.sort(sorted, new Comparator<BlockedFlips.Entry>() {
            @Override
            public int compare(BlockedFlips.Entry a, BlockedFlips.Entry b) {
                return Double.compare(b.profit, a.profit);
            }
        });
        StringBuilder h = new StringBuilder("§6Flips hidden by the blacklist\n");
        for (int i = 0; i < sorted.size(); i++) {
            if (i == MAX_BLOCKED_LINES) {
                h.append("§8+").append(sorted.size() - i).append(" more\n");
                break;
            }
            h.append("§7• ").append(sorted.get(i).line).append('\n');
        }
        h.append("§eClick to see the blacklist");
        int n = sorted.size();
        return new Msg().run(Msg.PREFIX + "§7Your filters blocked §e" + n + " §7flips §8(hover to see them)",
            "/flipscout blacklist list", h.toString());
    }

    /** One hover line for an auction flip the blacklist hid. */
    String blockedLine(AuctionFlip flip, String entry) {
        AuctionIndex.Listing l = flip.listing;
        return itemName(l) + " §8» §6" + Coins.compact(l.price) + " §8→ §a" + Coins.compact(flip.resale)
            + " §2+" + Coins.compact(flip.profit) + " §8(" + entry + ")";
    }

    /** One hover line for a bazaar flip the blacklist hid. */
    String blockedLine(BazaarFlip flip, String entry) {
        String what;
        switch (flip.type) {
            case ORDER:
                what = "§dBZ flip §f" + flip.name + " §2+" + Coins.decimal(flip.marginPercent, 1) + "% §8~"
                    + Coins.compact(flip.profit) + "/h";
                break;
            case CRASH:
                what = "§dBZ crash §f" + flip.name + " §2~+" + Coins.compact(flip.profit);
                break;
            default:
                what = "§dBZ→NPC §f" + flip.name + " §2+" + Coins.compact(flip.profit);
                break;
        }
        return what + " §8(" + entry + ")";
    }

    String itemName(AuctionIndex.Listing l) {
        String name = l.itemName == null || l.itemName.isEmpty() ? l.key.id : Text.stripCodes(l.itemName);
        String result = Text.rarityColor(l.tier) + name;
        if (l.count > 1) {
            result += " §7x" + l.count;
        }
        return result;
    }

    private static String volume(double perDay) {
        return Double.isNaN(perDay) ? "vol ?" : "vol " + Coins.decimal(perDay, perDay < 10 ? 1 : 0) + "/d";
    }
}
