package dev.flipscout.core;

import dev.flipscout.core.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Platform-neutral chat message: parts written with § colour codes, optionally clickable. */
public final class Msg {
    public static final String PREFIX = "§6[FlipScout]§r ";

    public enum Click { RUN, SUGGEST }

    public static final class Part {
        public final String text;
        /** Command (with leading slash) run or suggested on click, or null. */
        public final String command;
        public final Click click;
        /** Hover text with § codes and newlines, or null. */
        public final String hover;

        Part(String text, String command, Click click, String hover) {
            this.text = text;
            this.command = command;
            this.click = click;
            this.hover = hover;
        }
    }

    private final List<Part> parts = new ArrayList<Part>();

    public static Msg prefixed(String text) {
        return new Msg().add(PREFIX + text);
    }

    public static Msg plain(String text) {
        return new Msg().add(text);
    }

    public Msg add(String text) {
        parts.add(new Part(text, null, null, null));
        return this;
    }

    public Msg add(String text, String hover) {
        parts.add(new Part(text, null, null, hover));
        return this;
    }

    public Msg run(String text, String command, String hover) {
        parts.add(new Part(text, command, Click.RUN, hover));
        return this;
    }

    public Msg suggest(String text, String command, String hover) {
        parts.add(new Part(text, command, Click.SUGGEST, hover));
        return this;
    }

    public List<Part> parts() {
        return Collections.unmodifiableList(parts);
    }

    /** The first command attached to this message, or null. */
    public String firstCommand() {
        for (Part part : parts) {
            if (part.command != null && part.click == Click.RUN) {
                return part.command;
            }
        }
        return null;
    }

    public String toPlainString() {
        StringBuilder sb = new StringBuilder();
        for (Part part : parts) {
            sb.append(Text.stripCodes(part.text));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toPlainString();
    }
}
