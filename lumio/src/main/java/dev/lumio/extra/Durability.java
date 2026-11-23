package dev.lumio.extra;

import dev.lumio.Config;
import dev.lumio.ui.Cards;
import dev.lumio.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** A card when something in your hands or your armour is nearly broken (a tenth or less left). */
public final class Durability {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** each kind of item once every few minutes, so swapping tools does not keep warning */
    private static final long AGAIN_AFTER = 5 * 60_000L;
    private static final Map<String, Long> warned = new HashMap<>();
    private static int ticks;

    private Durability() {}

    public static void tick(Minecraft mc) {
        if (mc.player == null || !Config.get().durabilityWarning || ++ticks % 10 != 0) return;
        long now = System.currentTimeMillis();
        for (EquipmentSlot s : SLOTS) {
            ItemStack st = mc.player.getItemBySlot(s);
            if (st.isEmpty() || !st.isDamageableItem()) continue;
            int max = st.getMaxDamage();
            int left = max - st.getDamageValue();
            if (max < 20 || left > Math.max(2, max / 10)) continue;
            String key = st.getItem().getDescriptionId() + ":" + max;
            Long before = warned.get(key);
            if (before != null && now - before < AGAIN_AFTER) continue;
            warned.put(key, now);
            Cards.toast(Component.translatable("lumio.durability.title"),
                    Component.translatable("lumio.durability.text", st.getHoverName(), left, max), Draw.WARN, 6);
        }
    }

    /** A new server or world: warn again. */
    public static void reset() {
        warned.clear();
    }
}
