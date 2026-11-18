package com.example.stealthtakedown.client;

import com.example.stealthtakedown.StealthTakedownMod;
import com.example.stealthtakedown.config.TakedownConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to Better Combat through reflection (no compile dependency, and the mod still works without it).
 *
 * 1. The weapon's own attack animation is started with a length that makes the wind-up end exactly when the charge
 *    ends (length = (chargeTicks - slowShift) / upswing).
 * 2. Better Combat drives the animation speed with "gears" (speed changes at given tick times). We then rewrite
 *    those gears: the first slowStartTicks run at slowStartSpeed x the normal speed, after that everything continues
 *    at Better Combat's normal speeds, only shifted later by the time the slow start added.
 */
final class BetterCombatBridge {
    private BetterCombatBridge() {}

    private static boolean initDone;
    private static boolean available;
    private static Method getAttributes, attacksM, isTwoHandedM, animationM, upswingM, damageMultM, playM, stopM;
    private static Class<?> handClass, subStackClass;
    // slow start (optional: if any of this is missing the animation just plays at Better Combat's normal speed)
    private static boolean slowOk;
    private static Field subStackSpeedF, gearsF, speedF;
    private static Method setSpeedM, gearTimeM, gearSpeedM;
    private static Constructor<?> gearCtor;

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
            return;
        }
        try {
            subStackClass = Class.forName("net.bettercombat.client.animation.AttackAnimationSubStack");
            Class<?> modifier = Class.forName("net.bettercombat.client.animation.modifier.TransmissionSpeedModifier");
            Class<?> gear = Class.forName("net.bettercombat.client.animation.modifier.TransmissionSpeedModifier$Gear");
            subStackSpeedF = subStackClass.getField("speed");
            gearsF = findField(modifier, "gears");
            speedF = findField(modifier, "speed");
            setSpeedM = modifier.getMethod("set", float.class, List.class);
            gearCtor = gear.getDeclaredConstructor(float.class, float.class);
            gearCtor.setAccessible(true);
            gearTimeM = gear.getDeclaredMethod("time");
            gearTimeM.setAccessible(true);
            gearSpeedM = gear.getDeclaredMethod("speed");
            gearSpeedM.setAccessible(true);
            slowOk = gearsF != null && speedF != null;
        } catch (Throwable t) {
            slowOk = false;
            StealthTakedownMod.LOGGER.info("Slow-start hook unavailable, using Better Combat's normal speed ({})", t.toString());
        }
    }

    private static Field findField(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
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

            // A knife or spear goes for the neck (stab), a heavy weapon comes down from above (slam),
            // anything else uses its heaviest swing.
            Object best = null;
            int bestRank = -1;
            double bestMult = -1.0;
            for (Object a : attacks) {
                String anim = (String) animationM.invoke(a);
                if (anim == null || anim.isEmpty()) continue;
                String n = anim.toLowerCase(java.util.Locale.ROOT);
                int rank = (n.contains("stab") && !n.contains("mounted")) ? 2 : (n.contains("slam") ? 1 : 0);
                double mult = ((Number) damageMultM.invoke(a)).doubleValue();
                if (rank > bestRank || (rank == bestRank && mult > bestMult)) {
                    best = a;
                    bestRank = rank;
                    bestMult = mult;
                }
            }
            if (best == null) return false;

            String anim = (String) animationM.invoke(best);
            double upswing = Mth.clamp(((Number) upswingM.invoke(best)).doubleValue(), 0.25, 0.9);
            boolean twoHanded = (Boolean) isTwoHandedM.invoke(attrs);
            Object hand = Enum.valueOf((Class) handClass, twoHanded ? "TWO_HANDED" : "MAIN_HAND");

            int slow = Math.min(TakedownConfig.SLOW_START_TICKS.get(), chargeTicks / 2);
            double f = TakedownConfig.SLOW_START_SPEED.get();
            double shift = slow * (1.0 - f);   // real ticks the slow start adds to the wind-up
            float length = (float) Math.max(2.0, (chargeTicks - shift) / upswing);
            playM.invoke(player, anim, hand, length, (float) upswing);

            if (slow > 0 && slowOk) {
                try {
                    applySlowStart(player, slow, f, shift);
                } catch (Throwable t) {
                    slowOk = false;
                    StealthTakedownMod.LOGGER.warn("Slow start failed, playing at normal speed", t);
                }
            }
            return true;
        } catch (Throwable t) {
            StealthTakedownMod.LOGGER.warn("Better Combat animation failed", t);
            available = false;
            return false;
        }
    }

    private static void applySlowStart(LocalPlayer player, int slowTicks, double factor, double shift) throws Exception {
        Object subStack = null;
        for (Class<?> c = player.getClass(); c != null && subStack == null; c = c.getSuperclass()) {
            for (Field fld : c.getDeclaredFields()) {
                if (fld.getType() == subStackClass) {
                    fld.setAccessible(true);
                    subStack = fld.get(player);
                    break;
                }
            }
        }
        if (subStack == null) return;
        Object modifier = subStackSpeedF.get(subStack);
        float normal = ((Number) speedF.get(modifier)).floatValue();
        if (!(normal > 0.0F) || Float.isInfinite(normal)) return;
        List<?> old = (List<?>) gearsF.get(modifier);

        List<Object> gears = new ArrayList<>();
        gears.add(gearCtor.newInstance((float) slowTicks, normal));   // slow start over: back to the normal speed
        if (old != null) {
            for (Object g : old) {                                    // Better Combat's own speed changes, shifted later
                float time = ((Number) gearTimeM.invoke(g)).floatValue();
                float speed = ((Number) gearSpeedM.invoke(g)).floatValue();
                gears.add(gearCtor.newInstance((float) (time + shift), speed));
            }
        }
        setSpeedM.invoke(modifier, (float) (normal * factor), gears);
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
