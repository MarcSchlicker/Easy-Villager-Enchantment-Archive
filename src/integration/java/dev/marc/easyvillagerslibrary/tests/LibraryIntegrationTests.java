package dev.marc.easyvillagerslibrary.tests;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import de.maxhenkel.easyvillagers.blocks.ModBlocks;
import de.maxhenkel.easyvillagers.blocks.tileentity.TraderTileentityBase;
import de.maxhenkel.easyvillagers.datacomponents.VillagerData;
import de.maxhenkel.easyvillagers.entity.EasyVillagerEntity;
import de.maxhenkel.easyvillagers.items.ModItems;
import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.blockentity.EnchantmentLibraryBlockEntity;
import dev.marc.easyvillagerslibrary.menu.LibraryMarketMenu;
import dev.marc.easyvillagerslibrary.merchant.TraderRollControl;
import dev.marc.easyvillagerslibrary.merchant.RollTargetSearch;
import dev.marc.easyvillagerslibrary.mixin.LibraryMerchantMenuMixin;
import dev.marc.easyvillagerslibrary.network.TraderRollRequestPayload;
import dev.marc.easyvillagerslibrary.network.TraderSingleRollRequestPayload;
import dev.marc.easyvillagerslibrary.network.LibraryTraderViewPayload;
import dev.marc.easyvillagerslibrary.merchant.TradePrices;
import net.minecraft.ChatFormatting;
import io.netty.buffer.Unpooled;
import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Optional;
import java.util.List;
import java.util.Comparator;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

