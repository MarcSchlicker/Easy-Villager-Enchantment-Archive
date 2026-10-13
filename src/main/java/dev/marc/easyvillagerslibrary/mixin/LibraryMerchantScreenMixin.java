package dev.marc.easyvillagerslibrary.mixin;

import dev.marc.easyvillagerslibrary.client.LibraryMerchantScreenAccess;
import dev.marc.easyvillagerslibrary.client.TraderRollViewState;
import dev.marc.easyvillagerslibrary.client.TraderRollChoice;
import dev.marc.easyvillagerslibrary.merchant.RollTargetSearch;
import dev.marc.easyvillagerslibrary.network.TraderRollRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Only augments a real adjacent Easy Villagers Trader; Library has its own screen now. */
@Mixin(MerchantScreen.class)
public abstract class LibraryMerchantScreenMixin extends AbstractContainerScreen<MerchantMenu> implements LibraryMerchantScreenAccess {
    @Unique private EditBox easyVillagersLibrary$search;
    @Unique private final List<Button> easyVillagersLibrary$choices = new ArrayList<>();
    @Unique private List<TraderRollChoice> easyVillagersLibrary$matches = List.of();
    @Unique private int easyVillagersLibrary$selected = -1;
    @Unique private boolean easyVillagersLibrary$installed;
    private LibraryMerchantScreenMixin(MerchantMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void easyVillagersLibrary$resetControls(CallbackInfo callback) {
        easyVillagersLibrary$installed = false;
        easyVillagersLibrary$search = null;
        easyVillagersLibrary$choices.clear();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void easyVillagersLibrary$addControls(GuiGraphics graphics, int mouseX, int mouseY, float tick, CallbackInfo callback) {
        if (!easyVillagersLibrary$isTraderRollScreen()) return;
        if (!easyVillagersLibrary$installed) easyVillagersLibrary$install();
        for (int i = 0; i < easyVillagersLibrary$choices.size(); i++) {
            easyVillagersLibrary$choices.get(i).visible = easyVillagersLibrary$search.isFocused() && i < easyVillagersLibrary$matches.size();
        }
        // Background before vanilla render, widgets after it. No overlay on native book rows.
        graphics.fill(leftPos, topPos - 40, leftPos + 207, topPos - 2, 0xFF252525);
        graphics.drawString(font, "Library auto-roll · choose a book", leftPos + 5, topPos - 36, 0xFFE0E0E0, false);
    }

    @Unique private void easyVillagersLibrary$install() {
        easyVillagersLibrary$installed = true;
        easyVillagersLibrary$search = addRenderableWidget(new EditBox(font, leftPos + 5, topPos - 23, 146, 18, Component.literal("Roll target")));
        easyVillagersLibrary$search.setMaxLength(80);
        easyVillagersLibrary$search.setHint(Component.literal("Search enchantments..."));
        easyVillagersLibrary$search.setResponder(value -> {
            easyVillagersLibrary$selected = -1;
            easyVillagersLibrary$updateMatches();
        });
        addRenderableWidget(Button.builder(Component.literal("Roll"), button -> {
            int target = easyVillagersLibrary$selected;
            if (target < 0) target = easyVillagersLibrary$matches.stream()
                .filter(choice -> RollTargetSearch.exactName(easyVillagersLibrary$search.getValue(), choice.name()))
                .mapToInt(TraderRollChoice::rawId).findFirst().orElse(-1);
            if (!easyVillagersLibrary$search.getValue().isBlank()) PacketDistributor.sendToServer(new TraderRollRequestPayload(
                menu.containerId, target, easyVillagersLibrary$search.getValue()));
        }).bounds(leftPos + 155, topPos - 23, 48, 18).build());
        for (int i = 0; i < 3; i++) {
            final int index = i;
            Button choice = Button.builder(Component.empty(), button -> {
                if (index >= easyVillagersLibrary$matches.size()) return;
                TraderRollChoice selected = easyVillagersLibrary$matches.get(index);
                easyVillagersLibrary$search.setValue(selected.name());
                easyVillagersLibrary$selected = selected.rawId();
                easyVillagersLibrary$search.setFocused(false);
            }).bounds(leftPos + 5, topPos - 99 + i * 19, 198, 18).build();
            choice.visible = false;
            easyVillagersLibrary$choices.add(addRenderableWidget(choice));
        }
    }

    @Unique private void easyVillagersLibrary$updateMatches() {
        String needle = RollTargetSearch.normalize(easyVillagersLibrary$search.getValue());
        if (needle.isEmpty() || minecraft.level == null) easyVillagersLibrary$matches = List.of();
        else {
            var registry = minecraft.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            easyVillagersLibrary$matches = registry.holders().filter(holder -> holder.is(EnchantmentTags.TRADEABLE)).map(holder -> {
                String name = Enchantment.getFullname(holder, holder.value().getMaxLevel()).getString();
                String id = holder.key().location().toString();
                return new TraderRollChoice(registry.getId(holder.value()), name, RollTargetSearch.normalize(name + " " + id),
                    Component.translatable("enchantment." + holder.key().location().getNamespace() + "." + holder.key().location().getPath() + ".desc"));
            }).filter(choice -> RollTargetSearch.matches(needle, choice.search()))
                .sorted(Comparator.comparingInt((TraderRollChoice choice) -> RollTargetSearch.priority(needle, choice.name()))
                    .thenComparing(TraderRollChoice::name)).limit(3).toList();
        }
        for (int i = 0; i < easyVillagersLibrary$choices.size(); i++) {
            if (i >= easyVillagersLibrary$matches.size()) continue;
            TraderRollChoice choice = easyVillagersLibrary$matches.get(i);
            easyVillagersLibrary$choices.get(i).setMessage(Component.literal(choice.name()));
            easyVillagersLibrary$choices.get(i).setTooltip(Tooltip.create(choice.description()));
        }
    }

    // These are real overrides of inherited input methods, not optional failed injections.
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (easyVillagersLibrary$search != null && easyVillagersLibrary$search.isFocused()) {
            if (key == 256) { easyVillagersLibrary$search.setFocused(false); return true; }
            easyVillagersLibrary$search.keyPressed(key, scan, modifiers); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean charTyped(char character, int modifiers) {
        return easyVillagersLibrary$search != null && easyVillagersLibrary$search.isFocused()
            ? easyVillagersLibrary$search.charTyped(character, modifiers) : super.charTyped(character, modifiers);
    }
    @Override public boolean easyVillagersLibrary$isLibraryTradeScreen() { return false; }
    @Override public boolean easyVillagersLibrary$isTraderRollScreen() { return TraderRollViewState.isEnabled(menu.containerId); }
    @Override public int easyVillagersLibrary$rightPanelX() { return leftPos + imageWidth + 5; }
    @Override public int easyVillagersLibrary$rightPanelY() { return topPos; }
    @Override public boolean easyVillagersLibrary$handleLibraryFilterKey(int key, int scan, int modifiers) { return false; }
    @Override public boolean easyVillagersLibrary$handleLibraryFilterCharacter(char character, int modifiers) { return false; }
    @Override public boolean easyVillagersLibrary$handleDrawerScroll(double x, double y, double amount) { return false; }
}
