package dev.botfetch;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 和 Litematica 材料清单界面的软对接：全部走反射，没装 Litematica 也不影响加载。
 *
 * <p>数据来源：{@code GuiMaterialList#getMaterialList()} →
 * {@code MaterialListBase#getMaterialsFiltered(boolean)} → {@code MaterialListEntry#getStack()/getCountMissing()}。</p>
 *
 * <p>按钮用 malilib 的 {@code ButtonGeneric} + {@code GuiBase#addButton} 加，能和原有控件一起渲染。</p>
 */
public final class LitematicaMaterials {
    public record Need(ItemStack stack, String itemId, int missing) {
    }

    private static boolean resolved;
    private static Class<?> guiClass;
    private static Method getMaterialList;
    private static Method getMaterialsFiltered;
    private static Method getStack;
    private static Method getCountMissing;

    private static Class<?> guiBaseClass;
    private static Class<?> buttonBaseClass;
    private static Class<?> buttonGenericClass;
    private static Class<?> listenerClass;
    private static Method addButton;
    private static Method setActionListener;
    private static Constructor<?> buttonCtor;

    private LitematicaMaterials() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            guiClass = Class.forName("fi.dy.masa.litematica.gui.GuiMaterialList");
            getMaterialList = guiClass.getMethod("getMaterialList");

            Class<?> listBase = Class.forName("fi.dy.masa.litematica.materials.MaterialListBase");
            getMaterialsFiltered = listBase.getMethod("getMaterialsFiltered", boolean.class);

            Class<?> entry = Class.forName("fi.dy.masa.litematica.materials.MaterialListEntry");
            getStack = entry.getMethod("getStack");
            getCountMissing = entry.getMethod("getCountMissing");

            guiBaseClass = Class.forName("fi.dy.masa.malilib.gui.GuiBase");
            buttonBaseClass = Class.forName("fi.dy.masa.malilib.gui.button.ButtonBase");
            buttonGenericClass = Class.forName("fi.dy.masa.malilib.gui.button.ButtonGeneric");
            listenerClass = Class.forName("fi.dy.masa.malilib.gui.button.IButtonActionListener");
            addButton = guiBaseClass.getMethod("addButton", buttonBaseClass, listenerClass);
            setActionListener = buttonGenericClass.getMethod("setActionListener", listenerClass);
            buttonCtor = buttonGenericClass.getConstructor(
                    int.class, int.class, int.class, int.class, String.class, String[].class);
        } catch (Throwable t) {
            BotFetchClient.LOGGER.warn("[BotFetch] Litematica 材料清单接口不可用: {}", t.toString());
            guiClass = null;
        }
    }

    public static boolean isMaterialsScreen(Screen screen) {
        resolve();
        try {
            return guiClass != null && guiClass.isInstance(screen);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读出清单里所有“还缺”的材料，同一种物品合并计数。 */
    public static List<Need> readNeeds(Screen screen) {
        resolve();
        Map<String, Need> merged = new LinkedHashMap<>();
        if (guiClass == null) {
            return new ArrayList<>();
        }
        try {
            Object materialList = getMaterialList.invoke(screen);
            if (materialList == null) {
                return new ArrayList<>();
            }
            Object raw = getMaterialsFiltered.invoke(materialList, false);
            if (!(raw instanceof List<?> entries)) {
                return new ArrayList<>();
            }
            for (Object entry : entries) {
                ItemStack stack = (ItemStack) getStack.invoke(entry);
                int missing = (Integer) getCountMissing.invoke(entry);
                if (stack == null || stack.isEmpty() || missing <= 0) {
                    continue;
                }
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                Need old = merged.get(id);
                if (old == null) {
                    merged.put(id, new Need(stack, id, missing));
                } else {
                    merged.put(id, new Need(old.stack(), id, old.missing() + missing));
                }
            }
        } catch (Throwable t) {
            BotFetchClient.LOGGER.warn("[BotFetch] 读取材料清单失败: {}", t.toString());
        }
        return new ArrayList<>(merged.values());
    }

    /** 在材料清单界面右下角加一个「请求材料」按钮。 */
    public static boolean addRequestButton(Screen screen, Runnable onClick) {
        resolve();
        if (guiClass == null || buttonCtor == null) {
            return false;
        }
        try {
            // 按钮位置：右下角，往上挪一点，避开 Litematica/其他模组在底边的按钮
            int width = 100;
            int x = screen.width - width - 10;
            int y = screen.height - 52;

            String label = "请求材料";
            try {
                Class<?> stringUtils = Class.forName("fi.dy.masa.malilib.util.StringUtils");
                Object translated = stringUtils
                        .getMethod("getTranslatedOrFallback", String.class, String.class)
                        .invoke(null, "botfetch.button.request_materials", label);
                if (translated instanceof String s && !s.isBlank()) {
                    label = s;
                }
            } catch (Throwable ignored) {
                // 拿不到翻译就用中文字面量
            }

            Object button = buttonCtor.newInstance(x, y, width, 20, label, new String[0]);
            Object listener = Proxy.newProxyInstance(
                    LitematicaMaterials.class.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "BotFetchRequestMaterialsButton";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        onClick.run();
                        return null;
                    });
            setActionListener.invoke(button, listener);
            addButton.invoke(screen, button, listener);
            BotFetchClient.LOGGER.info("[BotFetch] 已在材料清单界面添加「请求材料」按钮");
            return true;
        } catch (Throwable t) {
            BotFetchClient.LOGGER.warn("[BotFetch] 添加请求按钮失败: {}", t.toString());
            return false;
        }
    }
}
