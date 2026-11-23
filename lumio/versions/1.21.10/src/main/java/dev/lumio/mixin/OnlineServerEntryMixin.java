package dev.lumio.mixin;

import dev.lumio.serverlist.ServerCard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server list entry: Lumio's look for servers that ask for it (1.21.9+: renderContent, position from the entry). */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class OnlineServerEntryMixin {
    @Shadow
    @Final
    private ServerData serverData;

    private ObjectSelectionList.Entry<?> lumio$self() {
        return (ObjectSelectionList.Entry<?>) (Object) this;
    }

    @Inject(method = "renderContent", at = @At("HEAD"))
    private void lumio$before(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float delta, CallbackInfo ci) {
        var e = lumio$self();
        ServerCard.before(g, serverData, e.getContentX(), e.getContentY(), e.getContentWidth(), e.getContentHeight(), hovered);
    }

    @Inject(method = "renderContent", at = @At("TAIL"))
    private void lumio$after(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float delta, CallbackInfo ci) {
        var e = lumio$self();
        ServerCard.after(g, serverData, e.getContentX(), e.getContentY(), e.getContentWidth(), e.getContentHeight());
    }
}
