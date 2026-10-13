package dev.marc.easyvillagerslibrary.merchant;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.trading.MerchantOffer;

/** Always show the rolled price, including when the current price is unchanged. */
public record TradePrices(int current, int original) {
    public static TradePrices of(MerchantOffer offer) { return new TradePrices(offer.getCostA().getCount(), offer.getBaseCostA().getCount()); }
    public boolean changed() { return current != original; }
    public Component label() {
        var originalNumber = Component.literal(Integer.toString(original));
        if (changed()) originalNumber.withStyle(ChatFormatting.STRIKETHROUGH);
        return Component.literal("♦ " + current + " (").append(originalNumber).append(")");
    }
}
