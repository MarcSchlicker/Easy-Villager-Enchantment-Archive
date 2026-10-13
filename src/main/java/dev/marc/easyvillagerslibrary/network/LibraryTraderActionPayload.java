package dev.marc.easyvillagerslibrary.network;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.menu.LibraryMarketMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Actions address the current Library menu, never an arbitrary world position. */
public record LibraryTraderActionPayload(int containerId, int action, int value) implements CustomPacketPayload {
    public static final int OPEN = 0, CLOSE = 1, SELECT = 2;
    public static final Type<LibraryTraderActionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(EasyVillagersEnchantmentLibrary.MOD_ID, "library_trader_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LibraryTraderActionPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> { buffer.writeVarInt(payload.containerId); buffer.writeVarInt(payload.action); buffer.writeVarInt(payload.value); },
        buffer -> new LibraryTraderActionPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
    @Override public Type<LibraryTraderActionPayload> type() { return TYPE; }
    public static void handle(LibraryTraderActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().containerMenu instanceof LibraryMarketMenu menu
                && menu.containerId == payload.containerId && menu.stillValid(context.player())) {
                if (payload.action == OPEN) menu.openTraderPanel();
                else if (payload.action == CLOSE) menu.closeTraderPanel();
                else if (payload.action == SELECT) menu.selectTraderOffer(payload.value);
            }
        });
    }
}
