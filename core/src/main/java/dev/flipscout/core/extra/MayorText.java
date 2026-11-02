package dev.flipscout.core.extra;

import dev.flipscout.core.Msg;
import dev.flipscout.core.util.Coins;
import dev.flipscout.core.util.Text;

import java.util.ArrayList;
import java.util.List;

/** Chat lines for the mayor, the election and the price analysis. */
public final class MayorText {
    private MayorText() {
    }

    /** The alert about the open election's favourite. */
    public static Msg alert(Mayors m, Market market, Mayors.Candidate favourite) {
        Mayors.Election e = m.election();
        int total = Math.max(1, e.totalVotes());
        Msg msg = new Msg().add(Msg.PREFIX + "§7Election: §e" + favourite.name + " §7leads with §f"
            + Coins.decimal(favourite.votes * 100.0 / total, 0) + "%§7. Under " + favourite.name + " usually:");
        for (Mayors.Effect effect : m.movers(favourite.key, true, 3)) {
            effect(msg, market, effect, true, " ");
        }
        for (Mayors.Effect effect : m.movers(favourite.key, false, 2)) {
            effect(msg, market, effect, false, " ");
        }
        return msg.run(" §b§l[MORE]", "/flipscout mayor", "§7The mayor, the election and every item the analysis found");
    }

    /** /flipscout mayor. */
    public static List<Msg> view(Mayors m, Market market, long now) {
        List<Msg> out = new ArrayList<Msg>();
        Mayors.Election e = m.election();
        if (e == null) {
            out.add(Msg.prefixed("§7The election could not be read yet."));
            return out;
        }
        StringBuilder head = new StringBuilder("§fMayor: §e").append(e.mayorName == null ? "?" : e.mayorName);
        if (!e.mayorPerks.isEmpty()) {
            head.append(" §8(").append(String.join(", ", e.mayorPerks)).append(')');
        }
        if (e.ministerName != null) {
            head.append(" §7+ minister §e").append(e.ministerName);
            if (e.ministerPerk != null) {
                head.append(" §8(").append(e.ministerPerk).append(')');
            }
        }
        out.add(Msg.prefixed(head.toString()));
        long next = m.nextMayorAt(now);
        if (next > now) {
            out.add(Msg.plain("§7  Next mayor in §f" + Text.duration(next - now)));
        }
        Mayors.Candidate favourite = e.favourite();
        if (favourite == null) {
            out.add(Msg.plain("§7  No election is open right now."));
        } else {
            int total = Math.max(1, e.totalVotes());
            Msg votes = new Msg().add("§7  Election §8(year " + e.year + ")§7:");
            for (Mayors.Candidate c : e.candidates) {
                String share = Coins.decimal(c.votes * 100.0 / total, 1) + "%";
                String text = c == favourite ? " §a▸ " + c.name + " " + share : " §7" + c.name + " §f" + share;
                votes.add(text, "§e" + c.name + "\n§7Votes: §f" + Coins.full(c.votes)
                    + (c.perks.isEmpty() ? "" : "\n§7Perks: §f" + String.join(", ", c.perks)));
            }
            out.add(votes);
        }
        if (m.analyzing()) {
            out.add(Msg.plain("§7  Price analysis: §ereading item " + m.progress() + " of " + m.progressTotal() + "..."));
        } else if (m.analyzedAt() == 0) {
            out.add(Msg.plain("§7  Price analysis: §enot done yet. §7It runs in the background (about two minutes) once"
                + " the bazaar has loaded; try again in a while."));
        }
        if (m.analyzedAt() > 0) {
            if (favourite != null) {
                section(out, m, market, favourite.key, favourite.name, "§7  If §e" + favourite.name
                    + " §7wins, prices usually move like this:", 5);
            }
            if (e.mayorKey != null && (favourite == null || !e.mayorKey.equals(favourite.key))) {
                section(out, m, market, e.mayorKey, e.mayorName, "§7  Under §e" + e.mayorName + " §7(now):", 3);
            }
            out.add(new Msg().add("§8  Median change during each of their terms versus the 10 days around it, over the last"
                    + " year (" + m.analyzedItems() + " items, " + Text.ago(now, m.analyzedAt()) + "). A tendency, not a promise. ")
                .link("§8[SkyCofl]", OpportunityText.DATA_URL, "§7Price history and past mayors: SkyCofl (sky.coflnet.com)"));
        }
        return out;
    }

    private static void section(List<Msg> out, Mayors m, Market market, String key, String name, String title, int limit) {
        out.add(Msg.plain(title));
        if (!m.knows(key)) {
            out.add(Msg.plain("§8    No term of " + name + " in the last year to learn from."));
            return;
        }
        List<Mayors.Effect> ups = m.movers(key, true, limit);
        List<Mayors.Effect> downs = m.movers(key, false, limit);
        if (ups.isEmpty() && downs.isEmpty()) {
            out.add(Msg.plain("§8    Nothing moves clearly with " + name + "."));
            return;
        }
        for (Mayors.Effect effect : ups) {
            out.add(effect(new Msg(), market, effect, true, "§7    "));
        }
        for (Mayors.Effect effect : downs) {
            out.add(effect(new Msg(), market, effect, false, "§7    "));
        }
    }

    private static Msg effect(Msg msg, Market market, Mayors.Effect e, boolean up, String before) {
        String pct = (e.change >= 0 ? "+" : "") + Coins.decimal(e.change * 100, 0) + "%";
        String text = before + (up ? "§a▲ " : "§c▼ ") + "§f" + e.name + " " + (up ? "§a" : "§c") + pct
            + " §8(" + e.agreeing + "/" + e.terms + " terms)";
        String hover = "§f" + e.name + "\n§7Median change during a term: " + (up ? "§a" : "§c") + pct
            + "\n§7Terms that went this way: §f" + e.agreeing + " of " + e.terms
            + "\n§8Prices: SkyCofl (sky.coflnet.com)";
        if (e.bazaar) {
            return msg.run(text, "/bz " + market.bazaarSearch(e.id), hover + "\n§eClick to open the bazaar");
        }
        return msg.suggest(text, "/ahs " + e.name, hover + "\n§eClick to type an auction search");
    }
}
