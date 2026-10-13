package dev.marc.easyvillagerslibrary.block;

import com.mojang.serialization.MapCodec;
import dev.marc.easyvillagerslibrary.blockentity.EnchantmentLibraryBlockEntity;
import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

public final class EnchantmentLibraryBlock extends BaseEntityBlock {
    public static final MapCodec<EnchantmentLibraryBlock> CODEC = simpleCodec(EnchantmentLibraryBlock::new);

    public EnchantmentLibraryBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnchantmentLibraryBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, LibraryRegistries.ENCHANTMENT_LIBRARY_BLOCK_ENTITY.get(),
            (serverLevel, blockPos, blockState, library) -> library.tickServer());
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        List<ItemStack> drops = super.getDrops(state, builder);
        if (builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof EnchantmentLibraryBlockEntity library) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    // The block item carries the complete persistent block-entity
                    // data. Placing it later restores the unlimited archive.
                    library.saveToItem(drop, builder.getLevel().registryAccess());
                }
            }
        }
        return drops;
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof EnchantmentLibraryBlockEntity library) {
            library.saveToItem(stack, level.registryAccess());
        }
        return stack;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof EnchantmentLibraryBlockEntity library
            && player instanceof ServerPlayer serverPlayer) {
            // Both interactions use the working merchant + real villager inventory,
            // not the old read-only management-preview menu.
            library.openBookMarket(player);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof EnchantmentLibraryBlockEntity library) {
            if (stack.is(de.maxhenkel.easyvillagers.items.ModItems.VILLAGER.get())) {
                if (!level.isClientSide) {
                    if (library.addVillager(stack)) stack.shrink(1);
                }
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
