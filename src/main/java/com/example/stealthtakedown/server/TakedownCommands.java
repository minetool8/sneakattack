package com.example.stealthtakedown.server;

import com.example.stealthtakedown.StealthTakedownMod;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * /takedown refuser  - spawns a frozen test zombie 3 blocks ahead, facing away, that ALWAYS breaks free:
 *                      turns around, shakes its head, shoves you and attacks.
 * /takedown why      - explains why the nearest zombie does or does not show the prompt.
 */
@Mod.EventBusSubscriber(modid = StealthTakedownMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TakedownCommands {
    private TakedownCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("takedown")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("refuser").executes(ctx -> spawnRefuser(ctx.getSource())))
                .then(Commands.literal("why").executes(ctx -> why(ctx.getSource()))));
    }

    private static int spawnRefuser(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        ServerLevel level = p.serverLevel();

        Vec3 look = p.getLookAngle();
        double ll = Math.sqrt(look.x * look.x + look.z * look.z);
        if (ll < 1.0E-4) {
            src.sendFailure(Component.literal("Look at the horizon first."));
            return 0;
        }
        float yaw = p.getYRot();   // same direction as the player: the zombie faces away from you

        Zombie z = EntityType.ZOMBIE.create(level);
        if (z == null) {
            src.sendFailure(Component.literal("Could not create a zombie."));
            return 0;
        }
        boolean placed = false;
        for (double dist = 3.0; dist >= 1.5 && !placed; dist -= 0.5) {
            z.moveTo(p.getX() + look.x / ll * dist, p.getY(), p.getZ() + look.z / ll * dist, yaw, 0.0F);
            placed = level.noCollision(z, z.getBoundingBox());
        }
        if (!placed) {
            src.sendFailure(Component.literal("No free space in front of you."));
            return 0;
        }
        z.setYRot(yaw);
        z.yBodyRot = yaw;
        z.setYHeadRot(yaw);
        z.setBaby(false);
        z.setNoAi(true);                 // frozen until it breaks free
        z.setPersistenceRequired();
        z.getPersistentData().putBoolean(TakedownServer.REFUSER_TAG, true);
        // do not burn in daylight while waiting for the test
        z.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        level.addFreshEntity(z);

        src.sendSuccess(() -> Component.literal("Refuser zombie spawned 3 blocks ahead, facing away. Sneak up behind it "
                + "with a melee weapon and hold right click: it will turn, shake its head, shove you and attack."), false);
        return 1;
    }

    private static int why(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        for (String line : TakedownServer.diagnose(p)) {
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
