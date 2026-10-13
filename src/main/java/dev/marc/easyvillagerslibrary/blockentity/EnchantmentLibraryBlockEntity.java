package dev.marc.easyvillagerslibrary.blockentity;

import de.maxhenkel.easyvillagers.datacomponents.VillagerData;
import de.maxhenkel.easyvillagers.entity.EasyVillagerEntity;
import de.maxhenkel.easyvillagers.Main;
import dev.marc.easyvillagerslibrary.menu.EnchantmentLibraryMenu;
import dev.marc.easyvillagerslibrary.menu.LibraryMarketMenu;
import dev.marc.easyvillagerslibrary.merchant.LibraryMerchantControl;
import dev.marc.easyvillagerslibrary.merchant.LibraryRollOfferPreview;
import dev.marc.easyvillagerslibrary.network.LibraryMerchantViewPayload;
import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.lang.reflect.Method;

/** Unlimited persistent Easy Villager archive and the shared highest-book market. */
public final class EnchantmentLibraryBlockEntity extends BlockEntity implements MenuProvider {
    /** Five complete rows leave room for a readable catalogue footer and a normal player inventory. */
    public static final int SUMMARY_SLOTS = 45;
    /** Visible trader rows in the Books view; remaining catalogue slots are used by Villagers. */
    public static final int BOOK_TRADE_ROWS = 5;
    private static final String VILLAGERS_TAG = "StoredVillagers";
    private static final String ROLL_QUEUE_TAG = "PendingRollVillagers";
    private static final String ACTIVE_ROLLER_TAG = "ActiveRollVillager";
    private static final String ROLL_TARGET_TAG = "RollTarget";
    private static final int ROLL_INTERVAL_TICKS = 10;

    /** Finished villagers. Their maximum-level books make up the main merchant screen. */
    private final List<ItemStack> villagers = new ArrayList<>();
    // Easy Villagers' item component has a weak cache shared by ItemStack copies.
    // Each archived villager needs its own persistent server entity while this block is loaded.
    private final Map<ItemStack, EasyVillagerEntity> storedEntities = new IdentityHashMap<>();
    /** Fresh villagers waiting for their turn at the embedded rolling workstation. */
    private final List<ItemStack> pendingRollVillagers = new ArrayList<>();
    /** Exactly one fresh villager is visible in the right-hand auto-roll panel at a time. */
    private ItemStack activeRollVillager = ItemStack.EMPTY;
    /** Maps the currently visible villager cards back to their unlimited archive indices. */
    private final List<Integer> visibleVillagerIndices = new ArrayList<>();
    /** Maps the five visible book-trade rows to their source villagers. */
    private final List<Integer> visibleBookVillagerIndices = new ArrayList<>();
    private final SimpleContainer summaries = new SimpleContainer(SUMMARY_SLOTS);
    /** Read-only menu preview; clicking it with a carried book sets the target without consuming it. */
    private final SimpleContainer rollTargetPreview = new SimpleContainer(1);
    /**
     * Client-synchronised display slots for the one rolling villager: slot 0
     * is the villager, then five groups of cost A / cost B / result. They are
     * deliberately read-only; the roll workstation may not lock trades before
     * the requested book has been found.
     */
    private final SimpleContainer rollWorkstation = new SimpleContainer(16);
    private ItemStack rollTarget = ItemStack.EMPTY;
    private int rollCursor;
    private int rollTimer;
    /** Next shared work cycle for stored Librarians, using Easy Villagers' Trader timing. */
    private long nextLibraryRestock;
    private CatalogueView catalogueView = CatalogueView.BOOKS;
    private int summaryPage;
    private int summaryPages = 1;
    private ItemStack activeTradingStack = ItemStack.EMPTY;
    private EasyVillagerEntity activeTrader;
    /** The currently opened combined merchant, used only to refresh its side-panel state. */
    private LibraryBookMerchant activeBookMerchant;
    private int archiveRevision;
    private static final String WORKSTATION_TAG = "TraderWorkstationVillager";
    private ItemStack workstationVillager = ItemStack.EMPTY;
    private EasyVillagerEntity workstationEntity;
    private int workstationOrigin = -1;
    /** The native-looking merchant facade around the one auto-roll worker. */
    private LibraryAutoRollMerchant activeAutoRollMerchant;

    public EnchantmentLibraryBlockEntity(BlockPos pos, BlockState state) {
        super(LibraryRegistries.ENCHANTMENT_LIBRARY_BLOCK_ENTITY.get(), pos, state);
    }

    public int villagerCount() {
        return (int) villagers.stream().filter(stack -> !stack.isEmpty()).count();
    }

    public int archiveSize() { return villagers.size(); }
    public int archiveRevision() { return archiveRevision; }
    public ItemStack archiveItem(int index) {
        return index >= 0 && index < villagers.size() ? villagers.get(index) : ItemStack.EMPTY;
    }
    private EasyVillagerEntity storedVillager(ItemStack stack) {
        if (stack.isEmpty() || level == null || level.isClientSide) return null;
        return storedEntities.computeIfAbsent(stack, key -> VillagerData.createEasyVillager(key, level));
    }
    public void setArchiveItem(int index, ItemStack stack) {
        if (index < 0 || (stack.isEmpty() && index >= villagers.size())) return;
        if (!stack.isEmpty() && !stack.is(de.maxhenkel.easyvillagers.items.ModItems.VILLAGER.get())) return;
        // Stable indices: taking one villager out must not move every following slot.
        while (villagers.size() <= index) villagers.add(ItemStack.EMPTY);
        ItemStack previous = villagers.set(index, stack.isEmpty() ? ItemStack.EMPTY : stack);
        if (previous != stack) storedEntities.remove(previous);
        if (!stack.isEmpty()) makeLibrarianIfUnemployed(stack);
        while (!villagers.isEmpty() && villagers.getLast().isEmpty()) villagers.removeLast();
        archiveChanged();
    }
    public void archiveChanged() {
        archiveRevision++;
        setChanged();
        refreshSummaries();
    }
    public Player marketPlayer() { return activeBookMerchant == null ? null : activeBookMerchant.getTradingPlayer(); }
    public void refreshOpenMarket() {
        if (activeBookMerchant != null) activeBookMerchant.refreshMarket();
    }

