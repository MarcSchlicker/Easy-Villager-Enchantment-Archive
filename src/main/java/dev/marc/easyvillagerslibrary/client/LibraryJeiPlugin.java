package dev.marc.easyvillagerslibrary.client;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IIngredientAliasRegistration;
import mezz.jei.api.registration.IModInfoRegistration;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Makes the add-on discoverable alongside Easy Villagers without claiming its namespace. */
@JeiPlugin
public final class LibraryJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
        EasyVillagersEnchantmentLibrary.MOD_ID, "jei_plugin"
    );
    private static final List<String> SEARCH_ALIASES = List.of(
        "easyvillager", "easy villager", "easy villagers", "bibliothek", "library"
    );

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerModInfo(IModInfoRegistration registration) {
        registration.addModAliases(EasyVillagersEnchantmentLibrary.MOD_ID, SEARCH_ALIASES);
    }

    /**
     * ATM10's JEI setup can omit ungrouped add-on items from its automatic list.
     * Registering this stack explicitly makes the Library visible in JEI regardless
     * of Creative-tab or mod-list filtering.
     */
    @Override
    public void registerExtraIngredients(IExtraIngredientRegistration registration) {
        registration.addExtraItemStacks(List.of(new ItemStack(LibraryRegistries.ENCHANTMENT_LIBRARY_ITEM.get())));
    }

    @Override
    public void registerIngredientAliases(IIngredientAliasRegistration registration) {
        registration.addAliases(LibraryRegistries.ENCHANTMENT_LIBRARY_ITEM.get(), SEARCH_ALIASES);
    }

    /**
     * Tell JEI that an expanded Library drawer occupies this rectangle. JEI
     * then lays out its ingredient list around it instead of rendering on top
     * of our controls.
     */
    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(LibraryMarketScreen.class, new IGuiContainerHandler<LibraryMarketScreen>() {
            @Override public List<Rect2i> getGuiExtraAreas(LibraryMarketScreen screen) { return screen.extraAreas(); }
        });
        registration.addGuiContainerHandler(MerchantScreen.class, new IGuiContainerHandler<MerchantScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(MerchantScreen screen) {
                if (screen instanceof LibraryMerchantScreenAccess libraryScreen
                    && libraryScreen.easyVillagersLibrary$isLibraryTradeScreen()) {
                    return List.of(new Rect2i(libraryScreen.easyVillagersLibrary$rightPanelX(),
                        libraryScreen.easyVillagersLibrary$rightPanelY(), 180, 208));
                }
                if (screen instanceof LibraryMerchantScreenAccess traderScreen
                    && traderScreen.easyVillagersLibrary$isTraderRollScreen()) {
                    return List.of(new Rect2i(screen.getGuiLeft(), screen.getGuiTop() - 101, 207, 99));
                }
                return List.of();
            }
        });
    }
}
