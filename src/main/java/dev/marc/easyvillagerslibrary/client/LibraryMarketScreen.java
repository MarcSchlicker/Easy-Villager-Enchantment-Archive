package dev.marc.easyvillagerslibrary.client;

import dev.marc.easyvillagerslibrary.menu.LibraryMarketMenu;
import dev.marc.easyvillagerslibrary.network.LibraryTraderActionPayload;
import dev.marc.easyvillagerslibrary.network.TraderRollRequestPayload;
import dev.marc.easyvillagerslibrary.network.TraderSingleRollRequestPayload;
import dev.marc.easyvillagerslibrary.merchant.RollTargetSearch;
import dev.marc.easyvillagerslibrary.merchant.TradePrices;
import de.maxhenkel.easyvillagers.items.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;

/** One rendered offer list is shared by search, scrolling, price guide, tooltips and selection. */
public final class LibraryMarketScreen extends AbstractContainerScreen<LibraryMarketMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/villager.png");
    private static final ResourceLocation PRICE_STRIKE = ResourceLocation.withDefaultNamespace("container/villager/discount_strikethrough");
    private EditBox search;
    private final List<Integer> visibleOffers = new ArrayList<>();
    private String query = "";
    private int priceFilter;
    private boolean stockOnly;
    private boolean drawerOpen;
    private int scroll;
    private int selectedOffer = -1;
    private boolean draggingScrollbar;
    private int lastOfferFingerprint;
    private int guideWidth;
    private Button previousPage;
    private Button nextPage;
    private boolean rollOpen;
    private EditBox rollSearch;
    private String rollQuery = "";
    private int rollTargetId = -1;
    private int traderScroll;
    private int lastRollRevision;
    private Button rollButton;
    private Button singleRollButton;
    private int rollChoiceOffset;
    private final List<Button> rollChoices = new ArrayList<>();
    private List<TraderRollChoice> rollMatches = List.of();
    private static final int ROLL_WIDTH = 238, ROLL_HEIGHT = 192;

    public LibraryMarketScreen(LibraryMarketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 276;
        imageHeight = 166;
        inventoryLabelX = 107;
        inventoryLabelY = 72;
    }

    @Override protected void init() {
        super.init();
        // Keep the guide and opened archive inside the viewport when space allows.
        int preferred = (width - imageWidth) / 2;
        int sideWidth = rollOpen ? ROLL_WIDTH + 5 : drawerOpen ? 182 : 10;
        int availableGuide = Math.min(220, Math.max(70, width - imageWidth - sideWidth - 14));
        leftPos = Math.max(availableGuide + 8, preferred);
        if (drawerOpen || rollOpen) leftPos = Math.min(leftPos, Math.max(8, width - imageWidth - sideWidth - 4));
        guideWidth = Math.max(0, Math.min(220, leftPos - 8));
        int contentHeight = rollOpen ? Math.max(imageHeight, (drawerOpen ? 117 : 0) + ROLL_HEIGHT) : imageHeight;
        topPos = Math.max(30, (height - contentHeight) / 2);
        menu.setDrawerOpen(drawerOpen);
        menu.setTraderPanelOpen(rollOpen);
        menu.positionTraderSlots(drawerOpen ? 117 : 0);
        search = addRenderableWidget(new EditBox(font, leftPos + 5, topPos - 22, 103, 18, Component.literal("Search books")));
        search.setHint(Component.literal("Search books..."));
        search.setMaxLength(80);
        search.setValue(query);
        search.setResponder(value -> { query = value; scroll = 0; rebuildOffers(); });
        addRenderableWidget(Button.builder(Component.literal("×"), button -> { search.setValue(""); search.setFocused(true); setFocused(search); })
            .bounds(leftPos + 110, topPos - 22, 18, 18).build());
        addRenderableWidget(Button.builder(priceFilterLabel(), button -> {
            priceFilter = (priceFilter + 1) % 4;
            button.setMessage(priceFilterLabel()); scroll = 0; rebuildOffers();
        }).bounds(leftPos + 130, topPos - 22, 60, 18).build());
        addRenderableWidget(Button.builder(Component.literal(stockOnly ? "Stock" : "All"), button -> {
            stockOnly = !stockOnly; button.setMessage(Component.literal(stockOnly ? "Stock" : "All"));
            scroll = 0; rebuildOffers();
        }).bounds(leftPos + 192, topPos - 22, 35, 18).build());
        addRenderableWidget(new PanelIconButton(leftPos + 229, topPos - 22, true, button -> {
            drawerOpen = !drawerOpen;
            rebuildWidgets();
            if (drawerOpen) focusSourceVillager();
        }));
        addRenderableWidget(new PanelIconButton(leftPos + 251, topPos - 22, false, button -> {
            rollOpen = !rollOpen;
            if (rollOpen && rollQuery.isBlank()) rollQuery = query;
            PacketDistributor.sendToServer(new LibraryTraderActionPayload(menu.containerId,
                rollOpen ? LibraryTraderActionPayload.OPEN : LibraryTraderActionPayload.CLOSE, 0));
            rebuildWidgets();
        }));
        previousPage = addRenderableWidget(Button.builder(Component.literal("‹"), button -> page(-1))
            .bounds(leftPos + 284, topPos + 7, 18, 16).build());
        nextPage = addRenderableWidget(Button.builder(Component.literal("›"), button -> page(1))
            .bounds(leftPos + 434, topPos + 7, 18, 16).build());
        previousPage.visible = nextPage.visible = drawerOpen;
        installRollControls();
        rebuildOffers();
    }

    private int rollY() { return topPos + (drawerOpen ? 117 : 0); }
    private void installRollControls() {
        rollChoices.clear();
        int x = leftPos + 281, y = rollY();
        singleRollButton = addRenderableWidget(Button.builder(Component.literal("Roll"), button -> {
            PacketDistributor.sendToServer(new TraderSingleRollRequestPayload(menu.containerId));
            rollSearch.setFocused(false);
        }).bounds(x + 177, y + 31, 54, 18).tooltip(Tooltip.create(Component.literal(
            "Reroll exactly once, without a target. Stops an active auto-roll search."))).build());
        singleRollButton.visible = rollOpen;
        rollSearch = addRenderableWidget(new EditBox(font, x + 7, y + 141, 156, 18, Component.literal("Auto-roll target")));
        rollSearch.setHint(Component.literal("Search enchantments..."));
        rollSearch.setMaxLength(80);
        rollSearch.setValue(rollQuery);
        rollSearch.setResponder(value -> { rollQuery = value; rollTargetId = -1; rollChoiceOffset = 0; updateRollMatches(); });
        rollSearch.visible = rollOpen;
        rollButton = addRenderableWidget(Button.builder(Component.literal("Auto-roll"), button -> {
            int targetId = rollTargetId;
            if (targetId < 0) targetId = rollMatches.stream().filter(choice -> RollTargetSearch.exactName(rollQuery, choice.name()))
                .mapToInt(TraderRollChoice::rawId).findFirst().orElse(-1);
            if (!rollQuery.isBlank()) PacketDistributor.sendToServer(new TraderRollRequestPayload(menu.containerId, targetId, rollQuery));
            rollSearch.setFocused(false);
        }).bounds(x + 167, y + 141, 64, 18).tooltip(Tooltip.create(Component.literal(
            "Find a NEW matching trade. Earned villager trades cannot be rerolled."))).build());
        rollButton.visible = rollOpen;
        int suggestionY = y + (y + 218 < height - 4 ? 161 : 82);
        for (int index = 0; index < 3; index++) {
            final int choiceIndex = index;
            Button choice = Button.builder(Component.empty(), button -> {
                int matchIndex = rollChoiceOffset + choiceIndex;
                if (matchIndex >= rollMatches.size()) return;
                TraderRollChoice target = rollMatches.get(matchIndex);
                rollSearch.setValue(target.name());
                rollTargetId = target.rawId();
                rollSearch.setFocused(false);
                if (minecraft.level != null) {
                    var enchantment = minecraft.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).byId(target.rawId());
                    if (enchantment != null) search.setValue(enchantment.description().getString());
                }
            }).bounds(x + 7, suggestionY + index * 19, 224, 18).build();
            choice.visible = false;
            rollChoices.add(addRenderableWidget(choice));
        }
        updateRollMatches();
    }
    private void updateRollMatches() {
        if (minecraft.level == null || rollQuery.isBlank()) rollMatches = List.of();
        else {
            var registry = minecraft.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            rollMatches = registry.holders().filter(holder -> holder.is(EnchantmentTags.TRADEABLE)).map(holder -> {
                String name = Enchantment.getFullname(holder, holder.value().getMaxLevel()).getString();
                String id = holder.key().location().toString().replace('_', ' ');
                return new TraderRollChoice(registry.getId(holder.value()), name, (name + " " + id).toLowerCase(Locale.ROOT),
                    Component.translatable("enchantment." + holder.key().location().getNamespace() + "." + holder.key().location().getPath() + ".desc"));
            }).filter(choice -> RollTargetSearch.matches(rollQuery, choice.search()))
                .sorted(Comparator.comparingInt((TraderRollChoice choice) -> RollTargetSearch.priority(rollQuery, choice.name()))
                    .thenComparing(TraderRollChoice::name)).toList();
        }
        rollChoiceOffset = Math.clamp(rollChoiceOffset, 0, Math.max(0, rollMatches.size() - rollChoices.size()));
        refreshRollChoices();
    }
    private void refreshRollChoices() {
        for (int index = 0; index < rollChoices.size(); index++) if (rollChoiceOffset + index < rollMatches.size()) {
            TraderRollChoice choice = rollMatches.get(rollChoiceOffset + index);
            rollChoices.get(index).setMessage(Component.literal(choice.name()));
            rollChoices.get(index).setTooltip(Tooltip.create(choice.description()));
        }
    }

    private Component priceFilterLabel() {
        return Component.literal(switch (priceFilter) { case 1 -> "Good"; case 2 -> "Fair"; case 3 -> "Expensive"; default -> "All prices"; });
    }

    private void rebuildOffers() {
        visibleOffers.clear();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        MerchantOffers offers = menu.getOffers();
        for (int index = 0; index < offers.size(); index++) {
            MerchantOffer offer = offers.get(index);
            Book book = book(offer);
            if (book == null || (stockOnly && offer.isOutOfStock())) continue;
            if (priceFilter != 0 && book.quality(offer.getBaseCostA().getCount()) != priceFilter) continue;
            if (!needle.isEmpty() && !book.searchText().contains(needle)) continue;
            visibleOffers.add(index);
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, visibleOffers.size() - 7));
        if (selectedOffer >= offers.size()) selectedOffer = -1;
    }

    @Override protected void containerTick() {
        super.containerTick();
        // Re-evaluate stock/price filters on offer packets and after purchases, not just keystrokes.
        int fingerprint = System.identityHashCode(menu.getOffers());
        for (MerchantOffer offer : menu.getOffers()) fingerprint = 31 * fingerprint + offer.getUses() + offer.getCostA().getCount();
        if (fingerprint != lastOfferFingerprint) { lastOfferFingerprint = fingerprint; rebuildOffers(); }
        previousPage.active = menu.archivePage() > 0;
        nextPage.active = menu.archivePage() + 1 < menu.archivePages();
        rollButton.active = rollOpen && menu.panelAvailable() && !menu.isRolling() && !rollQuery.isBlank();
        singleRollButton.active = rollOpen && menu.panelAvailable();
        traderScroll = Math.clamp(traderScroll, 0, Math.max(0, menu.traderOffers().size() - 5));
        if (menu.rollRevision() != lastRollRevision) {
            lastRollRevision = menu.rollRevision();
            var found = EnchantmentHelper.getEnchantmentsForCrafting(menu.matchedBook());
            if (found.size() == 1) search.setValue(found.entrySet().iterator().next().getKey().value().description().getString());
            if (menu.traderSelection() >= 0) traderScroll = Math.clamp(menu.traderSelection(), 0, Math.max(0, menu.traderOffers().size() - 5));
        }
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 512, 256);
        // The texture already supplies the ordinary grey payments and player-inventory slots.
        for (int row = 0; row < 7; row++) {
            int index = offerAtRow(row);
            int y = topPos + 18 + row * 20;
            if (index < 0) continue;
            MerchantOffer offer = menu.getOffers().get(index);
            graphics.fill(leftPos + 5, y, leftPos + 93, y + 20, index == selectedOffer ? 0xFFB69ABD : 0xFFA59F88);
            if (mouseX >= leftPos + 5 && mouseX < leftPos + 93 && mouseY >= y && mouseY < y + 20)
                graphics.fill(leftPos + 5, y, leftPos + 93, y + 20, 0x40FFFFFF);
            drawTradePrice(graphics, offer, leftPos + 10, y + 2);
            drawItem(graphics, offer.getCostB(), leftPos + 40, y + 2);
            graphics.drawString(font, offer.isOutOfStock() ? "×" : "→", leftPos + 57, y + 6,
                offer.isOutOfStock() ? 0xFFFF5555 : 0xFF777777, false);
            drawItem(graphics, offer.getResult(), leftPos + 73, y + 2);
            drawGuide(graphics, offer, y);
        }
        int thumb = visibleOffers.size() <= 7 ? 0 : Math.round(scroll * 113F / (visibleOffers.size() - 7));
        graphics.fill(leftPos + 94, topPos + 18 + thumb, leftPos + 100, topPos + 45 + thumb, 0xFFD0D0D0);
        if (drawerOpen) drawArchive(graphics);
        if (rollOpen) drawRollPanel(graphics, mouseX, mouseY);
        if (visibleOffers.isEmpty()) graphics.drawString(font, "No matches", leftPos + 9, topPos + 22, 0xFF444444, false);
    }

    private void drawItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) return;
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(font, stack, x, y);
    }

    /** The same original/current count placement and strike sprite as the vanilla MerchantScreen. */
    private void drawTradePrice(GuiGraphics graphics, MerchantOffer offer, int x, int y) {
        ItemStack current = offer.getCostA(), original = offer.getBaseCostA();
        graphics.renderItem(current, x, y);
        graphics.renderItemDecorations(font, original, x, y, Integer.toString(original.getCount()));
        if (current.getCount() != original.getCount()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            String number = Integer.toString(current.getCount());
            graphics.drawString(font, number, x + 31 - font.width(number), y + 9, 0xFFFFFFFF, true);
            graphics.pose().translate(0, 0, 100);
            graphics.blitSprite(PRICE_STRIKE, x + 7, y + 12, 9, 2);
            graphics.pose().popPose();
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "Trades", 30, 6, 0xFF404040, false);
        graphics.drawString(font, title, 104 + (172 - font.width(title)) / 2, 6, 0xFF404040, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF404040, false);
    }

    private void drawGuide(GuiGraphics graphics, MerchantOffer offer, int y) {
        if (guideWidth < 80) return;
        int x = leftPos - guideWidth - 5;
        graphics.fill(x, y, x + guideWidth, y + 20, 0xFF707070);
        graphics.fill(x + 1, y + 1, x + guideWidth - 1, y + 19, 0xFF252525);
        Book book = book(offer);
        int original = offer.getBaseCostA().getCount();
        Component price = TradePrices.of(offer).label().copy().append("  Range: " + book.minimum() + "–" + book.maximum());
        graphics.drawString(font, price,
            x + 4, y + 1, book.color(original), false);
        graphics.drawString(font, fit(book.name(), guideWidth - 8), x + 4, y + 10, 0xFF55AAFF, false);
    }

    private void drawArchive(GuiGraphics graphics) {
        int x = leftPos + 281;
        int y = topPos;
        graphics.fill(x, y, x + 178, y + 111, 0xFFEEEEEE);
        graphics.fill(x + 2, y + 2, x + 176, y + 109, 0xFFC6C6C6);
        graphics.drawString(font, "Villagers " + (menu.archivePage() + 1) + "/" + menu.archivePages(), x + 26, y + 11, 0xFF404040, false);
        LibraryMerchantViewState.State state = LibraryMerchantViewState.get(menu.containerId);
        int selectedSource = state == null ? -1 : state.sourceForOffer(selectedOffer);
        for (int index = 0; index < LibraryMarketMenu.PAGE_SIZE; index++) {
            int slotX = leftPos + 287 + index % 9 * 18;
            int slotY = topPos + 27 + index / 9 * 18;
            int color = menu.archivePage() * LibraryMarketMenu.PAGE_SIZE + index == selectedSource ? 0xFF55CC55 : 0xFF373737;
            graphics.fill(slotX, slotY, slotX + 18, slotY + 18, color);
            graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, color == 0xFF55CC55 ? 0xFF7ACC7A : 0xFF8B8B8B);
        }
        graphics.drawString(font, "Villagers only · Shift-click", x + 7, y + 87, 0xFF404040, false);
        graphics.drawString(font, "Green = selected trade", x + 7, y + 98, 0xFF196619, false);
    }

    private void drawRollPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos + 281, y = rollY();
        graphics.fill(x, y, x + ROLL_WIDTH, y + ROLL_HEIGHT, 0xFFEEEEEE);
        graphics.fill(x + 2, y + 2, x + ROLL_WIDTH - 2, y + ROLL_HEIGHT - 2, 0xFFC6C6C6);
        graphics.drawString(font, "Trades", x + 25, y + 8, 0xFF404040, false);
        graphics.drawString(font, "Library Trader", x + 103, y + 8, 0xFF404040, false);
        graphics.fill(x + 6, y + 21, x + 92, y + 25, 0xFF535353);
        graphics.fill(x + 104, y + 21, x + 229, y + 25, menu.isRolling() ? 0xFFBB922E : 0xFF527448);
        for (int row = 0; row < 5; row++) {
            int index = traderScroll + row;
            if (index >= menu.traderOffers().size()) break;
            MerchantOffer offer = menu.traderOffers().get(index);
            int rowY = y + 27 + row * 20;
            graphics.fill(x + 5, rowY, x + 92, rowY + 20, index == menu.traderSelection() ? 0xFFB69ABD : 0xFFA59F88);
            if (mouseX >= x + 5 && mouseX < x + 92 && mouseY >= rowY && mouseY < rowY + 20)
                graphics.fill(x + 5, rowY, x + 92, rowY + 20, 0x40FFFFFF);
            drawTradePrice(graphics, offer, x + 9, rowY + 2);
            drawItem(graphics, offer.getCostB(), x + 40, rowY + 2);
            graphics.drawString(font, offer.isOutOfStock() ? "×" : "→", x + 57, rowY + 6, 0xFF777777, false);
            drawItem(graphics, offer.getResult(), x + 71, rowY + 2);
        }
        if (menu.traderOffers().size() > 5) graphics.drawString(font, "↕", x + 94, y + 29, 0xFF404040, false);
        drawPaymentSlot(graphics, x + 113, y + 57, 18);
        drawPaymentSlot(graphics, x + 139, y + 57, 18);
        drawPaymentSlot(graphics, x + 194, y + 54, 24);
        graphics.drawString(font, "→", x + 167, y + 61, 0xFF777777, false);
        drawPaymentSlot(graphics, x + 111, y + 31, 18);
        graphics.drawString(font, "Villager", x + 134, y + 36, 0xFF404040, false);
        if (menu.traderSelection() >= 0 && menu.traderSelection() < menu.traderOffers().size()) {
            MerchantOffer selectedOffer = menu.traderOffers().get(menu.traderSelection());
            Book selected = book(selectedOffer);
            if (selected != null) {
                graphics.drawString(font, fit(selected.name(), 121), x + 104, y + 91, 0xFF245F8D, false);
                graphics.drawString(font, TradePrices.of(selectedOffer).label(), x + 104, y + 102,
                    selected.color(selectedOffer.getBaseCostA().getCount()), false);
            }
        }
        graphics.drawString(font, "Auto-roll target", x + 7, y + 130, 0xFF404040, false);
        if (rollSearch.isFocused() && rollMatches.size() > rollChoices.size()) {
            String page = (rollChoiceOffset + 1) + "–" + Math.min(rollMatches.size(), rollChoiceOffset + rollChoices.size())
                + "/" + rollMatches.size() + " ↕";
            graphics.drawString(font, page, x + ROLL_WIDTH - 7 - font.width(page), y + 130, 0xFF404040, false);
        }
        graphics.drawString(font, fit(menu.rollStatus(), ROLL_WIDTH - 14), x + 7, y + 165,
            menu.isRolling() ? 0xFF906A13 : 0xFF404040, false);
        graphics.drawString(font, "Auto-roll finds a NEW matching book", x + 7, y + 177, 0xFF404040, false);
    }
    private void drawPaymentSlot(GuiGraphics graphics, int x, int y, int size) {
        graphics.fill(x, y, x + size, y + size, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + size, y + size, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0xFF8B8B8B);
    }

    private final class PanelIconButton extends Button {
        private final boolean villager;
        PanelIconButton(int x, int y, boolean villager, OnPress action) {
            super(x, y, 20, 18, Component.empty(), action, DEFAULT_NARRATION);
            this.villager = villager;
            setTooltip(Tooltip.create(Component.literal(villager ? "Villager inventory" : "Library Trader · auto-roll")));
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
            super.renderWidget(graphics, mouseX, mouseY, tick);
            if (villager) graphics.renderItem(new ItemStack(ModItems.VILLAGER.get()), getX() + 2, getY() + 1);
            else {
                int x = getX(), y = getY(), color = 0xFFEEEEEE;
                graphics.fill(x + 6, y + 4, x + 13, y + 6, color);
                graphics.fill(x + 4, y + 6, x + 6, y + 12, color);
                graphics.fill(x + 6, y + 12, x + 13, y + 14, color);
                graphics.fill(x + 13, y + 9, x + 15, y + 12, color);
                graphics.fill(x + 11, y + 6, x + 16, y + 8, color);
                graphics.fill(x + 14, y + 4, x + 16, y + 9, color);
            }
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (int index = 0; index < rollChoices.size(); index++)
            rollChoices.get(index).visible = rollOpen && rollSearch.isFocused() && rollChoiceOffset + index < rollMatches.size();
        super.render(graphics, mouseX, mouseY, partialTick);
        // All tooltips are last, above decorations and both side panels.
        renderTooltip(graphics, mouseX, mouseY);
        int row = rowAt(mouseX, mouseY);
        if (row < 0 && guideWidth >= 80 && mouseX >= leftPos - guideWidth - 5 && mouseX < leftPos - 5
            && mouseY >= topPos + 18 && mouseY < topPos + 158) row = (mouseY - topPos - 18) / 20;
        int index = offerAtRow(row);
        if (index >= 0 && menu.getCarried().isEmpty()) {
            MerchantOffer offer = menu.getOffers().get(index);
            Book book = book(offer);
            List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(offer.getResult()));
            lines.add(Component.literal("Original price: " + offer.getBaseCostA().getCount() + " emeralds").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Current price: " + offer.getCostA().getCount() + " emeralds").withStyle(ChatFormatting.GREEN));
            lines.add(Component.literal("Vanilla roll range: " + book.minimum() + "–" + book.maximum()).withStyle(ChatFormatting.GRAY));
            if (offer.isOutOfStock()) lines.add(Component.literal("Out of stock").withStyle(ChatFormatting.RED));
            // The row is not a real slot: render its actual stack tooltip exactly once.
            graphics.renderTooltip(font, lines, offer.getResult().getTooltipImage(), offer.getResult(), mouseX, mouseY);
        }
        boolean suggestionHovered = rollChoices.stream().anyMatch(choice -> choice.visible && choice.isMouseOver(mouseX, mouseY));
        int traderIndex = suggestionHovered ? -1 : traderOfferAt(mouseX, mouseY);
        if (traderIndex >= 0 && menu.getCarried().isEmpty()) {
            MerchantOffer offer = menu.traderOffers().get(traderIndex);
            ItemStack result = offer.getResult();
            List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(result));
            lines.add(Component.literal("Original price: " + offer.getBaseCostA().getCount()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Current price: " + offer.getCostA().getCount()).withStyle(ChatFormatting.GREEN));
            Book book = book(offer);
            if (book != null) lines.add(Component.literal("Vanilla roll range: " + book.minimum() + "–" + book.maximum()).withStyle(ChatFormatting.GRAY));
            graphics.renderTooltip(font, lines, result.getTooltipImage(), result, mouseX, mouseY);
        }
    }

    private int rowAt(double x, double y) {
        return x >= leftPos + 5 && x < leftPos + 93 && y >= topPos + 18 && y < topPos + 158
            ? (int) (y - topPos - 18) / 20 : -1;
    }
    private int offerAtRow(int row) {
        int position = scroll + row;
        return row >= 0 && row < 7 && position < visibleOffers.size() ? visibleOffers.get(position) : -1;
    }
    private void select(int index) {
        selectedOffer = index;
        menu.setSelectionHint(index);
        menu.tryMoveItems(index);
        minecraft.getConnection().send(new ServerboundSelectTradePacket(index));
        if (drawerOpen) focusSourceVillager();
    }
    private void focusSourceVillager() {
        LibraryMerchantViewState.State state = LibraryMerchantViewState.get(menu.containerId);
        int source = state == null ? -1 : state.sourceForOffer(selectedOffer);
        if (source >= 0 && source / LibraryMarketMenu.PAGE_SIZE != menu.archivePage()) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                LibraryMarketMenu.PAGE_SELECT_OFFSET + source / LibraryMarketMenu.PAGE_SIZE);
        }
    }
    private void page(int delta) {
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
            delta > 0 ? LibraryMarketMenu.PAGE_NEXT : LibraryMarketMenu.PAGE_PREVIOUS);
    }
    private int traderOfferAt(double x, double y) {
        if (!rollOpen || x < leftPos + 286 || x >= leftPos + 373 || y < rollY() + 27 || y >= rollY() + 127) return -1;
        int index = traderScroll + (int) (y - rollY() - 27) / 20;
        return index < menu.traderOffers().size() ? index : -1;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (search != null && !search.isMouseOver(x, y)) search.setFocused(false);
        // Keep suggestions clickable even while their text box has focus.
        boolean suggestion = rollChoices.stream().anyMatch(choice -> choice.visible && choice.isMouseOver(x, y));
        if (rollSearch != null && !rollSearch.isMouseOver(x, y) && !suggestion) rollSearch.setFocused(false);
        if (suggestion) return super.mouseClicked(x, y, button);
        int traderIndex = traderOfferAt(x, y);
        if (button == 0 && traderIndex >= 0 && menu.getCarried().isEmpty()) {
            PacketDistributor.sendToServer(new LibraryTraderActionPayload(menu.containerId, LibraryTraderActionPayload.SELECT, traderIndex));
            return true;
        }
        int index = offerAtRow(rowAt(x, y));
        if (button == 0 && index >= 0 && menu.getCarried().isEmpty()) { select(index); return true; }
        if (button == 0 && x >= leftPos + 94 && x <= leftPos + 100 && y >= topPos + 18 && y < topPos + 158) {
            draggingScrollbar = true; dragScroll(y); return true;
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (draggingScrollbar) { dragScroll(y); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(x, y, button);
    }
    private void dragScroll(double y) {
        scroll = Math.clamp((int) Math.round((y - topPos - 31.5) / 113 * Math.max(0, visibleOffers.size() - 7)),
            0, Math.max(0, visibleOffers.size() - 7));
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (rollOpen && rollSearch.isFocused() && (rollSearch.isMouseOver(x, y)
            || rollChoices.stream().anyMatch(choice -> choice.visible && choice.isMouseOver(x, y)))) {
            rollChoiceOffset = Math.clamp(rollChoiceOffset - (int) Math.signum(vertical), 0,
                Math.max(0, rollMatches.size() - rollChoices.size()));
            refreshRollChoices();
            return true;
        }
        if (rollOpen && x >= leftPos + 281 && x < leftPos + 281 + ROLL_WIDTH && y >= rollY() && y < rollY() + ROLL_HEIGHT) {
            traderScroll = Math.clamp(traderScroll - (int) Math.signum(vertical), 0, Math.max(0, menu.traderOffers().size() - 5));
            return true;
        }
        if (drawerOpen && x >= leftPos + 281 && x <= leftPos + 459 && y >= topPos && y <= topPos + 111) {
            page(vertical < 0 ? 1 : -1); return true;
        }
        scroll = Math.clamp(scroll - (int) Math.signum(vertical), 0, Math.max(0, visibleOffers.size() - 7));
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (rollSearch != null && rollSearch.isFocused()) {
            if (key == 256) { rollSearch.setFocused(false); return true; }
            rollSearch.keyPressed(key, scan, modifiers); return true;
        }
        if (search != null && search.isFocused()) {
            if (key == 256) { search.setFocused(false); return true; }
            search.keyPressed(key, scan, modifiers);
            return true; // E/number/drop shortcuts must not close the screen or move items while typing.
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean charTyped(char character, int modifiers) {
        if (rollSearch != null && rollSearch.isFocused()) return rollSearch.charTyped(character, modifiers);
        return search != null && search.isFocused() ? search.charTyped(character, modifiers) : super.charTyped(character, modifiers);
    }
    @Override protected boolean hasClickedOutside(double x, double y, int guiLeft, int guiTop, int button) {
        if (rollOpen && x >= leftPos + 281 && x <= leftPos + 281 + ROLL_WIDTH && y >= rollY() && y <= rollY() + 220) return false;
        if (drawerOpen && x >= leftPos + 281 && x <= leftPos + 459 && y >= topPos && y <= topPos + 111) return false;
        if (y >= topPos - 22 && y < topPos && x >= leftPos && x < leftPos + imageWidth) return false;
        return super.hasClickedOutside(x, y, guiLeft, guiTop, button);
    }

    public List<Rect2i> extraAreas() {
        List<Rect2i> areas = new ArrayList<>();
        areas.add(new Rect2i(leftPos, topPos - 24, imageWidth, 24));
        if (guideWidth > 0) areas.add(new Rect2i(leftPos - guideWidth - 5, topPos + 18, guideWidth, 140));
        if (drawerOpen) areas.add(new Rect2i(leftPos + 281, topPos, 178, 111));
        if (rollOpen) areas.add(new Rect2i(leftPos + 281, rollY(), ROLL_WIDTH, rollSearch != null && rollSearch.isFocused() ? 220 : ROLL_HEIGHT));
        return areas;
    }
    private String fit(String text, int pixels) { return font.width(text) <= pixels ? text : font.plainSubstrByWidth(text, Math.max(0, pixels - 6)) + "…"; }
    private static Book book(MerchantOffer offer) {
        return bookStack(offer.getResult());
    }
    private static Book bookStack(ItemStack stack) {
        var enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        if (enchantments.size() != 1) return null;
        var entry = enchantments.entrySet().iterator().next();
        Holder<Enchantment> enchantment = entry.getKey();
        int level = entry.getIntValue();
        int multiplier = enchantment.is(EnchantmentTags.DOUBLE_TRADE_PRICE) ? 2 : 1;
        String name = Enchantment.getFullname(enchantment, level).getString();
        String id = enchantment.unwrapKey().map(key -> key.location().toString()).orElse("");
        return new Book(name, (name + " " + id).toLowerCase(Locale.ROOT), Math.min(64, (2 + 3 * level) * multiplier),
            Math.min(64, (6 + 13 * level) * multiplier));
    }
    private record Book(String name, String searchText, int minimum, int maximum) {
        int quality(int original) {
            double ratio = (original - minimum) / (double) Math.max(1, maximum - minimum);
            return ratio <= 1.0 / 3 ? 1 : ratio < 2.0 / 3 ? 2 : 3;
        }
        int color(int original) { return switch (quality(original)) { case 1 -> 0xFF55FF55; case 2 -> 0xFFFFD75A; default -> 0xFFFF5555; }; }
    }
}
