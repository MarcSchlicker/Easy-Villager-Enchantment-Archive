package dev.marc.easyvillagerslibrary.mixin;

import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** No optional injection into inherited methods: a mapped accessor to the actual merchant. */
@Mixin(MerchantMenu.class)
public interface LibraryMerchantMenuMixin {
    @Accessor("trader") Merchant easyVillagersLibrary$getMerchant();
}
