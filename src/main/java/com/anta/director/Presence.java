package com.anta.director;

import com.anta.AntaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Traces of presence", left sometimes after the watcher is gone: a closed wooden door nearby is now
 * open (silently), out of the player's sight. Nothing addressed to the player (no signs, no messages)
 * and nothing taken away - the watcher never makes contact, it only leaves the feeling someone was here.
 * Server-side only.
 */
public final class Presence {
    public static final int SCAN_RADIUS = 20;
    public static final int SCAN_HEIGHT = 5;

    private Presence() {}

    /** Called when a watcher is gone; leaves a trace with the configured chance. */
    public static void maybeLeave(ServerPlayer player, Vec3 watcherPos) {
        if (!AntaConfig.PRESENCE.get()) return;
        if (DeadWorld.isActive(player.serverLevel())) return;
        if (player.getRandom().nextDouble() >= AntaConfig.PRESENCE_CHANCE.get()) return;
        String done = openDoor(player);
        if (done != null) Director.say(player, done);
    }

    /**
     * Opens one closed wooden door near the player that he cannot see right now. Changes what players
     * built, so only in a world nobody else shares (see Director.worldChangesAllowed).
     * Returns a debug description, or null if nothing was done.
     */
    public static String openDoor(ServerPlayer player) {
        if (!Director.worldChangesAllowed(player)) return null;
        ServerLevel level = player.serverLevel();
        // Only real vanilla-style doors: a modded block in the wooden_doors tag may lack HALF/OPEN.
        BlockPos p = pick(level, player, (q, s) -> isDoor(s) && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                        && !s.getValue(DoorBlock.OPEN) && isDoor(level.getBlockState(q.above())),
                q -> CaveTraces.hiddenFromPlayers(level, q, 6.0) && CaveTraces.hiddenFromPlayers(level, q.above(), 6.0));
        if (p == null) return null;
        // Set the state directly: no door sound, it is simply open when you come back.
        level.setBlock(p, level.getBlockState(p).setValue(DoorBlock.OPEN, true), 3);
        BlockState up = level.getBlockState(p.above());
        if (isDoor(up)) level.setBlock(p.above(), up.setValue(DoorBlock.OPEN, true), 3);
        return String.format(Locale.ROOT, "presence: opened a door at %d %d %d", p.getX(), p.getY(), p.getZ());
    }

    private static boolean isDoor(BlockState s) {
        return s.is(BlockTags.WOODEN_DOORS) && s.getBlock() instanceof DoorBlock
                && s.hasProperty(DoorBlock.HALF) && s.hasProperty(DoorBlock.OPEN);
    }

    private interface Test {
        boolean ok(BlockPos p, BlockState s);
    }

    /** Max candidates tested for visibility (each test is a few raycasts). */
    private static final int MAX_VISIBILITY_TESTS = 40;

    /** Cheap block test over the area, then the expensive visibility test lazily on random candidates. */
    private static BlockPos pick(ServerLevel level, ServerPlayer player, Test cheap,
                                 java.util.function.Predicate<BlockPos> hidden) {
        List<BlockPos> found = scan(level, player, cheap);
        RandomSource rnd = player.getRandom();
        for (int i = 0; i < Math.min(found.size(), MAX_VISIBILITY_TESTS); i++) {
            int j = i + rnd.nextInt(found.size() - i);
            BlockPos t = found.get(i);
            found.set(i, found.get(j));
            found.set(j, t);
            if (hidden.test(found.get(i))) return found.get(i);
        }
        return null;
    }

    private static List<BlockPos> scan(ServerLevel level, ServerPlayer player, Test test) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos c = player.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++)
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++)
                for (int dy = -SCAN_HEIGHT; dy <= SCAN_HEIGHT; dy++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (!level.isLoaded(m)) continue;
                    BlockState s = level.getBlockState(m);
                    if (s.isAir()) continue;
                    BlockPos p = m.immutable();
                    if (test.ok(p, s)) out.add(p);
                }
        return out;
    }
}
