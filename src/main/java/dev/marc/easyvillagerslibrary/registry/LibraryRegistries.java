package dev.marc.easyvillagerslibrary.registry;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.block.EnchantmentLibraryBlock;
import dev.marc.easyvillagerslibrary.blockentity.EnchantmentLibraryBlockEntity;
import dev.marc.easyvillagerslibrary.menu.EnchantmentLibraryMenu;
import dev.marc.easyvillagerslibrary.menu.LibraryMarketMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LibraryRegistries {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EasyVillagersEnchantmentLibrary.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EasyVillagersEnchantmentLibrary.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EasyVillagersEnchantmentLibrary.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, EasyVillagersEnchantmentLibrary.MOD_ID);

    public static final DeferredBlock<EnchantmentLibraryBlock> ENCHANTMENT_LIBRARY = BLOCKS.register(
        "enchantment_library",
        () -> new EnchantmentLibraryBlock(BlockBehaviour.Properties.of().strength(4.0F).sound(SoundType.WOOD))
    );
    public static final DeferredHolder<Item, BlockItem> ENCHANTMENT_LIBRARY_ITEM = ITEMS.register(
        "enchantment_library",
        () -> new BlockItem(ENCHANTMENT_LIBRARY.get(), new Item.Properties())
    );
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnchantmentLibraryBlockEntity>> ENCHANTMENT_LIBRARY_BLOCK_ENTITY = BLOCK_ENTITIES.register(
        "enchantment_library",
        () -> BlockEntityType.Builder.of(EnchantmentLibraryBlockEntity::new, ENCHANTMENT_LIBRARY.get()).build(null)
    );
    public static final DeferredHolder<MenuType<?>, MenuType<EnchantmentLibraryMenu>> ENCHANTMENT_LIBRARY_MENU = MENUS.register(
        "enchantment_library",
        () -> new MenuType<>(EnchantmentLibraryMenu::new, FeatureFlags.DEFAULT_FLAGS)
    );
    public static final DeferredHolder<MenuType<?>, MenuType<LibraryMarketMenu>> LIBRARY_MARKET_MENU = MENUS.register(
        "library_market", () -> new MenuType<>(LibraryMarketMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private LibraryRegistries() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
    }
}
