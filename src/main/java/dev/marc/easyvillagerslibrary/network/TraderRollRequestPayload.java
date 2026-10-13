package dev.marc.easyvillagerslibrary.network;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.merchant.TraderRollControl;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record TraderRollRequestPayload(int containerId, int rawEnchantmentId, String query) implements CustomPacketPayload {
    public static final Type<TraderRollRequestPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(EasyVillagersEnchantmentLibrary.MOD_ID, "trader_roll_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TraderRollRequestPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> { buffer.writeVarInt(payload.containerId); buffer.writeVarInt(payload.rawEnchantmentId); buffer.writeUtf(payload.query, 80); },
        buffer -> new TraderRollRequestPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(80)));
    @Override public Type<TraderRollRequestPayload> type() { return TYPE; }
    public static void handle(TraderRollRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) TraderRollControl.request(player, payload);
        });
    }
}
