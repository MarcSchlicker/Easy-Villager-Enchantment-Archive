package dev.marc.easyvillagerslibrary;

import de.maxhenkel.easyvillagers.ModCreativeTabs;
import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Places the add-on block beside the normal Easy Villagers blocks and in Creative search. */
@EventBusSubscriber(modid = EasyVillagersEnchantmentLibrary.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class LibraryCreativeTab {

    private LibraryCreativeTab() {
    }

    @SubscribeEvent
    public static void addToEasyVillagersTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.TAB_EASY_VILLAGERS.getKey())) {
            event.accept(LibraryRegistries.ENCHANTMENT_LIBRARY_ITEM.get());
        }
    }
}
