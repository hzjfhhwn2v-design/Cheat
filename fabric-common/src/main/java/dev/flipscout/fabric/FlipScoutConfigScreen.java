package dev.flipscout.fabric;

import dev.flipscout.core.Config;
import dev.flipscout.core.ConfigEditor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Settings screen in the style of NEU's and SkyHanni's: sections on the left, a search box on
 * top, the settings of the section on the right. Changes apply at once and are saved on close.
 * Built from plain vanilla widgets only, so the same code works on every supported version.
 */
final class FlipScoutConfigScreen extends Screen {
    private static final int SIDEBAR = 110;
    private static final int TOP = 44;
    private static final int ROW = 24;
    private static final int BOTTOM = 32;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int ERROR_COLOR = 0xFFFF5555;

    private final ConfigEditor editor;
    private Config.Section section;
    private String query = "";
    private int page;
    private int pages = 1;
    private EditBox search;
    private StringWidget problem;

    FlipScoutConfigScreen(ConfigEditor editor) {
        super(Component.literal("FlipScout - Settings"));
        this.editor = editor;
        this.section = editor.sections().get(0);
    }

    @Override
    protected void init() {
        int listLeft = 10 + SIDEBAR + 10;
        int listRight = width - 10;
        Component heading = Component.literal("§6§lFlipScout §7- Settings");
        addRenderableWidget(new StringWidget((width - font.width(heading)) / 2, 8, font.width(heading), 10, heading, font));

        search = new EditBox(font, listLeft, 22, listRight - listLeft, 16, Component.literal("Search"));
        search.setMaxLength(40);
        search.setValue(query);
        search.setHint(Component.literal("§8Search a setting..."));
        search.setResponder(value -> {
            if (!value.equals(query)) {
                query = value;
                page = 0;
                rebuild(true);
            }
        });
        addRenderableWidget(search);

        List<Config.Section> sections = editor.sections();
        for (int i = 0; i < sections.size(); i++) {
            Config.Section s = sections.get(i);
            boolean selected = query.trim().isEmpty() && s == section;
            addRenderableWidget(Button.builder(Component.literal((selected ? "§e» " : "") + s.title), b -> {
                section = s;
                query = "";
                page = 0;
                rebuild(false);
            }).bounds(10, TOP + i * 22, SIDEBAR, 20).build());
        }

        List<Config.Setting> settings = editor.settings(section, query);
        int perPage = Math.max(1, (height - TOP - BOTTOM) / ROW);
        pages = Math.max(1, (settings.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        boolean searching = !query.trim().isEmpty();
        for (int i = page * perPage; i < Math.min(settings.size(), (page + 1) * perPage); i++) {
            addRow(settings.get(i), TOP + (i - page * perPage) * ROW, listLeft, listRight, searching);
        }
        if (settings.isEmpty()) {
            Component none = Component.literal("§7No settings found");
            addRenderableWidget(new StringWidget((listLeft + listRight - font.width(none)) / 2, TOP + 10, font.width(none), 10,
                none, font));
        }

        int bottom = height - 26;
        int middle = (listLeft + listRight) / 2;
        Button prev = addRenderableWidget(Button.builder(Component.literal("◀"), b -> turn(-1))
            .bounds(middle - 70, bottom, 20, 20).build());
        Button next = addRenderableWidget(Button.builder(Component.literal("▶"), b -> turn(1))
            .bounds(middle + 50, bottom, 20, 20).build());
        prev.active = page > 0;
        next.active = page < pages - 1;
        Component pageText = Component.literal("§7Page " + (page + 1) + "/" + pages);
        addRenderableWidget(new StringWidget(middle - font.width(pageText) / 2, bottom + 6, font.width(pageText), 10,
            pageText, font));
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(10, bottom, SIDEBAR, 20).build());
        problem = addRenderableWidget(new StringWidget(10, height - BOTTOM + 1, width - 20, 10, Component.empty(), font));
    }

    private void addRow(Config.Setting setting, int y, int listLeft, int listRight, boolean searching) {
        Tooltip tooltip = tooltip(setting);
        int controlRight = listRight - 24;
        Button reset = Button.builder(Component.literal("↺"), b -> {
            editor.reset(setting);
            rebuild(false);
        }).bounds(listRight - 20, y, 20, 20).tooltip(Tooltip.create(Component.literal("Reset to default")))
            .build();
        reset.visible = !editor.isDefault(setting);
        int controlLeft;
        switch (setting.kind()) {
            case TOGGLE:
                controlLeft = controlRight - 50;
                addRenderableWidget(Button.builder(toggleText(setting), b -> {
                    editor.toggle(setting);
                    b.setMessage(toggleText(setting));
                    reset.visible = !editor.isDefault(setting);
                }).bounds(controlLeft, y, 50, 20).tooltip(tooltip).build());
                break;
            case CHOICE:
                controlLeft = controlRight - 100;
                addRenderableWidget(Button.builder(Component.literal(editor.text(setting)), b -> {
                    editor.cycle(setting);
                    b.setMessage(Component.literal(editor.text(setting)));
                    reset.visible = !editor.isDefault(setting);
                }).bounds(controlLeft, y, 100, 20).tooltip(tooltip).build());
                break;
            default:
                int fieldWidth = setting.kind() == Config.Setting.Kind.TEXT ? 150 : 80;
                controlLeft = controlRight - fieldWidth;
                EditBox field = new EditBox(font, controlLeft, y + 2, fieldWidth, 16, Component.literal(setting.label));
                field.setMaxLength(setting.kind() == Config.Setting.Kind.TEXT ? 500 : 20);
                field.setValue(editor.text(setting));
                field.setTooltip(tooltip);
                field.setResponder(value -> {
                    String error = editor.set(setting, value);
                    field.setTextColor(error == null ? TEXT_COLOR : ERROR_COLOR);
                    reset.visible = !editor.isDefault(setting);
                    problem.setMessage(Component.literal(error == null ? "" : "§c" + setting.label + ": " + error));
                });
                addRenderableWidget(field);
                break;
        }
        addRenderableWidget(reset);

        String label = setting.label;
        if (searching) {
            Config.Section of = editor.sectionOf(setting);
            label = "§8" + (of != null ? of.title : "") + " › §f" + label;
        }
        label = (editor.isDefault(setting) ? "§f" : "§e") + font.plainSubstrByWidth(label, controlLeft - listLeft - 8);
        StringWidget text = new StringWidget(listLeft + 2, y + 6, controlLeft - listLeft - 6, 10, Component.literal(label), font);
        text.setTooltip(tooltip);
        addRenderableWidget(text);
    }

    private Component toggleText(Config.Setting setting) {
        return Component.literal(editor.isOn(setting) ? "§aON" : "§cOFF");
    }

    private Tooltip tooltip(Config.Setting setting) {
        return Tooltip.create(Component.literal(String.join("\n", editor.tooltip(setting))));
    }

    private void turn(int delta) {
        int target = Math.max(0, Math.min(pages - 1, page + delta));
        if (target != page) {
            page = target;
            rebuild(false);
        }
    }

    /** Lays the screen out again; keeps typing in the search box when it had the focus. */
    private void rebuild(boolean keepSearchFocus) {
        clearWidgets();
        init();
        if (keepSearchFocus) {
            setFocused(search);
            search.setFocused(true);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && pages > 1) {
            turn(scrollY < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void removed() {
        editor.save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
