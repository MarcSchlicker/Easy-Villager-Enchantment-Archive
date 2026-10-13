package dev.marc.easyvillagerslibrary.menu;

import dev.marc.easyvillagerslibrary.blockentity.EnchantmentLibraryBlockEntity;
import dev.marc.easyvillagerslibrary.merchant.LibraryMerchantControl;
import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;

/** Read-only catalogue. Villagers are inserted directly into the block, without a slot limit. */
public final class EnchantmentLibraryMenu extends AbstractContainerMenu {
    public static final int OPEN_BOOK_MARKET_BUTTON = 3;
    public static final int OPEN_ACTIVE_ROLLER_BUTTON = 4;
    /** Inserts one Easy Villager held by the cursor into the unlimited archive. */
    public static final int INSERT_CARRIED_VILLAGER_BUTTON = 5;
    private static final int BOOK_SLOT_START = 0;
    private static final int VILLAGER_SLOT_START = BOOK_SLOT_START + EnchantmentLibraryBlockEntity.BOOK_TRADE_ROWS;
    private static final int ROLL_TARGET_SLOT = VILLAGER_SLOT_START + EnchantmentLibraryBlockEntity.SUMMARY_SLOTS;
    private static final int ROLL_WORKSTATION_SLOT_START = ROLL_TARGET_SLOT + 1;
    private static final int ROLL_WORKSTATION_SLOTS = 16;
    private static final int PLAYER_SLOT_START = ROLL_WORKSTATION_SLOT_START + ROLL_WORKSTATION_SLOTS;
    private final Container summaries;
    private final EnchantmentLibraryBlockEntity library;
    private final DataSlot page = DataSlot.standalone();
    private final DataSlot catalogueView = DataSlot.standalone();
    private final DataSlot pendingRollVillagers = DataSlot.standalone();

