package dev.marc.easyvillagerslibrary.client;

import dev.marc.easyvillagerslibrary.merchant.LibraryRollOfferPreview;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client mirror for the two expandable panels beside a Library merchant. */
public final class LibraryMerchantViewState {
    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    private LibraryMerchantViewState() {
    }

    public static void update(int containerId, List<ItemStack> villagers, List<Integer> offerVillagerIndices,
                              ItemStack rollTarget, ItemStack activeRollVillager, int pendingRollVillagerCount,
                              List<LibraryRollOfferPreview> activeRollOffers) {
        STATES.put(containerId, new State(villagers.stream().map(ItemStack::copy).toList(),
            List.copyOf(offerVillagerIndices), rollTarget.copy(), activeRollVillager.copy(),
            pendingRollVillagerCount, List.copyOf(activeRollOffers)));
    }

    public static State get(int containerId) {
        return STATES.get(containerId);
    }

    public record State(List<ItemStack> villagers, List<Integer> offerVillagerIndices, ItemStack rollTarget,
                        ItemStack activeRollVillager, int pendingRollVillagerCount,
                        List<LibraryRollOfferPreview> activeRollOffers) {
        public int sourceForOffer(int offerIndex) {
            return offerIndex >= 0 && offerIndex < offerVillagerIndices.size()
                ? offerVillagerIndices.get(offerIndex) : -1;
        }
    }
}
