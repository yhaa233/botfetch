package dev.botfetch;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * 准星下方的状态条。
 *
 * <p>平时不显示；Alt+V 进入数量调节模式后才显示「物品 ×数量」，
 * 有请求在跑（等待/召唤）或刚完成/失败时显示状态。</p>
 */
public final class BotFetchHud implements HudElement {
    private static final int BACKGROUND = 0xB4000000;
    private static final int BORDER = 0x60FFFFFF;
    private static final int WHITE = 0xFFFFFFFF;

    @Override
    public void render(DrawContext context, RenderTickCounter tickCounter) {
        BotFetchConfig cfg = BotFetchClient.config;
        MinecraftClient client = MinecraftClient.getInstance();
        if (cfg == null || !cfg.hudEnabled || client.player == null || client.currentScreen != null) {
            return;
        }
        if (client.options.hudHidden) {
            return;
        }

        boolean adjusting = BotFetchClient.isAdjusting();
        boolean busy = RequestTracker.state() != RequestTracker.State.IDLE || RequestTracker.hasNotice();
        if (!adjusting && !busy) {
            return;
        }

        TextRenderer textRenderer = client.textRenderer;
        Text statusLine = adjusting && !RequestTracker.hasNotice()
                ? Text.translatable("hud.botfetch.adjusting")
                : RequestTracker.statusText();

        Text itemLine = null;
        ItemStack stack = null;
        if (adjusting) {
            TargetPicker.Target target = TargetPicker.current();
            if (target != null) {
                stack = target.stack();
                itemLine = Text.empty().append(stack.getName()).append(Text.literal(" ×" + cfg.count));
            } else {
                itemLine = Text.translatable("hud.botfetch.amount", cfg.count);
            }
        }

        int textWidth = textRenderer.getWidth(statusLine);
        if (itemLine != null) {
            textWidth = Math.max(textWidth, textRenderer.getWidth(itemLine));
        }
        int leftPadding = stack == null ? 8 : 24;
        int boxWidth = textWidth + leftPadding + 6;
        int boxHeight = itemLine == null ? 18 : 32;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int x = screenWidth / 2 - boxWidth / 2;
        int y = screenHeight - 68;

        context.fill(x, y, x + boxWidth, y + boxHeight, BACKGROUND);
        context.drawHorizontalLine(x, x + boxWidth, y, BORDER);
        context.drawHorizontalLine(x, x + boxWidth, y + boxHeight, BORDER);

        if (itemLine != null) {
            if (stack != null) {
                context.drawItem(stack, x + 4, y + 8);
            }
            context.drawTextWithShadow(textRenderer, itemLine, x + leftPadding, y + 4, WHITE);
            context.drawTextWithShadow(textRenderer, statusLine, x + leftPadding, y + 17, RequestTracker.statusColor());
        } else {
            context.drawTextWithShadow(textRenderer, statusLine, x + 8, y + 5, RequestTracker.statusColor());
        }
    }
}
