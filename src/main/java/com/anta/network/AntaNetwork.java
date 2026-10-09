package com.anta.network;

import com.anta.AntaMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The mod's only packet: server tells the client whether the dead day (grey fog) is on.
 * One boolean, server to client only; the client never sends anything.
 */
public final class AntaNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AntaMod.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /** Client side: the dead day is on (read by client.DeadWorldFog). Plain field: no client classes here. */
    public static volatile boolean clientDeadWorld = false;

    private AntaNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(DeadWorldPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DeadWorldPacket::encode)
                .decoder(DeadWorldPacket::decode)
                .consumerMainThread(DeadWorldPacket::handle)
                .add();
        CHANNEL.messageBuilder(SoundPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SoundPacket::encode)
                .decoder(SoundPacket::decode)
                .consumerMainThread(SoundPacket::handle)
                .add();
    }

    /**
     * Plays steps / a rustle (variant = ModSounds index) for this player only. {@code yawDeg} is the world direction it comes
     * from (Minecraft yaw convention); the client keeps the source a couple of blocks from the player
     * in that direction, so it never fades away when you run.
     */
    public static void sendBehindSound(ServerPlayer player, float yawDeg, int variant) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SoundPacket(yawDeg, variant));
    }

    public record SoundPacket(float yawDeg, int variant) {
        static void encode(SoundPacket p, FriendlyByteBuf buf) {
            buf.writeFloat(p.yawDeg);
            buf.writeVarInt(p.variant);
        }

        static SoundPacket decode(FriendlyByteBuf buf) {
            return new SoundPacket(buf.readFloat(), buf.readVarInt());
        }

        static void handle(SoundPacket p, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                        () -> () -> com.anta.client.BehindSound.play(p.yawDeg, p.variant));
            }
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendDeadWorld(ServerPlayer player, boolean active) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DeadWorldPacket(active));
    }

    public static void sendDeadWorld(MinecraftServer server, boolean active) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) sendDeadWorld(p, active);
    }

    public record DeadWorldPacket(boolean active) {
        static void encode(DeadWorldPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.active);
        }

        static DeadWorldPacket decode(FriendlyByteBuf buf) {
            return new DeadWorldPacket(buf.readBoolean());
        }

        static void handle(DeadWorldPacket p, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() == NetworkDirection.PLAY_TO_CLIENT) clientDeadWorld = p.active;
            ctx.get().setPacketHandled(true);
        }
    }
}
