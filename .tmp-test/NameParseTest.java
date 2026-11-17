import java.util.*;
import java.util.regex.*;

public class NameParseTest {
    static final Pattern SUCCESS = Pattern.compile(
            "取到\\s*(\\d+)\\s*/\\s*(\\d+)\\s*个\\s*([^，,]+)[，,]\\s*来自\\s*(\\d+)\\s*个箱(?:子)?");
    static final Pattern FINDBOX_SUCCESS = Pattern.compile("完成：取盒\\s*(\\d+)\\s*个");
    static final Pattern NAME_SEGMENT = Pattern.compile(
            "(?:已放到假人|交给假人)\\s*([^，,。]*?)\\s*(?:身上)?(?=[，,。]|$)");
    static final Pattern NAME_SPLIT = Pattern.compile("[、,，\\s]+");
    static final Set<String> NOT_A_PLAYER = Set.of("drop-fallback", "drop", "chest", "failed", "none", "fakeplayer");
    static final String FALLBACK = "qyb_botyong";

    static List<String> parse(String text) {
        List<String> names = new ArrayList<>();
        Matcher m = NAME_SEGMENT.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            if (raw == null || raw.isBlank()) continue;
            for (String p : NAME_SPLIT.split(raw.trim())) {
                String n = p.trim();
                if (!n.isEmpty() && !names.contains(n)) names.add(n);
            }
        }
        return names;
    }

    static List<String> resolve(List<String> parsed) {
        List<String> out = new ArrayList<>();
        for (String n : parsed) {
            if (n == null || n.isBlank()) continue;
            if (NOT_A_PLAYER.contains(n.toLowerCase(Locale.ROOT))) continue;
            if (!out.contains(n)) out.add(n);
        }
        if (out.isEmpty() && parsed.isEmpty()) out.add(FALLBACK);
        return out;
    }

    static void check(String text) {
        String s = text.replace('\n', ' ').replace('\r', ' ').trim();
        boolean find = SUCCESS.matcher(s).find();
        boolean box = FINDBOX_SUCCESS.matcher(s).find();
        List<String> parsed = parse(s);
        List<String> resolved = resolve(parsed);
        System.out.printf("%-5s %-5s parsed=%-28s -> summon=%s%n",
                find, box, parsed, resolved);
    }

    public static void main(String[] args) {
        check("996_bot悄悄地对你说：取到 1728/1728 个 黏性活塞，来自 3 个箱子，已放到假人 qyb_botyong、SF_1 身上，用时 6s");
        check("取到 32/1 个 去皮金合欢原木，来自 1 个箱子，已放到假人 qyb_botyong 身上，用时 4s");
        check("取到 32/1 个 去皮金合欢原木，来自 1 个箱子，用时 4s");
        check("完成：取盒 2 个，零头装盒 59 件，交给假人 qyb_botyong、SF_1，共 2 个盒子");
        check("完成：取盒 2 个，零头装盒 59 件，共 2 个盒子");
        check("取到 10/10 个 石头，来自 1 个箱子，已放到假人 drop-fallback 身上，用时 2s");
        check("取到 10/10 个 石头，来自 1 个箱子，已放到假人 SF_3 身上，用时 2s");
        check("取到 10/10 个 石头，来自 1 个箱子，已放到假人 qyb_botyong、SF_1、SF_2 身上，用时 2s");
    }
}
