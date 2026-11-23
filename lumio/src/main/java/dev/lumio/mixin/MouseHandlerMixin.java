package dev.lumio.mixin;

import dev.lumio.extra.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Zoom: the wheel zooms further while zooming, and the mouse turns slower the closer you look. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void lumio$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (window == dev.lumio.compat.Compat.window() && Minecraft.getInstance().screen == null && Zoom.scroll(yOffset)) ci.cancel();
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void lumio$zoomTurn(double movementTime, CallbackInfo ci) {
        double k = Zoom.mouseFactor();
        if (k != 1) {
            accumulatedDX *= k;
            accumulatedDY *= k;
        }
    }
}
