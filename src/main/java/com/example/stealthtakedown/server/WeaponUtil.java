package com.example.stealthtakedown.server;

import com.example.stealthtakedown.config.TakedownConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Shared by server and client: what counts as "a melee weapon of any kind". */
public final class WeaponUtil {
    private WeaponUtil() {}

    public static boolean isMelee(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null && TakedownConfig.WEAPON_BLACKLIST.get().contains(id.toString())) return false;
        double damage = 0.0;
        for (AttributeModifier m : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) damage += m.getAmount();
        }
        return damage >= TakedownConfig.MIN_WEAPON_DAMAGE.get();
    }
}
