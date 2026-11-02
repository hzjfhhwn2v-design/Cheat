package dev.flipscout.core.extra;

import dev.flipscout.core.Msg;
import dev.flipscout.core.util.Coins;

import java.util.Map;

/** Chat lines for SkyCofl's flips. */
public final class OpportunityText {
    /** Where SkyCofl asks to be linked from when its flips are shown. */
    public static final String FLIPS_URL = "https://sky.coflnet.com/flips";
    public static final String DATA_URL = "https://sky.coflnet.com/data";

    private OpportunityText() {
    }

    public static Msg line(Opportunity o) {
        StringBuilder line = new StringBuilder(Msg.PREFIX)
            .append(o.kind.color).append(o.kind.tag).append(' ').append(o.name);
        if (!o.note.isEmpty()) {
            line.append(' ').append(o.note);
        }
        line.append(" §8» §6").append(Coins.compact(o.cost))
            .append(" §8→ §a").append(Coins.compact(o.value))
            .append(" §8| §2+").append(Coins.compact(o.profit));
        if (o.hours > 0) {
            line.append(" §8(§2").append(Coins.compact(o.perHour())).append("/h§8) | §7").append(CoflLists.duration(o.hours));
        }
        if (o.perDay > 0) {
            line.append(" §8| §7").append(CoflLists.perDay(o.perDay)).append("/d");
        }
        if (o.command != null) {
            line.append(" §b§l[").append(o.button).append(']');
        }
        String text = Msg.profits(line.toString());
        String hover = Msg.profits(hover(o));
        if (o.command == null) {
            return new Msg().add(text, hover);
        }
        return o.suggest ? new Msg().suggest(text, o.command, hover) : new Msg().run(text, o.command, hover);
    }

    static String hover(Opportunity o) {
        StringBuilder h = new StringBuilder(o.name);
        if (!o.note.isEmpty()) {
            h.append(' ').append(o.note);
        }
        h.append('\n');
        for (String detail : o.details) {
            h.append(detail).append('\n');
        }
        h.append("§7Total cost: §6").append(Coins.full(o.cost)).append('\n');
        h.append("§7Profit: §2+").append(Coins.full(o.profit));
        if (o.cost > 0) {
            h.append(" §7(").append(Coins.decimal(o.profit * 100.0 / o.cost, 1)).append("%)");
        }
        if (o.hours > 0) {
            h.append(" §8| ").append(Coins.compact(o.perHour())).append("/h");
        }
        h.append("\n§8Data: SkyCofl (sky.coflnet.com)");
        if (o.command != null) {
            h.append(o.suggest ? "\n§eClick to type §f" : "\n§eClick to run §f").append(o.command);
        }
        return h.toString();
    }

    /** "Craft flips (best 10)" with the SkyCofl link next to it. */
    public static Msg header(String title) {
        return new Msg().add(Msg.PREFIX + "§f" + title + " ")
            .link("§8[SkyCofl]", FLIPS_URL, "§7Data: SkyCofl (sky.coflnet.com)\n§eClick to open the site");
    }

    /** The line after the first download of the lists: how many flips of each kind are worth a look. */
    public static Msg summary(Map<OpportunityKind, Integer> counts) {
        Msg msg = new Msg().add(Msg.PREFIX + "§7Other flips above your thresholds right now:");
        boolean any = false;
        for (Map.Entry<OpportunityKind, Integer> entry : counts.entrySet()) {
            OpportunityKind kind = entry.getKey();
            int count = entry.getValue();
            if (count == 0) {
                continue;
            }
            any = true;
            msg.run(" " + kind.color + count + " " + kind.id, "/flipscout " + kind.id,
                "§7Show the " + kind.title.toLowerCase(java.util.Locale.ROOT) + " flips");
        }
        if (!any) {
            msg.add(" §8none");
        }
        return msg.add(" ").link("§8[SkyCofl]", FLIPS_URL, "§7Data: SkyCofl (sky.coflnet.com)");
    }
}
