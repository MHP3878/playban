package dev.lumio.mixin;

import dev.lumio.chat.ChatMarks;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat: a message that names the player is added again, marked (see ChatMarks). Only what is shown changes. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Unique
    private boolean lumio$adding;

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), cancellable = true)
    private void lumio$mark(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        if (lumio$adding) return;
        ChatMarks.Marked marked;
        try {
            marked = ChatMarks.mark(message);
        } catch (RuntimeException e) {
            return;
        }
        if (marked == null) return;
        ci.cancel();
        lumio$adding = true;
        try {
            // a tag with an icon tells something about the message itself (changed, not secure): it stays
            GuiMessageTag keep = tag != null && tag.icon() != null ? tag : marked.tag();
            ((ChatComponent) (Object) this).addMessage(marked.message(), signature, keep);
        } finally {
            lumio$adding = false;
        }
    }
}
