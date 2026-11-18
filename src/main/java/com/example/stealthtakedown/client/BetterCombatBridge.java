package com.example.stealthtakedown.client;

import com.example.stealthtakedown.StealthTakedownMod;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/**
 * Talks to Better Combat through reflection (no compile dependency, and the mod still works without it).
 *
 * The wind-up is slowed by replaying the weapon's own attack animation with a long length: Better Combat plays the
 * first "upswing" fraction of that animation before the hit moment, so length = chargeTicks / upswing makes the
 * wind-up last exactly chargeTicks. The blow lands at the end of the charge.
 */
final class BetterCombatBridge {
    private BetterCombatBridge() {}

    private static boolean initDone;
    private static boolean available;
    private static Method getAttributes, attacksM, isTwoHandedM, animationM, upswingM, damageMultM, playM, stopM;
    private static Class<?> handClass;

    private static void init() {
        if (initDone) return;
        initDone = true;
        try {
            Class<?> registry = Class.forName("net.bettercombat.logic.WeaponRegistry");
            getAttributes = registry.getMethod("getAttributes", ItemStack.class);
            Class<?> attrs = Class.forName("net.bettercombat.api.WeaponAttributes");
            attacksM = attrs.getMethod("attacks");
            isTwoHandedM = attrs.getMethod("isTwoHanded");
            Class<?> attack = Class.forName("net.bettercombat.api.WeaponAttributes$Attack");
            animationM = attack.getMethod("animation");
            upswingM = attack.getMethod("upswing");
            damageMultM = attack.getMethod("damageMultiplier");
            handClass = Class.forName("net.bettercombat.logic.AnimatedHand");
            Class<?> animatable = Class.forName("net.bettercombat.client.animation.PlayerAttackAnimatable");
            playM = animatable.getMethod("playAttackAnimation", String.class, handClass, float.class, float.class);
            stopM = animatable.getMethod("stopAttackAnimation", float.class);
            available = true;
        } catch (Throwable t) {
            available = false;
            StealthTakedownMod.LOGGER.info("Better Combat not found or API changed: charge animation disabled ({})", t.toString());
        }
    }

    /** @return true when a Better Combat animation was started. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean play(LocalPlayer player, ItemStack stack, int chargeTicks) {
        init();
        if (!available) return false;
        try {
            Object attrs = getAttributes.invoke(null, stack);
            if (attrs == null) return false;
            Object[] attacks = (Object[]) attacksM.invoke(attrs);
            if (attacks == null || attacks.length == 0) return false;

            // the heaviest attack of the weapon is the best "heavy blow"
            Object best = null;
            double bestMult = -1.0;
            for (Object a : attacks) {
                String anim = (String) animationM.invoke(a);
                if (anim == null || anim.isEmpty()) continue;
                double mult = ((Number) damageMultM.invoke(a)).doubleValue();
                if (mult > bestMult) {
                    best = a;
                    bestMult = mult;
                }
            }
            if (best == null) return false;

            String anim = (String) animationM.invoke(best);
            double upswing = Mth.clamp(((Number) upswingM.invoke(best)).doubleValue(), 0.25, 0.9);
            boolean twoHanded = (Boolean) isTwoHandedM.invoke(attrs);
            Object hand = Enum.valueOf((Class) handClass, twoHanded ? "TWO_HANDED" : "MAIN_HAND");
            float length = (float) (chargeTicks / upswing);
            playM.invoke(player, anim, hand, length, (float) upswing);
            return true;
        } catch (Throwable t) {
            StealthTakedownMod.LOGGER.warn("Better Combat animation failed", t);
            available = false;
            return false;
        }
    }

    static void stop(LocalPlayer player, float fadeTicks) {
        init();
        if (!available) return;
        try {
            stopM.invoke(player, fadeTicks);
        } catch (Throwable t) {
            StealthTakedownMod.LOGGER.debug("Better Combat stop failed", t);
        }
    }
}
