package dev.marc.easyvillagerslibrary.network;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.menu.LibraryMarketMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LibraryTraderViewPayload(int containerId, ItemStack villager, MerchantOffers offers,
                                      int selectedOffer, boolean rolling, int attempts, int resultRevision,
                                      ItemStack matchedBook, String status) implements CustomPacketPayload {
    public static final Type<LibraryTraderViewPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(EasyVillagersEnchantmentLibrary.MOD_ID, "library_trader_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LibraryTraderViewPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeVarInt(payload.containerId);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.villager);
            MerchantOffers.STREAM_CODEC.encode(buffer, payload.offers);
            buffer.writeVarInt(payload.selectedOffer); buffer.writeBoolean(payload.rolling);
            buffer.writeVarInt(payload.attempts); buffer.writeVarInt(payload.resultRevision);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.matchedBook);
            buffer.writeUtf(payload.status, 160);
        }, buffer -> new LibraryTraderViewPayload(buffer.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
            MerchantOffers.STREAM_CODEC.decode(buffer), buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt(),
            buffer.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer), buffer.readUtf(160)));
    @Override public Type<LibraryTraderViewPayload> type() { return TYPE; }
    public static void handle(LibraryTraderViewPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Do not leak state into a later screen whose container id happens to be reused.
            if (context.player().containerMenu instanceof LibraryMarketMenu menu && menu.containerId == payload.containerId)
                menu.applyTraderView(payload);
        });
    }
}
