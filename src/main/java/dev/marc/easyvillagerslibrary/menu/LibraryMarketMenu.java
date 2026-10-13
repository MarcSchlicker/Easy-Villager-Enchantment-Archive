package dev.marc.easyvillagerslibrary.menu;

import de.maxhenkel.easyvillagers.items.ModItems;
import de.maxhenkel.easyvillagers.entity.EasyVillagerEntity;
import dev.marc.easyvillagerslibrary.network.LibraryTraderViewPayload;
import dev.marc.easyvillagerslibrary.mixin.LibrarySlotPositionAccess;
import dev.marc.easyvillagerslibrary.blockentity.EnchantmentLibraryBlockEntity;
import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.ClientSideMerchant;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Native merchant payments/results, with real paged archive slots rather than clickable pictures. */
public final class LibraryMarketMenu extends MerchantMenu {
    public static final int ARCHIVE_START = 39;
    public static final int PAGE_SIZE = 27;
    public static final int PAGE_PREVIOUS = 0;
    public static final int PAGE_NEXT = 1;
    public static final int PAGE_SELECT_OFFSET = 2;
    public static final int TRADER_START = ARCHIVE_START + PAGE_SIZE;
    public static final int WORKER_SLOT = TRADER_START + 3;
    private final EnchantmentLibraryBlockEntity library;
    private final SimpleContainer archive;
    private final DataSlot page = DataSlot.standalone();
    private final DataSlot pages = DataSlot.standalone();
    private int revision;
    private boolean drawerOpen;
    private final Player owner;
    private final ClientSideMerchant panelMerchant;
    private final MerchantContainer panelTrade;
    private EasyVillagerEntity activePanelVillager;
    private final SimpleContainer workerInput;
    private boolean traderPanelOpen;
    private boolean autoWaiting;
    private ItemStack panelVillager = ItemStack.EMPTY;
    private ItemStack matchedBook = ItemStack.EMPTY;
    private String rollStatus = "Insert a villager, or add fresh villagers to the Library.";
    private boolean rolling;
    private int rollAttempts;
    private int rollRevision;
    private int panelSelection = -1;
    private int panelFingerprint;
    private final MerchantOffers emptyTraderOffers = new MerchantOffers();

    public LibraryMarketMenu(int id, Inventory inventory) {
        this(id, inventory, new ClientSideMerchant(inventory.player), null);
    }

