package dev.flipscout.forge;

import dev.flipscout.core.Config;
import dev.flipscout.core.ConfigEditor;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen in the style of NEU's and SkyHanni's: sections on the left, a search box on
 * top, the settings of the section on the right. Changes apply at once and are saved on close.
 */
public class FlipScoutConfigGui extends GuiScreen {
    private static final int SIDEBAR = 110;
    private static final int TOP = 44;
    private static final int ROW = 24;
    private static final int BOTTOM = 32;
    private static final int PREV = 1;
    private static final int NEXT = 2;
    private static final int DONE = 3;
    private static final int SECTION = 100;
    private static final int CONTROL = 200;
    private static final int RESET = 300;
    private static final int FIELD = 400;
    private static final int TEXT_COLOR = 0xE0E0E0;
    private static final int ERROR_COLOR = 0xFF5555;

    /** One setting shown on the current page. */
    private static final class Row {
        final Config.Setting setting;
        final int y;
        GuiTextField field;
        GuiButton reset;

        Row(Config.Setting setting, int y) {
            this.setting = setting;
            this.y = y;
        }
    }

    private final GuiScreen parent;
    private final ConfigEditor editor;
    private final List<Row> rows = new ArrayList<Row>();
    private Config.Section section;
    private String query = "";
    private int page;
    private int pages;
    private GuiTextField search;
    private String problem;

    /** Used by Forge's mod list (the Config button). */
    public FlipScoutConfigGui(GuiScreen parent) {
        this(parent, FlipScoutForge.flipScout().editor());
    }

    FlipScoutConfigGui(GuiScreen parent, ConfigEditor editor) {
        this.parent = parent;
        this.editor = editor;
        this.section = editor.sections().get(0);
    }

    private int listLeft() {
        return 10 + SIDEBAR + 10;
    }

    private int listRight() {
        return width - 10;
    }

