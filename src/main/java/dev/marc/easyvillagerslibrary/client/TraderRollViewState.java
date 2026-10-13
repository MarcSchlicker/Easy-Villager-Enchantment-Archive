package dev.marc.easyvillagerslibrary.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Client-side marker for an Easy Villagers Trader that is touching a Library. */
public final class TraderRollViewState {
    private static AbstractContainerMenu enabledMenu;

    private TraderRollViewState() {
    }

    public static void enable(int containerId) {
        var player = Minecraft.getInstance().player;
        enabledMenu = player != null && player.containerMenu.containerId == containerId ? player.containerMenu : null;
    }

    public static boolean isEnabled(int containerId) {
        var player = Minecraft.getInstance().player;
        return player != null && player.containerMenu == enabledMenu && enabledMenu.containerId == containerId;
    }
}
