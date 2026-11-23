package dev.lumio.mixin;

import dev.lumio.serverlist.ServerCard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server list entry: Lumio's look for servers that ask for it (up to 1.21.8: render with the position). */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class OnlineServerEntryMixin {
    @Shadow
    @Final
    private ServerData serverData;

    @Inject(method = "render", at = @At("HEAD"))
    private void lumio$before(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                              boolean hovering, float delta, CallbackInfo ci) {
        ServerCard.before(g, serverData, left, top, width, height, hovering);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void lumio$after(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                             boolean hovering, float delta, CallbackInfo ci) {
        ServerCard.after(g, serverData, left, top, width, height);
    }
}
