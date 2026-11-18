package com.example.stealthtakedown.net;

import com.example.stealthtakedown.server.TakedownServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** One small packet type for everything. */
public class TakedownPacket {
    // client -> server
    public static final byte C2S_START = 0;       // player pressed right click on a prompt
    public static final byte C2S_CANCEL = 1;      // player let go / aborted
    // server -> client
    public static final byte S2C_CANDIDATE = 2;   // entityId = zombie that shows the prompt, -1 = none
    public static final byte S2C_BEGIN = 3;       // charge accepted: entityId, ticks
    public static final byte S2C_CANCELLED = 4;   // charge refused / broken
    public static final byte S2C_STRIKE = 5;      // the blow landed

    public final byte type;
    public final int entityId;
    public final int ticks;

    public TakedownPacket(byte type, int entityId, int ticks) {
        this.type = type;
        this.entityId = entityId;
        this.ticks = ticks;
    }

    public static TakedownPacket of(byte type) { return new TakedownPacket(type, -1, 0); }
    public static TakedownPacket candidate(int id) { return new TakedownPacket(S2C_CANDIDATE, id, 0); }
    public static TakedownPacket begin(int id, int ticks) { return new TakedownPacket(S2C_BEGIN, id, ticks); }

    public static void encode(TakedownPacket p, FriendlyByteBuf buf) {
        buf.writeByte(p.type);
        buf.writeInt(p.entityId);
        buf.writeInt(p.ticks);
    }

    public static TakedownPacket decode(FriendlyByteBuf buf) {
        return new TakedownPacket(buf.readByte(), buf.readInt(), buf.readInt());
    }

    public static void handle(TakedownPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        if (c.getDirection().getReceptionSide().isServer()) {
            ServerPlayer sender = c.getSender();
            c.enqueueWork(() -> TakedownServer.onPacket(sender, p));
        } else {
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.example.stealthtakedown.client.TakedownClient.onPacket(p)));
        }
        c.setPacketHandled(true);
    }
}
