package dev.marc.easyvillagerslibrary.merchant;

import net.minecraft.world.entity.player.Player;

/** Server-side control surface exposed only by the Library's merged merchant. */
public interface LibraryMerchantControl {
    /** Vanilla MerchantMenu button ids beginning here are reserved for Auto-roll targets. */
    int ROLL_TARGET_BUTTON_OFFSET = 10_000;
    /** Opens the one fresh villager currently assigned to the rolling station. */
    int OPEN_ACTIVE_ROLLER_BUTTON = 9_000;
    /** MerchantMenu button range reserved for taking a drawer villager out. */
    int REMOVE_VILLAGER_BUTTON_OFFSET = 30_000;
    /** Inserts the MerchantMenu cursor stack into the Library's villager-only drawer. */
    int INSERT_DRAWER_VILLAGER_BUTTON = 29_999;

    boolean easyVillagersLibrary$setRollTargetByRawId(int rawRegistryId);

    boolean easyVillagersLibrary$openActiveRoller(Player player);

    boolean easyVillagersLibrary$removeDrawerVillager(Player player, int displayIndex);

    boolean easyVillagersLibrary$insertDrawerVillager(Player player, net.minecraft.world.item.ItemStack stack);
}
