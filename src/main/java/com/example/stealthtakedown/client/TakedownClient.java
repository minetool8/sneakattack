package com.example.stealthtakedown.client;

import com.example.stealthtakedown.StealthTakedownMod;
import com.example.stealthtakedown.config.TakedownConfig;
import com.example.stealthtakedown.net.TakedownNet;
import com.example.stealthtakedown.net.TakedownPacket;
import com.example.stealthtakedown.server.WeaponUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/** Client side: the right-click prompt next to the zombie, the hold-to-charge input and the swing animation. */
@Mod.EventBusSubscriber(modid = StealthTakedownMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class TakedownClient {
    private TakedownClient() {}

    private static final ResourceLocation MOUSE = new ResourceLocation(StealthTakedownMod.MODID, "textures/gui/mouse_right.png");
    private static final ResourceLocation WHITE = new ResourceLocation(StealthTakedownMod.MODID, "textures/gui/white.png");
    private static final int FULL_LIGHT = 15728880;

    private static int candidateId = -1;
    private static boolean charging;
    private static int chargeTotal;
    private static int chargeElapsed;
    private static boolean animatedByBetterCombat;
    private static boolean wasDown;
    private static long trimAt;

    // ------------------------------------------------------------ packets in

    public static void onPacket(TakedownPacket p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        switch (p.type) {
            case TakedownPacket.S2C_CANDIDATE -> candidateId = p.entityId;
            case TakedownPacket.S2C_BEGIN -> {
                if (!mc.options.keyUse.isDown()) {   // released before the server answered
                    TakedownNet.toServer(TakedownPacket.of(TakedownPacket.C2S_CANCEL));
                    return;
                }
                charging = true;
                chargeTotal = Math.max(1, p.ticks);
                chargeElapsed = 0;
                animatedByBetterCombat = BetterCombatBridge.play(player, player.getMainHandItem(), p.ticks);
            }
            case TakedownPacket.S2C_CANCELLED -> {
                if (charging) BetterCombatBridge.stop(player, 3.0F);
                charging = false;
            }
            case TakedownPacket.S2C_STRIKE -> {
                charging = false;
                if (animatedByBetterCombat) {
                    int trim = TakedownConfig.FOLLOW_THROUGH_TICKS.get();
                    if (trim > 0 && mc.level != null) trimAt = mc.level.getGameTime() + trim;
                } else {
                    player.swing(InteractionHand.MAIN_HAND);   // weapon without Better Combat data: plain swing
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ input

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            reset();
            return;
        }
        boolean down = mc.options.keyUse.isDown() && mc.screen == null;
        if (charging) {
            chargeElapsed++;
            if (!down) {
                charging = false;
                TakedownNet.toServer(TakedownPacket.of(TakedownPacket.C2S_CANCEL));
                BetterCombatBridge.stop(player, 3.0F);
            }
        } else if (down && !wasDown && candidateId >= 0 && player.isShiftKeyDown()
                && WeaponUtil.isMelee(player.getMainHandItem())) {
            TakedownNet.toServer(TakedownPacket.of(TakedownPacket.C2S_START));
        }
        wasDown = down;

        if (trimAt > 0 && mc.level.getGameTime() >= trimAt) {
            trimAt = 0;
            BetterCombatBridge.stop(player, 4.0F);
        }
    }

    /** While a prompt is showing, right click must not also use the item / interact with the zombie. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        boolean promptActive = charging
                || (candidateId >= 0 && player.isShiftKeyDown() && WeaponUtil.isMelee(player.getMainHandItem()));
        if (!promptActive) return;
        if (e.isUseItem() || (charging && e.isAttack())) {
            e.setCanceled(true);
            e.setSwingHand(false);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        reset();
    }

    private static void reset() {
        candidateId = -1;
        charging = false;
        wasDown = false;
        trimAt = 0;
    }

    // -------------------------------------------------------------- the prompt

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || candidateId < 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;
        Entity ent = mc.level.getEntity(candidateId);
        if (ent == null || !ent.isAlive()) return;

        float pt = e.getPartialTick();
        Vec3 cam = e.getCamera().getPosition();
        double x = Mth.lerp(pt, ent.xo, ent.getX()) - cam.x;
        double y = Mth.lerp(pt, ent.yo, ent.getY()) + ent.getBbHeight() * 0.65 - cam.y;
        double z = Mth.lerp(pt, ent.zo, ent.getZ()) - cam.z;

        PoseStack ps = e.getPoseStack();
        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());   // face the camera
        ps.translate(-0.6, 0.0, 0.0);                                       // next to the zombie (screen right)
        float s = 0.0165F;
        ps.scale(-s, -s, s);                                                // 1 unit = 1 "pixel", y points down
        Matrix4f m = ps.last().pose();

        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        float progress = charging ? Mth.clamp((chargeElapsed + pt) / chargeTotal, 0.0F, 1.0F) : 0.0F;

        quad(buf, RenderType.textSeeThrough(WHITE), m, -36, -11, 38, 11, 0.0F, 0.0F, 1.0F, 1.0F, 0xB0101014);
        if (charging) {
            quad(buf, RenderType.textSeeThrough(WHITE), m, -36, 12, 38, 16, 0.0F, 0.0F, 1.0F, 1.0F, 0xB0101014);
            quad(buf, RenderType.textSeeThrough(WHITE), m, -36, 12, -36 + 74 * progress, 16, 0.0F, 0.0F, 1.0F, 1.0F, 0xFFE04040);
        }
        quad(buf, RenderType.textSeeThrough(MOUSE), m, -34, -8, -18, 8, 0.0F, 0.0F, 1.0F, 1.0F, 0xFFFFFFFF);
        mc.font.drawInBatch(Component.translatable("stealthtakedown.prompt"), -14.0F, -4.0F, 0xFFFFFFFF, false, m, buf,
                Font.DisplayMode.SEE_THROUGH, 0, FULL_LIGHT);
        buf.endBatch();

        ps.popPose();
    }

    private static void quad(MultiBufferSource.BufferSource buf, RenderType type, Matrix4f m,
                             float x0, float y0, float x1, float y1,
                             float u0, float v0, float u1, float v1, int argb) {
        int a = (argb >>> 24) & 255, r = (argb >>> 16) & 255, g = (argb >>> 8) & 255, b = argb & 255;
        VertexConsumer vc = buf.getBuffer(type);
        vc.vertex(m, x0, y0, 0.0F).color(r, g, b, a).uv(u0, v0).uv2(FULL_LIGHT).endVertex();
        vc.vertex(m, x0, y1, 0.0F).color(r, g, b, a).uv(u0, v1).uv2(FULL_LIGHT).endVertex();
        vc.vertex(m, x1, y1, 0.0F).color(r, g, b, a).uv(u1, v1).uv2(FULL_LIGHT).endVertex();
        vc.vertex(m, x1, y0, 0.0F).color(r, g, b, a).uv(u1, v0).uv2(FULL_LIGHT).endVertex();
        buf.endBatch(type);   // drawn right away so the layers stack in call order
    }
}
