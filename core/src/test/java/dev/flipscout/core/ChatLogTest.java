package dev.flipscout.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLogTest {
    @AfterEach
    void tearDown() {
        Log.setEcho(null);
    }

    @Test
    void warningsReachTheChatOnlyWhileOnAndNotTooOften() {
        FakePlatform platform = new FakePlatform();
        AtomicBoolean on = new AtomicBoolean(false);
        AtomicLong clock = new AtomicLong(1_000_000);
        Log.setEcho(new ChatLog(platform, on::get, clock::get));

        Log.warn("SkyCofl: HTTP 503");
        assertTrue(platform.messages.isEmpty());

        on.set(true);
        Log.warn("SkyCofl: HTTP 503");
        assertEquals(1, platform.messages.size());
        assertEquals("[FlipScout debug] SkyCofl: HTTP 503", platform.messages.get(0).toPlainString());
        // Within a second: skipped, and counted on the next line.
        Log.error("Unexpected error in a background task", new IOException("disk full"));
        assertEquals(1, platform.messages.size());
        clock.addAndGet(ChatLog.MIN_GAP_MS);
        Log.error("Unexpected error in a background task", new IllegalStateException());
        assertEquals(2, platform.messages.size());
        assertEquals("[FlipScout debug] Unexpected error in a background task (IllegalStateException) (+1 more skipped)",
            platform.messages.get(1).toPlainString());
        assertTrue(platform.messages.get(1).parts().get(0).text.contains("§c"));
        // The same text again within a minute: not repeated.
        clock.addAndGet(5_000);
        Log.warn("SkyCofl: HTTP 503");
        assertEquals(2, platform.messages.size());
        clock.addAndGet(ChatLog.REPEAT_MS);
        Log.warn("SkyCofl: HTTP 503");
        assertEquals(3, platform.messages.size());
        // Debug lines stay in the log file.
        Log.debugEnabled = true;
        clock.addAndGet(5_000);
        Log.debug("details");
        Log.debugEnabled = false;
        assertEquals(3, platform.messages.size());
    }
}
