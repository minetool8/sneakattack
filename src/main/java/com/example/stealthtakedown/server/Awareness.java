package com.example.stealthtakedown.server;

import com.example.stealthtakedown.StealthTakedownMod;
import com.example.stealthtakedown.config.TakedownConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;

/** Decides which mobs can be taken down, and whether they have noticed anybody. */
public final class Awareness {
    private Awareness() {}

    private static boolean tried;
    private static Method perceptionOf;       // PerceptionGoal.of(Mob)
    private static Method investigatePos;     // PerceptionGoal#investigatePos()

    public static boolean isTarget(Entity e) {
        if (!(e instanceof Mob mob) || !mob.isAlive()) return false;
        if (e instanceof Zombie) return true;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return id != null && TakedownConfig.EXTRA_TARGETS.get().contains(id.toString());
    }

    /** True when the mob has not noticed anyone: no target, not hunting, not walking to a sound. */
    public static boolean isUnaware(Mob mob) {
        if (mob.getTarget() != null) return false;
        if (mob.getLastHurtByMob() != null) return false;
        if (mob.isAggressive()) return false;
        if (TakedownConfig.INVESTIGATING_COUNTS_AS_AWARE.get() && isInvestigating(mob)) return false;
        return true;
    }

    /** zombieai integration through reflection (no compile dependency). */
    public static boolean isInvestigating(Mob mob) {
        if (!tried) {
            tried = true;
            try {
                Class<?> goal = Class.forName("com.example.zombieai.ai.PerceptionGoal");
                perceptionOf = goal.getMethod("of", Mob.class);
                investigatePos = goal.getMethod("investigatePos");
            } catch (Throwable t) {
                perceptionOf = null;
                investigatePos = null;
            }
        }
        if (perceptionOf == null) return false;
        try {
            Object g = perceptionOf.invoke(null, mob);
            return g != null && investigatePos.invoke(g) != null;
        } catch (Throwable t) {
            StealthTakedownMod.LOGGER.debug("zombieai check failed", t);
            perceptionOf = null;
            return false;
        }
    }
}
