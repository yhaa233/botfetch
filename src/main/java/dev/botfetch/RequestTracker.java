package dev.botfetch;

import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 请求的生命周期：发出 → 等 996_bot 私聊回复 → 解析假人名字 → kill + spawn 到身边。
 *
 * <p>机器人有两种完成回复，且都可能列出多个假人（qyb_botyong 满了会用 SF_1…）：</p>
 * <pre>
 * 取到 1728/1728 个 黏性活塞，来自 3 个箱子，已放到假人 qyb_botyong、SF_1 身上，用时 6s
 * 完成：取盒 2 个，零头装盒 59 件，交给假人 qyb_botyong、SF_1，共 2 个盒子
 * </pre>
 *
 * <p>回复里出现几个假人，就把几个全部 kill + spawn 到玩家脚下（命令同一瞬间发）。</p>
 */
public final class RequestTracker {
    public enum State {
        IDLE, WAITING, SUMMONING
    }

    /** 队列里的一项。 */
    public record Pending(TargetPicker.Target target, int count) {
    }

    /** find 的完成回复。 */
    private static final Pattern SUCCESS = Pattern.compile(
            "取到\\s*(\\d+)\\s*/\\s*(\\d+)\\s*个\\s*([^，,]+)[，,]\\s*来自\\s*(\\d+)\\s*个箱(?:子)?");
    /** findbox 的完成回复。 */
    private static final Pattern FINDBOX_SUCCESS = Pattern.compile("完成：取盒\\s*(\\d+)\\s*个");
    /** 「已放到假人 A、B 身上」/「交给假人 A、B」里的一整段名字。 */
    private static final Pattern NAME_SEGMENT = Pattern.compile(
            "(?:已放到假人|交给假人)\\s*([^，,。]*?)\\s*(?:身上)?(?=[，,。]|$)");
    /** 名字分隔符。 */
    private static final Pattern NAME_SPLIT = Pattern.compile("[、,，\\s]+");

    private static final Pattern FAILURE = Pattern.compile("没取到|正在忙「|索引是空的|仓库里没有");
    private static final Pattern BUSY = Pattern.compile("正在忙「");
    private static final int BUSY_RETRIES = 3;
    private static final int BUSY_RETRY_TICKS = 100;
    /** 机器人投递失败时会把 "drop-fallback" 之类当成假人名，别去 kill 它。 */
    private static final Set<String> NOT_A_PLAYER =
            Set.of("drop-fallback", "drop", "chest", "failed", "none", "fakeplayer");

    private static State state = State.IDLE;
    private static TargetPicker.Target target;
    private static int count = 1;
    private static long sentAt;
    private static List<String> fakeNames = List.of();

    private static final Deque<Pending> QUEUE = new ArrayDeque<>();
    private static boolean batch;
    private static int batchIndex;
    private static int batchTotal;
    private static int batchOk;
    private static int batchFail;
    /** 整个批量过程中出现过的假人（最后一个任务结束后一起召唤）。 */
    private static final LinkedHashSet<String> batchFakeNames = new LinkedHashSet<>();

    private static List<String> summonNames = List.of();
    private static int summonTicks;

    private static Pending retryPending;
    private static int retryTicks;
    private static int retryCount;

    private static Text notice;
    private static int noticeColor = 0xFFFFFF;
    private static long noticeUntil;

    private RequestTracker() {
    }

    public static State state() {
        return state;
    }

    public static boolean isBusy() {
        return state == State.WAITING;
    }

    public static TargetPicker.Target target() {
        return target;
    }

    public static int count() {
        return count;
    }

    public static boolean isBatch() {
        return batch;
    }

    /** 单条请求。 */
    public static void begin(TargetPicker.Target requested, int amount) {
        QUEUE.clear();
        batch = false;
        batchIndex = 0;
        batchTotal = 0;
        batchOk = 0;
        batchFail = 0;
        batchFakeNames.clear();
        clearRetry();
        send(requested, amount);
    }

    /** 批量请求：逐个发送，全部结束后再召唤假人。 */
    public static void beginBatch(List<Pending> items) {
        QUEUE.clear();
        QUEUE.addAll(items);
        batch = true;
        batchIndex = 0;
        batchTotal = items.size();
        batchOk = 0;
        batchFail = 0;
        batchFakeNames.clear();
        clearRetry();
        sendNext();
    }

