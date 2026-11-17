package dev.botfetch;

import dev.botfetch.gui.BotFetchConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 客户端入口：按键处理、HUD、私聊回复解析。
 *
 * <p>按键自己管，不走原版 KeyBinding，这样才能做「V 下单 / V+C 打开配置」这种组合键：
 * 单独按 V 在松开时下单；按住 V 再按 C 则取消下单、打开配置界面。</p>
 */
public class BotFetchClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("BotFetch");
    public static BotFetchConfig config;

    /** 当前按住的键。 */
    private static final Set<Integer> HELD_KEYS = new HashSet<>();
    /** 按下 V 之后等松开再下单。 */
    private static boolean requestArmed;
    /** 组合键已经触发过，避免一直按住时反复打开界面。 */
    private static boolean comboFired;
    /** 数量调节模式：Alt+V 开关，开启时滚轮改数量。 */
    private static boolean adjusting;
    /** 上一次保存配置的时间，滚轮调整用。 */
    private static long lastFlush;
    /** 待写入聊天栏的消息（在 tick 里统一 flush）。 */
    private static final java.util.Queue<Text> QUEUED_CHAT = new java.util.ArrayDeque<>();

    @Override
    public void onInitializeClient() {
        config = BotFetchConfig.load();

        HudElementRegistry.addLast(Identifier.of("botfetch", "hud"), new BotFetchHud());
        ClientTickEvents.END_CLIENT_TICK.register(BotFetchClient::onTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                RequestTracker.onChat(message);
            }
        });
        // 有些服务端把私聊当普通聊天发下来，两条路都监听，解析器自己会去重。
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) ->
                RequestTracker.onChat(message));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (config != null) {
                config.save();
            }
        });
        // Litematica 材料清单界面：右下角加「请求材料」按钮
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (LitematicaMaterials.isMaterialsScreen(screen)) {
                LitematicaMaterials.addRequestButton(screen, () -> requestMaterials(screen));
            }
        });
        LOGGER.info("[BotFetch] 已加载，机器人：{}，下单键：{}，配置键：{}",
                config.botName, config.requestKey, config.openConfigKeys);
    }

    private static void onTick(MinecraftClient client) {
        if (config == null) {
            return;
        }
        if (client.player == null || client.world == null) {
            TargetPicker.clear();
            return;
        }
        TargetPicker.update(client);
        RequestTracker.tick();
        flushChat(client);
        if (System.currentTimeMillis() - lastFlush > 1500L) {
            lastFlush = System.currentTimeMillis();
            config.flushIfDirty();
        }
    }

    /** 聊天消息先排队，下一 tick 再塞进聊天栏，避免在收消息事件里重入 ChatHud。 */
    private static void flushChat(MinecraftClient client) {
        while (!QUEUED_CHAT.isEmpty()) {
            client.inGameHud.getChatHud().addMessage(QUEUED_CHAT.poll());
        }
    }

    // ---------------------------------------------------------------- 按键

    /** KeyboardMixin 转发进来的原始按键事件。 */
    public static void onRawKey(int key, int action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof BotFetchConfigScreen screen) {
            screen.handleRawKey(key, action);
            return;
        }
        if (client.currentScreen != null) {
            if (!HELD_KEYS.isEmpty() || requestArmed) {
                resetKeyState();
            }
            return;
        }
        if (client.player == null || config == null) {
            return;
        }

        if (action == GLFW.GLFW_PRESS) {
            HELD_KEYS.add(key);
            if (!comboFired && comboComplete()) {
                comboFired = true;
                requestArmed = false;
                openConfigScreen(client);
                return;
            }
            if (key == config.requestKey) {
                if (isAltDown(client)) {
                    toggleAdjusting();
                } else {
                    requestArmed = true;
                }
            }
        } else if (action == GLFW.GLFW_RELEASE) {
            HELD_KEYS.remove(key);
            if (key == config.requestKey && requestArmed) {
                requestArmed = false;
                request(client);
            }
            if (!comboComplete()) {
                comboFired = false;
            }
        }
    }

    private static boolean comboComplete() {
        return config != null && !config.openConfigKeys.isEmpty() && HELD_KEYS.containsAll(config.openConfigKeys);
    }

    public static void resetKeyState() {
        HELD_KEYS.clear();
        requestArmed = false;
        comboFired = false;
    }

    private static void openConfigScreen(MinecraftClient client) {
        requestArmed = false;
        HELD_KEYS.clear();
        client.setScreen(new BotFetchConfigScreen());
    }

    private static void toggleAdjusting() {
        adjusting = !adjusting;
        chat(Text.translatable(adjusting ? "message.botfetch.adjust_on" : "message.botfetch.adjust_off"));
    }

    private static void request(MinecraftClient client) {
        if (client.getNetworkHandler() == null) {
            return;
        }
        TargetPicker.Target target = TargetPicker.current();
        if (target == null) {
            chat(Text.translatable("message.botfetch.no_target"));
            return;
        }
        if (RequestTracker.isBusy()) {
            chat(Text.translatable("message.botfetch.busy", config.botName));
            return;
        }
        // 指令统一由 RequestTracker 发送（单条/批量共用同一条发送路径，别在这里再发一次）
        RequestTracker.begin(target, config.count);
        chat(Text.translatable("message.botfetch.sent", target.stack().getName(), config.count));
    }

    /** 材料清单界面点「请求材料」：把缺口排进批量队列。 */
    static void requestMaterials(net.minecraft.client.gui.screen.Screen screen) {
        if (config == null) {
            return;
        }
        if (RequestTracker.isBusy()) {
            chat(Text.translatable("message.botfetch.busy", config.botName));
            return;
        }
        java.util.List<LitematicaMaterials.Need> needs = LitematicaMaterials.readNeeds(screen);
        if (needs.isEmpty()) {
            chat(Text.translatable("message.botfetch.batch_empty"));
            return;
        }
        int cap = Math.max(1, config.batchMaxItems);
        if (needs.size() > cap) {
            chat(Text.translatable("message.botfetch.batch_truncated", needs.size(), cap));
            needs = needs.subList(0, cap);
        }
        int total = 0;
        for (LitematicaMaterials.Need need : needs) {
            total += need.missing();
        }
        RequestTracker.beginBatch(RequestTracker.toPending(needs));
        chat(Text.translatable("message.botfetch.batch_start", needs.size(), total));
    }

    /** 鼠标滚轮：只有调节模式才接管，返回 true 表示要吞掉这次滚轮。 */
    public static boolean handleScroll(double amount) {
        if (config == null || amount == 0.0D || !adjusting) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.currentScreen != null) {
            return false;
        }
        boolean shift = isKeyDown(client, GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyDown(client, GLFW.GLFW_KEY_RIGHT_SHIFT);
        int step = Math.max(1, shift ? config.scrollShiftStep : config.scrollStep);
        int delta = amount > 0.0D ? step : -step;
        config.count = BotFetchConfig.clamp(config.count + delta, config.minCount, config.maxCount);
        config.markDirty();
        lastFlush = 0L;
        return true;
    }

    public static boolean isAdjusting() {
        return adjusting;
    }

    private static boolean isKeyDown(MinecraftClient client, int keyCode) {
        return InputUtil.isKeyPressed(client.getWindow(), keyCode);
    }

    /** 是否按住 Alt（左右都算）。 */
    public static boolean isAltDown(MinecraftClient client) {
        return isKeyDown(client, GLFW.GLFW_KEY_LEFT_ALT) || isKeyDown(client, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    public static void chat(Text text) {
        QUEUED_CHAT.add(text);
    }

    public static void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            return;
        }
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        client.getNetworkHandler().sendChatCommand(normalized);
    }
}