    public LibraryMarketMenu(int id, Inventory inventory, Merchant merchant, EnchantmentLibraryBlockEntity library) {
        super(id, inventory, merchant);
        this.library = library;
        owner = inventory.player;
        panelMerchant = new ClientSideMerchant(owner) {
            @Override public MerchantOffers getOffers() {
                return library == null ? super.getOffers() : rollerVillager() == null ? emptyTraderOffers : activePanelVillager.getOffers();
            }
            @Override public void notifyTrade(MerchantOffer offer) {
                if (rollerVillager() != null) activePanelVillager.notifyTrade(offer);
            }
            @Override public int getVillagerXp() { return rollerVillager() == null ? super.getVillagerXp() : activePanelVillager.getVillagerXp(); }
            @Override public void overrideXp(int xp) {
                if (rollerVillager() != null) { activePanelVillager.overrideXp(xp); saveRoller(); }
                else super.overrideXp(xp);
            }
        };
        panelTrade = new MerchantContainer(panelMerchant);
        archive = library == null ? new SimpleContainer(PAGE_SIZE) : new ArchiveContainer();
        addDataSlot(page);
        addDataSlot(pages);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(archive, row * 9 + column, 288 + column * 18, 28 + row * 18) {
                    @Override public boolean mayPlace(ItemStack stack) { return stack.is(ModItems.VILLAGER.get()); }
                    @Override public int getMaxStackSize() { return 1; }
                    @Override public boolean isActive() { return library != null || drawerOpen; }
                });
            }
        }
        addSlot(new Slot(panelTrade, 0, 395, 58) {
            @Override public boolean mayPlace(ItemStack stack) { return panelAvailable(); }
            @Override public boolean mayPickup(Player player) { return panelAvailable(); }
            @Override public boolean isActive() { return traderPanelOpen; }
        });
        addSlot(new Slot(panelTrade, 1, 421, 58) {
            @Override public boolean mayPlace(ItemStack stack) { return panelAvailable(); }
            @Override public boolean mayPickup(Player player) { return panelAvailable(); }
            @Override public boolean isActive() { return traderPanelOpen; }
        });
        addSlot(new MerchantResultSlot(owner, panelMerchant, panelTrade, 2, 479, 58) {
            @Override public boolean mayPickup(Player player) { return panelAvailable() && !rolling; }
            @Override public boolean isActive() { return traderPanelOpen; }
        });
        workerInput = library == null ? new SimpleContainer(1) : new WorkstationContainer();
        addSlot(new Slot(workerInput, 0, 393, 32) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.is(ModItems.VILLAGER.get()); }
            @Override public int getMaxStackSize() { return 1; }
            @Override public boolean isActive() { return traderPanelOpen; }
        });
        updatePageCount();
        revision = library == null ? 0 : library.archiveRevision();
    }

    @Override public MenuType<?> getType() { return LibraryRegistries.LIBRARY_MARKET_MENU.get(); }
    public int archivePage() { return page.get(); }
    public int archivePages() { return Math.max(1, pages.get()); }
    public void setDrawerOpen(boolean open) { drawerOpen = open; }
    public void setTraderPanelOpen(boolean open) { traderPanelOpen = open; }
    public void positionTraderSlots(int yOffset) {
        panelYOffset = yOffset;
        for (int index = TRADER_START; index < TRADER_START + 3; index++)
            ((LibrarySlotPositionAccess) slots.get(index)).easyVillagersLibrary$setY(58 + yOffset);
        ((LibrarySlotPositionAccess) slots.get(WORKER_SLOT)).easyVillagersLibrary$setY(32 + yOffset);
    }
    private int panelYOffset;
    public int panelYOffset() { return panelYOffset; }
    public MerchantOffers traderOffers() { return panelMerchant.getOffers(); }
    public ItemStack traderVillager() { return panelVillager; }
    public int traderSelection() { return panelSelection; }
    public boolean isRolling() { return rolling; }
    public int rollAttempts() { return rollAttempts; }
    public int rollRevision() { return rollRevision; }
    public ItemStack matchedBook() { return matchedBook; }
    public String rollStatus() { return rollStatus; }
    public boolean panelAvailable() { return traderPanelOpen && (library == null ? !panelVillager.isEmpty() : rollerVillager() != null); }
    public EasyVillagerEntity rollerVillager() {
        if (library == null || library.isRemoved() || activePanelVillager == null
            || library.workstationEntity() != activePanelVillager || activePanelVillager.getTradingPlayer() != owner) return null;
        return activePanelVillager;
    }
    public void openTraderPanel() {
        if (library == null) return;
        traderPanelOpen = true;
        autoWaiting = library.workstationItem().isEmpty() && !library.borrowFreshWorkstation();
        refreshArchiveMarket();
        refreshTrader();
        sendTraderView();
    }
    public void closeTraderPanel() {
        if (library == null) return;
        traderPanelOpen = false;
        autoWaiting = false;
        detachTrader();
        library.returnWorkstationToArchive();
        refreshArchiveMarket();
        sendTraderView();
        super.broadcastChanges();
    }
    private void refreshTrader() {
        if (!traderPanelOpen || library == null) return;
        if (rollerVillager() != null) return;
        if (autoWaiting && library.workstationItem().isEmpty() && library.borrowFreshWorkstation()) {
            autoWaiting = false;
            refreshArchiveMarket();
        }
        detachTrader();
        EasyVillagerEntity villager = library.workstationEntity();
        if (villager == null) return;
        activePanelVillager = villager;
        villager.setTradingPlayer(owner);
        EnchantmentLibraryBlockEntity.applyPlayerPrices(villager, owner);
        panelVillager = library.workstationItem().copy();
        panelSelection = villager.getOffers().isEmpty() ? -1 : 0;
        panelTrade.setSelectionHint(Math.max(0, panelSelection));
        rollStatus = "Roll once, or choose a book for Auto-roll.";
    }
    private void returnTraderPayments() {
        if (library == null) return;
        for (int slot = 0; slot < 2; slot++) {
            ItemStack payment = panelTrade.removeItemNoUpdate(slot);
            if (!payment.isEmpty()) {
                if (owner.isAlive() && !(owner instanceof ServerPlayer server && server.hasDisconnected())) owner.getInventory().placeItemBackInInventory(payment);
                else owner.drop(payment, false);
            }
        }
        panelTrade.setItem(2, ItemStack.EMPTY);
        panelTrade.updateSellItem();
    }
    public void saveRoller() {
        if (library != null) library.saveWorkstation();
    }
    private void detachTrader() {
        returnTraderPayments();
        if (activePanelVillager != null && activePanelVillager.getTradingPlayer() == owner) {
            saveRoller();
            for (MerchantOffer offer : activePanelVillager.getOffers()) offer.resetSpecialPriceDiff();
            activePanelVillager.setTradingPlayer(null);
        }
        activePanelVillager = null; panelVillager = ItemStack.EMPTY; panelSelection = -1; rolling = false;
        rollStatus = "Insert a villager, or add fresh villagers to the Library.";
    }
    public void selectTraderOffer(int index) {
        if (!panelAvailable() || rolling || index < 0 || index >= traderOffers().size()) return;
        panelSelection = index;
        returnTraderPayments();
        panelTrade.setSelectionHint(index);
        MerchantOffer offer = traderOffers().get(index);
        fillTraderPayment(0, offer.getItemCostA());
        offer.getItemCostB().ifPresent(cost -> fillTraderPayment(1, cost));
        panelTrade.updateSellItem();
        if (library != null) { sendTraderView(); super.broadcastChanges(); }
    }
    private void fillTraderPayment(int slot, ItemCost cost) {
        for (int index = 3; index < ARCHIVE_START; index++) {
            ItemStack stack = slots.get(index).getItem();
            if (stack.isEmpty() || !cost.test(stack)) continue;
            ItemStack payment = panelTrade.getItem(slot);
            if (!payment.isEmpty() && !ItemStack.isSameItemSameComponents(payment, stack)) continue;
            int moved = Math.min(stack.getMaxStackSize() - payment.getCount(), stack.getCount());
            panelTrade.setItem(slot, stack.copyWithCount(payment.getCount() + moved));
            stack.shrink(moved); slots.get(index).setChanged();
        }
    }
    public void startTraderRoll() { returnTraderPayments(); rolling = true; rollAttempts = 0; rollStatus = "Rolling..."; sendTraderView(); }
    public void finishSingleRoll() {
        panelSelection = traderOffers().isEmpty() ? -1 : 0;
        matchedBook = ItemStack.EMPTY;
        panelTrade.setSelectionHint(Math.max(0, panelSelection));
        // Do not increment the auto-roll result revision or change the client's search filter.
        updateTraderRoll(1, true, -1, "Rolled once.");
    }
    public void updateTraderRoll(int attempts, boolean finished, int found, String status) {
        rolling = !finished; rollAttempts = attempts; rollStatus = status;
        if (finished && found >= 0 && found < traderOffers().size()) {
            panelSelection = found; matchedBook = traderOffers().get(found).getResult().copy(); rollRevision++;
            panelTrade.setSelectionHint(found); saveRoller();
        }
        sendTraderView();
        super.broadcastChanges();
    }
    private void sendTraderView() {
        if (library != null) panelVillager = library.workstationItem().copy();
        if (owner instanceof ServerPlayer server && library != null)
            PacketDistributor.sendToPlayer(server, new LibraryTraderViewPayload(containerId, panelVillager, traderOffers(),
                panelSelection, rolling, rollAttempts, rollRevision, matchedBook, rollStatus));
    }
    public void applyTraderView(LibraryTraderViewPayload view) {
        if (library != null) return;
        panelVillager = view.villager(); panelMerchant.overrideOffers(view.offers()); panelSelection = view.selectedOffer();
        rolling = view.rolling(); rollAttempts = view.attempts(); rollRevision = view.resultRevision();
        matchedBook = view.matchedBook(); rollStatus = view.status();
        panelTrade.setSelectionHint(Math.max(0, panelSelection));
    }

    private void updatePageCount() {
        if (library == null) return;
        // Always leave at least one empty slot/page for another villager.
        pages.set(library.archiveSize() / PAGE_SIZE + 1);
        page.set(Math.clamp(page.get(), 0, pages.get() - 1));
    }

    @Override public boolean clickMenuButton(Player player, int button) {
        if (library == null || !stillValid(player)) return false;
        updatePageCount();
        if (button == PAGE_PREVIOUS || button == PAGE_NEXT) {
            page.set(Math.clamp(page.get() + (button == PAGE_NEXT ? 1 : -1), 0, archivePages() - 1));
        } else if (button >= PAGE_SELECT_OFFSET && button - PAGE_SELECT_OFFSET < archivePages()) {
            page.set(button - PAGE_SELECT_OFFSET);
        } else return false;
        broadcastChanges();
        return true;
    }

    @Override public boolean stillValid(Player player) {
        return super.stillValid(player) && (library == null || !library.isRemoved()
            && player.distanceToSqr(library.getBlockPos().getCenter()) <= 64);
    }

    @Override public void broadcastChanges() {
        refreshArchiveMarket();
        if (library != null && traderPanelOpen) {
            refreshTrader();
            int fingerprint = System.identityHashCode(traderOffers());
            for (MerchantOffer offer : traderOffers()) fingerprint = fingerprint * 31 + offer.getUses() + offer.getCostA().getCount();
            if (fingerprint != panelFingerprint) { panelFingerprint = fingerprint; saveRoller(); sendTraderView(); }
        }
        super.broadcastChanges();
    }

    private void refreshArchiveMarket() {
        if (library != null && revision != library.archiveRevision()) {
            // A removed villager must not leave a purchasable stale offer/result.
            for (int slot = 0; slot < 2; slot++) {
                ItemStack payment = slots.get(slot).getItem();
                if (!payment.isEmpty()) {
                    Player player = library.marketPlayer();
                    slots.get(slot).set(ItemStack.EMPTY);
                    if (player != null) player.getInventory().placeItemBackInInventory(payment);
                }
            }
            library.refreshOpenMarket();
            revision = library.archiveRevision();
            updatePageCount();
        }
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index >= 3 && index < ARCHIVE_START && stack.is(ModItems.VILLAGER.get())) {
            if (library == null) return ItemStack.EMPTY; // The authoritative server sends the result.
            if (traderPanelOpen && library.workstationItem().isEmpty()) {
                if (!moveItemStackTo(stack, WORKER_SLOT, WORKER_SLOT + 1, false)) return ItemStack.EMPTY;
            } else
            while (!stack.isEmpty() && library.addVillager(stack)) stack.shrink(1);
        } else if (index == 2 || index == TRADER_START + 2) {
            // Vanilla casts the merchant to Entity here. Our combined merchant is not an Entity.
            if (!moveItemStackTo(stack, 3, ARCHIVE_START, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(stack, original);
            if (!player.level().isClientSide) player.level().playSound(null, player.blockPosition(),
                SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1, 1);
        } else if (index >= ARCHIVE_START || index < 2) {
            if (!moveItemStackTo(stack, 3, ARCHIVE_START, false)) return ItemStack.EMPTY;
        } else if (index < 30) {
            if (!moveItemStackTo(stack, 30, ARCHIVE_START, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 3, 30, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override public void removed(Player player) {
        if (library != null) { detachTrader(); library.returnWorkstationToArchive(); }
        super.removed(player);
    }

    private final class WorkstationContainer extends SimpleContainer {
        private WorkstationContainer() { super(1); }
        @Override public ItemStack getItem(int slot) { return library.workstationItem(); }
        @Override public void setItem(int slot, ItemStack stack) {
            autoWaiting = false;
            if (library.workstationItem() != stack) detachTrader();
            library.setWorkstationItem(stack);
        }
        @Override public ItemStack removeItem(int slot, int count) {
            autoWaiting = false;
            detachTrader(); library.saveWorkstation();
            ItemStack stored = getItem(slot);
            ItemStack removed = stored.split(count);
            library.setWorkstationItem(stored.isEmpty() ? ItemStack.EMPTY : stored);
            return removed;
        }
        @Override public ItemStack removeItemNoUpdate(int slot) {
            autoWaiting = false;
            detachTrader(); library.saveWorkstation();
            ItemStack removed = getItem(slot);
            library.setWorkstationItem(ItemStack.EMPTY);
            return removed;
        }
        @Override public void setChanged() { library.saveWorkstation(); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return stack.is(ModItems.VILLAGER.get()); }
        @Override public boolean stillValid(Player player) { return LibraryMarketMenu.this.stillValid(player); }
    }

    /** No copies: vanilla Slot/Container click handling owns cursor, drag, swap and shift transfers. */
    private final class ArchiveContainer extends SimpleContainer {
        private ArchiveContainer() { super(PAGE_SIZE); }
        private int absolute(int slot) { return page.get() * PAGE_SIZE + slot; }
        @Override public ItemStack getItem(int slot) { return library.archiveItem(absolute(slot)); }
        @Override public void setItem(int slot, ItemStack stack) { library.setArchiveItem(absolute(slot), stack); }
        @Override public ItemStack removeItem(int slot, int count) {
            ItemStack stored = getItem(slot);
            if (stored.isEmpty()) return ItemStack.EMPTY;
            ItemStack removed = stored.split(count);
            setItem(slot, stored);
            return removed;
        }
        @Override public ItemStack removeItemNoUpdate(int slot) {
            ItemStack removed = getItem(slot);
            setItem(slot, ItemStack.EMPTY);
            return removed;
        }
        @Override public void setChanged() { library.archiveChanged(); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return stack.is(ModItems.VILLAGER.get()); }
        @Override public boolean stillValid(Player player) { return LibraryMarketMenu.this.stillValid(player); }
    }
}