    private static void clearRetry() {
        retryPending = null;
        retryTicks = 0;
        retryCount = 0;
    }

    private static void send(TargetPicker.Target requested, int amount) {
        BotFetchConfig cfg = BotFetchClient.config;
        if (cfg == null) {
            return;
        }
        state = State.WAITING;
        target = requested;
        count = amount;
        sentAt = System.currentTimeMillis();
        fakeNames = List.of();
        String command = cfg.requestCommand
                .replace("{bot}", cfg.botName)
                .replace("{item}", requested.itemId())
                .replace("{count}", Integer.toString(amount));
        BotFetchClient.sendCommand(command);
    }

    private static void sendNext() {
        Pending next = QUEUE.poll();
        if (next == null) {
            finishBatch();
            return;
        }
        batchIndex = batchTotal - QUEUE.size();
        send(next.target(), next.count());
    }

    private static void setNotice(Text text, int color, long millis) {
        notice = text;
        noticeColor = color;
        noticeUntil = System.currentTimeMillis() + millis;
    }

    public static void tick() {
        BotFetchConfig cfg = BotFetchClient.config;
        if (cfg == null) {
            return;
        }
        if (retryTicks > 0 && state == State.IDLE) {
            if (--retryTicks == 0) {
                Pending pending = retryPending;
                retryPending = null;
                if (pending != null) {
                    send(pending.target(), pending.count());
                }
            }
        }
        if (state == State.WAITING && System.currentTimeMillis() - sentAt > cfg.replyTimeoutSeconds * 1000L) {
            boolean wasBatch = batch;
            QUEUE.clear();
            batch = false;
            state = State.IDLE;
            Text text = Text.translatable("message.botfetch.timeout", cfg.replyTimeoutSeconds);
            setNotice(text, 0xFF6B6B, 4000);
            BotFetchClient.chat(text);
            if (wasBatch) {
                BotFetchClient.chat(Text.translatable("message.botfetch.batch_aborted"));
            }
        }
        if (state == State.SUMMONING && summonTicks-- <= 0) {
            List<String> names = summonNames;
            summonNames = List.of();
            state = State.IDLE;
            if (!names.isEmpty()) {
                // 全部同一瞬间发
                for (String name : names) {
                    BotFetchClient.sendCommand(cfg.spawnCommand.replace("{fake}", name));
                }
                Text text = summary("message.botfetch.summoned", names);
                BotFetchClient.chat(text);
                setNotice(text, 0x7CFC00, 5000);
            }
        }
    }

    /** 所有聊天消息都会进来，这里只认机器人那两句完成回复。 */
    public static void onChat(Text message) {
        if (state != State.WAITING) {
            return;
        }
        String text = message.getString().replace('\n', ' ').replace('\r', ' ').trim();

        boolean find = SUCCESS.matcher(text).find();
        boolean findbox = FINDBOX_SUCCESS.matcher(text).find();
        if (find || findbox) {
            advance(resolveNames(parseFakeNames(text)), true);
            return;
        }

        Matcher failure = FAILURE.matcher(text);
        if (failure.find()) {
            if (BUSY.matcher(text).find() && retryCount < BUSY_RETRIES) {
                // 机器人正忙：保留这条请求，过几秒重发，别当成失败
                retryCount++;
                retryPending = new Pending(target, count);
                retryTicks = BUSY_RETRY_TICKS;
                state = State.IDLE;
                BotFetchClient.chat(Text.translatable("message.botfetch.retry",
                        BUSY_RETRY_TICKS / 20, retryCount));
                return;
            }
            advance(List.of(), false);
        }
    }

