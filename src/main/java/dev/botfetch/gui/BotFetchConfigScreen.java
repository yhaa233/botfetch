package dev.botfetch.gui;

import dev.botfetch.BotFetchClient;
import dev.botfetch.BotFetchConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * masa 风格的配置界面：一行一个功能，左边名字，右边依次是「开关 / 按键 / 重置」。
 *
 * <p>按键录制：点按键按钮后，按住想要的键（V+C 这种组合键就两个一起按住），
 * 全部松开即生效；Esc 取消。</p>
 */
public final class BotFetchConfigScreen extends Screen {
    private static final int PAD = 16;
    private static final int TOP = 66;
    private static final int ROW_HEIGHT = 26;
    private static final int ROW_COUNT = 3;

    private static final int ROW_SUMMON = 0;
    private static final int ROW_OPEN = 1;
    private static final int ROW_REQUEST = 2;

    private ButtonWidget summonButton;
    private ButtonWidget openKeyButton;
    private ButtonWidget requestKeyButton;

    private int labelMaxWidth = 200;

    /** -1 = 没在录制，否则是要录制的行号。 */
    private int recordingRow = -1;
    private final List<Integer> pendingKeys = new ArrayList<>();

    private Text status;
    private long statusUntil;

    public BotFetchConfigScreen() {
        super(Text.translatable("gui.botfetch.title"));
    }

    private BotFetchConfig cfg() {
        return BotFetchClient.config;
    }

