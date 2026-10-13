package dev.marc.easyvillagerslibrary.mixin;

import de.maxhenkel.easyvillagers.blocks.tileentity.TraderTileentityBase;
import dev.marc.easyvillagerslibrary.merchant.TraderRollControl;
import dev.marc.easyvillagerslibrary.network.TraderRollViewPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Advertises the roll controls only for Traders directly touching a Library. */
@Mixin(TraderTileentityBase.class)
public abstract class TraderTileentityMixin {
    @Inject(method = "openTradingGUI", at = @At("TAIL"), remap = false)
    private void easyVillagersLibrary$enableLibraryRollControls(Player player, CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ() || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        TraderTileentityBase trader = (TraderTileentityBase) (Object) this;
        if (TraderRollControl.hasAdjacentLibrary(trader)) {
            PacketDistributor.sendToPlayer(serverPlayer, new TraderRollViewPayload(serverPlayer.containerMenu.containerId));
        }
    }
}
