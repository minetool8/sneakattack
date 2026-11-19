package com.example.stealthtakedown.server;

import com.example.stealthtakedown.StealthTakedownMod;
import com.example.stealthtakedown.config.TakedownConfig;
import com.example.stealthtakedown.net.TakedownNet;
import com.example.stealthtakedown.net.TakedownPacket;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side, authoritative: finds the zombie that gets the prompt (sneaking up from behind), holds it in a grab
 * while the charge runs, and either kills it when the charge completes or - sometimes - lets it break free.
 */
@Mod.EventBusSubscriber(modid = StealthTakedownMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TakedownServer {
    private TakedownServer() {}

    /** Persistent-data tag of the test zombie spawned by /takedown refuser: it always breaks free. */
    public static final String REFUSER_TAG = "stealthtakedown_refuser";

    private static final UUID SLOW_UUID = UUID.fromString("7c1d2f0e-5a3b-4a6e-9d21-3f7b8c0a4e11");
    /** Distance between the player and the held zombie (their bodies touch). */
    private static final double GRAB_DISTANCE = 0.95;
    /** Length of the "breaks free" sequence: turn around, shake the head, shove. */
    private static final int BREAK_TICKS = 14;

    private static final class Charge {
        final int targetId;
        final int slot;
        int ticksLeft;
        boolean resist;
        int resistAt;          // ticksLeft value at which the zombie starts fighting back
        int breakTick = -1;    // >= 0 while the zombie is breaking free
        int voiceCooldown;
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
            Charge c = new Charge(target.getId(), p.getInventory().selected, ticks);
            // does this zombie fight back? decided once, when the grab starts
            c.resist = target.getPersistentData().getBoolean(REFUSER_TAG)
                    || p.level().random.nextDouble() < TakedownConfig.RESIST_CHANCE.get();
            double when = 0.40 + 0.25 * p.level().random.nextDouble();   // breaks free 40-65% into the charge
            c.resistAt = Math.max(1, ticks - (int) (ticks * when));
            CHARGES.put(id, c);
            applySlow(p);
            // the grab: cloth and leather rustling, quiet
            p.serverLevel().playSound(null, target.getX(), target.getY() + 1.0, target.getZ(),
                    SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 0.8F, 0.75F);
            TakedownNet.toPlayer(p, TakedownPacket.begin(target.getId(), ticks));
        } else if (pkt.type == TakedownPacket.C2S_CANCEL) {
            endCharge(p);
        }
    }

    // ---------------------------------------------------------------- the logic

    /** The unaware zombie behind which a sneaking, melee-armed player stands within range, or null. */
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
            if (!isBehind(m, p)) continue;
            Vec3 to = m.getBoundingBox().getCenter().subtract(eye);
            double len = to.length();
            if (len > 1.0E-4 && look.dot(to) / len < 0.25) continue;   // roughly in front of the player
            if (!p.hasLineOfSight(m)) continue;
            best = m;
            bestDist = d;
        }
        return best;
    }

    /** Facing direction of the zombie dotted with the direction to the player: -1 = exactly behind it. */
    private static double facingDot(Mob m, ServerPlayer p) {
        double yaw = Math.toRadians(m.getYHeadRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double dx = p.getX() - m.getX();
        double dz = p.getZ() - m.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) return -1.0;
        return (fx * dx + fz * dz) / len;
    }

    private static boolean isBehind(Mob m, ServerPlayer p) {
        if (!TakedownConfig.BACKSTAB_ONLY.get()) return true;
        return facingDot(m, p) < TakedownConfig.MAX_FACING_DOT.get();
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
        if (ok && c.breakTick < 0 && TakedownConfig.REQUIRE_SNEAK_WHILE_CHARGING.get() && !p.isShiftKeyDown()) ok = false;
        if (!ok) {
            abort(p);
            return;
        }
        Mob mob = (Mob) ent;

        if (c.breakTick >= 0) {
            tickBreak(p, mob, c);
            return;
        }

        if (TakedownConfig.CALM_ZOMBIE_DURING_CHARGE.get()) {
            mob.getNavigation().stop();
            Vec3 v = mob.getDeltaMovement();
            mob.setDeltaMovement(0.0, v.y, 0.0);
            if (mob.getTarget() != null) mob.setTarget(null);
        }
        if (TakedownConfig.GRAB_ZOMBIE.get()) {
            grab(p, mob);
        }
        if (TakedownConfig.GRAB_VOICE.get() && c.voiceCooldown-- <= 0) {
            c.voiceCooldown = 6 + p.level().random.nextInt(6);
            groan(p.serverLevel(), mob, true);   // muffled: a hand over its mouth
        }

        c.ticksLeft--;
        if (c.resist && c.ticksLeft <= c.resistAt) {
            beginStruggle(p, mob, c);
        } else if (c.ticksLeft <= 0) {
            strike(p, mob);
        }
    }

    /** Pulls the zombie in front of the player and turns it to face the same way: held from behind. */
    private static void grab(ServerPlayer p, Mob mob) {
        float yaw = Mth.approachDegrees(mob.getYRot(), p.getYRot(), 30.0F);
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.setYHeadRot(yaw);
        mob.setXRot(0.0F);

        Vec3 look = p.getLookAngle();
        double lx = look.x, lz = look.z;
        double ll = Math.sqrt(lx * lx + lz * lz);
        if (ll < 1.0E-4) return;
        double wx = p.getX() + lx / ll * GRAB_DISTANCE;
        double wz = p.getZ() + lz / ll * GRAB_DISTANCE;
        double dx = wx - mob.getX();
        double dz = wz - mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.03) return;
        double step = Math.min(dist, 0.15);   // a smooth slide, never a teleport
        double sx = dx / dist * step;
        double sz = dz / dist * step;
        if (mob.level().noCollision(mob, mob.getBoundingBox().move(sx, 0.0, sz))) {
            mob.setPos(mob.getX() + sx, mob.getY(), mob.getZ() + sz);
        }
    }

    // ------------------------------------------------------------- breaking free

    /** The zombie stops being held: it roars (no longer muffled) and starts fighting back. */
    private static void beginStruggle(ServerPlayer p, Mob mob, Charge c) {
        c.breakTick = 0;
        ServerLevel level = p.serverLevel();
        groan(level, mob, false);
        level.playSound(null, mob.getX(), mob.getEyeY(), mob.getZ(), SoundEvents.ZOMBIE_AMBIENT,
                SoundSource.HOSTILE, 1.3F, 0.65F);
        TakedownNet.toPlayer(p, TakedownPacket.of(TakedownPacket.S2C_STRUGGLE));
    }

    /**
     * Turn around -> shake the head violently -> shove the player away -> attack.
     * Runs for BREAK_TICKS ticks; the shove lands on the last one.
     */
    private static void tickBreak(ServerPlayer p, Mob mob, Charge c) {
        int t = c.breakTick++;
        mob.getNavigation().stop();
        Vec3 v = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, v.y, 0.0);

        double dx = p.getX() - mob.getX();
        double dz = p.getZ() - mob.getZ();
        float toPlayer = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float body = Mth.approachDegrees(mob.yBodyRot, toPlayer, 45.0F);   // turns around to face the player
        float head = body;
        float pitch = 0.0F;
        if (t >= 2) {                                                      // then thrashes its head
            float s = (t - 2) * 1.7F;
            head = body + Mth.sin(s) * 45.0F;
            body += Mth.sin(s + 1.0F) * 12.0F;
            pitch = Mth.sin(s * 1.4F) * 22.0F;
        }
        mob.setYRot(body);
        mob.yBodyRot = body;
        mob.setYHeadRot(head);
        mob.setXRot(pitch);

        if (t >= BREAK_TICKS - 1) {
            shove(p, mob);
        }
    }

    private static void shove(ServerPlayer p, Mob mob) {
        ServerLevel level = p.serverLevel();
        mob.swing(InteractionHand.MAIN_HAND);
        p.knockback(1.2, mob.getX() - p.getX(), mob.getZ() - p.getZ());   // pushed away from the zombie
        p.hurtMarked = true;                                              // send the new velocity to the client
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 1, false, false));   // stagger
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK,
                SoundSource.HOSTILE, 1.0F, 0.8F);

        mob.setNoAi(false);   // the test zombie is frozen until now
        mob.setTarget(p);
        mob.setAggressive(true);

        endCharge(p);
        TakedownNet.toPlayer(p, TakedownPacket.of(TakedownPacket.S2C_BROKEN));
    }

    // ------------------------------------------------------------------- sounds

    /** The zombie's pain sound. Muffled = low and quiet, as if a hand were pressed over its mouth. */
    private static void groan(ServerLevel level, Mob mob, boolean muffled) {
        SoundEvent s = mob instanceof Drowned ? SoundEvents.DROWNED_HURT
                : mob instanceof Husk ? SoundEvents.HUSK_HURT : SoundEvents.ZOMBIE_HURT;
        float pitch = muffled ? 0.5F + level.random.nextFloat() * 0.15F : 0.75F + level.random.nextFloat() * 0.2F;
        float volume = muffled ? 0.35F : 1.2F;
        level.playSound(null, mob.getX(), mob.getEyeY(), mob.getZ(), s, SoundSource.HOSTILE, volume, pitch);
    }

    // ----------------------------------------------------------------- the rest

    private static void applySlow(ServerPlayer p) {
        if (!TakedownConfig.SLOW_PLAYER.get()) return;
        AttributeInstance a = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a != null && a.getModifier(SLOW_UUID) == null) {
            a.addTransientModifier(new AttributeModifier(SLOW_UUID, "stealthtakedown_slow", -0.6,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    private static void clearSlow(ServerPlayer p) {
        AttributeInstance a = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a != null) a.removeModifier(SLOW_UUID);
    }

    private static void endCharge(ServerPlayer p) {
        CHARGES.remove(p.getUUID());
        LAST_CANDIDATE.remove(p.getUUID());
        clearSlow(p);
    }

    private static void abort(ServerPlayer p) {
        endCharge(p);
        TakedownNet.toPlayer(p, TakedownPacket.of(TakedownPacket.S2C_CANCELLED));
    }

    /** The finishing blow: kills the mob no matter its health. */
    private static void strike(ServerPlayer p, Mob mob) {
        endCharge(p);

        ServerLevel level = p.serverLevel();
        DamageSource src = level.damageSources().playerAttack(p);
        mob.invulnerableTime = 0;
        mob.hurt(src, Float.MAX_VALUE);
        if (mob.isAlive()) {   // some other mod soaked the damage: make sure it dies anyway
            mob.setHealth(0.0F);
            mob.die(src);
        }

        double x = mob.getX(), y = mob.getY() + mob.getBbHeight() * 0.8, z = mob.getZ();
        // a heavy, dull impact and a small spray of blood; nothing flashy
        level.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 0.65F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()),
                x, y, z, 14, 0.12, 0.15, 0.12, 0.06);
        p.swing(InteractionHand.MAIN_HAND, false);   // other players see a swing

        if (TakedownConfig.DAMAGE_WEAPON.get() && !p.getAbilities().instabuild) {
            ItemStack stack = p.getMainHandItem();
            stack.hurtAndBreak(1, p, pl -> pl.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        }
        TakedownNet.toPlayer(p, TakedownPacket.of(TakedownPacket.S2C_STRIKE));
    }

    // ------------------------------------------------------- /takedown why

    /** Explains, check by check, why the nearest zombie does or does not get the prompt. */
    public static List<String> diagnose(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        ItemStack held = p.getMainHandItem();
        out.add("sneaking: " + p.isShiftKeyDown());
        out.add("melee weapon: " + WeaponUtil.isMelee(held) + " (" + held.getItem() + ", attack bonus "
                + WeaponUtil.attackDamage(held) + ", needs " + TakedownConfig.MIN_WEAPON_DAMAGE.get() + ")");

        Mob nearest = null;
        double nd = Double.MAX_VALUE;
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(8.0), Awareness::isTarget)) {
            double d = m.distanceTo(p);
            if (d < nd) {
                nd = d;
                nearest = m;
            }
        }
        if (nearest == null) {
            out.add("no zombie within 8 blocks");
            return out;
        }
        Vec3 eye = p.getEyePosition();
        double reach = distanceToBox(nearest, eye);
        out.add("nearest zombie: " + nearest.getType().getDescriptionId() + ", " + String.format("%.2f", reach)
                + " blocks away (needs <= " + TakedownConfig.RANGE.get() + "): " + (reach <= TakedownConfig.RANGE.get()));
        out.add("it has noticed you: target=" + nearest.getTarget() + ", hurtBy=" + nearest.getLastHurtByMob()
                + ", aggressive=" + nearest.isAggressive()
                + ", investigating(zombieai)=" + Awareness.isInvestigating(nearest)
                + "  -> unaware: " + Awareness.isUnaware(nearest));
        double fd = facingDot(nearest, p);
        out.add("you are behind it: facing dot " + String.format("%.2f", fd) + " (needs < "
                + TakedownConfig.MAX_FACING_DOT.get() + "): " + isBehind(nearest, p));
        Vec3 to = nearest.getBoundingBox().getCenter().subtract(eye);
        double len = to.length();
        double ld = len > 1.0E-4 ? p.getLookAngle().dot(to) / len : 1.0;
        out.add("you look at it: dot " + String.format("%.2f", ld) + " (needs >= 0.25): " + (ld >= 0.25));
        out.add("line of sight: " + p.hasLineOfSight(nearest));
        out.add("=> prompt for this zombie: " + (findCandidate(p) == nearest));
        return out;
    }
}
