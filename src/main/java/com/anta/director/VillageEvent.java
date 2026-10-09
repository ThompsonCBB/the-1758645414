package com.anta.director;

import com.anta.AntaConfig;
import com.anta.AntaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

/**
 * "The village is empty": once per player per world, you sleep through the night inside a GENERATED
 * village and in the morning its villagers and iron golems (and, by config, its animals) are gone
 * without a trace - for exactly one day; then they are back in their places (EntityVault). Only
 * entities inside that village's structure bounds are touched, so a player's own trading hall or farm
 * elsewhere is never affected. Blocks are never touched. Named, leashed, tamed and ridden creatures
 * are always spared.
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID)
public final class VillageEvent {
    public static final String KEY_DONE = "anta_village_done";
    /** Extra margin around the village structure's bounding box, blocks. */
    public static final int MARGIN = 8;

    private VillageEvent() {}

    @SubscribeEvent
    public static void onWakeUp(PlayerWakeUpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!AntaConfig.EMPTY_VILLAGE.get()) return;
        if (!Director.worldChangesAllowed(player)) return;
        ServerLevel level = player.serverLevel();
        if (level.getDayTime() % 24000L > 2000L) return; // only when the night was slept through
        CompoundTag tag = Director.persisted(player);
        if (tag.getBoolean(KEY_DONE)) return;
        if (tag.getInt(Director.KEY_SIGHTINGS) < AntaConfig.EMPTY_VILLAGE_MIN_SIGHTINGS.get()) return;
        BlockPos bed = player.getSleepingPos().orElse(player.blockPosition());
        int n;
        try {
            n = empty(level, bed);
        } catch (RuntimeException ex) {
            AntaMod.LOGGER.error("Empty village event failed, skipped", ex);
            return;
        }
        if (n > 0) {
            tag.putBoolean(KEY_DONE, true);
            Director.say(player, String.format(Locale.ROOT, "village: %d creatures are gone", n));
        }
    }

    /**
     * Takes away the villagers, golems and (by config) animals of the generated village at {@code pos}
     * for exactly one day (they are kept in EntityVault and come back to the same places).
     * Returns how many were taken; -1 if {@code pos} is not inside a generated village.
     */
    public static int empty(ServerLevel level, BlockPos pos) {
        StructureStart village = level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE);
        if (!village.isValid()) return -1;
        AABB box = AABB.of(village.getBoundingBox()).inflate(MARGIN);
        List<LivingEntity> all = level.getEntitiesOfClass(LivingEntity.class, box, VillageEvent::shouldVanish);
        EntityVault vault = EntityVault.get(level);
        int n = 0;
        for (LivingEntity e : all) {
            if (!e.isRemoved() && vault.store(level, e, DeadWorld.DAY)) n++;
        }
        return n;
    }

    private static boolean shouldVanish(LivingEntity e) {
        if (e.hasCustomName() || e.isVehicle() || e.isPassenger()) return false;
        if (e instanceof AbstractVillager || e instanceof IronGolem) return true;
        if (e instanceof Animal a && AntaConfig.EMPTY_VILLAGE_ANIMALS.get()) {
            if (a.isLeashed()) return false;
            if (a instanceof TamableAnimal t && t.isTame()) return false;
            if (a instanceof AbstractHorse h && h.isTamed()) return false;
            return true;
        }
        return false;
    }
}