    /** 从「已放到假人 A、B 身上」/「交给假人 A、B」里取出所有名字。 */
    static List<String> parseFakeNames(String text) {
        List<String> names = new ArrayList<>();
        Matcher segment = NAME_SEGMENT.matcher(text);
        while (segment.find()) {
            String raw = segment.group(1);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String piece : NAME_SPLIT.split(raw.trim())) {
                String name = piece.trim();
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 过滤掉 "drop-fallback" 之类的伪名字；回复里完全没写假人时才用配置兜底。 */
    private static List<String> resolveNames(List<String> parsed) {
        List<String> out = new ArrayList<>();
        for (String name : parsed) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (NOT_A_PLAYER.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        if (out.isEmpty() && parsed.isEmpty()) {
            BotFetchConfig cfg = BotFetchClient.config;
            String fallback = cfg == null || cfg.fallbackFakePlayer == null ? "" : cfg.fallbackFakePlayer.trim();
            if (!fallback.isEmpty()) {
                out.add(fallback);
            }
        }
        return out;
    }

    private static void advance(List<String> names, boolean ok) {
        state = State.IDLE;
        if (ok) {
            retryCount = 0;
            batchOk++;
            if (!names.isEmpty()) {
                fakeNames = names;
                batchFakeNames.addAll(names);
            }
        } else {
            batchFail++;
        }
        if (!QUEUE.isEmpty()) {
            sendNext();
            return;
        }
        finishBatch();
    }

    private static void finishBatch() {
        boolean wasBatch = batch;
        batch = false;
        if (wasBatch) {
            BotFetchClient.chat(Text.translatable("message.botfetch.batch_done", batchOk, batchFail));
        }
        setNotice(Text.translatable("message.botfetch.done"), 0x7CFC00, 4000);

        BotFetchConfig cfg = BotFetchClient.config;
        List<String> names = new ArrayList<>(batchFakeNames);
        batchFakeNames.clear();
        fakeNames = List.of();
        if (cfg == null || !cfg.summonEnabled || names.isEmpty()) {
            return;
        }
        state = State.SUMMONING;
        summonNames = names;
        summonTicks = Math.max(1, cfg.summonDelayTicks);
        for (String name : names) {
            BotFetchClient.sendCommand(cfg.killCommand.replace("{fake}", name));
        }
        BotFetchClient.chat(summary("message.botfetch.summoning", names));
    }

    private static Text summary(String key, List<String> names) {
        String joined = String.join("、", names);
        if (names.size() == 1) {
            return Text.translatable(key, joined);
        }
        return Text.translatable(key + ".multi", names.size(), joined);
    }

    /** HUD 第二行。 */
    public static Text statusText() {
        BotFetchConfig cfg = BotFetchClient.config;
        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            return notice;
        }
        if (retryTicks > 0) {
            return Text.translatable("hud.botfetch.retry");
        }
        if (batch && state == State.WAITING) {
            return Text.translatable("hud.botfetch.batch", batchIndex, batchTotal);
        }
        return switch (state) {
            case WAITING -> Text.translatable("hud.botfetch.waiting",
                    cfg == null ? "996_bot" : cfg.botName);
            case SUMMONING -> Text.translatable("hud.botfetch.summoning",
                    String.join("、", summonNames));
            default -> Text.translatable("hud.botfetch.hint");
        };
    }

    public static int statusColor() {
        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            return noticeColor;
        }
        return switch (state) {
            case WAITING -> 0xFFD966;
            case SUMMONING -> 0x7CFC00;
            default -> 0xA0A0A0;
        };
    }

    /** 是否有还没过期的提示（完成/失败/超时）。 */
    public static boolean hasNotice() {
        return notice != null && System.currentTimeMillis() < noticeUntil;
    }

    /** HUD 上显示的目标：等待中显示上次请求的，否则显示当前瞄准的。 */
    public static TargetPicker.Target displayTarget() {
        if (state != State.IDLE) {
            return target;
        }
        return TargetPicker.current();
    }

    public static int displayCount() {
        if (state != State.IDLE && target != null) {
            return count;
        }
        BotFetchConfig cfg = BotFetchClient.config;
        return cfg == null ? 1 : cfg.count;
    }

    /** 供批量请求组装数据用。 */
    public static List<Pending> toPending(List<LitematicaMaterials.Need> needs) {
        List<Pending> list = new ArrayList<>();
        for (LitematicaMaterials.Need need : needs) {
            int amount = BotFetchConfig.clamp(need.missing(), 1, Integer.MAX_VALUE);
            list.add(new Pending(new TargetPicker.Target(need.stack(), need.itemId(), false), amount));
        }
        return list;
    }
}
