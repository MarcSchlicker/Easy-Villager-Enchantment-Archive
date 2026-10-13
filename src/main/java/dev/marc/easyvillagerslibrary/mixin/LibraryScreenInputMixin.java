package dev.marc.easyvillagerslibrary.mixin;

import dev.marc.easyvillagerslibrary.client.LibraryMerchantScreenAccess;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Screen, rather than MerchantScreen, declares the keyboard methods in the
 * ATM10 runtime. Keeping this separate avoids an inherited-method injection
 * target, which Mixin treats as a fatal startup error.
 */
@Mixin(Screen.class)
public abstract class LibraryScreenInputMixin {
    // Lunar's bundled NeoForge can differ slightly from the development API.
    // Input convenience must never turn a missing callback into a startup crash.
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void easyVillagersLibrary$keepKeysInLibraryFilter(int keyCode, int scanCode, int modifiers,
                                                              CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof LibraryMerchantScreenAccess libraryScreen
            && libraryScreen.easyVillagersLibrary$handleLibraryFilterKey(keyCode, scanCode, modifiers)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true, require = 0)
    private void easyVillagersLibrary$typeIntoLibraryFilter(char character, int modifiers,
                                                            CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof LibraryMerchantScreenAccess libraryScreen
            && libraryScreen.easyVillagersLibrary$handleLibraryFilterCharacter(character, modifiers)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, require = 0)
    private void easyVillagersLibrary$scrollLibraryDrawer(double mouseX, double mouseY, double horizontalAmount,
                                                          double verticalAmount, CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof LibraryMerchantScreenAccess libraryScreen
            && libraryScreen.easyVillagersLibrary$handleDrawerScroll(mouseX, mouseY, verticalAmount)) {
            callback.setReturnValue(true);
        }
    }
}