    private int rowsPerPage() {
        return Math.max(1, (height - TOP - BOTTOM) / ROW);
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        rows.clear();
        boolean searchFocused = search != null && search.isFocused();
        search = new GuiTextField(FIELD - 1, fontRendererObj, listLeft(), 22, listRight() - listLeft(), 14);
        search.setMaxStringLength(40);
        search.setText(query);
        search.setFocused(searchFocused);

        List<Config.Section> sections = editor.sections();
        for (int i = 0; i < sections.size(); i++) {
            Config.Section s = sections.get(i);
            boolean selected = query.trim().isEmpty() && s == section;
            buttonList.add(new GuiButton(SECTION + i, 10, TOP + i * 22, SIDEBAR, 20, (selected ? "§e» " : "") + s.title));
        }

        List<Config.Setting> settings = editor.settings(section, query);
        int perPage = rowsPerPage();
        pages = Math.max(1, (settings.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        int right = listRight();
        for (int i = page * perPage; i < Math.min(settings.size(), (page + 1) * perPage); i++) {
            Row row = new Row(settings.get(i), TOP + (i - page * perPage) * ROW);
            int index = rows.size();
            int controlRight = right - 24;
            switch (row.setting.kind()) {
                case TOGGLE:
                    buttonList.add(new GuiButton(CONTROL + index, controlRight - 50, row.y, 50, 20, toggleText(row.setting)));
                    break;
                case CHOICE:
                    buttonList.add(new GuiButton(CONTROL + index, controlRight - 100, row.y, 100, 20,
                        editor.text(row.setting)));
                    break;
                default:
                    int fieldWidth = row.setting.kind() == Config.Setting.Kind.TEXT ? 150 : 80;
                    row.field = new GuiTextField(FIELD + index, fontRendererObj, controlRight - fieldWidth, row.y + 2,
                        fieldWidth, 16);
                    row.field.setMaxStringLength(row.setting.kind() == Config.Setting.Kind.TEXT ? 500 : 20);
                    row.field.setText(editor.text(row.setting));
                    row.field.setCursorPositionZero();
                    break;
            }
            row.reset = new GuiButton(RESET + index, right - 20, row.y, 20, 20, "↺");
            row.reset.visible = !editor.isDefault(row.setting);
            buttonList.add(row.reset);
            rows.add(row);
        }

        int bottom = height - 26;
        int middle = (listLeft() + right) / 2;
        GuiButton prev = new GuiButton(PREV, middle - 70, bottom, 20, 20, "◀");
        GuiButton next = new GuiButton(NEXT, middle + 50, bottom, 20, 20, "▶");
        prev.enabled = page > 0;
        next.enabled = page < pages - 1;
        buttonList.add(prev);
        buttonList.add(next);
        buttonList.add(new GuiButton(DONE, 10, bottom, SIDEBAR, 20, "Done"));
    }

    private String toggleText(Config.Setting setting) {
        return editor.isOn(setting) ? "§aON" : "§cOFF";
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        int id = button.id;
        if (id == DONE) {
            mc.displayGuiScreen(parent);
        } else if (id == PREV || id == NEXT) {
            page += id == NEXT ? 1 : -1;
            initGui();
        } else if (id >= SECTION && id < CONTROL) {
            section = editor.sections().get(id - SECTION);
            query = "";
            page = 0;
            initGui();
        } else if (id >= CONTROL && id < RESET) {
            Row row = rows.get(id - CONTROL);
            if (row.setting.kind() == Config.Setting.Kind.TOGGLE) {
                editor.toggle(row.setting);
                button.displayString = toggleText(row.setting);
            } else {
                editor.cycle(row.setting);
                button.displayString = editor.text(row.setting);
            }
            row.reset.visible = !editor.isDefault(row.setting);
        } else if (id >= RESET && id < FIELD) {
            editor.reset(rows.get(id - RESET).setting);
            problem = null;
            initGui();
        }
    }

    @Override
    protected void keyTyped(char typed, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (search.textboxKeyTyped(typed, key)) {
            if (!search.getText().equals(query)) {
                query = search.getText();
                page = 0;
                initGui();
            }
            return;
        }
        for (Row row : rows) {
            if (row.field != null && row.field.textboxKeyTyped(typed, key)) {
                String error = editor.set(row.setting, row.field.getText());
                row.field.setTextColor(error == null ? TEXT_COLOR : ERROR_COLOR);
                problem = error == null ? null : row.setting.label + ": " + error;
                row.reset.visible = !editor.isDefault(row.setting);
                return;
            }
        }
        super.keyTyped(typed, key);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        search.mouseClicked(mouseX, mouseY, button);
        for (Row row : rows) {
            if (row.field != null) {
                row.field.mouseClicked(mouseX, mouseY, button);
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int target = Math.max(0, Math.min(pages - 1, page + (wheel < 0 ? 1 : -1)));
            if (target != page) {
                page = target;
                initGui();
            }
        }
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
        for (Row row : rows) {
            if (row.field != null) {
                row.field.updateCursorCounter();
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(5, TOP - 4, 10 + SIDEBAR + 5, height - BOTTOM, 0x60000000);
        drawRect(listLeft() - 5, TOP - 4, width - 5, height - BOTTOM, 0x60000000);
        drawCenteredString(fontRendererObj, "§6§lFlipScout §7- Settings", width / 2, 8, 0xFFFFFF);
        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused()) {
            drawString(fontRendererObj, "§8Search a setting...", listLeft() + 4, 25, 0xFFFFFF);
        }
        List<String> hover = null;
        boolean searching = !query.trim().isEmpty();
        for (Row row : rows) {
            int labelRight = row.field != null ? row.field.xPosition - 6 : listRight() - 24 - 106;
            String label = row.setting.label;
            if (searching) {
                Config.Section of = editor.sectionOf(row.setting);
                label = "§8" + (of != null ? of.title : "") + " › §f" + label;
            }
            label = fontRendererObj.trimStringToWidth(label, labelRight - listLeft() - 4);
            drawString(fontRendererObj, label, listLeft() + 2, row.y + 6, editor.isDefault(row.setting) ? 0xFFFFFF : 0xFFFF55);
            if (row.field != null) {
                row.field.drawTextBox();
            }
            boolean overRow = mouseY >= row.y && mouseY < row.y + 20 && mouseX >= listLeft() && mouseX < listRight() - 22;
            if (overRow) {
                hover = new ArrayList<String>();
                for (String line : editor.tooltip(row.setting)) {
                    hover.addAll(fontRendererObj.listFormattedStringToWidth(line, 260));
                }
            }
        }
        if (rows.isEmpty()) {
            drawCenteredString(fontRendererObj, "§7No settings found", (listLeft() + listRight()) / 2, TOP + 10,
                0xFFFFFF);
        }
        int middle = (listLeft() + listRight()) / 2;
        drawCenteredString(fontRendererObj, "Page " + (page + 1) + "/" + pages, middle, height - 20, 0xAAAAAA);
        if (problem != null) {
            drawString(fontRendererObj, "§c" + fontRendererObj.trimStringToWidth(problem, width - 20), 10, height - BOTTOM + 1,
                0xFFFFFF);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hover != null) {
            drawHoveringText(hover, mouseX, mouseY);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        editor.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
