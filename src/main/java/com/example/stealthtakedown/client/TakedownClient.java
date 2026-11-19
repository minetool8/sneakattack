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
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/** Client side: the right-click prompt next to the zombie, the hold-to-charge input, the swing and camera effects. */
@Mod.EventBusSubscriber(modid = StealthTakedownMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class TakedownClient {
    private TakedownClient() {}

    private static final ResourceLocation WHITE = new ResourceLocation(StealthTakedownMod.MODID, "textures/gui/white.png");
    private static final int FULL_LIGHT = 15728880;

    private static int candidateId = -1;
    private static boolean charging;
    private static boolean struggling;   // the zombie is fighting back: the bar freezes, the camera shakes
    private static int chargeTotal;
    private static int chargeElapsed;
    private static boolean animatedByBetterCombat;
    private static boolean wasDown;
    private static long trimAt;
    private static float zoom;           // 0..1, eased while charging
    private static int kickTicks;        // camera kick after the blow
    private static int shakeTicks;       // camera shake after being shoved
    private static final int KICK_TOTAL = 8;
    private static final int SHAKE_TOTAL = 14;

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
                struggling = false;
                chargeTotal = Math.max(1, p.ticks);
                chargeElapsed = 0;
                animatedByBetterCombat = BetterCombatBridge.play(player, player.getMainHandItem(), p.ticks);
            }
            case TakedownPacket.S2C_CANCELLED -> {
                if (charging) BetterCombatBridge.stop(player, 3.0F);
                charging = false;
                struggling = false;
            }
            case TakedownPacket.S2C_STRUGGLE -> struggling = true;
            case TakedownPacket.S2C_BROKEN -> {
                charging = false;
                struggling = false;
                shakeTicks = SHAKE_TOTAL;
                BetterCombatBridge.stop(player, 2.0F);
            }
            case TakedownPacket.S2C_STRIKE -> {
                charging = false;
                struggling = false;
                kickTicks = KICK_TOTAL;
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
            if (!struggling) chargeElapsed++;
            if (!down) {
                charging = false;
                struggling = false;
                TakedownNet.toServer(TakedownPacket.of(TakedownPacket.C2S_CANCEL));
                BetterCombatBridge.stop(player, 3.0F);
            }
        } else if (down && !wasDown && candidateId >= 0 && player.isShiftKeyDown()
                && WeaponUtil.isMelee(player.getMainHandItem())) {
            TakedownNet.toServer(TakedownPacket.of(TakedownPacket.C2S_START));
        }
        wasDown = down;

        zoom += ((charging ? 1.0F : 0.0F) - zoom) * 0.2F;
        if (kickTicks > 0) kickTicks--;
        if (shakeTicks > 0) shakeTicks--;

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

    // ---------------------------------------------------------------- camera

    /** Subtle focus while holding the zombie: the view narrows a little. */
    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov e) {
        if (!TakedownConfig.CAMERA_EFFECTS.get() || zoom < 0.001F) return;
        e.setFOV(e.getFOV() * (1.0 - 0.10 * zoom));
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        if (!TakedownConfig.CAMERA_EFFECTS.get()) return;
        Minecraft mc = Minecraft.getInstance();
        float pt = (float) e.getPartialTick();
        float time = (mc.level != null ? mc.level.getGameTime() : 0L) + pt;

        // a small downward kick when the blow lands
        if (kickTicks > 0) {
            float k = Mth.clamp((kickTicks - pt) / KICK_TOTAL, 0.0F, 1.0F);
            e.setPitch(e.getPitch() + Mth.sin(k * Mth.PI) * 3.0F);
        }
        // the zombie thrashes in the player's grip: a steady tremble
        if (struggling) {
            e.setRoll(e.getRoll() + Mth.sin(time * 2.7F) * 1.3F);
            e.setPitch(e.getPitch() + Mth.sin(time * 3.9F) * 0.6F);
        }
        // being shoved: a hard jolt that fades out
        if (shakeTicks > 0) {
            float k = Mth.clamp((shakeTicks - pt) / SHAKE_TOTAL, 0.0F, 1.0F);
            e.setRoll(e.getRoll() + Mth.sin(time * 2.4F) * 4.5F * k);
            e.setPitch(e.getPitch() - Mth.sin(k * Mth.PI) * 5.0F);   // head snaps back
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        reset();
    }

    private static void reset() {
        candidateId = -1;
        charging = false;
        struggling = false;
        wasDown = false;
        trimAt = 0;
        zoom = 0.0F;
        kickTicks = 0;
        shakeTicks = 0;
    }

    // -------------------------------------------------------------- the prompt

    // Everything is built from plain coloured rectangles (one 4x4 white texture, tinted by vertex colour), so there is
    // no icon texture that could fail to load. Units are "pixels": 1 px = PIXEL blocks.
    private static final float PIXEL = 0.005F;
    private static final float H = 17.0F;       // half size of the frame
    private static final float T = 4.0F;        // frame thickness
    private static final int TRACK = 0xFF5A5A5E;
    private static final int FILL = 0xFFFFFFFF;
    private static final int BACKDROP = 0xB0101014;

    // the right-click mouse, drawn on a 16x16 grid (same design as the old PNG)
    private static final int M_OUTLINE = 0xFF1E1E22;
    private static final int M_BODY = 0xFFEBEBEE;
    private static final int M_SHADE = 0xFFBEBEC6;
    private static final int M_RED = 0xFFD63030;
    private static final int M_RED_DARK = 0xFF961C1C;

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
        ps.translate(-0.35, 0.0, 0.0);                                      // next to the zombie (screen right)
        ps.scale(-PIXEL, -PIXEL, PIXEL);                                    // y points down, like name tags
        Matrix4f m = ps.last().pose();

        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        float progress = charging ? Mth.clamp((chargeElapsed + (struggling ? 0.0F : pt)) / chargeTotal, 0.0F, 1.0F) : 0.0F;

        // one batch, drawn in call order (the see-through type has no depth test)
        VertexConsumer vc = buf.getBuffer(RenderType.textSeeThrough(WHITE));

        // backdrop and the grey track: the four sides of the frame
        rect(vc, m, -H + T, -H + T, H - T, H - T, BACKDROP);
        rect(vc, m, -H, -H, H, -H + T, TRACK);          // top
        rect(vc, m, H - T, -H, H, H, TRACK);            // right
        rect(vc, m, -H, H - T, H, H, TRACK);            // bottom
        rect(vc, m, -H, -H, -H + T, H, TRACK);          // left

        // white fill that runs clockwise from the top-left corner
        if (progress > 0.0F) {
            float side = 2.0F * H;
            float d = progress * 4.0F * side;
            rect(vc, m, -H, -H, -H + Math.min(d, side), -H + T, FILL);                                   // top: left -> right
            if (d > side) rect(vc, m, H - T, -H, H, -H + Math.min(d - side, side), FILL);                // right: down
            if (d > 2.0F * side) rect(vc, m, H - Math.min(d - 2.0F * side, side), H - T, H, H, FILL);    // bottom: right -> left
            if (d > 3.0F * side) rect(vc, m, -H, H - Math.min(d - 3.0F * side, side), -H + T, H, FILL);  // left: up
        }

        // the right-click mouse in the middle
        float c = 18.0F / 16.0F;   // size of one grid cell
        float o = -9.0F;           // top-left of the icon
        cell(vc, m, o, c, 4, 0, 11, 0, M_OUTLINE);
        cell(vc, m, o, c, 4, 1, 11, 1, M_OUTLINE);
        cell(vc, m, o, c, 3, 2, 3, 14, M_OUTLINE);
        cell(vc, m, o, c, 12, 2, 12, 14, M_OUTLINE);
        cell(vc, m, o, c, 4, 15, 11, 15, M_OUTLINE);
        cell(vc, m, o, c, 4, 2, 11, 14, M_BODY);
        cell(vc, m, o, c, 8, 2, 11, 7, M_RED);           // right button
        cell(vc, m, o, c, 8, 7, 11, 7, M_RED_DARK);
        cell(vc, m, o, c, 7, 2, 8, 7, M_OUTLINE);        // divider and wheel
        cell(vc, m, o, c, 7, 4, 8, 4, M_SHADE);
        cell(vc, m, o, c, 4, 8, 11, 8, M_OUTLINE);       // split between buttons and body
        cell(vc, m, o, c, 4, 14, 11, 14, M_SHADE);
        buf.endBatch(RenderType.textSeeThrough(WHITE));

        mc.font.drawInBatch(Component.translatable("stealthtakedown.prompt"), H + 5.0F, -4.0F, 0xFFFFFFFF, true, m, buf,
                Font.DisplayMode.SEE_THROUGH, 0, FULL_LIGHT);
        buf.endBatch();

        ps.popPose();
    }

    /** Fills the grid cells gx0..gx1 / gy0..gy1 (inclusive) of the mouse icon. */
    private static void cell(VertexConsumer vc, Matrix4f m, float origin, float size,
                             int gx0, int gy0, int gx1, int gy1, int argb) {
        rect(vc, m, origin + gx0 * size, origin + gy0 * size, origin + (gx1 + 1) * size, origin + (gy1 + 1) * size, argb);
    }

    /** A solid rectangle; corner order matches the glyph quads Minecraft draws. */
    private static void rect(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        vertex(vc, m, x0, y0, 0.0F, 0.0F, argb);
        vertex(vc, m, x0, y1, 0.0F, 1.0F, argb);
        vertex(vc, m, x1, y1, 1.0F, 1.0F, argb);
        vertex(vc, m, x1, y0, 1.0F, 0.0F, argb);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float u, float v, int argb) {
        vc.vertex(m, x, y, 0.0F).color((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) & 255)
                .uv(u, v).uv2(FULL_LIGHT).endVertex();
    }
}
