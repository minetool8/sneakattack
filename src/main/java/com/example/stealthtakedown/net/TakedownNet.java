package com.example.stealthtakedown.net;

import com.example.stealthtakedown.StealthTakedownMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class TakedownNet {
    private TakedownNet() {}

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(StealthTakedownMod.MODID, "main"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));

    private static boolean done;

    public static void init() {
        if (done) return;
        done = true;
        CHANNEL.registerMessage(0, TakedownPacket.class, TakedownPacket::encode, TakedownPacket::decode, TakedownPacket::handle);
    }

    public static void toPlayer(ServerPlayer player, TakedownPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void toServer(TakedownPacket packet) {
        CHANNEL.sendToServer(packet);
    }
}
