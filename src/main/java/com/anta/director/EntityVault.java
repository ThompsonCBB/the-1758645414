package com.anta.director;

import com.anta.AntaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Creatures that are "gone for a while" (the empty village, the dead day). Each one is saved here with
 * its full data before it is removed from the world, and put back, unchanged, at its old place when its
 * time is up. Nothing is ever lost: the vault is saved inside the world (data/anta_vault.dat), so it
 * survives quitting the game; an entity whose chunk is not loaded at release time waits until it is.
 *
 * While the dead day is active nothing is released (the world must stay empty).
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID)
public final class EntityVault extends SavedData {
    private static final String NAME = "anta_vault";
    /** Safety cap: beyond this many stored creatures, the rest are simply left in the world. */
    public static final int MAX_ENTRIES = 4000;

    /** True while the vault itself is adding entities back (so DeadWorld does not block them). */
    static boolean restoring = false;

    private static final class Entry {
        CompoundTag data;
        BlockPos pos;
        long dueDayTime, dueGameTime; // released when either clock reaches its value
        boolean afterDeadDay;         // released as soon as the dead day is over
    }

    private final List<Entry> entries = new ArrayList<>();

    public static EntityVault get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(EntityVault::load, EntityVault::new, NAME);
    }

    public int size() {
        return entries.size();
    }

    public boolean isFull() {
        return entries.size() >= MAX_ENTRIES;
    }

    private boolean contains(java.util.UUID id) {
        for (Entry e : entries) if (e.data.hasUUID("UUID") && e.data.getUUID("UUID").equals(id)) return true;
        return false;
    }

    /**
     * Saves the entity (with its passengers) and removes it from the world.
     *
     * @param forTicks gone for this many ticks (one day = 24000); &lt;= 0 means "until the dead day ends"
     * @return false if it could not be saved (then it is NOT removed)
     */
    public boolean store(ServerLevel level, Entity root, long forTicks) {
        if (isFull()) return false;
        CompoundTag tag = new CompoundTag();
        if (!root.save(tag)) return false; // passengers, players and unsaveable entities stay
        Entry e = new Entry();
        e.data = tag;
        e.pos = root.blockPosition();
        if (forTicks > 0) {
            e.dueDayTime = level.getDayTime() + forTicks;
            e.dueGameTime = level.getGameTime() + forTicks;
        } else {
            e.afterDeadDay = true;
            e.dueDayTime = e.dueGameTime = Long.MAX_VALUE;
        }
        entries.add(e);
        setDirty();
        root.getPassengersAndSelf().toList().forEach(Entity::discard);
        return true;
    }

    /** Saves an entity that is being loaded from disk during the dead day (it never enters the world). */
    public boolean storeLoading(Entity entity) {
        if (isFull()) return false;
        if (contains(entity.getUUID())) return true; // already kept (its chunk was saved before we took it): no copy
        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return false;
        Entry e = new Entry();
        e.data = tag;
        e.pos = entity.blockPosition();
        e.afterDeadDay = true;
        e.dueDayTime = e.dueGameTime = Long.MAX_VALUE;
        entries.add(e);
        setDirty();
        return true;
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % 20 != 0) return;
        EntityVault vault = level.getDataStorage().get(EntityVault::load, NAME);
        if (vault == null || vault.entries.isEmpty()) return;
        try {
            vault.release(level);
        } catch (RuntimeException ex) {
            AntaMod.LOGGER.error("Could not put stored creatures back yet, will retry", ex);
        }
    }

    private void release(ServerLevel level) {
        if (DeadWorld.isActive(level)) return;
        long day = level.getDayTime(), game = level.getGameTime();
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            boolean due = e.afterDeadDay || day >= e.dueDayTime || game >= e.dueGameTime;
            if (!due) continue;
            // Wait until its chunk and the chunk's entities are loaded: then it goes back exactly where it was.
            if (!level.isLoaded(e.pos) || !level.areEntitiesLoaded(ChunkPos.asLong(e.pos))) continue;
            Entity root = EntityType.loadEntityRecursive(e.data, level, x -> x);
            if (root != null) {
                restoring = true;
                try {
                    if (!level.tryAddFreshEntityWithPassengers(root)) {
                        AntaMod.LOGGER.warn("Stored creature {} already exists, not added twice", root.getUUID());
                    }
                } finally {
                    restoring = false;
                }
            }
            it.remove();
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag t = new CompoundTag();
            t.put("data", e.data);
            t.putLong("pos", e.pos.asLong());
            t.putLong("dueDay", e.dueDayTime);
            t.putLong("dueGame", e.dueGameTime);
            t.putBoolean("afterDead", e.afterDeadDay);
            list.add(t);
        }
        tag.put("entries", list);
        return tag;
    }

    private static EntityVault load(CompoundTag tag) {
        EntityVault v = new EntityVault();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Entry e = new Entry();
            e.data = t.getCompound("data");
            e.pos = BlockPos.of(t.getLong("pos"));
            e.dueDayTime = t.getLong("dueDay");
            e.dueGameTime = t.getLong("dueGame");
            e.afterDeadDay = t.getBoolean("afterDead");
            v.entries.add(e);
        }
        return v;
    }
}
