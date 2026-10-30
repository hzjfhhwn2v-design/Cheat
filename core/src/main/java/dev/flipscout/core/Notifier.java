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

/** Builds the chat messages (in Italian) for flips. */
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
            line.append(" §b+upgrade");
        }
        if (flip.weak) {
            line.append(" §c⚠");
        }
        line.append(" §b§l[APRI]");
        return new Msg().run(line.toString(), flip.command(), auctionHover(flip, now));
    }

    String auctionHover(AuctionFlip flip, long now) {
        AuctionIndex.Listing l = flip.listing;
        Valuation v = flip.valuation;
        StringBuilder h = new StringBuilder();
        h.append(itemName(l)).append('\n');
        h.append("§8").append(l.key.key).append('\n');
        h.append("§7Prezzo: §6").append(Coins.full(l.price));
        if (l.count > 1) {
            h.append(" §8(").append(Coins.compact(l.unitPrice)).append(" l'uno)");
        }
        h.append('\n');
        h.append("§7Rivendita stimata: §a").append(Coins.full(flip.resale)).append('\n');
        if (v.bazaar) {
            h.append("§7  Vendita istantanea al bazaar: §f").append(Coins.unit(v.bazaarInstaSell)).append(" l'uno\n");
        } else {
            if (!Double.isNaN(v.lbinOther)) {
                h.append("§7  BIN più basso (altri): §f").append(Coins.compact(v.lbinOther))
                    .append(" §8(").append(v.otherListings).append(" in vendita)\n");
            }
            if (!Double.isNaN(v.salesMedian)) {
                h.append("§7  Mediana vendite recenti: §f").append(Coins.compact(v.salesMedian))
                    .append(" §8(").append(v.salesSamples).append(" vendite)\n");
            }
            if (!Double.isNaN(v.coflMedian)) {
                h.append("§7  Mediana Coflnet: §f").append(Coins.compact(v.coflMedian)).append('\n');
            }
        }
        if (v.upgradeExtra < 0) {
            h.append("§7Meno upgrade del BIN di riferimento: §c").append(Coins.compact(v.upgradeExtra))
                .append(" §8(").append(Coins.decimal(v.upgradeShare * 100, 0)).append("% della differenza)\n");
        } else if (v.upgradeExtra > 0) {
            h.append("§7Upgrade contati: §b+").append(Coins.compact(v.upgradeExtra))
                .append(" §8(").append(Coins.decimal(v.upgradeShare * 100, 0)).append("% del loro valore)\n");
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
                h.append("§7  già nel BIN di riferimento: §c-").append(Coins.compact(v.referenceUpgrades)).append('\n');
            }
        }
        h.append("§7Tasse di vendita: §c-").append(Coins.compact(flip.fees)).append('\n');
        h.append("§7Profitto: §2+").append(Coins.full(flip.profit))
            .append(" §7(").append(Coins.decimal(flip.percent, 1)).append("%)\n");
        h.append("§7Vendite al giorno: §f").append(Double.isNaN(v.volumePerDay) ? "?" : Coins.decimal(v.volumePerDay, 1));
        if (v.volumeSource != null) {
            h.append(" §8(").append(v.volumeSource).append(')');
        }
        h.append('\n');
        if (l.start > 0) {
            h.append("§7Messa in vendita: §f").append(Text.ago(now, l.start)).append('\n');
        }
        if (flip.weak) {
            h.append("§cUn solo riferimento di prezzo: controlla prima di comprare\n");
        }
        if (flip.upgradeOnly) {
            h.append("§bConviene solo contando gli upgrade: rivenderlo può richiedere più tempo\n");
        }
        h.append("§eClicca per aprire l'asta");
        return h.toString();
    }

    Msg bazaar(BazaarFlip flip) {
        StringBuilder line = new StringBuilder(Msg.PREFIX);
        String hover;
        switch (flip.type) {
            case ORDER:
                line.append("§dBZ flip §f").append(flip.name)
                    .append(" §8» §7compra §e").append(Coins.unit(flip.buyOrderPrice))
                    .append(" §7vendi §a").append(Coins.unit(flip.sellOfferPrice))
                    .append(" §8| §2+").append(Coins.decimal(flip.marginPercent, 1)).append('%')
                    .append(" §8| §7~").append(Coins.compact(flip.profit)).append("/h");
                hover = "§f" + flip.name + "\n"
                    + "§7Buy order a §e" + Coins.unit(flip.buyOrderPrice)
                    + " §8(vendita istantanea ora: " + Coins.unit(flip.instaSell) + ")\n"
                    + "§7Sell offer a §a" + Coins.unit(flip.sellOfferPrice)
                    + " §8(acquisto istantaneo ora: " + Coins.unit(flip.instaBuy) + ")\n"
                    + "§7Margine per pezzo dopo la tassa: §2" + Coins.unit(flip.margin)
                    + " §7(" + Coins.decimal(flip.marginPercent, 1) + "%)\n"
                    + "§7Volume: §f" + Coins.compact(flip.buysPerHour) + " §7acquisti/h, §f"
                    + Coins.compact(flip.sellsPerHour) + " §7vendite/h\n"
                    + "§7Potenziale: §2" + Coins.compact(flip.profit) + "/h §8(se prendi tutto il volume)\n"
                    + "§eClicca per aprire il bazaar";
                break;
            case CRASH:
                line.append("§dBZ crollo §f").append(flip.name)
                    .append(" §8» §ecompra a ").append(Coins.unit(flip.instaBuy))
                    .append(" §7(di solito ").append(Coins.unit(flip.baseline))
                    .append(", §c-").append(Coins.decimal(flip.dropPercent, 0)).append("%§7)")
                    .append(" §8| §2~+").append(Coins.compact(flip.profit));
                hover = "§f" + flip.name + "\n"
                    + "§7Prezzo di acquisto istantaneo: §e" + Coins.unit(flip.instaBuy) + "\n"
                    + "§7Mediana dell'ultima ora e mezza: §a" + Coins.unit(flip.baseline) + "\n"
                    + "§7Pezzi sotto quel prezzo: §f" + Coins.compact(flip.units)
                    + " §7per §6" + Coins.compact(flip.cost) + "\n"
                    + "§7Profitto se li rivendi al prezzo solito: §2+" + Coins.compact(flip.profit) + "\n"
                    + "§8Il prezzo potrebbe non risalire: è una scommessa.\n"
                    + "§eClicca per aprire il bazaar";
                break;
            default:
                line.append("§dBZ→NPC §f").append(flip.name)
                    .append(" §8» §ecompra ").append(Coins.unit(flip.instaBuy))
                    .append(" §7→ NPC §a").append(Coins.unit(flip.npcPrice))
                    .append(" §8| §2+").append(Coins.compact(flip.profit))
                    .append(" §7(").append(Coins.compact(flip.units)).append(" pz)");
                hover = "§f" + flip.name + "\n"
                    + "§7Compra istantaneamente sotto §a" + Coins.unit(flip.npcPrice)
                    + " §7e vendi a un NPC\n"
                    + "§7Pezzi disponibili: §f" + Coins.compact(flip.units)
                    + " §7per §6" + Coins.compact(flip.cost) + "\n"
                    + "§7Profitto: §2+" + Coins.compact(flip.profit) + "\n"
                    + "§eClicca per aprire il bazaar";
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
        StringBuilder h = new StringBuilder("§6Flip nascosti dalla blacklist\n");
        for (int i = 0; i < sorted.size(); i++) {
            if (i == MAX_BLOCKED_LINES) {
                h.append("§8... e altri ").append(sorted.size() - i).append('\n');
                break;
            }
            h.append("§7• ").append(sorted.get(i).line).append('\n');
        }
        h.append("§eClicca per vedere la blacklist");
        int n = sorted.size();
        return new Msg().run(Msg.PREFIX + "§7I tuoi filtri hanno bloccato §e" + n + " §7flip §8(passa il mouse per vederli)",
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
                what = "§dBZ crollo §f" + flip.name + " §2~+" + Coins.compact(flip.profit);
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
        return Double.isNaN(perDay) ? "vol ?" : "vol " + Coins.decimal(perDay, perDay < 10 ? 1 : 0) + "/g";
    }
}
