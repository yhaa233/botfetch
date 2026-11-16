package dev.botfetch.mixin;

import dev.botfetch.BotFetchClient;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 抓原始按键事件：用来做 V（下单）和 V+C（打开配置）这种组合键，
 * 顺带把事件转发给配置界面录制按键。
 */
@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"))
    private void botfetch$onKey(long window, int action, KeyInput input, CallbackInfo ci) {
        BotFetchClient.onRawKey(input.key(), action);
    }
}