    /** The worker is moved out of the archive, never copied into a second trading location. */
    public ItemStack workstationItem() { return workstationVillager; }
    public EasyVillagerEntity workstationEntity() {
        if (workstationVillager.isEmpty() || level == null || level.isClientSide) return null;
        if (workstationEntity == null) workstationEntity = VillagerData.createEasyVillager(workstationVillager, level);
        workstationEntity.setPos(worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5);
        return workstationEntity;
    }
    public void saveWorkstation() {
        if (!workstationVillager.isEmpty() && workstationEntity != null) persistVillager(workstationVillager, workstationEntity);
        setChanged();
    }
    public void setWorkstationItem(ItemStack stack) {
        if (!stack.isEmpty() && (!stack.is(de.maxhenkel.easyvillagers.items.ModItems.VILLAGER.get()) || stack.getCount() != 1)) return;
        if (workstationVillager == stack) { saveWorkstation(); return; }
        saveWorkstation();
        if (workstationEntity != null) workstationEntity.setTradingPlayer(null);
        workstationVillager = stack; workstationEntity = null; workstationOrigin = -1;
        EasyVillagerEntity villager = workstationEntity();
        if (villager != null && !villager.isBaby() && villager.getVillagerXp() == 0
            && villager.getVillagerData().getProfession() != VillagerProfession.NITWIT
            && villager.getVillagerData().getProfession() != VillagerProfession.LIBRARIAN) {
            villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.LIBRARIAN));
            villager.recalculateOffers();
        }
        saveWorkstation();
    }
    public boolean borrowFreshWorkstation() {
        if (!workstationVillager.isEmpty()) return true;
        for (int index = 0; index < villagers.size(); index++) {
            ItemStack stored = villagers.get(index);
            if (stored.isEmpty() || !isFreshRollCandidate(stored)) continue;
            EasyVillagerEntity villager = storedVillager(stored);
            persistVillager(stored, villager);
            // Clearing the archive invalidates its market offers before the worker is attached to the panel.
            setArchiveItem(index, ItemStack.EMPTY);
            workstationVillager = stored; workstationEntity = villager; workstationOrigin = index;
            saveWorkstation();
            return true;
        }
        return false;
    }
    public void returnWorkstationToArchive() {
        if (workstationVillager.isEmpty()) return;
        saveWorkstation();
        ItemStack returned = workstationVillager;
        int index = workstationOrigin;
        if (workstationEntity != null) {
            for (MerchantOffer offer : workstationEntity.getOffers()) offer.resetSpecialPriceDiff();
            workstationEntity.setTradingPlayer(null);
        }
        workstationVillager = ItemStack.EMPTY; workstationEntity = null; workstationOrigin = -1;
        if (index < 0 || !archiveItem(index).isEmpty()) {
            index = 0;
            while (index < villagers.size() && !villagers.get(index).isEmpty()) index++;
        }
        setArchiveItem(index, returned);
    }

    public int finishedVillagerCount() {
        return villagers.size();
    }

    public int pendingRollVillagerCount() {
        return 0;
    }

    public ItemStack activeRollVillager() {
        return activeRollVillager.copy();
    }

    /** A compact snapshot for the right-hand rolling trader preview. */
    public List<LibraryRollOfferPreview> activeRollOffers() {
        Level level = getLevel();
        if (level == null || level.isClientSide || activeRollVillager.isEmpty()) {
            return List.of();
        }
        EasyVillagerEntity villager = VillagerData.getCacheVillager(activeRollVillager, level);
        if (villager == null) {
            return List.of();
        }
        List<LibraryRollOfferPreview> offers = new ArrayList<>();
        for (MerchantOffer offer : villager.getOffers()) {
            offers.add(new LibraryRollOfferPreview(offer.getCostA().copy(), offer.getCostB().copy(), offer.getResult().copy()));
            if (offers.size() == 5) {
                break;
            }
        }
        return offers;
    }

    public SimpleContainer summaries() {
        return summaries;
    }

    public int summaryPage() {
        return summaryPage;
    }

    public int summaryPages() {
        return summaryPages;
    }

    public boolean isVillagerView() {
        return catalogueView == CatalogueView.VILLAGERS;
    }

    public boolean isBookView() {
        return catalogueView == CatalogueView.BOOKS;
    }

    public boolean isRollView() {
        return catalogueView == CatalogueView.ROLLS;
    }

    public int catalogueViewId() {
        return catalogueView.ordinal();
    }

    public SimpleContainer rollTargetPreview() {
        return rollTargetPreview;
    }

    public SimpleContainer rollWorkstation() {
        return rollWorkstation;
    }

    public void setVillagerView(boolean showVillagers) {
        CatalogueView requested = showVillagers ? CatalogueView.VILLAGERS : CatalogueView.BOOKS;
        if (catalogueView == requested) {
            return;
        }
        catalogueView = requested;
        summaryPage = 0;
        refreshSummaries();
    }

    /** Cycles the management screen through Books, Villagers and Auto-roll. */
    public void cycleCatalogueView(int delta) {
        CatalogueView[] views = CatalogueView.values();
        CatalogueView requested = views[Math.floorMod(catalogueView.ordinal() + delta, views.length)];
        if (catalogueView == requested) {
            return;
        }
        catalogueView = requested;
        summaryPage = 0;
        refreshSummaries();
    }

    /** Selects a named management tab directly, rather than requiring the player to infer arrow order. */
    public void setCatalogueView(int viewId) {
        CatalogueView[] views = CatalogueView.values();
        if (viewId < 0 || viewId >= views.length || catalogueView == views[viewId]) {
            return;
        }
        catalogueView = views[viewId];
        summaryPage = 0;
        refreshSummaries();
    }

    public void changeSummaryPage(int delta) {
        summaryPage = Math.clamp(summaryPage + delta, 0, Math.max(0, summaryPages - 1));
        refreshSummaries();
    }

    public boolean addVillager(ItemStack stack) {
        if (level == null || level.isClientSide || !stack.is(de.maxhenkel.easyvillagers.items.ModItems.VILLAGER.get())) {
            return false;
        }
        ItemStack stored = stack.copyWithCount(1);
        // Every inserted villager enters the archive. A worker is borrowed only when its panel is opened.
        int empty = 0;
        while (empty < villagers.size() && !villagers.get(empty).isEmpty()) empty++;
        setArchiveItem(empty, stored);
        return true;
    }

    private boolean isFreshRollCandidate(ItemStack villagerStack) {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return false;
        }
        EasyVillagerEntity villager = storedVillager(villagerStack);
        return villager != null && !villager.isBaby() && villager.getVillagerXp() == 0
            && villager.getVillagerData().getProfession() != VillagerProfession.NITWIT;
    }

    /**
     * The Library acts as the librarian's workstation, like an embedded
     * Lectern. Any unlevelled non-Nitwit can take the Librarian job; a
     * levelled villager keeps its profession and all earned trades.
     */
    private boolean makeLibrarianIfUnemployed(ItemStack villagerStack) {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return false;
        }
        EasyVillagerEntity villager = storedVillager(villagerStack);
        if (villager == null || villager.isBaby() || villager.getVillagerXp() > 0
            || villager.getVillagerData().getProfession() == VillagerProfession.NITWIT
            || villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN) {
            return false;
        }

        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.LIBRARIAN));
        villager.recalculateOffers();
        VillagerData.applyToItem(villagerStack, villager);
        return true;
    }

    public ItemStack removeLastVillager() {
        if (villagers.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = villagers.removeLast();
        refreshSummaries();
        syncBookMarketView();
        setChanged();
        return removed;
    }

    /** Removes one card from the right-hand merchant drawer and returns it to the player. */
    private boolean removeDrawerVillager(Player player, int displayIndex) {
        if (displayIndex < 0) {
            return false;
        }
        ItemStack removed = ItemStack.EMPTY;
        if (displayIndex < villagers.size()) {
            removed = villagers.remove(displayIndex);
        }
        if (removed.isEmpty()) {
            return false;
        }
        if (!player.getInventory().add(removed)) {
            player.drop(removed, false);
        }
        refreshSummaries();
        syncBookMarketView();
        setChanged();
        return true;
    }

    /** Receives one cursor-held Easy Villager from the Library's merchant drawer. */
    private boolean insertDrawerVillager(Player player, ItemStack stack) {
        return addVillager(stack);
    }

    /** Removes the exact Villager selected in the catalogue, rather than an arbitrary last entry. */
    public ItemStack removeVisibleVillager(int visibleSlot) {
        if (!isVillagerView() || visibleSlot < 0 || visibleSlot >= visibleVillagerIndices.size()) {
            return ItemStack.EMPTY;
        }
        int archiveIndex = visibleVillagerIndices.get(visibleSlot);
        if (archiveIndex < 0 || archiveIndex >= villagers.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = villagers.remove(archiveIndex);
        refreshSummaries();
        setChanged();
        return removed;
    }

    public List<ItemStack> removeAllVillagers() {
        returnWorkstationToArchive();
        List<ItemStack> removed = new ArrayList<>(villagers);
        villagers.clear();
        pendingRollVillagers.clear();
        activeRollVillager = ItemStack.EMPTY;
        summaries.clearContent();
        syncBookMarketView();
        setChanged();
        return removed;
    }

    /** Right-clicking a book sets an exact target; no book is consumed. */
    public boolean setRollTarget(ItemStack book) {
        if (!book.is(Items.ENCHANTED_BOOK) || EnchantmentHelper.getEnchantmentsForCrafting(book).isEmpty()) {
            return false;
        }
        rollTarget = book.copyWithCount(1);
        rollTargetPreview.setItem(0, rollTarget.copy());
        rollCursor = 0;
        ensureActiveRollVillager();
        refreshRollWorkstation();
        syncBookMarketView();
        setChanged();
        return true;
    }

    /**
     * Selects an enchantment from the Auto-roll search screen. The client sends
     * a registry id chosen from its filtered list; the server always resolves
     * and validates that id again before changing the stored target.
     */
    public boolean setRollTargetById(String enchantmentId) {
        Level level = getLevel();
        if (level == null || level.isClientSide || enchantmentId == null || enchantmentId.length() > 128) {
            return false;
        }
        String normalized = enchantmentId.trim().toLowerCase(Locale.ROOT);
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(normalized);
        if (id == null) {
            return false;
        }
        Registry<Enchantment> enchantments = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        return enchantments.getHolder(id)
            .map(holder -> setRollTarget(EnchantedBookItem.createForEnchantment(
                new EnchantmentInstance(holder, holder.value().getMaxLevel()))))
            .orElse(false);
    }

    /** Uses the registry id carried by a vanilla menu-button click. */
    public boolean setRollTargetByRawId(int rawId) {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return false;
        }
        Registry<Enchantment> enchantments = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Enchantment enchantment = enchantments.byId(rawId);
        if (enchantment == null) {
            return false;
        }
        net.minecraft.resources.ResourceLocation id = enchantments.getKey(enchantment);
        return id != null && setRollTargetById(id.toString());
    }

    /** Clears the target and stops auto-rolling; no player item is ever consumed. */
    public void clearRollTarget() {
        rollTarget = ItemStack.EMPTY;
        rollTargetPreview.setItem(0, ItemStack.EMPTY);
        rollCursor = 0;
        refreshRollWorkstation();
        syncBookMarketView();
        setChanged();
    }

    public ItemStack rollTarget() {
        return rollTarget;
    }

    /**
     * Handles the requested Shift-click directly on a stored villager card.
     * It opens Easy Villagers' own merchant screen, so the complete trade list
     * and player-specific modifiers such as the Artifacts Villager Hat apply.
     */
    public boolean openVisibleVillagerTrade(int visibleSlot, Player player) {
        if (!isVillagerView() || visibleSlot < 0 || visibleSlot >= visibleVillagerIndices.size()) {
            return false;
        }
        int archiveIndex = visibleVillagerIndices.get(visibleSlot);
        if (archiveIndex < 0 || archiveIndex >= villagers.size()) {
            return false;
        }
        return openVillagerTrade(villagers.get(archiveIndex), player);
    }

    /**
     * Opens one real merchant screen containing every highest-level book in the
     * library. The offers are the original Villager offers (not display copies),
     * so uses, stock and the Villager's stored data remain correct after a sale.
     */
    public boolean openVisibleBookTrade(int visibleRow, Player player) {
        if (isVillagerView() || visibleRow < 0 || visibleRow >= visibleBookVillagerIndices.size()) {
            return false;
        }
        return openBookMarket(player);
    }

    /** Opens the shared book merchant directly, for the Books tab button. */
    public boolean openBookMarket(Player player) {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return false;
        }
        if (activeBookMerchant != null && activeBookMerchant.getTradingPlayer() != player) {
            player.displayClientMessage(Component.literal("This Library is already being used."), true);
            return false;
        }
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer();

        // A Library is a merged merchant, so it must explicitly run the same
        // vanilla price calculation a real Villager runs when this player opens
        // a trade. This includes demand increases and modded reputation effects
        // such as the Artifacts Villager Hat.
        preparePricesFor(player);

        MerchantOffers sharedOffers = new MerchantOffers();
        Map<MerchantOffer, OfferSource> sourceByOffer = new IdentityHashMap<>();
        List<Integer> offerVillagerIndices = new ArrayList<>();
        for (BookOffer bookOffer : collectHighestBookOffers(level)) {
            int index = bookOffer.villagerIndex();
            if (index < 0 || index >= villagers.size()) {
                continue;
            }
            ItemStack stored = villagers.get(index);
            EasyVillagerEntity sourceVillager = storedVillager(stored);
            if (sourceVillager == null) {
                continue;
            }
            // Preserve the live offer object. MerchantMenu will therefore use
            // the same stock, original price and demand values as the villager.
            sharedOffers.add(bookOffer.sourceOffer());
            sourceByOffer.put(bookOffer.sourceOffer(), new OfferSource(stored, sourceVillager));
            offerVillagerIndices.add(index);
        }
        LibraryBookMerchant merchant = new LibraryBookMerchant(this, sharedOffers, sourceByOffer, offerVillagerIndices, level);
        // MerchantMenu normally obtains this from a real Villager. Setting it
        // here keeps the cached Easy Villagers in the same player-trading state.
        // Close the previous menu before marking entities as trading with the new merchant.
        // Otherwise MerchantMenu.removed can clear the new trading player.
        merchant.setTradingPlayer(player);
        var opened = player.openMenu(new SimpleMenuProvider(
            (id, inventory, owner) -> new LibraryMarketMenu(id, inventory, merchant, this),
            Component.literal("Enchantment Library")));
        activeBookMerchant = merchant;
        if (opened.isPresent()) player.sendMerchantOffers(opened.getAsInt(), sharedOffers, 1, 0, false, true);
        merchant.sendViewState(player);
        return true;
    }

    /** Opens a real merchant screen so all player-specific modifiers (including Artifacts' Villager Hat) apply. */
    public boolean openVillagerTrade(ItemStack villagerStack, Player player) {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return false;
        }
        if (activeTrader != null && activeTrader.isTrading()) {
            return false;
        }
        EasyVillagerEntity villager = storedVillager(villagerStack);
        if (villager == null || villager.isBaby()) {
            return false;
        }
        villager.setPos(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.0D, worldPosition.getZ() + 0.5D);
        if (!startNativeTrade(villager, player)) {
            return false;
        }
        activeTradingStack = villagerStack;
        activeTrader = villager;
        return true;
    }

    /** Opens the one fresh villager currently occupying the rolling workplace. */
    public boolean openActiveRollVillagerTrade(Player player) {
        Level level = getLevel();
        if (level == null || level.isClientSide || activeRollVillager.isEmpty()) {
            return false;
        }
        // The roller is deliberately just the real Easy Villagers trader for
        // this one fresh villager. It must never be a second fake or waiting
        // screen: the player sees the exact normal Librarian trades.
        makeLibrarianIfUnemployed(activeRollVillager);
        refreshRollWorkstation();
        return openVillagerTrade(activeRollVillager, player);
    }

    /**
     * Easy Villagers uses a package-private vanilla trade entrypoint. Invoking that exact entrypoint
     * preserves its standard player-reputation calculation, including Artifacts' Villager Hat bonus.
     */
    private static boolean startNativeTrade(EasyVillagerEntity villager, Player player) {
        for (Class<?> type = villager.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method startTrading = type.getDeclaredMethod("startTrading", Player.class);
                startTrading.setAccessible(true);
                startTrading.invoke(villager, player);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Continue through the vanilla villager hierarchy; the method is package-private.
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return false;
            }
        }
        return false;
    }

    /** Applies player-specific vanilla/modded trade modifiers to every archived Villager. */
    private void preparePricesFor(Player player) {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        for (ItemStack storedVillager : villagers) {
            if (storedVillager.isEmpty()) continue;
            EasyVillagerEntity villager = storedVillager(storedVillager);
            if (villager == null) {
                continue;
            }
            resetSpecialPrices(villager);
            updateSpecialPrices(villager, player);
        }
        refreshSummaries();
    }

    /** Mirrors Villager.stopTrading: temporary player discounts must not be saved into the item. */
    private static void resetSpecialPrices(EasyVillagerEntity villager) {
        invokeVillagerMethod(villager, "resetSpecialPrices");
    }

    /** Mirrors the private vanilla Villager.updateSpecialPrices(Player) entrypoint. */
    private static void updateSpecialPrices(EasyVillagerEntity villager, Player player) {
        invokeVillagerMethod(villager, "updateSpecialPrices", new Class<?>[]{Player.class}, player);
    }
    public static void applyPlayerPrices(EasyVillagerEntity villager, Player player) {
        resetSpecialPrices(villager);
        updateSpecialPrices(villager, player);
    }
    /** Store stock/demand/XP, but never store a player's temporary Hat/Hero discount in the villager item. */
    private static void persistVillager(ItemStack stack, EasyVillagerEntity villager) {
        MerchantOffers offers = villager.getOffers();
        int[] specialPrices = offers.stream().mapToInt(MerchantOffer::getSpecialPriceDiff).toArray();
        for (MerchantOffer offer : offers) offer.resetSpecialPriceDiff();
        try {
            VillagerData.applyToItem(stack, villager);
        } finally {
            for (int i = 0; i < offers.size(); i++) offers.get(i).setSpecialPriceDiff(specialPrices[i]);
        }
    }

    private static void invokeVillagerMethod(EasyVillagerEntity villager, String methodName) {
        invokeVillagerMethod(villager, methodName, new Class<?>[0]);
    }

    private static void invokeVillagerMethod(EasyVillagerEntity villager, String methodName, Class<?>[] parameterTypes,
                                              Object... arguments) {
        for (Class<?> type = villager.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                method.invoke(villager, arguments);
                return;
            } catch (NoSuchMethodException ignored) {
                // The relevant methods are private in vanilla Villager, so walk
                // through Easy Villagers' inheritance chain until we reach it.
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return;
            }
        }
    }

    public void tickServer() {
        if (activeTrader != null) {
            VillagerData.applyToItem(activeTradingStack, activeTrader);
            if (!activeTrader.isTrading()) {
                activeTrader = null;
                activeTradingStack = ItemStack.EMPTY;
                refreshSummaries();
                setChanged();
            }
        }
        restockStoredLibrarians();
    }

    /**
     * The Library is the stored villagers' workstation. It never replaces
     * offers: it only refills the use counters, exactly like an Easy Villagers
     * Trader does when its work cycle is due.
     */
    private void restockStoredLibrarians() {
        Level level = getLevel();
        if (level == null || level.isClientSide || villagers.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        if (nextLibraryRestock == 0L) {
            nextLibraryRestock = gameTime + nextLibraryRestockDelay(level);
            return;
        }
        if (gameTime < nextLibraryRestock) {
            return;
        }
        boolean changed = false;
        for (ItemStack stored : villagers) {
            if (stored.isEmpty()) continue;
            EasyVillagerEntity villager = storedVillager(stored);
            if (villager == null || villager.isTrading()
                || villager.getVillagerData().getProfession() != VillagerProfession.LIBRARIAN) {
                continue;
            }
            villager.restock();
            VillagerData.applyToItem(stored, villager);
            changed = true;
        }
        nextLibraryRestock = gameTime + nextLibraryRestockDelay(level);
        if (changed) {
            refreshSummaries();
            syncBookMarketView();
            setChanged();
        }
    }

    private static long nextLibraryRestockDelay(Level level) {
        int minimum = Main.SERVER_CONFIG.traderMinRestockTime.get();
        int maximum = Main.SERVER_CONFIG.traderMaxRestockTime.get();
        return minimum + level.random.nextInt(Math.max(1, maximum - minimum));
    }

    /** Pulls exactly one fresh villager into the visible rolling workplace. */
    private boolean ensureActiveRollVillager() {
        if (!activeRollVillager.isEmpty() || pendingRollVillagers.isEmpty()) {
            return false;
        }
        activeRollVillager = pendingRollVillagers.removeFirst();
        rollTimer = 0;
        return true;
    }

    /** Moves the successful librarian into the ordinary combined trade market. */
    private void finishActiveRollVillager() {
        if (activeRollVillager.isEmpty()) {
            return;
        }
        villagers.add(activeRollVillager);
        activeRollVillager = ItemStack.EMPTY;
        rollTimer = 0;
        ensureActiveRollVillager();
        refreshSummaries();
        setChanged();
        // Merchant offers are transferred to the client only when a merchant
        // menu opens. Reopen the same combined Library menu once here, so the
        // newly successful librarian appears in the *main* normal trade list
        // immediately rather than only after the player closes and reopens it.
        reopenBookMarketAfterCompletedRoll();
        syncBookMarketView();
    }

    private void syncBookMarketView() {
        if (activeBookMerchant != null) {
            activeBookMerchant.sendViewStateToTradingPlayer();
        }
    }

    private void reopenBookMarketAfterCompletedRoll() {
        LibraryBookMerchant previousMerchant = activeBookMerchant;
        if (previousMerchant == null || !(previousMerchant.getTradingPlayer() instanceof ServerPlayer player)) {
            return;
        }
        // Clearing first keeps the closing old Merchant from clearing the new
        // reference during openTradingScreen.
        activeBookMerchant = null;
        openBookMarket(player);
    }

    public void refreshSummaries() {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        refreshRollWorkstation();
        if (isVillagerView()) {
            refreshVillagerSummaries();
            return;
        }
        if (isRollView()) {
            summaryPages = 1;
            summaryPage = 0;
            summaries.clearContent();
            rollTargetPreview.setItem(0, rollTarget.isEmpty() ? ItemStack.EMPTY : rollTarget.copy());
            return;
        }

        List<BookOffer> offers = collectHighestBookOffers(level);
        visibleBookVillagerIndices.clear();
        Map<BookKey, Integer> bestPrices = new HashMap<>();
        for (BookOffer offer : offers) {
            bestPrices.merge(BookKey.of(offer.enchantment(), offer.level()), offer.emeralds(), Math::min);
        }
        offers.sort(Comparator.comparing((BookOffer offer) -> offer.enchantment().value().description().getString()).thenComparingInt(BookOffer::emeralds));
        summaryPages = Math.max(1, (offers.size() + BOOK_TRADE_ROWS - 1) / BOOK_TRADE_ROWS);
        summaryPage = Math.min(summaryPage, summaryPages - 1);
        summaries.clearContent();
        int start = summaryPage * BOOK_TRADE_ROWS;
        for (int index = 0; index < Math.min(BOOK_TRADE_ROWS, offers.size() - start); index++) {
            BookOffer offer = offers.get(start + index);
            boolean cheapest = offer.emeralds() == bestPrices.get(BookKey.of(offer.enchantment(), offer.level()));
            ItemStack display = offer.book();
            display.set(DataComponents.CUSTOM_NAME, Enchantment.getFullname(offer.enchantment(), offer.level()).copy()
                .append(" | " + (cheapest ? "BEST " : "MORE ") + offer.currentEmeralds()
                    + " | Original " + offer.emeralds() + " | Villager #" + (offer.villagerIndex() + 1)));
            summaries.setItem(index, display);
            visibleBookVillagerIndices.add(offer.villagerIndex());
        }
    }

    private void refreshRollWorkstation() {
        rollWorkstation.clearContent();
        if (activeRollVillager.isEmpty()) {
            return;
        }
        rollWorkstation.setItem(0, activeRollVillager.copy());
        List<LibraryRollOfferPreview> offers = activeRollOffers();
        for (int index = 0; index < offers.size() && index < 5; index++) {
            LibraryRollOfferPreview offer = offers.get(index);
            int slot = 1 + index * 3;
            rollWorkstation.setItem(slot, offer.costA());
            rollWorkstation.setItem(slot + 1, offer.costB());
            rollWorkstation.setItem(slot + 2, offer.result());
        }
    }

    /** Collects sellable maximum-level book offers while retaining their real MerchantOffer objects. */
    private List<BookOffer> collectHighestBookOffers(Level level) {
        List<BookOffer> offers = new ArrayList<>();
        boolean updatedVillager = false;
        for (int villagerIndex = 0; villagerIndex < villagers.size(); villagerIndex++) {
            ItemStack storedVillager = villagers.get(villagerIndex);
            if (storedVillager.isEmpty()) continue;
            updatedVillager |= makeLibrarianIfUnemployed(storedVillager);
            EasyVillagerEntity villager = storedVillager(storedVillager);
            if (villager == null) {
                continue;
            }
            for (MerchantOffer offer : villager.getOffers()) {
                ItemStack result = offer.getResult();
                if (!result.is(Items.ENCHANTED_BOOK) || !offer.getBaseCostA().is(Items.EMERALD)) {
                    continue;
                }
                for (Object2IntMap.Entry<Holder<Enchantment>> entry : EnchantmentHelper.getEnchantmentsForCrafting(result).entrySet()) {
                    if (entry.getIntValue() == entry.getKey().value().getMaxLevel()) {
                        // Compare the original roll, not temporary curing or reputation discounts.
                        offers.add(new BookOffer(result.copy(), entry.getKey(), entry.getIntValue(),
                            offer.getBaseCostA().getCount(), offer.getCostA().getCount(), villagerIndex, offer));
                    }
                }
            }
        }
        if (updatedVillager) {
            setChanged();
        }
        return offers;
    }

    /** Builds a paged view of every stored villager without imposing a storage limit. */
    private void refreshVillagerSummaries() {
        summaryPages = Math.max(1, (villagers.size() + SUMMARY_SLOTS - 1) / SUMMARY_SLOTS);
        summaryPage = Math.min(summaryPage, summaryPages - 1);
        summaries.clearContent();
        visibleVillagerIndices.clear();

        int start = summaryPage * SUMMARY_SLOTS;
        int end = Math.min(villagers.size(), start + SUMMARY_SLOTS);
        for (int archiveIndex = start; archiveIndex < end; archiveIndex++) {
            ItemStack display = villagers.get(archiveIndex).copyWithCount(1);
            if (display.isEmpty()) continue;
            int visibleSlot = visibleVillagerIndices.size();
            display.set(DataComponents.CUSTOM_NAME, Component.literal("Villager #" + (archiveIndex + 1))
                .append(" | Shift-click: trades"));
            summaries.setItem(visibleSlot, display);
            visibleVillagerIndices.add(archiveIndex);
        }
    }

    private boolean hasTargetTrade(EasyVillagerEntity villager) {
        Object2IntMap.Entry<Holder<Enchantment>> target = EnchantmentHelper.getEnchantmentsForCrafting(rollTarget).entrySet().iterator().next();
        String targetId = BookKey.of(target.getKey(), target.getIntValue()).enchantmentId();
        for (MerchantOffer offer : villager.getOffers()) {
            if (!offer.getResult().is(Items.ENCHANTED_BOOK) || !offer.getBaseCostA().is(Items.EMERALD)) {
                continue;
            }
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : EnchantmentHelper.getEnchantmentsForCrafting(offer.getResult()).entrySet()) {
                if (entry.getIntValue() >= target.getIntValue()
                    && BookKey.of(entry.getKey(), entry.getIntValue()).enchantmentId().equals(targetId)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // An interrupted UI session (including mining while open) returns its saved worker to the archive.
        if (level != null && !level.isClientSide) returnWorkstationToArchive();
        // Migrate any villagers that an older version placed in the retired
        // internal roller. Nothing is discarded; they become normal archive
        // entries as soon as their Library is loaded.
        if (!activeRollVillager.isEmpty()) {
            villagers.add(activeRollVillager);
            activeRollVillager = ItemStack.EMPTY;
        }
        if (!pendingRollVillagers.isEmpty()) {
            villagers.addAll(pendingRollVillagers);
            pendingRollVillagers.clear();
        }
        rollTarget = ItemStack.EMPTY;
        rollTargetPreview.setItem(0, ItemStack.EMPTY);
        rollWorkstation.clearContent();
        refreshSummaries();
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.easy_villagers_enchantment_library.enchantment_library")
            .append(" (" + villagerCount() + " villagers)");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, net.minecraft.world.entity.player.Player player) {
        preparePricesFor(player);
        return new EnchantmentLibraryMenu(containerId, playerInventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag stored = new ListTag();
        for (ItemStack villager : villagers) {
            EasyVillagerEntity entity = storedEntities.get(villager);
            if (entity != null) persistVillager(villager, entity);
            stored.add(villager.isEmpty() ? new CompoundTag() : villager.save(registries));
        }
        tag.put(VILLAGERS_TAG, stored);
        if (!workstationVillager.isEmpty() && workstationEntity != null) persistVillager(workstationVillager, workstationEntity);
        if (!workstationVillager.isEmpty()) {
            tag.put(WORKSTATION_TAG, workstationVillager.save(registries));
            tag.putInt("TraderWorkstationOrigin", workstationOrigin);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        villagers.clear();
        storedEntities.clear();
        workstationVillager = tag.contains(WORKSTATION_TAG, Tag.TAG_COMPOUND)
            ? ItemStack.parse(registries, tag.getCompound(WORKSTATION_TAG)).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        workstationEntity = null;
        workstationOrigin = tag.contains("TraderWorkstationOrigin") ? tag.getInt("TraderWorkstationOrigin") : -1;
        for (Tag value : tag.getList(VILLAGERS_TAG, Tag.TAG_COMPOUND)) {
            villagers.add(((CompoundTag) value).isEmpty() ? ItemStack.EMPTY
                : ItemStack.parse(registries, (CompoundTag) value).orElse(ItemStack.EMPTY));
        }
        pendingRollVillagers.clear();
        for (Tag value : tag.getList(ROLL_QUEUE_TAG, Tag.TAG_COMPOUND)) {
            ItemStack.parse(registries, (CompoundTag) value).ifPresent(pendingRollVillagers::add);
        }
        activeRollVillager = tag.contains(ACTIVE_ROLLER_TAG, Tag.TAG_COMPOUND)
            ? ItemStack.parse(registries, tag.getCompound(ACTIVE_ROLLER_TAG)).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
        rollTarget = tag.contains(ROLL_TARGET_TAG, Tag.TAG_COMPOUND)
            ? ItemStack.parse(registries, tag.getCompound(ROLL_TARGET_TAG)).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
        rollTargetPreview.setItem(0, rollTarget.isEmpty() ? ItemStack.EMPTY : rollTarget.copy());
        refreshRollWorkstation();
        refreshSummaries();
    }

    private record BookOffer(ItemStack book, Holder<Enchantment> enchantment, int level, int emeralds, int currentEmeralds,
                             int villagerIndex,
                             MerchantOffer sourceOffer) {
    }

    private record OfferSource(ItemStack storedStack, EasyVillagerEntity villager) {
    }

    /**
     * A real MerchantMenu facade for the single fresh worker. It makes the
     * roller look and behave like the ordinary Easy Villagers trader while the
     * Library remains authoritative for the requested enchantment and queue.
     */
    private static final class LibraryAutoRollMerchant implements Merchant, LibraryMerchantControl {
        private final EnchantmentLibraryBlockEntity library;
        private final EasyVillagerEntity villager;
        private final ItemStack storedStack;
        private final Level level;
        private Player tradingPlayer;

        private LibraryAutoRollMerchant(EnchantmentLibraryBlockEntity library, EasyVillagerEntity villager,
                                        ItemStack storedStack, Level level) {
            this.library = library;
            this.villager = villager;
            this.storedStack = storedStack;
            this.level = level;
        }

        @Override
        public void setTradingPlayer(Player player) {
            tradingPlayer = player;
            villager.setTradingPlayer(player);
            if (player == null) {
                resetSpecialPrices(villager);
                VillagerData.applyToItem(storedStack, villager);
                if (library.activeAutoRollMerchant == this) {
                    library.activeAutoRollMerchant = null;
                }
                library.refreshSummaries();
                library.setChanged();
            }
        }

        @Override
        public Player getTradingPlayer() {
            return tradingPlayer;
        }

        @Override
        public MerchantOffers getOffers() {
            return villager.getOffers();
        }

        @Override
        public void overrideOffers(MerchantOffers offers) {
            villager.overrideOffers(offers);
        }

        @Override
        public void notifyTrade(MerchantOffer offer) {
            villager.notifyTrade(offer);
            VillagerData.applyToItem(storedStack, villager);
            library.refreshSummaries();
            library.setChanged();
        }

        @Override
        public void notifyTradeUpdated(ItemStack stack) {
            // MerchantMenu refreshes the output itself.
        }

        @Override
        public int getVillagerXp() {
            return villager.getVillagerXp();
        }

        @Override
        public void overrideXp(int xp) {
            villager.setVillagerXp(xp);
        }

        @Override
        public boolean showProgressBar() {
            return villager.showProgressBar();
        }

        @Override
        public SoundEvent getNotifyTradeSound() {
            return villager.getNotifyTradeSound();
        }

        @Override
        public boolean isClientSide() {
            return level.isClientSide;
        }

        @Override
        public boolean easyVillagersLibrary$setRollTargetByRawId(int rawRegistryId) {
            boolean changed = library.setRollTargetByRawId(rawRegistryId);
            if (changed && tradingPlayer instanceof ServerPlayer player) {
                player.displayClientMessage(Component.literal("Auto-roll target set"), true);
            }
            return changed;
        }

        @Override
        public boolean easyVillagersLibrary$openActiveRoller(Player player) {
            return false;
        }

        @Override
        public boolean easyVillagersLibrary$removeDrawerVillager(Player player, int displayIndex) {
            return false;
        }

        @Override
        public boolean easyVillagersLibrary$insertDrawerVillager(Player player, ItemStack stack) {
            return false;
        }
    }

    /**
     * Server-only Merchant facade for the Books tab. It intentionally delegates
     * each completed sale to its source Easy Villager, then writes that entity
     * back into the corresponding Easy Villager item.
     */
    private static final class LibraryBookMerchant implements Merchant, LibraryMerchantControl {
        private final EnchantmentLibraryBlockEntity library;
        private MerchantOffers offers;
        private final Map<MerchantOffer, OfferSource> sourceByOffer;
        private final List<Integer> offerVillagerIndices;
        private final Level level;
        private Player tradingPlayer;

        private LibraryBookMerchant(EnchantmentLibraryBlockEntity library, MerchantOffers offers,
                                    Map<MerchantOffer, OfferSource> sourceByOffer, List<Integer> offerVillagerIndices,
                                    Level level) {
            this.library = library;
            this.offers = offers;
            this.sourceByOffer = sourceByOffer;
            this.offerVillagerIndices = new ArrayList<>(offerVillagerIndices);
            this.level = level;
        }

        @Override
        public void setTradingPlayer(Player player) {
            tradingPlayer = player;
            for (OfferSource source : sourceByOffer.values()) {
                source.villager().setTradingPlayer(player);
            }
            if (player == null) {
                for (OfferSource source : sourceByOffer.values()) {
                    resetSpecialPrices(source.villager());
                }
                persistSources();
                if (library.activeBookMerchant == this) {
                    library.activeBookMerchant = null;
                }
            }
        }

        @Override
        public Player getTradingPlayer() {
            return tradingPlayer;
        }

        @Override
        public MerchantOffers getOffers() {
            return offers;
        }

        @Override
        public void overrideOffers(MerchantOffers offers) {
            this.offers = offers;
        }

        @Override
        public void notifyTrade(MerchantOffer offer) {
            OfferSource source = sourceByOffer.get(offer);
            if (source == null) {
                return;
            }
            source.villager().notifyTrade(offer);
            persistVillager(source.storedStack(), source.villager());
            library.refreshSummaries();
            library.setChanged();
            if (tradingPlayer instanceof ServerPlayer player) {
                player.sendMerchantOffers(player.containerMenu.containerId, offers, 1, 0, false, true);
            }
        }

        @Override
        public void notifyTradeUpdated(ItemStack stack) {
            // The vanilla MerchantMenu already updates its output preview.
        }

        @Override
        public int getVillagerXp() {
            return 0;
        }

        @Override
        public void overrideXp(int xp) {
            // The individual source villagers own their experience values.
        }

        @Override
        public boolean showProgressBar() {
            return false;
        }

        @Override
        public SoundEvent getNotifyTradeSound() {
            return SoundEvents.VILLAGER_YES;
        }

        @Override
        public boolean isClientSide() {
            return level.isClientSide;
        }

        @Override
        public boolean easyVillagersLibrary$setRollTargetByRawId(int rawRegistryId) {
            boolean changed = library.setRollTargetByRawId(rawRegistryId);
            if (changed && tradingPlayer != null) {
                sendViewState(tradingPlayer);
            }
            return changed;
        }

        @Override
        public boolean easyVillagersLibrary$openActiveRoller(Player player) {
            return library.openActiveRollVillagerTrade(player);
        }

        @Override
        public boolean easyVillagersLibrary$removeDrawerVillager(Player player, int displayIndex) {
            return library.removeDrawerVillager(player, displayIndex);
        }

        @Override
        public boolean easyVillagersLibrary$insertDrawerVillager(Player player, ItemStack stack) {
            return library.insertDrawerVillager(player, stack);
        }

        private void sendViewState(Player player) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            // Archive entries stay first because offerVillagerIndices refer to
            // these exact positions. Rolling is handled by a neighbouring
            // Easy Villagers Trader, never by a hidden Library worker.
            // Actual inventory slots synchronize only the current page. Do not send
            // the unlimited archive as one giant custom packet for every insertion.
            List<ItemStack> copies = List.of();
            PacketDistributor.sendToPlayer(serverPlayer, new LibraryMerchantViewPayload(
                serverPlayer.containerMenu.containerId, copies, offerVillagerIndices, ItemStack.EMPTY,
                ItemStack.EMPTY, 0, List.of()));
        }

        private void sendViewStateToTradingPlayer() {
            if (tradingPlayer != null) {
                sendViewState(tradingPlayer);
            }
        }

        private void refreshMarket() {
            // Persist/reset old sources before resolving the archive's current contents.
            for (OfferSource source : sourceByOffer.values()) {
                source.villager().setTradingPlayer(null);
                resetSpecialPrices(source.villager());
                persistVillager(source.storedStack(), source.villager());
            }
            library.preparePricesFor(tradingPlayer);
            offers.clear();
            sourceByOffer.clear();
            offerVillagerIndices.clear();
            for (BookOffer book : library.collectHighestBookOffers(level)) {
                ItemStack stored = library.villagers.get(book.villagerIndex());
                EasyVillagerEntity entity = library.storedVillager(stored);
                if (entity == null) continue;
                offers.add(book.sourceOffer());
                sourceByOffer.put(book.sourceOffer(), new OfferSource(stored, entity));
                offerVillagerIndices.add(book.villagerIndex());
                entity.setTradingPlayer(tradingPlayer);
            }
            if (tradingPlayer instanceof ServerPlayer player) {
                player.sendMerchantOffers(player.containerMenu.containerId, offers, 1, 0, false, true);
                sendViewState(player);
            }
        }

        private void persistSources() {
            for (OfferSource source : sourceByOffer.values()) {
                persistVillager(source.storedStack(), source.villager());
            }
            library.refreshSummaries();
            library.setChanged();
        }
    }

    private record BookKey(String enchantmentId, int level) {
        static BookKey of(Holder<Enchantment> enchantment, int level) {
            return new BookKey(enchantment.unwrapKey().map(key -> key.location().toString()).orElse("unknown"), level);
        }
    }

    private enum CatalogueView {
        BOOKS,
        VILLAGERS,
        ROLLS
    }
}
