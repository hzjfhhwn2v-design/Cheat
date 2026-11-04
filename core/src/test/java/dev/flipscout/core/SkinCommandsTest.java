package dev.flipscout.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /flipscout skins, skin and debug, and /skininvestment. */
class SkinCommandsTest {
    @TempDir
    File dir;

    private FakePlatform platform;
    private FakeFetcher http;
    private FlipScout flipScout;

    @BeforeEach
    void setUp() {
        platform = new FakePlatform();
        http = new FakeFetcher();
        flipScout = new FlipScout(platform, dir, http, new AtomicLong(1_793_707_200_000L)::get);
        flipScout.engine().skinHistory().learn("PET_SKIN_BLACK_CAT_CATGIRL", "Catgirl Black Cat Skin");
        flipScout.engine().skinHistory().learn("PET_SKIN_BLACK_CAT_ONYX", "Onyx Black Cat Skin");
        flipScout.engine().skinHistory().learn("CROWN_OF_AVARICE_ROYAL", "Grand Royal Skin");
    }

    @AfterEach
    void tearDown() {
        Log.setEcho(null);
    }

    @Test
    void theListWaitsForTheAuctionHouseToBeRead() {
        flipScout.command(new String[] {"skins"});
        assertTrue(platform.allText().contains("Still reading the auction house"), platform.allText());
        platform.messages.clear();
        flipScout.skinCommand(new String[0]);
        assertTrue(platform.allText().contains("Still reading the auction house"), platform.allText());
        platform.messages.clear();
        flipScout.command(new String[] {"skins", "lots"});
        assertTrue(platform.allText().contains("Usage: /flipscout skins [number]"), platform.allText());
    }

    @Test
    void aSkinIsFoundByIdNameOrWords() {
        Commands commands = new Commands(flipScout.config(), new File(dir, "x.properties"), flipScout.engine(),
            flipScout.tracker(), null, platform, System::currentTimeMillis);
        assertEquals(Arrays.asList("CROWN_OF_AVARICE_ROYAL"), commands.findSkins("crown_of_avarice_royal"));
        assertEquals(Arrays.asList("CROWN_OF_AVARICE_ROYAL"), commands.findSkins("grand royal"));
        assertEquals(Arrays.asList("CROWN_OF_AVARICE_ROYAL"), commands.findSkins("Grand Royal Skin"));
        assertEquals(Arrays.asList("PET_SKIN_BLACK_CAT_CATGIRL", "PET_SKIN_BLACK_CAT_ONYX"), commands.findSkins("black cat"));
        assertEquals(Arrays.asList("PET_SKIN_BLACK_CAT_ONYX"), commands.findSkins("onyx cat"));
        assertTrue(commands.findSkins("hyperion").isEmpty());
    }

    @Test
    void aSkinWithSeveralMatchesAsksWhichOne() {
        flipScout.command(new String[] {"skin", "black", "cat"});
        String text = platform.allText();
        assertTrue(text.contains("Which one?"), text);
        assertTrue(text.contains("Catgirl Black Cat Skin (PET_SKIN_BLACK_CAT_CATGIRL)"), text);
        assertEquals(1, platform.withCommand("/flipscout skin PET_SKIN_BLACK_CAT_ONYX").size());
        platform.messages.clear();
        flipScout.command(new String[] {"skin", "nothing", "like", "it"});
        assertTrue(platform.allText().contains("No skin called \"nothing like it\" seen on the auction house yet."),
            platform.allText());
    }

    @Test
    void aSkinWhoseHistoryCannotBeReadSaysSo() {
        flipScout.command(new String[] {"skins", "detail", "grand", "royal"});
        assertEquals(1, http.count("https://sky.coflnet.com/api/item/price/CROWN_OF_AVARICE_ROYAL/history/full"));
        assertTrue(platform.allText().contains("Could not read the price history from SkyCofl: SkyCofl: HTTP 404"),
            platform.allText());
    }

    @Test
    void aSkinIsShownWithItsHistory() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 60; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"min\":100,\"max\":120,\"avg\":110,\"volume\":3,\"time\":\"")
                .append(java.time.LocalDate.ofEpochDay(20760 - 59 + i)).append("T00:00:00\"}");
        }
        http.put("https://sky.coflnet.com/api/item/price/CROWN_OF_AVARICE_ROYAL/history/full", sb.append(']').toString());
        flipScout.command(new String[] {"skin", "Grand", "Royal"});
        String text = platform.allText();
        assertTrue(text.contains("Grand Royal Skin - skin investment over 30 days [chart]"), text);
        assertTrue(text.contains("Not advised: no BIN auction of it is up right now"), text);
        assertTrue(text.contains("Last 30 days"), text);
    }

    @Test
    void debugShowsWarningsInChatUntilTurnedOff() {
        flipScout.command(new String[] {"debug"});
        assertTrue(platform.allText().contains("Warnings and errors in chat: off"), platform.allText());
        flipScout.command(new String[] {"debug", "on"});
        assertTrue(flipScout.config().debugChat);
        assertTrue(flipScout.config().debug);
        assertTrue(platform.allText().contains("No errors right now"), platform.allText());
        platform.messages.clear();
        Log.warn("Could not read skin prices from SkyCofl: HTTP 503");
        assertEquals("[FlipScout debug] Could not read skin prices from SkyCofl: HTTP 503",
            platform.messages.get(0).toPlainString());
        // /skininvestment debug off is the same.
        flipScout.skinCommand(new String[] {"debug", "off"});
        assertFalse(flipScout.config().debugChat);
        assertFalse(flipScout.config().debug);
        platform.messages.clear();
        Log.warn("Another warning");
        assertTrue(platform.messages.isEmpty());
        // Saved in the settings file.
        String saved = new String(readAll(new File(new File(dir, "flipscout"), "flipscout.properties")));
        assertTrue(saved.contains("debug.chat=false"), saved);
    }

    @Test
    void tabCompletion() {
        assertEquals(Arrays.asList("detail", "debug"), flipScout.complete(new String[] {"skins", "d"}));
        assertEquals(Arrays.asList("on", "off"), flipScout.complete(new String[] {"debug", ""}));
        assertEquals(Arrays.asList("on", "off"), flipScout.complete(new String[] {"skins", "debug", ""}));
        // By a word of the name: both are black cats.
        List<String> cats = flipScout.complete(new String[] {"skin", "cat"});
        assertEquals(Arrays.asList("PET_SKIN_BLACK_CAT_CATGIRL", "PET_SKIN_BLACK_CAT_ONYX"), cats);
        assertEquals(Arrays.asList("PET_SKIN_BLACK_CAT_ONYX"), flipScout.complete(new String[] {"skin", "ony"}));
        assertEquals(Arrays.asList("PET_SKIN_BLACK_CAT_CATGIRL", "PET_SKIN_BLACK_CAT_ONYX"),
            flipScout.complete(new String[] {"skins", "detail", "PET_SKIN_B"}));
        assertEquals(Arrays.asList("CROWN_OF_AVARICE_ROYAL"), flipScout.completeSkinCommand(new String[] {"detail", "roy"}));
        assertTrue(flipScout.complete(new String[] {"sk"}).containsAll(Arrays.asList("skins", "skin")));
    }

    private static byte[] readAll(File file) {
        try {
            return java.nio.file.Files.readAllBytes(file.toPath());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
