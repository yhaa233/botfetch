package dev.botfetch;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

/**
 * 决定“我现在指着什么东西”：
 * 先看 Litematica 投影世界（准星打的第一个投影方块），
 * 投影比真实方块远就用真实方块，都没打到就返回 null。
 */
public final class TargetPicker {
    /** 方块本身 + 它的物品 id（如 minecraft:sticky_piston）。 */
    public record Target(ItemStack stack, String itemId, boolean projection) {
    }

    private static Target current;

    private TargetPicker() {
    }

    public static Target current() {
        return current;
    }

    public static void clear() {
        current = null;
    }

    public static void update(MinecraftClient client) {
        current = pick(client);
    }

    private static Target pick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return null;
        }
        BotFetchConfig cfg = BotFetchClient.config;
        int reach = cfg == null ? 6 : cfg.reach;

        Vec3d eye = player.getEyePos();
        Vec3d dir = player.getRotationVec(1.0F);
        Vec3d end = eye.add(dir.multiply(reach));

        BlockState realState = null;
        double realDistance = Double.MAX_VALUE;
        BlockHitResult realHit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (realHit != null && realHit.getType() == HitResult.Type.BLOCK) {
            realDistance = realHit.getPos().distanceTo(eye);
            realState = world.getBlockState(realHit.getBlockPos());
        }

        if (cfg == null || cfg.projectionEnabled) {
            ProjectionHit projection = projectionHit(eye, dir, reach);
            if (projection != null && projection.distance() <= realDistance + 1.0E-3) {
                Target target = fromState(projection.state(), true);
                if (target != null) {
                    return target;
                }
            }
        }

        if (realState != null) {
            return fromState(realState, false);
        }
        return null;
    }

    private record ProjectionHit(BlockState state, double distance) {
    }

    /** 沿着视线在投影世界里步进，找第一个非空气方块。 */
    private static ProjectionHit projectionHit(Vec3d eye, Vec3d dir, int reach) {
        if (!LitematicaBridge.available()) {
            return null;
        }
        BlockView projection = LitematicaBridge.schematicWorld();
        if (projection == null) {
            return null;
        }
        final double step = 0.05;
        for (double t = step; t <= reach; t += step) {
            Vec3d point = eye.add(dir.multiply(t));
            BlockPos pos = BlockPos.ofFloored(point.x, point.y, point.z);
            BlockState state;
            try {
                state = projection.getBlockState(pos);
            } catch (Throwable t2) {
                return null;
            }
            if (state == null || state.isAir()) {
                continue;
            }
            if (state.getBlock().asItem() == Items.AIR) {
                continue;
            }
            return new ProjectionHit(state, t);
        }
        return null;
    }

    private static Target fromState(BlockState state, boolean projection) {
        if (state == null || state.isAir()) {
            return null;
        }
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) {
            return null;
        }
        ItemStack stack = new ItemStack(item);
        String id = Registries.ITEM.getId(item).toString();
        return new Target(stack, id, projection);
    }
}