    public EnchantmentLibraryMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null, new SimpleContainer(EnchantmentLibraryBlockEntity.SUMMARY_SLOTS),
            new SimpleContainer(1), new SimpleContainer(ROLL_WORKSTATION_SLOTS));
    }

    public EnchantmentLibraryMenu(int containerId, Inventory playerInventory, EnchantmentLibraryBlockEntity library) {
        this(containerId, playerInventory, library, library.summaries(), library.rollTargetPreview(), library.rollWorkstation());
    }

    private EnchantmentLibraryMenu(int containerId, Inventory playerInventory, EnchantmentLibraryBlockEntity library, Container summaries,
                                   Container rollTarget, Container rollWorkstation) {
        super(LibraryRegistries.ENCHANTMENT_LIBRARY_MENU.get(), containerId);
        this.summaries = summaries;
        this.library = library;
        addDataSlot(page);
        addDataSlot(catalogueView);
        addDataSlot(pendingRollVillagers);
        if (library != null) {
            page.set(library.summaryPage());
            catalogueView.set(library.catalogueViewId());
            pendingRollVillagers.set(library.pendingRollVillagerCount());
        }
        checkContainerSize(summaries, EnchantmentLibraryBlockEntity.SUMMARY_SLOTS);
        for (int row = 0; row < EnchantmentLibraryBlockEntity.BOOK_TRADE_ROWS; row++) {
            // The Books page paints these rows itself so each row, rather
            // than only a tiny output slot, can open the complete trade list.
            addSlot(new ConditionalReadOnlySlot(summaries, row, -10_000, -10_000, () -> false));
        }
        for (int index = 0; index < EnchantmentLibraryBlockEntity.SUMMARY_SLOTS; index++) {
            addSlot(new ConditionalReadOnlySlot(summaries, index, 8 + (index % 9) * 18, 48 + (index / 9) * 18, this::isVillagerView));
        }
        // The target book stays to the right of the Auto-roll filter and its
        // search results instead of covering the text entry field.
        addSlot(new ConditionalReadOnlySlot(rollTarget, 0, 145, 49, this::isRollView));
        // These slots are only a data bridge for the custom screen. Keeping
        // them inactive prevents the vanilla container renderer from drawing
        // duplicate invisible slots or accepting clicks.
        for (int index = 0; index < ROLL_WORKSTATION_SLOTS; index++) {
            addSlot(new ConditionalReadOnlySlot(rollWorkstation, index, -10_000, -10_000, () -> false));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 164 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, 8 + column * 18, 224));
        }
    }

    public ItemStack summaryAt(int index) {
        return summaries.getItem(index);
    }

    public int page() {
        return page.get();
    }

    public boolean isVillagerView() {
        return catalogueView.get() == 1;
    }

    public boolean isBookView() {
        return catalogueView.get() == 0;
    }

    public boolean isRollView() {
        return catalogueView.get() == 2;
    }

    /** Called only by the validated serverbound Auto-roll target payload. */
    public boolean setRollTargetById(String enchantmentId) {
        return library != null && library.setRollTargetById(enchantmentId);
    }

    public static int rollTargetButtonId(int rawRegistryId) {
        return LibraryMerchantControl.ROLL_TARGET_BUTTON_OFFSET + rawRegistryId;
    }

    /** The synced read-only target preview used by the client Auto-roll panel. */
    public ItemStack rollTargetPreview() {
        return getSlot(ROLL_TARGET_SLOT).getItem();
    }

    public ItemStack activeRollVillagerPreview() {
        return getSlot(ROLL_WORKSTATION_SLOT_START).getItem();
    }

    public ItemStack activeRollCostA(int offerIndex) {
        return rollWorkstationSlot(offerIndex, 0);
    }

    public ItemStack activeRollCostB(int offerIndex) {
        return rollWorkstationSlot(offerIndex, 1);
    }

    public ItemStack activeRollResult(int offerIndex) {
        return rollWorkstationSlot(offerIndex, 2);
    }

    public int pendingRollVillagerCount() {
        return pendingRollVillagers.get();
    }

    private ItemStack rollWorkstationSlot(int offerIndex, int column) {
        if (offerIndex < 0 || offerIndex >= 5) {
            return ItemStack.EMPTY;
        }
        return getSlot(ROLL_WORKSTATION_SLOT_START + 1 + offerIndex * 3 + column).getItem();
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (library == null) {
            return false;
        }
        if (buttonId == OPEN_BOOK_MARKET_BUTTON) {
            return library.openBookMarket(player);
        }
        if (buttonId == OPEN_ACTIVE_ROLLER_BUTTON) {
            return library.openActiveRollVillagerTrade(player);
        }
        if (buttonId == INSERT_CARRIED_VILLAGER_BUTTON) {
            ItemStack carried = getCarried();
            if (!library.addVillager(carried)) {
                return false;
            }
            carried.shrink(1);
            setCarried(carried);
            broadcastChanges();
            return true;
        }
        if (buttonId >= LibraryMerchantControl.ROLL_TARGET_BUTTON_OFFSET) {
            return library.setRollTargetByRawId(buttonId - LibraryMerchantControl.ROLL_TARGET_BUTTON_OFFSET);
        }
        if (buttonId < 0 || buttonId > 2) {
            return false;
        }
        // 0 = Books, 1 = Villagers, 2 = Auto-roll. These are explicit tabs,
        // not arrows whose order has to be discovered by trial and error.
        library.setCatalogueView(buttonId);
        page.set(library.summaryPage());
        catalogueView.set(library.catalogueViewId());
        return true;
    }

    @Override
    public void broadcastChanges() {
        if (library != null) {
            page.set(library.summaryPage());
            catalogueView.set(library.catalogueViewId());
            pendingRollVillagers.set(library.pendingRollVillagerCount());
        }
        super.broadcastChanges();
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        boolean primaryClick = clickType == ClickType.PICKUP && button == 0;
        if (library != null && library.isBookView() && slotId >= BOOK_SLOT_START
            && slotId < BOOK_SLOT_START + EnchantmentLibraryBlockEntity.BOOK_TRADE_ROWS
            && primaryClick && library.openVisibleBookTrade(slotId, player)) {
                return;
        }
        if (library != null && library.isRollView() && slotId == ROLL_TARGET_SLOT && clickType == ClickType.PICKUP) {
            ItemStack carried = getCarried();
            if (carried.isEmpty()) {
                library.clearRollTarget();
            } else {
                library.setRollTarget(carried);
            }
            return;
        }
        int villagerSlot = slotId - VILLAGER_SLOT_START;
        if (library != null && library.isVillagerView() && villagerSlot >= 0 && villagerSlot < EnchantmentLibraryBlockEntity.SUMMARY_SLOTS) {
            if (clickType == ClickType.PICKUP && button == 1) {
                ItemStack removed = library.removeVisibleVillager(villagerSlot);
                if (!removed.isEmpty() && !player.getInventory().add(removed)) {
                    player.drop(removed, false);
                }
                return;
            }
            boolean opensTrade = primaryClick || clickType == ClickType.QUICK_MOVE;
            if (opensTrade && library.openVisibleVillagerTrade(villagerSlot, player)) {
                return;
            }
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Shift-clicking an Easy Villager from the player's inventory puts one
        // back into the unlimited Library archive immediately.
        if (library != null && index >= PLAYER_SLOT_START && index < slots.size()) {
            Slot playerSlot = slots.get(index);
            ItemStack stack = playerSlot.getItem();
            if (library.isRollView() && library.setRollTarget(stack)) {
                return stack.copyWithCount(1);
            }
            if (library.addVillager(stack)) {
                ItemStack inserted = stack.copyWithCount(1);
                stack.shrink(1);
                playerSlot.setChanged();
                return inserted;
            }
        }
        return ItemStack.EMPTY;
    }

    private static final class ConditionalReadOnlySlot extends Slot {
        private final BooleanSupplier active;

        private ConditionalReadOnlySlot(Container container, int index, int x, int y, BooleanSupplier active) {
            super(container, index, x, y);
            this.active = active;
        }

        @Override
        public boolean isActive() {
            return active.getAsBoolean();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
