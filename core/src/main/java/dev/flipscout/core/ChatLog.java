package dev.flipscout.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Shows the mod's warnings and errors in chat while {@code debug.chat} is on, so they can be read
 * in game and reported. At most one line a second, and the same text once a minute: a failing
 * download retried every few seconds does not fill the chat.
 */
final class ChatLog implements Log.Echo {
    static final long MIN_GAP_MS = 1_000L;
    static final long REPEAT_MS = 60_000L;
    private static final int MAX_LENGTH = 200;

    private final Platform platform;
    private final BooleanSupplier enabled;
    private final LongSupplier clock;
    private final Map<String, Long> shown = new HashMap<String, Long>();
    private long last;
    private int dropped;

    ChatLog(Platform platform, BooleanSupplier enabled, LongSupplier clock) {
        this.platform = platform;
        this.enabled = enabled;
        this.clock = clock;
    }

    @Override
    public void echo(Log.Level level, String message, Throwable error) {
        if (!enabled.getAsBoolean() || message == null) {
            return;
        }
        String text = message;
        if (error != null) {
            String detail = error.getMessage();
            text += " (" + error.getClass().getSimpleName() + (detail == null || detail.isEmpty() ? "" : ": " + detail) + ")";
        }
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH - 3) + "...";
        }
        String line;
        synchronized (this) {
            long now = clock.getAsLong();
            Long before = shown.get(text);
            if (before != null && now - before < REPEAT_MS) {
                return;
            }
            if (now - last < MIN_GAP_MS) {
                dropped++;
                return;
            }
            last = now;
            shown.put(text, now);
            if (shown.size() > 200) {
                for (Iterator<Long> it = shown.values().iterator(); it.hasNext(); ) {
                    if (now - it.next() >= REPEAT_MS) {
                        it.remove();
                    }
                }
            }
            line = "§8[FlipScout debug] " + (level == Log.Level.ERROR ? "§c" : "§e") + text
                + (dropped > 0 ? " §8(+" + dropped + " more skipped)" : "");
            dropped = 0;
        }
        platform.showMessage(Msg.plain(line));
    }
}
