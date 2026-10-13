package dev.marc.easyvillagerslibrary.client;

/** Marker for the Library's merged MerchantScreen, safe to reference from mixins. */
public interface LibraryMerchantScreenAccess {
    boolean easyVillagersLibrary$isLibraryTradeScreen();

    boolean easyVillagersLibrary$isTraderRollScreen();

    int easyVillagersLibrary$rightPanelX();

    int easyVillagersLibrary$rightPanelY();

    boolean easyVillagersLibrary$handleLibraryFilterKey(int keyCode, int scanCode, int modifiers);

    boolean easyVillagersLibrary$handleLibraryFilterCharacter(char character, int modifiers);

    boolean easyVillagersLibrary$handleDrawerScroll(double mouseX, double mouseY, double verticalAmount);
}
