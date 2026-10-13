package dev.marc.easyvillagerslibrary.merchant;

import net.minecraft.world.item.ItemStack;

/**
 * A display-only live offer from the one fresh villager in the rolling
 * workstation. It deliberately contains no MerchantOffer state: that villager
 * cannot be traded with until the requested book has been found and moved into
 * the normal Library market.
 */
public record LibraryRollOfferPreview(ItemStack costA, ItemStack costB, ItemStack result) {
    public LibraryRollOfferPreview {
        costA = costA.copy();
        costB = costB.copy();
        result = result.copy();
    }
}
