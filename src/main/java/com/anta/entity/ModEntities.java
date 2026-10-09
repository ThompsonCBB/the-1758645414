package com.anta.entity;

import com.anta.AntaMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AntaMod.MODID);

    public static final RegistryObject<EntityType<WatcherEntity>> WATCHER = ENTITIES.register("watcher",
            () -> EntityType.Builder.<WatcherEntity>of(WatcherEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(10)
                    .build("watcher"));

    public static final RegistryObject<EntityType<CarcassEntity>> CARCASS = ENTITIES.register("carcass",
            () -> EntityType.Builder.<CarcassEntity>of(CarcassEntity::new, MobCategory.MISC)
                    .sized(1.0f, 0.5f)
                    .clientTrackingRange(8)
                    .updateInterval(100) // never moves
                    .build("carcass"));

    private ModEntities() {}
}
