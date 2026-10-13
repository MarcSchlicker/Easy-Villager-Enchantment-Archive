package dev.marc.easyvillagerslibrary;

import dev.marc.easyvillagerslibrary.registry.LibraryRegistries;
import dev.marc.easyvillagerslibrary.network.LibraryMerchantViewPayload;
import dev.marc.easyvillagerslibrary.network.TraderRollViewPayload;
import dev.marc.easyvillagerslibrary.network.TraderRollRequestPayload;
import dev.marc.easyvillagerslibrary.network.TraderSingleRollRequestPayload;
import dev.marc.easyvillagerslibrary.network.LibraryTraderActionPayload;
import dev.marc.easyvillagerslibrary.network.LibraryTraderViewPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(EasyVillagersEnchantmentLibrary.MOD_ID)
public final class EasyVillagersEnchantmentLibrary {
    public static final String MOD_ID = "easy_villagers_enchantment_library";

    public EasyVillagersEnchantmentLibrary(IEventBus modBus) {
        LibraryRegistries.register(modBus);
        modBus.addListener(this::registerPayloads);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MOD_ID);
        registrar.playToClient(LibraryMerchantViewPayload.TYPE, LibraryMerchantViewPayload.STREAM_CODEC,
            LibraryMerchantViewPayload::handle);
        registrar.playToClient(TraderRollViewPayload.TYPE, TraderRollViewPayload.STREAM_CODEC,
            TraderRollViewPayload::handle);
        registrar.playToServer(TraderRollRequestPayload.TYPE, TraderRollRequestPayload.STREAM_CODEC,
            TraderRollRequestPayload::handle);
        registrar.playToServer(TraderSingleRollRequestPayload.TYPE, TraderSingleRollRequestPayload.STREAM_CODEC,
            TraderSingleRollRequestPayload::handle);
        registrar.playToServer(LibraryTraderActionPayload.TYPE, LibraryTraderActionPayload.STREAM_CODEC,
            LibraryTraderActionPayload::handle);
        registrar.playToClient(LibraryTraderViewPayload.TYPE, LibraryTraderViewPayload.STREAM_CODEC,
            LibraryTraderViewPayload::handle);
    }
}
