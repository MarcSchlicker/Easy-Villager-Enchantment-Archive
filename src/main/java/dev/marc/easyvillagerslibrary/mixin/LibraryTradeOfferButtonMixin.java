package dev.marc.easyvillagerslibrary.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ATM10's merchant fake slots can produce a large opaque tooltip. It affects
 * ordinary traders too, so suppress that broken fake-slot tooltip globally;
 * ordinary inventory tooltips are not touched.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.MerchantScreen$TradeOfferButton")
public abstract class LibraryTradeOfferButtonMixin {
    @Inject(method = "renderToolTip", at = @At("HEAD"), cancellable = true)
    private void easyVillagersLibrary$hideBrokenLibraryTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                                               CallbackInfo callback) {
        callback.cancel();
    }
}
