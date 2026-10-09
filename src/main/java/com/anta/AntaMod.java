package com.anta;

import com.anta.entity.ModEntities;
import com.anta.entity.WatcherEntity;
import com.anta.network.AntaNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(AntaMod.MODID)
public class AntaMod {
    public static final String MODID = "anta";
    public static final Logger LOGGER = LogUtils.getLogger();

    @SuppressWarnings("removal") // FMLJavaModLoadingContext.get(): the form every Forge 47.x build supports
    public AntaMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITIES.register(modBus);
        com.anta.sound.ModSounds.SOUNDS.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AntaConfig.SPEC, "the1758645414-common.toml");
        AntaNetwork.register();
        modBus.addListener(this::onAttributes);
    }

    private void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.WATCHER.get(), WatcherEntity.createAttributes().build());
    }
}
