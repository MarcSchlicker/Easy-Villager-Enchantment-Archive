package dev.marc.easyvillagerslibrary.network;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.client.TraderRollViewState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Sent only when a real Easy Villagers Trader has a Library beside it. */
public record TraderRollViewPayload(int containerId) implements CustomPacketPayload {
    public static final Type<TraderRollViewPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(EasyVillagersEnchantmentLibrary.MOD_ID, "trader_roll_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TraderRollViewPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeVarInt(payload.containerId),
        buffer -> new TraderRollViewPayload(buffer.readVarInt()));

    @Override
    public Type<TraderRollViewPayload> type() {
        return TYPE;
    }

    public static void handle(TraderRollViewPayload payload, IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            context.enqueueWork(() -> TraderRollViewState.enable(payload.containerId));
        }
    }
}
