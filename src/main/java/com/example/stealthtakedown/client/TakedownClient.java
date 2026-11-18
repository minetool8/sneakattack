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

    // geometry in "pixels" (1 px = PIXEL blocks): a square frame around the 16 px mouse icon
    private static final float PIXEL = 0.02F;
    private static final float H = 17.0F;       // half size of the frame
    private static final float T = 4.0F;        // frame thickness
    private static final int TRACK = 0xFFE8E8E8;

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
        ps.translate(-0.65, 0.0, 0.0);                                      // next to the zombie (screen right)
        ps.scale(-PIXEL, -PIXEL, PIXEL);                                    // y points down, like name tags
        Matrix4f m = ps.last().pose();

        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        RenderType white = RenderType.textSeeThrough(WHITE);
        float progress = charging ? Mth.clamp((chargeElapsed + pt) / chargeTotal, 0.0F, 1.0F) : 0.0F;

        // dark backdrop inside the frame
        quad(buf, white, m, -H + T, -H + T, H - T, H - T, 0xB0101014, 0xB0101014, 0xB0101014, 0xB0101014);
        // grey track: the four sides
        quad(buf, white, m, -H, -H, H, -H + T, TRACK, TRACK, TRACK, TRACK);          // top
        quad(buf, white, m, H - T, -H, H, H, TRACK, TRACK, TRACK, TRACK);            // right
        quad(buf, white, m, -H, H - T, H, H, TRACK, TRACK, TRACK, TRACK);            // bottom
        quad(buf, white, m, -H, -H, -H + T, H, TRACK, TRACK, TRACK, TRACK);          // left
        // orange fill that runs clockwise from the top-left corner
        if (progress > 0.0F) {
            float side = 2.0F * H;
            float d = progress * 4.0F * side;
            // top: left -> right
            float a = Math.min(d, side);
            quad(buf, white, m, -H, -H, -H + a, -H + T,
                    fillColor(0.0F), fillColor(0.0F), fillColor(a / (4.0F * side)), fillColor(a / (4.0F * side)));
            // right: top -> bottom
            if (d > side) {
                float b = Math.min(d - side, side);
                quad(buf, white, m, H - T, -H, H, -H + b,
                        fillColor(side / (4.0F * side)), fillColor((side + b) / (4.0F * side)),
                        fillColor((side + b) / (4.0F * side)), fillColor(side / (4.0F * side)));
            }
            // bottom: right -> left
            if (d > 2.0F * side) {
                float c = Math.min(d - 2.0F * side, side);
                quad(buf, white, m, H - c, H - T, H, H,
                        fillColor((2.0F * side + c) / (4.0F * side)), fillColor((2.0F * side + c) / (4.0F * side)),
                        fillColor(2.0F * side / (4.0F * side)), fillColor(2.0F * side / (4.0F * side)));
            }
            // left: bottom -> top
            if (d > 3.0F * side) {
                float l = Math.min(d - 3.0F * side, side);
                quad(buf, white, m, -H, H - l, -H + T, H,
                        fillColor((3.0F * side + l) / (4.0F * side)), fillColor(3.0F * side / (4.0F * side)),
                        fillColor(3.0F * side / (4.0F * side)), fillColor((3.0F * side + l) / (4.0F * side)));
            }
        }
        // the right-click icon in the middle
        quad(buf, RenderType.textSeeThrough(MOUSE), m, -8, -8, 8, 8, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, true);
        // label
        mc.font.drawInBatch(Component.translatable("stealthtakedown.prompt"), H + 5.0F, -4.0F, 0xFFFFFFFF, true, m, buf,
                Font.DisplayMode.SEE_THROUGH, 0, FULL_LIGHT);
        buf.endBatch();

        ps.popPose();
    }

    /** Orange -> red-orange -> orange, like the reference frame. t = 0..1 along the whole frame. */
    private static int fillColor(float t) {
        float k = t < 0.3F ? t / 0.3F : 1.0F - (t - 0.3F) / 0.7F * 0.7F;   // 0 at the start, 1 at 30%, 0.3 at the end
        int r = (int) Mth.lerp(k, 246.0F, 240.0F);
        int g = (int) Mth.lerp(k, 124.0F, 85.0F);
        int b = (int) Mth.lerp(k, 30.0F, 38.0F);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Colour order: top-left, bottom-left, bottom-right, top-right (same corner order as the glyph quads). */
    private static void quad(MultiBufferSource.BufferSource buf, RenderType type, Matrix4f m,
                             float x0, float y0, float x1, float y1,
                             int cTL, int cBL, int cBR, int cTR) {
        quad(buf, type, m, x0, y0, x1, y1, cTL, cBL, cBR, cTR, false);
    }

    private static void quad(MultiBufferSource.BufferSource buf, RenderType type, Matrix4f m,
                             float x0, float y0, float x1, float y1,
                             int cTL, int cBL, int cBR, int cTR, boolean textured) {
        VertexConsumer vc = buf.getBuffer(type);
        vertex(vc, m, x0, y0, 0.0F, 0.0F, cTL);
        vertex(vc, m, x0, y1, 0.0F, 1.0F, cBL);
        vertex(vc, m, x1, y1, 1.0F, 1.0F, cBR);
        vertex(vc, m, x1, y0, 1.0F, 0.0F, cTR);
        buf.endBatch(type);   // drawn right away so the layers stack in call order
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float u, float v, int argb) {
        vc.vertex(m, x, y, 0.0F).color((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) & 255)
                .uv(u, v).uv2(FULL_LIGHT).endVertex();
    }
}