/** Runs only in an isolated dev server. Not packaged in the distributed mod. */
@EventBusSubscriber(modid = EasyVillagersEnchantmentLibrary.MOD_ID)
public final class LibraryIntegrationTests {
    private static int passed;
    private static int ticks;
    private static TestPlayer player;
    private static EasyVillagerEntity roller;
    private static MerchantMenu rollMenu;
    private static int phase;
    private static EnchantmentLibraryBlockEntity testedLibrary;
    private static LibraryMarketMenu panelMenu;
    private static MerchantOffers previousRollOffers;
    private static MerchantOffers libraryOffers;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        LogUtils.getLogger().info("LIBRARY TEST PASS: {}", name);
        passed++;
    }
    private static ItemStack villager(ServerLevel level, boolean locked) {
        ItemStack stack = new ItemStack(ModItems.VILLAGER.get());
        EasyVillagerEntity entity = VillagerData.getCacheVillager(stack, level);
        entity.setAge(0);
        entity.setVillagerData(entity.getVillagerData().setProfession(VillagerProfession.LIBRARIAN));
        entity.setVillagerXp(locked ? 1 : 0);
        var protection = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.PROTECTION);
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 22), Optional.of(new ItemCost(Items.BOOK)),
            EnchantedBookItem.createForEnchantment(new EnchantmentInstance(protection, 4)), 12, 1, 0.2F));
        entity.setOffers(offers);
        VillagerData.applyToItem(stack, entity);
        return stack;
    }

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        if (!Boolean.getBoolean("library.integrationTests")) return;
        try {
            ServerLevel level = event.getServer().overworld();
            List<String> protectionNames = List.of("Blast Protection IV", "Fire Protection IV", "Projectile Protection IV", "Protection IV");
            List<String> fullMatches = protectionNames.stream().filter(name -> RollTargetSearch.matches("Protection IV", name))
                .sorted(Comparator.comparingInt((String name) -> RollTargetSearch.priority("Protection IV", name)).thenComparing(name -> name)).toList();
            check(fullMatches.size() == 4 && fullMatches.getFirst().equals("Protection IV"),
                "exact Protection IV appears before Blast, Fire and Projectile Protection without losing other results");
            List<String> prefixMatches = protectionNames.stream().filter(name -> RollTargetSearch.matches("prot", name))
                .sorted(Comparator.comparingInt((String name) -> RollTargetSearch.priority("prot", name)).thenComparing(name -> name)).toList();
            check(prefixMatches.getFirst().equals("Protection IV"), "name prefix results outrank substring-only variants");
            check(RollTargetSearch.exactName("  pRoTection   IV  ", "Protection IV")
                && !RollTargetSearch.exactName("Protection IV", "Projectile Protection IV"),
                "typed exact targets ignore whitespace/case without selecting a different Protection variant");
            check(RollTargetSearch.matches("luck of the sea", "minecraft:luck_of_the_sea")
                && !RollTargetSearch.matches("", "Protection IV"), "search still supports item IDs and rejects blank targets");
            RegistryFriendlyByteBuf singleBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            TraderSingleRollRequestPayload.STREAM_CODEC.encode(singleBuffer, new TraderSingleRollRequestPayload(123));
            check(TraderSingleRollRequestPayload.STREAM_CODEC.decode(singleBuffer).containerId() == 123,
                "single-roll request network roundtrip needs only the live menu ID, not an enchantment target");
            singleBuffer.release();
            BlockPos pos = new BlockPos(8, 64, 8);
            level.getChunkAt(pos);
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos.east(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos, LibraryRegistries.ENCHANTMENT_LIBRARY.get().defaultBlockState());
            EnchantmentLibraryBlockEntity library = (EnchantmentLibraryBlockEntity) level.getBlockEntity(pos);
            testedLibrary = library;
            player = new TestPlayer(level);
            player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            check(library.openBookMarket(player), "empty Library opens");
            LibraryMarketMenu menu = (LibraryMarketMenu) player.containerMenu;
            check(menu.getType() == LibraryRegistries.LIBRARY_MARKET_MENU.get() && menu.slots.size() == 70,
                "real custom merchant menu has 27 archive slots, three payments/results and one worker slot");
            check(!menu.slots.get(39).mayPlace(new ItemStack(Items.EMERALD)), "drawer rejects non-villagers");

            menu.setCarried(villager(level, true));
            menu.clicked(39, 0, ClickType.PICKUP, player);
            menu.broadcastChanges();
            check(menu.getCarried().isEmpty() && library.villagerCount() == 1 && menu.slots.get(39).hasItem(), "cursor insertion moves exactly one villager");
            check(menu.getOffers().size() == 1, "inserted villager's real book trade appears immediately");
            menu.clicked(39, 0, ClickType.PICKUP, player);
            menu.broadcastChanges();
            check(menu.getCarried().is(ModItems.VILLAGER.get()) && library.villagerCount() == 0 && menu.getOffers().isEmpty(),
                "cursor withdrawal removes villager and stale trades");
            menu.setCarried(ItemStack.EMPTY);
            menu.setCarried(new ItemStack(Items.DIAMOND, 3));
            menu.clicked(39, 0, ClickType.PICKUP, player);
            check(menu.getCarried().getCount() == 3 && library.villagerCount() == 0, "invalid insertion neither consumes nor stores item");
            menu.setCarried(ItemStack.EMPTY);

            player.getInventory().setItem(9, villager(level, true).copyWithCount(3));
            menu.clicked(3, 0, ClickType.QUICK_MOVE, player);
            menu.broadcastChanges();
            check(player.getInventory().getItem(9).isEmpty() && library.villagerCount() == 3, "shift insertion stores all villagers without a capacity cap");
            ItemStack second = library.archiveItem(1);
            menu.clicked(39, 0, ClickType.QUICK_MOVE, player);
            menu.broadcastChanges();
            check(library.villagerCount() == 2 && library.archiveItem(0).isEmpty() && library.archiveItem(1) == second,
                "shift withdrawal preserves following villager slot indices");
            library.setArchiveItem(27, villager(level, true));
            menu.broadcastChanges();
            check(menu.archivePages() == 2 && menu.clickMenuButton(player, LibraryMarketMenu.PAGE_NEXT)
                && menu.archivePage() == 1 && menu.slots.get(39).hasItem(), "page navigation displays actual archive slot 28");
            menu.clickMenuButton(player, LibraryMarketMenu.PAGE_PREVIOUS);
            check(menu.clickMenuButton(player, LibraryMarketMenu.PAGE_SELECT_OFFSET + 1) && menu.archivePage() == 1,
                "selected trade can jump to its source villager page");
            menu.clickMenuButton(player, LibraryMarketMenu.PAGE_PREVIOUS);
            MerchantOffer discounted = menu.getOffers().getFirst();
            discounted.addToSpecialPriceDiff(-5);

            var saved = library.saveWithFullMetadata(level.registryAccess());
            var restored = (EnchantmentLibraryBlockEntity) BlockEntity.loadStatic(pos, library.getBlockState(), saved, level.registryAccess());
            check(restored != null && restored.villagerCount() == 3 && restored.archiveItem(0).isEmpty()
                && restored.archiveItem(27).is(ModItems.VILLAGER.get()), "save/load preserves all villagers and paged holes");
            ItemStack packed = new ItemStack(LibraryRegistries.ENCHANTMENT_LIBRARY_ITEM.get());
            library.saveToItem(packed, level.registryAccess());
            check(packed.has(DataComponents.BLOCK_ENTITY_DATA), "mined block item stores its villager archive");
            check(discounted.getCostA().getCount() == 17
                && VillagerData.getCacheVillager(restored.archiveItem(1), level).getOffers().getFirst().getSpecialPriceDiff() == 0,
                "saving preserves the live discount but does not freeze a player's discount into the villager item");
            discounted.resetSpecialPriceDiff();
            TradePrices samePrice = TradePrices.of(discounted);
            check(samePrice.label().getString().equals("♦ 22 (22)") && !samePrice.changed(), "original price remains visible even without a discount");
            discounted.addToSpecialPriceDiff(-5);
            TradePrices salePrice = TradePrices.of(discounted);
            check(salePrice.label().getString().equals("♦ 17 (22)") && salePrice.label().getSiblings().getFirst().getStyle().isStrikethrough(),
                "discounted price retains a struck-through original price");
            discounted.resetSpecialPriceDiff();

            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 64));
            player.getInventory().setItem(1, new ItemStack(Items.BOOK, 64));
            MerchantOffer offer = menu.getOffers().getFirst();
            menu.setSelectionHint(0); menu.tryMoveItems(0);
            check(menu.slots.get(2).getItem().is(Items.ENCHANTED_BOOK), "selecting a merged trade fills the native result slot");
            menu.clicked(2, 0, ClickType.QUICK_MOVE, player);
            check(offer.getUses() == 2 && player.getInventory().contains(stack -> stack.is(Items.ENCHANTED_BOOK)),
                "shift buying completes real trade without Entity cast crash");
            player.closeContainer();
            check(VillagerData.getCacheVillager(library.archiveItem(1), level).getOffers().getFirst().getUses() == 2,
                "purchase persists stock on the source villager");
            var restock = EnchantmentLibraryBlockEntity.class.getDeclaredField("nextLibraryRestock");
            restock.setAccessible(true); restock.setLong(library, level.getGameTime() - 1);
            library.tickServer();
            MerchantOffer restocked = VillagerData.getCacheVillager(library.archiveItem(1), level).getOffers().getFirst();
            check(restocked.getUses() == 0 && restocked.getResult().is(Items.ENCHANTED_BOOK), "work cycle restocks rather than re-rolling book trades");

            BlockPos traderPos = pos.east();
            level.setBlockAndUpdate(traderPos, ModBlocks.TRADER.get().defaultBlockState());
            TraderTileentityBase trader = (TraderTileentityBase) level.getBlockEntity(traderPos);
            trader.setVillager(villager(level, false));
            check(TraderRollControl.hasAdjacentLibrary(trader), "real Trader detects adjacent Library");
            check(trader.openTradingGUI(player), "Easy Villagers native Trader opens with Library hook enabled");
            roller = trader.getVillagerEntity();
            rollMenu = (MerchantMenu) player.containerMenu;
            check(rollMenu.stillValid(player), "native Trader remains valid before rolling");
            check(rollMenu instanceof LibraryMerchantMenuMixin access && access.easyVillagersLibrary$getMerchant() == roller,
                "required merchant accessor binds to the real Trader entity");
            MerchantOffers initial = roller.getOffers();
            TraderRollControl.requestOnce(player, rollMenu.containerId + 1);
            check(roller.getOffers() == initial, "stale single-roll packet is rejected on the native Trader");
            TraderRollControl.requestOnce(player, rollMenu.containerId);
            check(roller.getOffers() != initial && roller.getVillagerXp() == 0 && rollMenu.getOffers() == roller.getOffers(),
                "single-roll replaces native Trader offers without a target or earned XP");
            initial = roller.getOffers();
            int id = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getId(
                level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).get(Enchantments.SHARPNESS));
            TraderRollControl.request(player, new TraderRollRequestPayload(rollMenu.containerId + 1, id, "sharp"));
            check(initial == roller.getOffers(), "stale roll packet is rejected");
            TraderRollControl.request(player, new TraderRollRequestPayload(rollMenu.containerId, id, "sharp"));
        } catch (Throwable error) {
            LogUtils.getLogger().error("LIBRARY INTEGRATION FAILED", error);
            event.getServer().halt(false);
        }
    }

    @SubscribeEvent public static void poll(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("library.integrationTests") || roller == null) return;
        try {
            var sharpness = player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SHARPNESS);
            boolean found = roller.getOffers().stream().anyMatch(offer -> EnchantmentHelper.getEnchantmentsForCrafting(offer.getResult()).getLevel(sharpness) == 5);
            if (!found && phase < 3 && ++ticks < 1200) return;
            if (phase == 0) {
                check(found, "server roll action finds real Sharpness V trade");
                check(player.containerMenu == rollMenu && rollMenu.stillValid(player), "rolling keeps the same live native trading menu open");
                previousRollOffers = roller.getOffers();
                phase = 1; ticks = 0;
                int rawId = player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getId(sharpness.value());
                TraderRollControl.request(player, new TraderRollRequestPayload(rollMenu.containerId, rawId, "sharp"));
                return;
            }
            if (phase == 1) {
                if (roller.getOffers() == previousRollOffers && ++ticks < 1200) return;
                check(found && roller.getOffers() != previousRollOffers, "clicking Roll again replaces an already-matching native trade with genuinely new offers");
                MerchantOffers locked = roller.getOffers();
                roller.setVillagerXp(10);
                TraderRollControl.request(player, new TraderRollRequestPayload(rollMenu.containerId, -1, "mending"));
                check(locked == roller.getOffers(), "earned/locked villager trades cannot be rolled away");
                roller.setVillagerXp(0);
                player.closeContainer();
                ItemStack freshWorker = new ItemStack(ModItems.VILLAGER.get());
                VillagerData.applyToItem(freshWorker, roller);
                testedLibrary.setArchiveItem(0, freshWorker);
                player.serverLevel().setBlockAndUpdate(testedLibrary.getBlockPos().east(), Blocks.AIR.defaultBlockState());
                testedLibrary.openBookMarket(player);
                panelMenu = (LibraryMarketMenu) player.containerMenu;
                check(panelMenu.rollerVillager() == null && testedLibrary.villagerCount() == 4, "collapsed roll panel leaves all villagers in the archive");
                panelMenu.openTraderPanel();
                roller = panelMenu.rollerVillager();
                libraryOffers = panelMenu.getOffers();
                check(roller != null && panelMenu.traderOffers() == roller.getOffers() && roller.getTradingPlayer() == player
                    && player.level().getBlockEntity(testedLibrary.getBlockPos().east()) == null,
                    "Library side panel works with no adjacent Trader block");
                check(testedLibrary.villagerCount() == 3 && testedLibrary.archiveItem(0).isEmpty() && panelMenu.slots.get(69).hasItem(),
                    "auto-loading moves the fresh archive villager to the real worker slot exactly once");
                check(panelMenu.getOffers().size() == 3, "the worker's previous offers are removed from the aggregate market while rolling");
                var workerSave = testedLibrary.saveWithFullMetadata(player.level().registryAccess());
                var recovered = (EnchantmentLibraryBlockEntity) BlockEntity.loadStatic(testedLibrary.getBlockPos(), testedLibrary.getBlockState(),
                    workerSave, player.level().registryAccess());
                recovered.setLevel(player.level()); recovered.onLoad();
                check(recovered.villagerCount() == 4 && recovered.workstationItem().isEmpty() && recovered.archiveItem(0).is(ModItems.VILLAGER.get()),
                    "save/load after an interrupted session returns the worker to its original archive slot without duplication");
                var occupiedOrigin = (EnchantmentLibraryBlockEntity) BlockEntity.loadStatic(testedLibrary.getBlockPos(), testedLibrary.getBlockState(),
                    workerSave, player.level().registryAccess());
                occupiedOrigin.setLevel(player.level());
                ItemStack marker = villager(player.serverLevel(), true);
                occupiedOrigin.setArchiveItem(0, marker); occupiedOrigin.onLoad();
                check(occupiedOrigin.archiveItem(0) == marker && occupiedOrigin.villagerCount() == 5 && occupiedOrigin.workstationItem().isEmpty(),
                    "returning a worker never overwrites a newly occupied original archive slot");
                ItemStack packedWorker = new ItemStack(LibraryRegistries.ENCHANTMENT_LIBRARY_ITEM.get());
                testedLibrary.saveToItem(packedWorker, player.level().registryAccess());
                check(packedWorker.get(DataComponents.BLOCK_ENTITY_DATA).copyTag().contains("TraderWorkstationVillager"),
                    "mined Library data includes its live internal worker");
                panelMenu.positionTraderSlots(117);
                check(panelMenu.slots.get(66).y == 175 && panelMenu.slots.get(69).y == 149 && panelMenu.slots.get(39).y == 28,
                    "expanding the archive moves panel payment/result and worker slots but not archive slots");
                panelMenu.positionTraderSlots(0);
                previousRollOffers = roller.getOffers();
                TraderRollControl.request(player, new TraderRollRequestPayload(panelMenu.containerId + 1, -1, "sharp"));
                check(panelMenu.rollRevision() == 0 && roller.getOffers() == previousRollOffers, "stale Library roll requests cannot mutate the neighbor");
                phase = 2; ticks = 0;
                int rawId = player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getId(sharpness.value());
                TraderRollControl.request(player, new TraderRollRequestPayload(panelMenu.containerId, rawId, "sharp"));
                return;
            }
            if (phase == 2) {
                if (panelMenu.rollRevision() < 1 && ++ticks < 1200) return;
                check(found && panelMenu.rollRevision() == 1 && panelMenu.rollAttempts() > 0 && roller.getOffers() != previousRollOffers,
                    "internal Library Roll finds new Sharpness V even when the worker already has it");
                check(player.containerMenu == panelMenu && panelMenu.getOffers() == libraryOffers,
                    "side-panel rolling leaves the main Library menu and aggregate offers untouched");
                check(EnchantmentHelper.getEnchantmentsForCrafting(panelMenu.matchedBook()).getLevel(sharpness) == 5,
                    "server exposes the exact found book for automatic client filtering");
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.level().registryAccess());
                LibraryTraderViewPayload snapshot = new LibraryTraderViewPayload(panelMenu.containerId, panelMenu.traderVillager(),
                    panelMenu.traderOffers(), panelMenu.traderSelection(), false, panelMenu.rollAttempts(), panelMenu.rollRevision(),
                    panelMenu.matchedBook(), panelMenu.rollStatus());
                LibraryTraderViewPayload.STREAM_CODEC.encode(buffer, snapshot);
                LibraryTraderViewPayload decoded = LibraryTraderViewPayload.STREAM_CODEC.decode(buffer);
                buffer.release();
                LibraryMarketMenu clientMirror = new LibraryMarketMenu(987, player.getInventory());
                clientMirror.setTraderPanelOpen(true); clientMirror.applyTraderView(decoded);
                check(clientMirror.panelAvailable() && clientMirror.traderOffers().size() == roller.getOffers().size()
                    && clientMirror.rollRevision() == 1 && EnchantmentHelper.getEnchantmentsForCrafting(clientMirror.matchedBook()).getLevel(sharpness) == 5,
                    "network roundtrip populates side-panel offers, villager, status and found-book client mirror");
                previousRollOffers = roller.getOffers(); phase = 3; ticks = 0;
                TraderRollControl.request(player, new TraderRollRequestPayload(panelMenu.containerId, -1, "sharp"));
                return;
            }
            if (phase == 3) {
                if (panelMenu.rollRevision() < 2 && ++ticks < 1200) return;
                check(panelMenu.rollRevision() == 2 && panelMenu.rollAttempts() > 0 && roller.getOffers() != previousRollOffers,
                    "partial text search also rerolls an already-matching book instead of accepting the old offer");
                phase = 4; ticks = 0;
                int protectionId = player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getId(player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).get(Enchantments.PROTECTION));
                TraderRollControl.request(player, new TraderRollRequestPayload(panelMenu.containerId, protectionId, "Protection IV"));
                return;
            }
            if (panelMenu.rollRevision() < 3 && ++ticks < 1200) return;
            var protection = player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.PROTECTION);
            var foundEnchantments = EnchantmentHelper.getEnchantmentsForCrafting(panelMenu.matchedBook());
            check(panelMenu.rollRevision() == 3 && foundEnchantments.size() == 1 && foundEnchantments.getLevel(protection) == 4,
                "exact Protection IV auto-roll finds plain Protection IV, not Blast, Fire or Projectile Protection");
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 64));
            player.getInventory().setItem(1, new ItemStack(Items.BOOK, 64));
            panelMenu.selectTraderOffer(panelMenu.traderSelection());
            check(panelMenu.slots.get(68).getItem().is(Items.ENCHANTED_BOOK), "side-panel selecting a trade fills its real native result slot");
            MerchantOffer bought = panelMenu.traderOffers().get(panelMenu.traderSelection());
            panelMenu.clicked(68, 0, ClickType.QUICK_MOVE, player);
            check(bought.getUses() > 0 && roller.getVillagerXp() > 0 && player.getInventory().contains(stack -> stack.is(Items.ENCHANTED_BOOK)),
                    "side-panel shift purchase charges real payments and updates internal worker stock and XP");
            MerchantOffers locked = roller.getOffers();
            TraderRollControl.request(player, new TraderRollRequestPayload(panelMenu.containerId, -1, "mending"));
            check(locked == roller.getOffers(), "side-panel rolling cannot erase a bought and locked trade");
            TraderRollControl.requestOnce(player, panelMenu.containerId);
            check(locked == roller.getOffers(), "manual single-roll cannot erase bought and locked trades either");
            panelMenu.closeTraderPanel();
            check(roller.getTradingPlayer() == null && panelMenu.slots.get(66).getItem().isEmpty()
                && panelMenu.slots.get(67).getItem().isEmpty() && panelMenu.slots.get(68).getItem().isEmpty(),
                "collapsing side panel releases the villager and returns remaining payments");
            check(testedLibrary.villagerCount() == 4 && testedLibrary.workstationItem().isEmpty() && panelMenu.slots.get(69).getItem().isEmpty(),
                "collapsing the worker panel returns exactly one villager to the archive");
            EasyVillagerEntity returnedWorker = VillagerData.getCacheVillager(testedLibrary.archiveItem(0), player.level());
            check(returnedWorker.getVillagerXp() > 0 && returnedWorker.getOffers().stream().anyMatch(offer -> offer.getUses() > 0),
                "returning the worker preserves newly rolled offers, earned XP and bought stock");
            panelMenu.openTraderPanel();
            check(panelMenu.rollerVillager() == null && !panelMenu.panelAvailable() && panelMenu.slots.get(69).isActive(),
                "auto-loader skips locked villagers but leaves an empty worker slot available");
            testedLibrary.addVillager(villager(player.serverLevel(), false)); panelMenu.broadcastChanges();
            check(panelMenu.rollerVillager() != null && testedLibrary.villagerCount() == 4 && testedLibrary.workstationItem().getCount() == 1,
                "an empty opened workstation loads a fresh villager added to the archive afterward");
            panelMenu.clicked(69, 0, ClickType.QUICK_MOVE, player); panelMenu.broadcastChanges();
            check(panelMenu.rollerVillager() == null && testedLibrary.workstationItem().isEmpty(),
                "manually removing a worker disables automatic stealing of another archive villager until reopen");
            panelMenu.setCarried(new ItemStack(Items.DIAMOND, 2));
            panelMenu.clicked(69, 0, ClickType.PICKUP, player);
            check(panelMenu.getCarried().getCount() == 2 && testedLibrary.workstationItem().isEmpty(), "worker slot rejects non-villagers without consuming them");
            ItemStack unemployed = new ItemStack(ModItems.VILLAGER.get());
            EasyVillagerEntity freshEntity = VillagerData.createEasyVillager(unemployed, player.level());
            freshEntity.setAge(0); freshEntity.setVillagerXp(0);
            freshEntity.setVillagerData(freshEntity.getVillagerData().setProfession(VillagerProfession.NONE));
            VillagerData.applyToItem(unemployed, freshEntity);
            panelMenu.setCarried(unemployed.copyWithCount(2));
            panelMenu.clicked(69, 0, ClickType.PICKUP, player); panelMenu.broadcastChanges();
            check(panelMenu.getCarried().getCount() == 1 && testedLibrary.workstationItem().getCount() == 1
                && panelMenu.rollerVillager().getVillagerData().getProfession() == VillagerProfession.LIBRARIAN,
                "manual worker insertion takes exactly one item and provides the Librarian job");
            player.getInventory().placeItemBackInInventory(panelMenu.getCarried()); panelMenu.setCarried(ItemStack.EMPTY);
            MerchantOffers beforeSingle = panelMenu.traderOffers();
            MerchantOffers marketBeforeSingle = panelMenu.getOffers();
            EasyVillagerEntity workerBeforeSingle = panelMenu.rollerVillager();
            int revisionBeforeSingle = panelMenu.rollRevision();
            panelMenu.slots.get(66).set(new ItemStack(Items.EMERALD, 8));
            panelMenu.slots.get(67).set(new ItemStack(Items.BOOK, 4));
            int emeraldsBeforeSingle = inventoryCount(Items.EMERALD) + 8;
            int booksBeforeSingle = inventoryCount(Items.BOOK) + 4;
            TraderRollControl.requestOnce(player, panelMenu.containerId + 1);
            check(panelMenu.traderOffers() == beforeSingle && panelMenu.slots.get(66).getItem().getCount() == 8,
                "stale manual Library roll leaves both offers and payments untouched");
            TraderRollControl.requestOnce(player, panelMenu.containerId);
            check(panelMenu.traderOffers() != beforeSingle && panelMenu.rollAttempts() == 1 && !panelMenu.isRolling()
                && panelMenu.rollStatus().equals("Rolled once."), "Library manual Roll generates exactly one offer set and stops immediately");
            check(panelMenu.rollRevision() == revisionBeforeSingle && panelMenu.matchedBook().isEmpty()
                && panelMenu.getOffers() == marketBeforeSingle, "single-roll does not trigger auto-roll filtering or mutate aggregate Library offers");
            check(panelMenu.rollerVillager() == workerBeforeSingle && workerBeforeSingle.getVillagerXp() == 0
                && testedLibrary.workstationItem().getCount() == 1, "single-roll preserves the same live villager without duplicating or locking it");
            check(panelMenu.slots.get(66).getItem().isEmpty() && panelMenu.slots.get(67).getItem().isEmpty()
                && panelMenu.slots.get(68).getItem().isEmpty() && inventoryCount(Items.EMERALD) == emeraldsBeforeSingle
                && inventoryCount(Items.BOOK) == booksBeforeSingle, "single-roll safely returns pending payments without item loss or free output");
            panelMenu.clicked(69, 0, ClickType.PICKUP, player); panelMenu.broadcastChanges();
            check(panelMenu.getCarried().is(ModItems.VILLAGER.get()) && testedLibrary.workstationItem().isEmpty()
                && panelMenu.traderOffers().isEmpty(), "manual worker withdrawal returns the item and clears stale panel trades");
            player.getInventory().placeItemBackInInventory(panelMenu.getCarried()); panelMenu.setCarried(ItemStack.EMPTY);
            player.getInventory().setItem(9, villager(player.serverLevel(), false).copyWithCount(2));
            panelMenu.clicked(3, 0, ClickType.QUICK_MOVE, player); panelMenu.broadcastChanges();
            check(testedLibrary.workstationItem().getCount() == 1 && testedLibrary.villagerCount() == 5
                && player.getInventory().getItem(9).isEmpty(), "Shift insertion fills the worker slot first and sends further villagers to the archive");
            EasyVillagerEntity finalWorker = panelMenu.rollerVillager();
            player.closeContainer();
            check(finalWorker.getTradingPlayer() == null && testedLibrary.workstationItem().isEmpty() && testedLibrary.villagerCount() == 6,
                "closing the whole Library releases both sessions and returns the manually inserted worker exactly once");
            roller = null;
            LogUtils.getLogger().info("LIBRARY INTEGRATION SUCCESS: {} checks passed", passed);
            event.getServer().halt(false);
        } catch (Throwable error) {
            roller = null;
            LogUtils.getLogger().error("LIBRARY INTEGRATION FAILED", error);
            event.getServer().halt(false);
        }
    }

    private static int inventoryCount(net.minecraft.world.item.Item item) {
        int count = 0;
        for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static final class TestPlayer extends FakePlayer {
        private int menuId;
        private TestPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "ArchiveTest")); }
        @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> extraData) {
            if (containerMenu != inventoryMenu) closeContainer();
            containerMenu = provider.createMenu(++menuId, getInventory(), this);
            return OptionalInt.of(menuId);
        }
        @Override public boolean hasDisconnected() { return false; }
        @Override public void displayClientMessage(Component message, boolean actionBar) {
            LogUtils.getLogger().info("TEST PLAYER MESSAGE: {}", message.getString());
        }
    }
}