    @Override
    protected void init() {
        BotFetchConfig cfg = cfg();
        if (cfg == null) {
            return;
        }
        int right = width - PAD;
        int resetWidth = 56;
        int keyWidth = Math.min(180, Math.max(120, width / 4));
        int toggleWidth = 56;

        int resetX = right - resetWidth;
        int keyX = resetX - 10 - keyWidth;
        int toggleX = keyX - 10 - toggleWidth;
        labelMaxWidth = Math.max(60, toggleX - PAD - 10);

        int y0 = TOP;
        int y1 = TOP + ROW_HEIGHT;
        int y2 = TOP + ROW_HEIGHT * 2;

        summonButton = addDrawableChild(ButtonWidget.builder(summonLabel(cfg.summonEnabled), button -> {
            BotFetchConfig c = cfg();
            if (c == null) {
                return;
            }
            c.summonEnabled = !c.summonEnabled;
            c.save();
            refresh();
        }).dimensions(toggleX, y0, toggleWidth, 20).build());

        addDisabled(Text.translatable("gui.botfetch.none"), keyX, y0, keyWidth);
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.botfetch.reset"), button -> {
            BotFetchConfig c = cfg();
            if (c == null) {
                return;
            }
            c.summonEnabled = true;
            c.save();
            refresh();
        }).dimensions(resetX, y0, resetWidth, 20).build());

        addDisabled(Text.translatable("gui.botfetch.none"), toggleX, y1, toggleWidth);
        openKeyButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> startRecording(ROW_OPEN))
                .dimensions(keyX, y1, keyWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.botfetch.reset"), button -> {
            BotFetchConfig c = cfg();
            if (c == null) {
                return;
            }
            c.openConfigKeys = new ArrayList<>(List.of(GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_C));
            c.save();
            refresh();
        }).dimensions(resetX, y1, resetWidth, 20).build());

        addDisabled(Text.translatable("gui.botfetch.none"), toggleX, y2, toggleWidth);
        requestKeyButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> startRecording(ROW_REQUEST))
                .dimensions(keyX, y2, keyWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.botfetch.reset"), button -> {
            BotFetchConfig c = cfg();
            if (c == null) {
                return;
            }
            c.requestKey = GLFW.GLFW_KEY_V;
            c.save();
            refresh();
        }).dimensions(resetX, y2, resetWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.botfetch.done"), button -> close())
                .dimensions(width / 2 - 60, height - 30, 120, 20).build());

        refresh();
    }

    private void addDisabled(Text label, int x, int y, int w) {
        ButtonWidget widget = addDrawableChild(ButtonWidget.builder(label, button -> {
        }).dimensions(x, y, w, 20).build());
        widget.active = false;
    }

    private static Text summonLabel(boolean enabled) {
        return enabled
                ? Text.translatable("gui.botfetch.yes").formatted(Formatting.GREEN)
                : Text.translatable("gui.botfetch.no").formatted(Formatting.RED);
    }

    private void refresh() {
        BotFetchConfig cfg = cfg();
        if (cfg == null) {
            return;
        }
        if (summonButton != null) {
            summonButton.setMessage(summonLabel(cfg.summonEnabled));
        }
        if (openKeyButton != null) {
            openKeyButton.setMessage(recordingRow == ROW_OPEN ? recordingLabel() : keysLabel(cfg.openConfigKeys));
        }
        if (requestKeyButton != null) {
            requestKeyButton.setMessage(recordingRow == ROW_REQUEST ? recordingLabel() : keyLabel(cfg.requestKey));
        }
    }

    private Text recordingLabel() {
        if (pendingKeys.isEmpty()) {
            return Text.translatable("gui.botfetch.press").formatted(Formatting.YELLOW);
        }
        return Text.literal(keysLabel(pendingKeys).getString() + "…").formatted(Formatting.YELLOW);
    }

    private Text keysLabel(List<Integer> keys) {
        if (keys == null || keys.isEmpty()) {
            return Text.translatable("gui.botfetch.none");
        }
        StringBuilder sb = new StringBuilder();
        for (Integer key : keys) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(keyName(key));
        }
        return Text.literal(sb.toString());
    }

    private Text keyLabel(int key) {
        return key <= 0 ? Text.translatable("gui.botfetch.none") : Text.literal(keyName(key));
    }

    private static String keyName(int key) {
        if (key <= 0) {
            return "?";
        }
        try {
            return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private void startRecording(int row) {
        recordingRow = row;
        pendingKeys.clear();
        status = null;
        refresh();
    }

    private void cancelRecording() {
        recordingRow = -1;
        pendingKeys.clear();
        refresh();
    }

    private void setStatus(String translationKey) {
        status = Text.translatable(translationKey).formatted(Formatting.RED);
        statusUntil = System.currentTimeMillis() + 4000L;
        refresh();
    }

    /** 键盘原始事件由 KeyboardMixin 转发进来（按键按下/松开）。 */
    public void handleRawKey(int key, int action) {
        if (recordingRow < 0) {
            return;
        }
        if (action == GLFW.GLFW_PRESS) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                return; // Esc 交给 keyPressed 取消录制，避免顺手把整个界面关掉
            }
            if (!pendingKeys.contains(key) && pendingKeys.size() < 2) {
                pendingKeys.add(key);
            }
            refresh();
            return;
        }
        if (action == GLFW.GLFW_RELEASE) {
            if (pendingKeys.isEmpty()) {
                return;
            }
            boolean stillDown = false;
            for (int k : pendingKeys) {
                if (InputUtil.isKeyPressed(client.getWindow(), k)) {
                    stillDown = true;
                    break;
                }
            }
            if (!stillDown) {
                finishRecording();
            }
        }
    }

    private void finishRecording() {
        BotFetchConfig cfg = cfg();
        int row = recordingRow;
        List<Integer> keys = new ArrayList<>(pendingKeys);
        recordingRow = -1;
        pendingKeys.clear();
        if (cfg == null || keys.isEmpty()) {
            refresh();
            return;
        }
        if (row == ROW_OPEN) {
            if (keys.size() == 1 && keys.get(0) == cfg.requestKey) {
                setStatus("gui.botfetch.conflict");
                return;
            }
            cfg.openConfigKeys = keys;
        } else if (row == ROW_REQUEST) {
            if (keys.size() > 1) {
                setStatus("gui.botfetch.single_only");
                return;
            }
            int key = keys.get(0);
            if (cfg.openConfigKeys != null && cfg.openConfigKeys.size() == 1 && cfg.openConfigKeys.get(0) == key) {
                setStatus("gui.botfetch.conflict");
                return;
            }
            cfg.requestKey = key;
        }
        cfg.save();
        refresh();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (recordingRow >= 0) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                cancelRecording();
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        drawRows(context);
        super.render(context, mouseX, mouseY, delta);
        drawStatusLine(context);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xF0101113);
        context.fill(0, 0, width, 46, 0xFF1B1D21);
        context.fill(0, 46, width, 47, 0xFF3A3D42);
    }

    private void drawRows(DrawContext context) {
        int left = PAD - 6;
        int right = width - PAD + 6;
        for (int i = 0; i < ROW_COUNT; i++) {
            int y = TOP + i * ROW_HEIGHT;
            context.fill(left, y - 4, right, y + 20, (i % 2 == 0) ? 0x24FFFFFF : 0x12FFFFFF);
        }
        String[] labels = {
                Text.translatable("gui.botfetch.summon").getString(),
                Text.translatable("gui.botfetch.open").getString(),
                Text.translatable("gui.botfetch.request").getString()
        };
        for (int i = 0; i < labels.length; i++) {
            int y = TOP + i * ROW_HEIGHT + 6;
            context.drawTextWithShadow(textRenderer, fitLabel(labels[i]), PAD, y, 0xFFE6E6E6);
        }
        context.drawTextWithShadow(textRenderer, title, PAD, 12, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("gui.botfetch.subtitle"), PAD, 28, 0xFF9AA0A6);
    }

    /** 太长就截断加省略号，避免压到右边的开关按钮。 */
    private String fitLabel(String text) {
        if (textRenderer.getWidth(text) <= labelMaxWidth) {
            return text;
        }
        String ellipsis = "…";
        int limit = labelMaxWidth - textRenderer.getWidth(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (textRenderer.getWidth(sb.toString() + c) > limit) {
                break;
            }
            sb.append(c);
        }
        return sb + ellipsis;
    }

    private void drawStatusLine(DrawContext context) {
        Text line;
        if (status != null && System.currentTimeMillis() < statusUntil) {
            line = status;
        } else {
            line = Text.translatable("gui.botfetch.hint");
        }
        int y = height - 50;
        context.drawTextWithShadow(textRenderer, line, width / 2 - textRenderer.getWidth(line) / 2, y, 0xFF9AA0A6);
    }

    @Override
    public void close() {
        BotFetchConfig cfg = cfg();
        if (cfg != null) {
            cfg.save();
        }
        BotFetchClient.resetKeyState();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
