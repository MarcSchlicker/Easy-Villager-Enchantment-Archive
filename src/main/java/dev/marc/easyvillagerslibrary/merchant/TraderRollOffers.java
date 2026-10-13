package dev.marc.easyvillagerslibrary.merchant;

import de.maxhenkel.easyvillagers.entity.EasyVillagerEntity;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;

final class TraderRollOffers {
    private TraderRollOffers() {
    }

    static boolean hasTarget(EasyVillagerEntity villager, Enchantment target) {
        for (MerchantOffer offer : villager.getOffers()) {
            if (!offer.getResult().is(Items.ENCHANTED_BOOK)) {
                continue;
            }
            for (Object2IntMap.Entry<Holder<Enchantment>> entry
                : EnchantmentHelper.getEnchantmentsForCrafting(offer.getResult()).entrySet()) {
                if (entry.getKey().value() == target && entry.getIntValue() == target.getMaxLevel()) {
                    return true;
                }
            }
        }
        return false;
    }
}
