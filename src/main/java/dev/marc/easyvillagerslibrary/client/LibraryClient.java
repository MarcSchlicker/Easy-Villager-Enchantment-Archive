package dev.marc.easyvillagerslibrary.client;

import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.blockentity.EnchantmentLibraryBlockEntity;
import dev.marc.easyvillagerslibrary.menu.EnchantmentLibraryMenu;
import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import de.maxhenkel.easyvillagers.items.ModItems;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The fixed Library UI used by every ordinary right-click on the block. */
@EventBusSubscriber(modid = EasyVillagersEnchantmentLibrary.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LibraryClient {
    private LibraryClient() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(LibraryRegistries.ENCHANTMENT_LIBRARY_MENU.get(), EnchantmentLibraryScreen::new);
        event.register(LibraryRegistries.LIBRARY_MARKET_MENU.get(), LibraryMarketScreen::new);
    }

    private static final class EnchantmentLibraryScreen extends AbstractContainerScreen<EnchantmentLibraryMenu> {
        private static final int LEFT_WIDTH = 178;
        private static final int RIGHT_X = 184;
        private static final int RIGHT_WIDTH = 172;
        private static final int TOP_CONTENT_BOTTOM = 160;
        private static final int ROLL_MATCH_ROWS = 3;
        private static final int BOOK_ROW_Y = 48;
        private static final int BOOK_ROW_HEIGHT = 20;

        private EditBox bookFilter;
        private EditBox rollFilter;
        private final List<Button> rollMatchButtons = new ArrayList<>();
        private List<RollChoice> rollChoices = List.of();

        private EnchantmentLibraryScreen(EnchantmentLibraryMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
            imageWidth = 360;
            imageHeight = 248;
            inventoryLabelY = 153;
        }

        @Override
        protected void init() {
            super.init();
            bookFilter = new EditBox(font, leftPos + 7, topPos + 29, 137, 12, Component.literal("Filter books"));
            bookFilter.setHint(Component.literal("Filter books..."));
            bookFilter.setMaxLength(64);
            addRenderableWidget(bookFilter);

            addRenderableWidget(Button.builder(Component.literal("Books"), ignored -> selectPage(0))
                .bounds(leftPos + 7, topPos + 6, 42, 14).build());
            addRenderableWidget(Button.builder(Component.literal("Villagers"), ignored -> selectPage(1))
                .bounds(leftPos + 52, topPos + 6, 65, 14).build());
            addRenderableWidget(Button.builder(Component.literal("Open trades"), ignored -> openBookMarket())
                .bounds(leftPos + 120, topPos + 6, 54, 14).build());
        }

        private void selectPage(int page) {
            Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, page);
        }

        private void openBookMarket() {
            Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId,
                EnchantmentLibraryMenu.OPEN_BOOK_MARKET_BUTTON);
        }

        @Override
        protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
            // This is a self-contained canvas. It does not inject widgets into
            // MerchantScreen, so ATM10/Easy Villagers cannot hide the controls.
            graphics.fill(leftPos - 3, topPos - 3, leftPos + imageWidth + 3, topPos + imageHeight + 3, 0xFF202020);
            graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFFC6C6C6);
            graphics.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xFF8B8B8B);

            drawPanel(graphics, leftPos + 4, topPos + 25, LEFT_WIDTH - 8, TOP_CONTENT_BOTTOM - 25);
            drawPanel(graphics, leftPos + RIGHT_X, topPos + 25, RIGHT_WIDTH, 198);
            graphics.fill(leftPos + 4, topPos + 163, leftPos + LEFT_WIDTH - 4, topPos + imageHeight - 4, 0xFFC6C6C6);

            if (menu.isBookView()) {
                renderBookRows(graphics);
            } else {
                renderVillagerPage(graphics);
            }
            renderTraderRollHint(graphics);

            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 9; column++) {
                    drawSlot(graphics, leftPos + 8 + column * 18, topPos + 164 + row * 18);
                }
            }
            for (int column = 0; column < 9; column++) {
                drawSlot(graphics, leftPos + 8 + column * 18, topPos + 224);
            }
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            bookFilter.visible = menu.isBookView();
            if (!bookFilter.visible) {
                bookFilter.setFocused(false);
            }
            super.render(graphics, mouseX, mouseY, partialTick);
            renderBookTooltip(graphics, mouseX, mouseY);
        }

        private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
            graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF373737);
            graphics.fill(x, y, x + width, y + height, 0xFF555555);
            graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xFFAAAAAA);
        }

        private static void drawSlot(GuiGraphics graphics, int x, int y) {
            graphics.fill(x, y, x + 18, y + 18, 0xFF373737);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
            graphics.fill(x + 2, y + 2, x + 16, y + 16, 0xFF555555);
        }

        private void renderBookRows(GuiGraphics graphics) {
            String filter = bookFilter.getValue().trim().toLowerCase(Locale.ROOT);
            for (int row = 0; row < EnchantmentLibraryBlockEntity.BOOK_TRADE_ROWS; row++) {
                ItemStack book = menu.summaryAt(row);
                int x = leftPos + 7;
                int y = topPos + BOOK_ROW_Y + row * BOOK_ROW_HEIGHT;
                boolean matches = filter.isEmpty() || bookName(book).toLowerCase(Locale.ROOT).contains(filter);
                int background = matches ? 0xFFB8B8B8 : 0xFF777777;
                graphics.fill(x, y, x + 164, y + 18, 0xFF373737);
                graphics.fill(x + 1, y + 1, x + 163, y + 17, background);
                if (book.isEmpty() || !matches) {
                    continue;
                }
                TradePrice price = tradePrice(book);
                graphics.renderItem(new ItemStack(Items.EMERALD), x + 3, y + 1);
                graphics.drawString(font, Integer.toString(price == null ? 0 : price.emeralds()), x + 18, y + 5,
                    price != null && price.cheapest() ? 0xFF2BAA2B : 0xFFAA3333, false);
                graphics.renderItem(new ItemStack(Items.BOOK), x + 45, y + 1);
                graphics.drawString(font, Component.literal("→"), x + 66, y + 5, 0xFF3F3F3F, false);
                graphics.renderItem(book, x + 82, y + 1);
                graphics.renderItemDecorations(font, book, x + 82, y + 1);
                graphics.drawString(font, Component.literal(fit(bookName(book), 57)), x + 102, y + 5, 0xFF2B2B2B, false);
            }
        }

        private void renderVillagerPage(GuiGraphics graphics) {
            graphics.drawString(font, Component.literal("Left-click: trades   Right-click: remove"), leftPos + 8, topPos + 31,
                0xFF3F3F3F, false);
            for (int index = 0; index < EnchantmentLibraryBlockEntity.SUMMARY_SLOTS; index++) {
                drawSlot(graphics, leftPos + 8 + (index % 9) * 18, topPos + 48 + (index / 9) * 18);
            }
        }

        private void renderTraderRollHint(GuiGraphics graphics) {
            int x = leftPos + RIGHT_X;
            int y = topPos;
            graphics.drawString(font, Component.literal("INSERT VILLAGER"), x + 6, y + 29, 0xFF3F3F3F, false);
            drawSlot(graphics, x + 7, y + 38);
            graphics.drawString(font, Component.literal("Pick up an Easy Villager"), x + 31, y + 42, 0xFF3F3F3F, false);
            graphics.drawString(font, Component.literal("and click this slot."), x + 31, y + 56, 0xFF3F3F3F, false);
            graphics.drawString(font, Component.literal("Shift-clicking one below"), x + 6, y + 86, 0xFF3F3F3F, false);
            graphics.drawString(font, Component.literal("also inserts it directly."), x + 6, y + 100, 0xFF3F3F3F, false);
            graphics.drawString(font, Component.literal("TRADER ROLLING"), x + 6, y + 132, 0xFF3F3F3F, false);
            graphics.drawString(font, Component.literal("Put this Library next to"), x + 6, y + 151, 0xFF3F3F3F, false);
            graphics.drawString(font, Component.literal("an Easy Villagers Trader."), x + 6, y + 165, 0xFF3F3F3F, false);
        }

        @Override
        protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.drawString(font, Component.literal("Enchantment Library"), 8, 7, 0xFF3F3F3F, false);
            if (menu.isBookView()) {
                graphics.drawString(font, Component.literal("Highest-level books — click a row to trade"), 8, 36, 0xFF3F3F3F, false);
            } else {
                graphics.drawString(font, Component.literal("Stored finished villagers"), 8, 36, 0xFF3F3F3F, false);
            }
            graphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int insertX = leftPos + RIGHT_X + 7;
            int insertY = topPos + 38;
            if (button == 0 && mouseX >= insertX && mouseX < insertX + 18
                && mouseY >= insertY && mouseY < insertY + 18
                && menu.getCarried().is(ModItems.VILLAGER.get())) {
                Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId,
                    EnchantmentLibraryMenu.INSERT_CARRIED_VILLAGER_BUTTON);
                return true;
            }
            if (menu.isBookView() && button == 0 && mouseX >= leftPos + 7 && mouseX < leftPos + LEFT_WIDTH - 4
                && mouseY >= topPos + BOOK_ROW_Y && mouseY < topPos + BOOK_ROW_Y + BOOK_ROW_HEIGHT * EnchantmentLibraryBlockEntity.BOOK_TRADE_ROWS) {
                int row = (int) ((mouseY - (topPos + BOOK_ROW_Y)) / BOOK_ROW_HEIGHT);
                ItemStack book = menu.summaryAt(row);
                String filter = bookFilter.getValue().trim().toLowerCase(Locale.ROOT);
                if (!book.isEmpty() && (filter.isEmpty() || bookName(book).toLowerCase(Locale.ROOT).contains(filter))) {
                    openBookMarket();
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (handleFocusedInput(bookFilter, keyCode, scanCode, modifiers, false)) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        private boolean handleFocusedInput(EditBox box, int keyCode, int scanCode, int modifiers, boolean chooseOnEnter) {
            if (box == null || !box.isFocused()) {
                return false;
            }
            if (chooseOnEnter && (keyCode == 257 || keyCode == 335)) {
                chooseRollChoice(0);
                return true;
            }
            if (keyCode == 256) {
                box.setFocused(false);
                return true;
            }
            // Consume E here so Minecraft does not close the screen before
            // charTyped inserts it into either search field.
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }

        @Override
        public boolean charTyped(char character, int modifiers) {
            if (bookFilter.isFocused() && bookFilter.charTyped(character, modifiers)) {
                return true;
            }
            return super.charTyped(character, modifiers);
        }

        private void updateRollChoices() {
            String filter = rollFilter == null ? "" : rollFilter.getValue().trim().toLowerCase(Locale.ROOT);
            if (filter.isEmpty() || Minecraft.getInstance().level == null) {
                rollChoices = List.of();
            } else {
                var enchantments = Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
                rollChoices = enchantments.holders()
                    .map(holder -> RollChoice.of(holder, holder.value().getMaxLevel(), enchantments.getId(holder.value())))
                    .filter(choice -> choice.searchText().contains(filter))
                    .sorted(Comparator.comparing(RollChoice::name))
                    .limit(ROLL_MATCH_ROWS)
                    .toList();
            }
            for (int index = 0; index < rollMatchButtons.size(); index++) {
                if (index < rollChoices.size()) {
                    rollMatchButtons.get(index).setMessage(Component.literal(fit(rollChoices.get(index).name(), 141)));
                }
            }
        }

        private void chooseRollChoice(int index) {
            if (index < 0 || index >= rollChoices.size()) {
                return;
            }
            RollChoice choice = rollChoices.get(index);
            Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId,
                EnchantmentLibraryMenu.rollTargetButtonId(choice.rawRegistryId()));
        }

        private void renderBookTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
            if (!menu.isBookView() || mouseX < leftPos + 7 || mouseX >= leftPos + LEFT_WIDTH - 4
                || mouseY < topPos + BOOK_ROW_Y || mouseY >= topPos + BOOK_ROW_Y + BOOK_ROW_HEIGHT * EnchantmentLibraryBlockEntity.BOOK_TRADE_ROWS) {
                return;
            }
            int row = (mouseY - (topPos + BOOK_ROW_Y)) / BOOK_ROW_HEIGHT;
            ItemStack book = menu.summaryAt(row);
            if (book.isEmpty()) {
                return;
            }
            TradePrice price = tradePrice(book);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(bookName(book)).withStyle(ChatFormatting.AQUA));
            if (price != null) {
                lines.add(Component.literal((price.cheapest() ? "Cheapest" : "Higher") + " original price: " + price.emeralds() + " emeralds")
                    .withStyle(price.cheapest() ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            lines.add(Component.literal("Click to open all real trades").withStyle(ChatFormatting.GRAY));
            graphics.renderTooltip(font, lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }

        private static TradePrice tradePrice(ItemStack stack) {
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            if (name == null) {
                return null;
            }
            String text = name.getString();
            int bestIndex = text.indexOf(" | BEST ");
            int moreIndex = text.indexOf(" | MORE ");
            int marker = bestIndex >= 0 ? bestIndex : moreIndex;
            if (marker < 0) {
                return null;
            }
            int start = marker + 8;
            int end = text.indexOf(" |", start);
            try {
                return new TradePrice(Integer.parseInt(end < 0 ? text.substring(start) : text.substring(start, end)), bestIndex >= 0);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static String bookName(ItemStack stack) {
            if (stack.isEmpty()) {
                return "";
            }
            Component customName = stack.get(DataComponents.CUSTOM_NAME);
            String custom = customName == null ? "" : customName.getString();
            int divider = custom.indexOf(" | ");
            if (divider > 0) {
                return custom.substring(0, divider);
            }
            var enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
            if (stack.is(Items.ENCHANTED_BOOK) && enchantments.size() == 1) {
                Object2IntMap.Entry<Holder<Enchantment>> entry = enchantments.entrySet().iterator().next();
                return Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString();
            }
            return stack.getHoverName().getString();
        }

        private String fit(String text, int maxWidth) {
            if (font.width(text) <= maxWidth) {
                return text;
            }
            int end = text.length();
            while (end > 0 && font.width(text.substring(0, end) + "…") > maxWidth) {
                end--;
            }
            return end <= 0 ? "…" : text.substring(0, end) + "…";
        }

        private record RollChoice(String name, String searchText, int rawRegistryId) {
            private static RollChoice of(Holder<Enchantment> enchantment, int level, int rawRegistryId) {
                String id = enchantment.unwrapKey().map(key -> key.location().toString()).orElse("unknown");
                String name = Enchantment.getFullname(enchantment, level).getString();
                return new RollChoice(name, (name + " " + id).toLowerCase(Locale.ROOT), rawRegistryId);
            }
        }

        private record TradePrice(int emeralds, boolean cheapest) {
        }
    }
}
