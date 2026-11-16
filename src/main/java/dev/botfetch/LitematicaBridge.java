package dev.botfetch;

import net.minecraft.world.BlockView;

import java.lang.reflect.Method;

/**
 * 对 Litematica 的软依赖：只用反射，装没装都不影响加载。
 *
 * <p>等价于直接调用 {@code fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld()}，
 * 返回一个只包含投影方块的只读世界（{@link BlockView}）。没装 Litematica、没加载投影、
 * 或者 API 变了，都会安静地返回 {@code null}。</p>
 */
public final class LitematicaBridge {
    private static boolean checked;
    private static Method getSchematicWorld;

    private LitematicaBridge() {
    }

    private static void resolve() {
        if (checked) {
            return;
        }
        checked = true;
        try {
            Class<?> handler = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            getSchematicWorld = handler.getMethod("getSchematicWorld");
        } catch (Throwable ignored) {
            getSchematicWorld = null;
        }
    }

    public static boolean available() {
        resolve();
        return getSchematicWorld != null;
    }

    /** 当前投影世界，没有投影时为 null。 */
    public static BlockView schematicWorld() {
        if (!available()) {
            return null;
        }
        try {
            Object world = getSchematicWorld.invoke(null);
            return world instanceof BlockView view ? view : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
