package dev.botfetch.mixin;

import dev.botfetch.BotFetchClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 按住 Alt 时吞掉滚轮，用来调数量；否则保持原版快捷栏切换。
 */
@Mixin(Mouse.class)
public class MouseMixin {
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void botfetch$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (BotFetchClient.handleScroll(vertical)) {
            ci.cancel();
        }
    }
}
