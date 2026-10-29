package dev.flipscout.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditorTest {
    @TempDir
    File dir;

    private FakePlatform platform;
    private FlipScout flipScout;
    private ConfigEditor editor;

    @BeforeEach
    void setUp() {
        platform = new FakePlatform();
        flipScout = new FlipScout(platform, dir, new FakeFetcher(), new AtomicLong(1_800_000_000_000L)::get);
        editor = flipScout.editor();
    }

    private static Config.Setting setting(String key) {
        return Config.find(key);
    }

    @Test
    void everySettingHasALabelAKindAndASection() {
        int count = 0;
        for (Config.Section section : editor.sections()) {
            for (Config.Setting s : editor.settings(section, "")) {
                assertFalse(s.label.isEmpty(), s.key);
                assertTrue(s.kind() != null, s.key);
                assertEquals(section, editor.sectionOf(s));
                assertEquals(5, editor.tooltip(s).size() - (s.hint().isEmpty() ? -1 : 0), s.key);
                count++;
            }
        }
        assertEquals(Config.allSettings().size(), count);
    }

    @Test
    void searchLooksAtNamesKeysAndDescriptions() {
        List<Config.Setting> found = editor.settings(null, "coflnet");
        assertTrue(found.contains(setting("source.coflnet")));
        assertTrue(found.contains(setting("url.coflnet")));
        assertTrue(editor.settings(null, "PAUSA TRA").contains(setting("bz.flip.cooldownMinutes")));
        assertTrue(editor.settings(null, "nessuna impostazione si chiama così").isEmpty());
    }

    @Test
    void valuesAreCheckedAppliedAndReset() {
        Config.Setting minProfit = setting("ah.minProfit");
        assertEquals("500k", editor.text(minProfit));
        assertTrue(editor.isDefault(minProfit));
        assertNull(editor.set(minProfit, "2m"));
        assertEquals(2_000_000L, flipScout.config().ahMinProfit);
        assertEquals("2m", editor.text(minProfit));
        assertFalse(editor.isDefault(minProfit));
        assertEquals("serve un importo, es. 500k o 2m", editor.set(minProfit, "tanti"));
        assertEquals(2_000_000L, flipScout.config().ahMinProfit);
        editor.reset(minProfit);
        assertTrue(editor.isDefault(minProfit));

        Config.Setting interval = setting("bz.intervalSeconds");
        assertEquals("deve essere tra 10 e 600", editor.set(interval, "5"));
        assertEquals("10 - 600", interval.hint());

        Config.Setting sound = setting("sound");
        assertTrue(editor.isOn(sound));
        editor.toggle(sound);
        assertFalse(flipScout.config().sound);
        assertEquals("off", editor.text(sound));

        Config.Setting strategy = setting("ah.strategy");
        editor.cycle(strategy);
        assertEquals("lbin", flipScout.config().ahStrategy);
        editor.cycle(strategy);
        editor.cycle(strategy);
        assertEquals("conservative", flipScout.config().ahStrategy);
    }

    @Test
    void savingWritesTheFile() {
        editor.set(setting("ah.minPercent"), "7.5");
        editor.save();
        Config reloaded = new Config();
        reloaded.load(new File(new File(dir, "flipscout"), "flipscout.properties"));
        assertEquals(7.5, reloaded.ahMinPercent, 1e-9);
    }

    @Test
    void theCommandOpensTheScreen() {
        flipScout.command(new String[] {"gui"});
        assertEquals(1, platform.configScreens);
        assertEquals("1234567", ConfigEditor.shortCoins(1_234_567));
        assertEquals("1250k", ConfigEditor.shortCoins(1_250_000));
        assertEquals("3b", ConfigEditor.shortCoins(3_000_000_000L));
    }
}
