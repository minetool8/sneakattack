package com.example.stealthtakedown.server;

import com.example.stealthtakedown.config.TakedownConfig;
import com.example.stealthtakedown.net.TakedownNet;
import com.example.stealthtakedown.net.TakedownPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side, authoritative: finds the zombie that gets the prompt, runs the charge timer, and kills the zombie
 * when the charge completes.
 */
@Mod.EventBusSubscriber(modid = com.example.stealthtakedown.StealthTakedownMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TakedownServer {
    private TakedownServer() {}

    private static final class Charge {
        final int targetId;
        final int slot;
        int ticksLeft;
        Charge(int targetId, int slot, int ticksLeft) {
            this.targetId = targetId;
            this.slot = slot;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final Map<UUID, Charge> CHARGES = new HashMap<>();
    private static final Map<UUID, Integer> LAST_CANDIDATE = new HashMap<>();

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.player instanceof ServerPlayer p)) return;

        Charge c = CHARGES.get(p.getUUID());
        if (c != null) {
            tickCharge(p, c);
            return;
        }
        if (p.tickCount % 3 != 0) return;   // the search is cheap, but there is no need to do it every tick

        Mob candidate = findCandidate(p);
        int id = candidate == null ? -1 : candidate.getId();
        Integer last = LAST_CANDIDATE.get(p.getUUID());
        if (last == null || last.intValue() != id) {
            LAST_CANDIDATE.put(p.getUUID(), id);
            TakedownNet.toPlayer(p, TakedownPacket.candidate(id));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        CHARGES.remove(e.getEntity().getUUID());
        LAST_CANDIDATE.remove(e.getEntity().getUUID());
    }

    // ----------------------------------------------------------------- packets

    public static void onPacket(ServerPlayer p, TakedownPacket pkt) {
        if (p == null) return;
        UUID id = p.getUUID();
        if (pkt.type == TakedownPacket.C2S_START) {
            if (CHARGES.containsKey(id)) return;
            Mob target = findCandidate(p);
            if (target == null) {
                TakedownNet.toPlayer(p, TakedownPacket.of(TakedownPacket.S2C_CANCELLED));
                return;
            }
            int ticks = TakedownConfig.CHARGE_TICKS.get();
            CHARGES.put(id, new Charge(target.getId(), p.getInventory().selected, ticks));
            TakedownNet.toPlayer(p, TakedownPacket.begin(target.getId(), ticks));
        } else if (pkt.type == TakedownPacket.C2S_CANCEL) {
            CHARGES.remove(id);
            LAST_CANDIDATE.remove(id);
        }
    }

    // ---------------------------------------------------------------- the logic

    /** The unaware zombie in front of a sneaking, melee-armed player within range, or null. */
    public static Mob findCandidate(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || !p.isShiftKeyDown()) return null;
        if (!WeaponUtil.isMelee(p.getMainHandItem())) return null;

        double range = TakedownConfig.RANGE.get();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        AABB search = p.getBoundingBox().inflate(range + 1.0);

        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, search, Awareness::isTarget)) {
            double d = distanceToBox(m, eye);
            if (d > range || d >= bestDist) continue;
            if (!Awareness.isUnaware(m)) continue;
            Vec3 to = m.getBoundingBox().getCenter().subtract(eye);
            double len = to.length();
            if (len > 1.0E-4 && look.dot(to) / len < 0.25) continue;   // roughly in front of the player
            if (!p.hasLineOfSight(m)) continue;
            best = m;
            bestDist = d;
        }
        return best;
    }

    private static double distanceToBox(Entity e, Vec3 point) {
        AABB b = e.getBoundingBox();
        double dx = Math.max(Math.max(b.minX - point.x, 0.0), point.x - b.maxX);
        double dy = Math.max(Math.max(b.minY - point.y, 0.0), point.y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - point.z, 0.0), point.z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void tickCharge(ServerPlayer p, Charge c) {
        Entity ent = p.level().getEntity(c.targetId);
        boolean ok = p.isAlive() && !p.isSpectator()
                && p.getInventory().selected == c.slot
                && WeaponUtil.isMelee(p.getMainHandItem())
                && ent instanceof Mob
                && ent.isAlive()
                && distanceToBox(ent, p.getEyePosition()) <= TakedownConfig.RANGE.get() + 1.0;
        if (ok && TakedownConfig.REQUIRE_SNEAK_WHILE_CHARGING.get() && !p.isShiftKeyDown()) ok = false;
        if (!ok) {
            abort(p);
            return;
        }
        Mob mob = (Mob) ent;
        if (TakedownConfig.CALM_ZOMBIE_DURING_CHARGE.get()) {
            mob.getNavigation().stop();
            Vec3 v = mob.getDeltaMovement();
            mob.setDeltaMovement(0.0, v.y, 0.0);
            if (mob.getTarget() != null) mob.setTarget(null);
        }
        c.ticksLeft--;
        if (c.ticksLeft <= 0) {
            strike(p, mob);
        }
    }

    private static void abort(ServerPlayer p) {
        CHARGES.remove(p.getUUID());
        LAST_CANDIDATE.remove(p.getUUID());
        TakedownNet.toPlayer(p, TakedownPacket.of(TakedownPacket.S2C_CANCELLED));
    }

    /** The heavy blow: kills the mob no matter its health. */
    private static void strike(ServerPlayer p, Mob mob) {
        CHARGES.remove(p.getUUID());
        LAST_CANDIDATE.remove(p.getUUID());

        ServerLevel level = p.serverLevel();
        DamageSource src = level.damageSources().playerAttack(p);
        mob.invulnerableTime = 0;
        mob.hurt(src, Float.MAX_VALUE);
        if (mob.isAlive()) {   // some other mod soaked the damage: make sure it dies anyway
            mob.setHealth(0.0F);
            mob.die(src);
        }

        double x = mob.getX(), y = mob.getY() + mob.getBbHeight() * 0.6, z = mob.getZ();
        level.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 0.7F);
        level.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 0.8F);
        level.sendParticles(ParticleTypes.CRIT, x, y, z, 24, 0.35, 0.4, 0.35, 0.4);
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, x, y, z, 12, 0.3, 0.3, 0.3, 0.3);
        p.swing(InteractionHand.MAIN_HAND, false);   // other players see a swing

        if (TakedownConfig.DAMAGE_WEAPON.get() && !p.getAbilities().instabuild) {
            ItemStack stack = p.getMainHandItem();
            stack.hurtAndBreak(1, p, pl -> pl.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        }
        TakedownNet.toPlayer(p, TakedownPacket.of(TakedownPacket.S2C_STRIKE));
    }
}
