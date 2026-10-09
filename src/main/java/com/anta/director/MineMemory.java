package com.anta.director;

import com.anta.AntaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers where players dug underground themselves: only coordinates of 8x8x8 cells with a count of
 * blocks broken there. Saved inside the world (data/anta_mine.dat), nothing else.
 * Used by the Director: coming back to your own tunnels after a while, it is already waiting there.
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID)
public final class MineMemory extends SavedData {
    /** Blocks broken in a cell before it counts as "your tunnel". */
    public static final int DUG_THRESHOLD = 6;
    /** Broken at least this deep under the surface counts as digging underground. */
    public static final int MIN_DEPTH = 4;
    private static final int MAX_CELLS = 20_000;
    private static final String NAME = "anta_mine";

    private final Map<Long, Integer> cells = new HashMap<>();

    public static MineMemory get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(MineMemory::load, MineMemory::new, NAME);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getPlayer() == null) return;
        if (event.getPlayer() instanceof FakePlayer) return; // quarries and other machines are not "you digging"
        if (!level.dimensionType().hasSkyLight()) return;    // Nether/End: no surface to be "under"
        BlockPos p = event.getPos();
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, p.getX(), p.getZ());
        if (p.getY() > surface - MIN_DEPTH) return;
        MineMemory mem = get(level);
        if (mem.cells.size() >= MAX_CELLS) return;
        mem.cells.merge(key(p), 1, Integer::sum);
        mem.setDirty();
    }

    /** Inside or right next to (one cell) tunnels a player dug. */
    public boolean isDugNear(BlockPos p) {
        int cx = p.getX() >> 3, cy = p.getY() >> 3, cz = p.getZ() >> 3;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (cells.getOrDefault(BlockPos.asLong(cx + dx, cy + dy, cz + dz), 0) >= DUG_THRESHOLD) return true;
        return false;
    }

    public int dugCells() {
        return (int) cells.values().stream().filter(v -> v >= DUG_THRESHOLD).count();
    }

    private static long key(BlockPos p) {
        return BlockPos.asLong(p.getX() >> 3, p.getY() >> 3, p.getZ() >> 3);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        long[] keys = new long[cells.size()];
        int[] counts = new int[cells.size()];
        int i = 0;
        for (Map.Entry<Long, Integer> e : cells.entrySet()) {
            keys[i] = e.getKey();
            counts[i++] = e.getValue();
        }
        tag.putLongArray("cells", keys);
        tag.putIntArray("counts", counts);
        return tag;
    }

    private static MineMemory load(CompoundTag tag) {
        MineMemory m = new MineMemory();
        long[] keys = tag.getLongArray("cells");
        int[] counts = tag.getIntArray("counts");
        for (int i = 0; i < Math.min(keys.length, counts.length); i++) m.cells.put(keys[i], counts[i]);
        return m;
    }
}
