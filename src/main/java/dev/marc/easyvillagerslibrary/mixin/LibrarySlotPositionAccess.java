package dev.marc.easyvillagerslibrary.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Relocate only this menu's three side-panel slots when the archive is expanded. */
@Mixin(Slot.class)
public interface LibrarySlotPositionAccess {
    @Mutable @Accessor("y") void easyVillagersLibrary$setY(int y);
}
