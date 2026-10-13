package dev.marc.easyvillagerslibrary.network;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.merchant.TraderRollControl;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** A normal, single reroll: deliberately has no search target. */
public record TraderSingleRollRequestPayload(int containerId) implements CustomPacketPayload {
    public static final Type<TraderSingleRollRequestPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(EasyVillagersEnchantmentLibrary.MOD_ID, "trader_single_roll_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TraderSingleRollRequestPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeVarInt(payload.containerId),
        buffer -> new TraderSingleRollRequestPayload(buffer.readVarInt()));

    @Override public Type<TraderSingleRollRequestPayload> type() { return TYPE; }
    public static void handle(TraderSingleRollRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) TraderRollControl.requestOnce(player, payload.containerId());
        });
    }
}
