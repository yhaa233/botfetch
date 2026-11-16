package dev.botfetch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 模组配置，落在 config/botfetch.json。
 */
public final class BotFetchConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 仓库机器人名字（/w 后面那个）。 */
    public String botName = "996_bot";
    /** 下单指令模板，可用占位符：{bot} {item} {count}。 */
    public String requestCommand = "/w {bot} find {item} {count}";

    /** 收到完成回复之后，是否自动召唤假人。 */
    public boolean summonEnabled = true;
    /** 先把假人 kill 掉（保留背包），再用 spawn 放到自己脚下。 */
    public String killCommand = "/player {fake} kill";
    public String spawnCommand = "/player {fake} spawn";
    /** 机器人回复里没写「已放到假人 X 身上」时，默认对谁执行 kill/spawn；留空则跳过召唤。 */
    public String fallbackFakePlayer = "qyb_botyong";
    /** kill 与 spawn 之间等待的 tick 数。 */
    public int summonDelayTicks = 20;

    /** 执行下单的按键（GLFW keycode）。 */
    public int requestKey = GLFW.GLFW_KEY_V;
    /** 打开配置界面的组合键（1~2 个 keycode，全部按住才触发）。 */
    public List<Integer> openConfigKeys = new ArrayList<>(List.of(GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_C));

    /** 当前数量（滚轮调，持久化）。 */
    public int count = 1;
    public int minCount = 1;
    public int maxCount = 9999;
    public int scrollStep = 1;
    public int scrollShiftStep = 10;

    public boolean hudEnabled = true;
    /** 是否读取 Litematica 投影方块。 */
    public boolean projectionEnabled = true;
    /** 触发距离（格）。 */
    public int reach = 6;
    /** 等机器人回复的超时时间（秒）。 */
    public int replyTimeoutSeconds = 600;
    /** 材料清单「请求材料」一次最多排队多少种物品。 */
    public int batchMaxItems = 64;

    private transient boolean dirty;
    private transient long lastChange;

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("botfetch.json");
    }

    public static BotFetchConfig load() {
        Path file = path();
        BotFetchConfig cfg = new BotFetchConfig();
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                BotFetchConfig parsed = GSON.fromJson(json, BotFetchConfig.class);
                if (parsed != null) {
                    parsed.minCount = Math.max(1, parsed.minCount);
                    parsed.maxCount = Math.max(parsed.minCount, parsed.maxCount);
                    parsed.count = clamp(parsed.count, parsed.minCount, parsed.maxCount);
                    parsed.summonDelayTicks = Math.max(1, parsed.summonDelayTicks);
                    parsed.reach = Math.max(2, parsed.reach);
                    parsed.validateKeys();
                    cfg = parsed;
                }
            } catch (Exception e) {
                BotFetchClient.LOGGER.warn("[BotFetch] 读取配置失败，使用默认值: {}", e.toString());
            }
        }
        cfg.dirty = false;
        cfg.save();
        return cfg;
    }

    private void validateKeys() {
        if (requestKey <= 0) {
            requestKey = GLFW.GLFW_KEY_V;
        }
        if (openConfigKeys == null || openConfigKeys.isEmpty()) {
            openConfigKeys = new ArrayList<>(List.of(GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_C));
        } else {
            List<Integer> cleaned = new ArrayList<>();
            for (Integer key : openConfigKeys) {
                if (key != null && key > 0 && !cleaned.contains(key) && cleaned.size() < 2) {
                    cleaned.add(key);
                }
            }
            openConfigKeys = cleaned.isEmpty()
                    ? new ArrayList<>(List.of(GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_C))
                    : cleaned;
        }
    }

    public void clampCount() {
        count = clamp(count, minCount, maxCount);
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void markDirty() {
        dirty = true;
        lastChange = System.currentTimeMillis();
    }

    /** 滚轮调整后延迟落盘，避免每格都写文件。 */
    public void flushIfDirty() {
        if (dirty && System.currentTimeMillis() - lastChange > 1500L) {
            save();
        }
    }

    public void save() {
        dirty = false;
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BotFetchClient.LOGGER.warn("[BotFetch] 保存配置失败: {}", e.toString());
        }
    }
}
