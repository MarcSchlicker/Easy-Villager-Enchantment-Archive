package dev.marc.easyvillagerslibrary.network;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.client.LibraryMerchantViewState;
import dev.marc.easyvillagerslibrary.merchant.LibraryRollOfferPreview;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Syncs the exact source-villager index for every merged Library offer. */
public record LibraryMerchantViewPayload(int containerId, List<ItemStack> villagers,
                                         List<Integer> offerVillagerIndices, ItemStack rollTarget,
                                         ItemStack activeRollVillager, int pendingRollVillagerCount,
                                         List<LibraryRollOfferPreview> activeRollOffers)
    implements CustomPacketPayload {
    public static final Type<LibraryMerchantViewPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(EasyVillagersEnchantmentLibrary.MOD_ID, "merchant_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LibraryMerchantViewPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeVarInt(payload.containerId);
            buffer.writeVarInt(payload.villagers.size());
            for (ItemStack villager : payload.villagers) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, villager);
            }
            buffer.writeVarInt(payload.offerVillagerIndices.size());
            for (int index : payload.offerVillagerIndices) {
                buffer.writeVarInt(index);
            }
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.rollTarget);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.activeRollVillager);
            buffer.writeVarInt(payload.pendingRollVillagerCount);
            buffer.writeVarInt(payload.activeRollOffers.size());
            for (LibraryRollOfferPreview offer : payload.activeRollOffers) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, offer.costA());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, offer.costB());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, offer.result());
            }
        },
        buffer -> {
            int containerId = buffer.readVarInt();
            int villagerCount = buffer.readVarInt();
            List<ItemStack> villagers = new ArrayList<>(villagerCount);
            for (int index = 0; index < villagerCount; index++) {
                villagers.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            }
            int offerCount = buffer.readVarInt();
            List<Integer> sources = new ArrayList<>(offerCount);
            for (int index = 0; index < offerCount; index++) {
                sources.add(buffer.readVarInt());
            }
            ItemStack rollTarget = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            ItemStack activeRollVillager = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            int pendingRollVillagerCount = buffer.readVarInt();
            int activeOfferCount = buffer.readVarInt();
            List<LibraryRollOfferPreview> activeOffers = new ArrayList<>(activeOfferCount);
            for (int index = 0; index < activeOfferCount; index++) {
                activeOffers.add(new LibraryRollOfferPreview(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)));
            }
            return new LibraryMerchantViewPayload(containerId, villagers, sources, rollTarget,
                activeRollVillager, pendingRollVillagerCount, activeOffers);
        });

    @Override
    public Type<LibraryMerchantViewPayload> type() {
        return TYPE;
    }

    public static void handle(LibraryMerchantViewPayload payload, IPayloadContext context) {
        if (context.flow() != PacketFlow.CLIENTBOUND) {
            return;
        }
        context.enqueueWork(() -> LibraryMerchantViewState.update(payload.containerId, payload.villagers,
            payload.offerVillagerIndices, payload.rollTarget, payload.activeRollVillager,
            payload.pendingRollVillagerCount, payload.activeRollOffers));
    }
}
