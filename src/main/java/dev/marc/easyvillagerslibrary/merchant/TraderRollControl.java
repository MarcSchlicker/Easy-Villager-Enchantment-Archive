package dev.marc.easyvillagerslibrary.merchant;

import de.maxhenkel.easyvillagers.blocks.tileentity.TraderTileentityBase;
import de.maxhenkel.easyvillagers.entity.EasyVillagerEntity;
import dev.marc.easyvillagerslibrary.EasyVillagersEnchantmentLibrary;
import dev.marc.easyvillagerslibrary.blockentity.EnchantmentLibraryBlockEntity;
import dev.marc.easyvillagerslibrary.mixin.LibraryMerchantMenuMixin;
import dev.marc.easyvillagerslibrary.menu.LibraryMarketMenu;
import dev.marc.easyvillagerslibrary.network.TraderRollRequestPayload;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Re-rolls actual offers, without reopening the menu or stalling a server tick. */
@EventBusSubscriber(modid = EasyVillagersEnchantmentLibrary.MOD_ID)
public final class TraderRollControl {
    private static final Map<UUID, Roll> ACTIVE = new HashMap<>();
    private static final int MAX_ROLLS = 100_000;
    private TraderRollControl() { }
    public static boolean hasAdjacentLibrary(BlockEntity trader) {
        if (trader.getLevel() == null) return false;
        for (Direction direction : Direction.values()) {
            if (trader.getLevel().getBlockEntity(trader.getBlockPos().relative(direction)) instanceof EnchantmentLibraryBlockEntity) return true;
        }
        return false;
    }
    public static void request(ServerPlayer player, TraderRollRequestPayload payload) {
        requestInternal(player, payload, false);
    }
    public static void requestOnce(ServerPlayer player, int containerId) {
        requestInternal(player, new TraderRollRequestPayload(containerId, -1, ""), true);
    }
    private static void requestInternal(ServerPlayer player, TraderRollRequestPayload payload, boolean single) {
        if (player.containerMenu.containerId != payload.containerId() || !(player.containerMenu instanceof MerchantMenu menu)
            || !menu.stillValid(player)) return;
        EasyVillagerEntity villager;
        TraderTileentityBase trader = null;
        if (menu instanceof LibraryMarketMenu libraryMenu) {
            villager = libraryMenu.rollerVillager();
            if (villager == null || !libraryMenu.panelAvailable()) { message(player, "Open the Library Trader and insert a villager."); return; }
        } else {
            if (!(menu instanceof LibraryMerchantMenuMixin access)
                || !(access.easyVillagersLibrary$getMerchant() instanceof EasyVillagerEntity realVillager)) return;
            villager = realVillager;
            BlockEntity entity = player.level().getBlockEntity(villager.blockPosition().below());
            if (!(entity instanceof TraderTileentityBase realTrader) || realTrader.getVillagerEntity() != villager || !hasAdjacentLibrary(realTrader)) {
                message(player, "A Library must touch this Trader."); return;
            }
            trader = realTrader;
        }
        if (villager.isBaby() || villager.getVillagerXp() != 0 || villager.getVillagerData().getProfession() != VillagerProfession.LIBRARIAN) {
            message(player, "Only a fresh, untraded Librarian can be rolled."); return;
        }
        if (single) {
            // A manual click stops an in-progress search and generates exactly one new offer set.
            ACTIVE.remove(player.getUUID());
            returnPayments(player, menu);
            new Roll(player, menu, villager, trader, Set.of(), false).once();
            return;
        }
        var registry = player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Set<Enchantment> targets = new HashSet<>();
        boolean exact = payload.rawEnchantmentId() >= 0;
        String needle = payload.query().trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        for (Holder.Reference<Enchantment> holder : registry.holders().toList()) {
            if (!holder.is(EnchantmentTags.TRADEABLE)) continue;
            String id = holder.key().location().toString().replace('_', ' ');
            if (exact ? registry.getId(holder.value()) == payload.rawEnchantmentId() : !needle.isEmpty() && id.contains(needle)) targets.add(holder.value());
        }
        if (targets.isEmpty()) { message(player, "No tradeable enchantment matches. Choose a search result."); return; }
        returnPayments(player, menu);
        Roll roll = new Roll(player, menu, villager, trader, targets, exact);
        ACTIVE.put(player.getUUID(), roll);
        if (roll.step(12_000_000L)) ACTIVE.remove(player.getUUID());
    }
    private static void returnPayments(ServerPlayer player, MerchantMenu menu) {
        if (menu instanceof LibraryMarketMenu libraryMenu) libraryMenu.startTraderRoll();
        else for (int slot = 0; slot < 2; slot++) {
            ItemStack payment = menu.slots.get(slot).getItem();
            menu.slots.get(slot).set(ItemStack.EMPTY);
            player.getInventory().placeItemBackInInventory(payment);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var iterator = ACTIVE.values().iterator();
        long deadline = System.nanoTime() + 8_000_000L;
        while (iterator.hasNext() && System.nanoTime() < deadline) {
            if (iterator.next().step(Math.max(1, deadline - System.nanoTime()))) iterator.remove();
        }
    }
    private static void message(ServerPlayer player, String text) { player.displayClientMessage(Component.literal(text), true); }
    private static final class Roll {
        private final ServerPlayer player;
        private final MerchantMenu menu;
        private final EasyVillagerEntity villager;
        private final EasyVillagerEntity candidate;
        private final TraderTileentityBase trader;
        private final Set<Enchantment> targets;
        private final boolean highest;
        private int attempts;
        private Roll(ServerPlayer player, MerchantMenu menu, EasyVillagerEntity villager, TraderTileentityBase trader,
                     Set<Enchantment> targets, boolean highest) {
            this.player = player; this.menu = menu; this.villager = villager; this.trader = trader; this.targets = targets; this.highest = highest;
            // Search on a detached entity. Until a match exists, native payments/output/stock stay unchanged.
            candidate = new EasyVillagerEntity(EntityType.VILLAGER, villager.level());
            CompoundTag saved = new CompoundTag();
            villager.addAdditionalSaveData(saved);
            candidate.readAdditionalSaveData(saved);
        }
        private void rerollCandidate() {
            // recalculateOffers alone changes prices, not the generated trades.
            candidate.setOffers(null);
            candidate.recalculateOffers();
            attempts++;
        }
        private void commitCandidate() {
            villager.setOffers(candidate.getOffers());
            EnchantmentLibraryBlockEntity.applyPlayerPrices(villager, player);
        }
        private void saveVillager() {
            if (menu instanceof LibraryMarketMenu panel) panel.saveRoller();
            else { trader.saveVillagerEntity(); trader.setChanged(); }
        }
        private void syncNativeOffers(int selected) {
            menu.setOffers(villager.getOffers());
            menu.setSelectionHint(selected);
            menu.slotsChanged(player.getInventory());
            player.sendMerchantOffers(menu.containerId, villager.getOffers(), villager.getVillagerData().getLevel(),
                villager.getVillagerXp(), true, true);
        }
        private void once() {
            rerollCandidate();
            commitCandidate();
            saveVillager();
            if (menu instanceof LibraryMarketMenu panel) panel.finishSingleRoll();
            else syncNativeOffers(0);
            menu.broadcastChanges();
            message(player, "Rolled once.");
        }
        private int match() {
            for (int index = 0; index < candidate.getOffers().size(); index++) {
                for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(candidate.getOffers().get(index).getResult()).entrySet()) {
                    if (targets.contains(entry.getKey().value()) && (!highest || entry.getIntValue() == entry.getKey().value().getMaxLevel())) return index;
                }
            }
            return -1;
        }
        private boolean step(long budget) {
            boolean sourceValid = menu instanceof LibraryMarketMenu panel
                ? panel.panelAvailable() && panel.rollerVillager() == villager
                : trader != null && !trader.isRemoved() && trader.getVillagerEntity() == villager && hasAdjacentLibrary(trader);
            if (player.hasDisconnected() || player.containerMenu != menu || villager.getVillagerXp() != 0
                || !sourceValid || !menu.stillValid(player)) {
                if (player.containerMenu == menu && menu instanceof LibraryMarketMenu panel && panel.panelAvailable())
                    panel.updateTraderRoll(attempts, true, -1, "Rolling stopped: the villager or Trader changed.");
                return true;
            }
            long deadline = System.nanoTime() + budget;
            // Every click searches NEW offers, even when the current villager already matches.
            int found = attempts == 0 ? -1 : match();
            while (found < 0 && attempts < MAX_ROLLS && System.nanoTime() < deadline) {
                // Easy Villagers' recalculateOffers only recalculates prices.
                // GuiEvents.onCycleTrades clears offers first to trigger actual trade generation.
                rerollCandidate(); found = match();
            }
            if (found < 0 && attempts < MAX_ROLLS) {
                String status = "Rolling... " + attempts + " attempts";
                if (menu instanceof LibraryMarketMenu panel) panel.updateTraderRoll(attempts, false, -1, status);
                else message(player, status);
                return false;
            }
            if (found >= 0) {
                commitCandidate();
            }
            saveVillager();
            String status = found >= 0 ? "Book found after " + attempts + " rolls." : "No matching trade after " + MAX_ROLLS + " rolls. Villager preserved.";
            if (menu instanceof LibraryMarketMenu panel) panel.updateTraderRoll(attempts, true, found, status);
            else syncNativeOffers(found >= 0 ? found : 0);
            menu.broadcastChanges();
            message(player, status);
            return true;
        }
    }
}
