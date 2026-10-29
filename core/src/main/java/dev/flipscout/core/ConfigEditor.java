package dev.flipscout.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the settings screens need, the same on every Minecraft version: the settings by section,
 * their values, checked changes, defaults and saving. The screens only draw it.
 */
public final class ConfigEditor {
    private final Config config;
    private final File file;
    private final Config defaults = new Config();
    private volatile boolean changed;

    ConfigEditor(Config config, File file) {
        this.config = config;
        this.file = file;
    }

    public List<Config.Section> sections() {
        return Config.SECTIONS;
    }

    /**
     * The settings of {@code section}; when {@code search} is not blank, the settings of every
     * section whose name, key or description contains it.
     */
    public List<Config.Setting> settings(Config.Section section, String search) {
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Config.Setting> out = new ArrayList<Config.Setting>();
        if (query.isEmpty()) {
            if (section != null) {
                out.addAll(section.settings);
            }
            return out;
        }
        for (Config.Section s : Config.SECTIONS) {
            for (Config.Setting setting : s.settings) {
                if (setting.label.toLowerCase(Locale.ROOT).contains(query)
                    || setting.key.toLowerCase(Locale.ROOT).contains(query)
                    || setting.help.toLowerCase(Locale.ROOT).contains(query)) {
                    out.add(setting);
                }
            }
        }
        return out;
    }

    public Config.Section sectionOf(Config.Setting setting) {
        for (Config.Section section : Config.SECTIONS) {
            if (section.settings.contains(setting)) {
                return section;
            }
        }
        return null;
    }

    /** The value as typed in a text field; round amounts as 500k or 2m. */
    public String text(Config.Setting setting) {
        return text(setting, config);
    }

    private static String text(Config.Setting setting, Config from) {
        String value = setting.get(from);
        if (setting instanceof Config.CoinsSetting) {
            return shortCoins(Long.parseLong(value));
        }
        if (setting.kind() == Config.Setting.Kind.TOGGLE) {
            return Boolean.parseBoolean(value) ? "on" : "off";
        }
        return value;
    }

    public boolean isOn(Config.Setting setting) {
        return Boolean.parseBoolean(setting.get(config));
    }

    public void toggle(Config.Setting setting) {
        setting.set(config, String.valueOf(!isOn(setting)));
        changed = true;
    }

    /** Moves a choice to the next value, back to the first after the last. */
    public void cycle(Config.Setting setting) {
        List<String> choices = setting.suggestions();
        if (choices.isEmpty()) {
            return;
        }
        int at = choices.indexOf(setting.get(config));
        setting.set(config, choices.get((at + 1) % choices.size()));
        changed = true;
    }

    /** Applies a typed value: null when it was accepted, otherwise why not (in Italian). */
    public String set(Config.Setting setting, String value) {
        try {
            setting.set(config, value);
            changed = true;
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public boolean isDefault(Config.Setting setting) {
        return setting.get(config).equals(setting.get(defaults));
    }

    public void reset(Config.Setting setting) {
        setting.set(config, setting.get(defaults));
        changed = true;
    }

    /** Lines for the tooltip of a setting, with colour codes; the first is its name. */
    public List<String> tooltip(Config.Setting setting) {
        List<String> lines = new ArrayList<String>();
        lines.add("§f" + setting.label);
        lines.add("§7" + setting.help);
        if (!setting.hint().isEmpty()) {
            lines.add("§8Valori: " + setting.hint());
        }
        String byDefault = text(setting, defaults);
        lines.add("§8Predefinito: " + (byDefault.isEmpty() ? "(vuoto)" : byDefault));
        lines.add("§8Chiave: " + setting.key);
        return lines;
    }

    /** Writes the settings file if anything changed since the last save. */
    public void save() {
        if (changed) {
            changed = false;
            config.save(file);
        }
    }

    /** 500000 -> 500k, 2000000 -> 2m, 1234567 -> 1234567: short, but exact. */
    static String shortCoins(long coins) {
        if (coins != 0 && coins % 1_000_000_000L == 0) {
            return coins / 1_000_000_000L + "b";
        }
        if (coins != 0 && coins % 1_000_000L == 0) {
            return coins / 1_000_000L + "m";
        }
        if (coins != 0 && coins % 1_000L == 0) {
            return coins / 1_000L + "k";
        }
        return String.valueOf(coins);
    }
}
