package dev.flipscout.core.extra;

import java.util.Collections;
import java.util.List;

/**
 * One flip from a SkyCofl list (a Kat upgrade, a craft, a forge, a fusion, an NPC purchase, a
 * book combination), priced with live data where we have it.
 */
public final class Opportunity {
    public final OpportunityKind kind;
    /** Identifies the same flip across refreshes, for not notifying it twice. */
    public final String key;
    /** Item name, with colour codes. */
    public final String name;
    /** Short note after the name ("RARE→EPIC", "HotM 7", "x2"), or "". */
    public final String note;
    /** Coins put in. */
    public final double cost;
    /** Coins out, after selling fees. */
    public final double value;
    public final double profit;
    /** How long it takes in hours (Kat upgrades, forging), NaN when it is immediate. */
    public final double hours;
    /** How many of the item sell per day, NaN when unknown. */
    public final double perDay;
    /** Hover lines, with colour codes. */
    public final List<String> details;
    /** Command for a click (with slash), or null. */
    public final String command;
    /** The click suggests the command instead of running it. */
    public final boolean suggest;
    /** Label of the click button ("OPEN", "BAZAAR", "RECIPE"). */
    public final String button;

    public Opportunity(OpportunityKind kind, String key, String name, String note, double cost, double value,
                       double hours, double perDay, List<String> details, String command, boolean suggest, String button) {
        this.kind = kind;
        this.key = key;
        this.name = name;
        this.note = note == null ? "" : note;
        this.cost = cost;
        this.value = value;
        this.profit = value - cost;
        this.hours = hours;
        this.perDay = perDay;
        this.details = Collections.unmodifiableList(details);
        this.command = command;
        this.suggest = suggest;
        this.button = button;
    }

    /** Profit per hour of waiting, NaN for flips that take no time. */
    public double perHour() {
        return hours > 0 ? profit / hours : Double.NaN;
    }

    /** What the list is sorted and filtered by: profit per hour for forges, profit for the rest. */
    public double score() {
        return kind == OpportunityKind.FORGE && hours > 0 ? perHour() : profit;
    }
}
