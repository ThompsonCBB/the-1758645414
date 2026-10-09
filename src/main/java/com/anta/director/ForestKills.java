package com.anta.director;

import com.anta.AntaConfig;
import com.anta.AntaMod;
import com.anta.entity.CarcassEntity;
import com.anta.entity.ModEntities;
import com.anta.network.AntaNetwork;
import com.anta.sound.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Something in the forest": now and then, near trees, a farm animal is killed out of your sight.
 * You hear steps or a rustle behind you - a few blocks away, so it neither fades when you run
 * nor points at a block - and if you turn and go to look, you find the animal with its head
 * torn off ({@link CarcassEntity}). Nobody is ever shown doing it.
 *
 * Surface of the Overworld only, never in caves, never during the dead day. The carcass is put on
 * natural ground (dirt / grass / podzol / moss) among at least two tree trunks, out of view, never
 * next to a block entity (your chests, furnaces...) - it does not break or replace any block.
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID)
public final class ForestKills {
    private static final int CHECK_EVERY = 20;
    private static final int RETRY_TICKS = 30 * 20;
    private static final int ATTEMPTS = 60;
    /** Behind you: at least this far from where the player looks, degrees. */
    private static final double OUT_OF_VIEW_DEG = 110.0;

    private static final Map<UUID, int[]> STATE = new HashMap<>(); // {played, nextKillAt, nextSoundAt}

    private ForestKills() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        int[] st = STATE.computeIfAbsent(player.getUUID(),
                id -> new int[]{0, Director.graceTicks() + interval(player.getRandom()), Director.graceTicks() + soundInterval(player.getRandom())});
        st[0]++;
        if (st[0] % CHECK_EVERY != 0 || (st[0] < st[1] && st[0] < st[2])) return;
        if (player.isSpectator() || !player.isAlive() || player.isSleeping()) return;
        if (!Director.enabled) return; // /anta director off silences the forest too
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD || Director.inCave(player) || DeadWorld.isActive(level)) return;
        if (st[0] >= st[2]) {
            // Now and then, just steps or a rustle behind you - only while you are among trees.
            if (AntaConfig.FOREST_SOUNDS.get() && logsAround(level, player.blockPosition()) >= 2) {
                behindYou(player);
                st[2] = st[0] + soundInterval(player.getRandom());
            } else {
                st[2] = st[0] + RETRY_TICKS;
            }
        }
        // The carcass stays in the shared world: on a server only when world changes are allowed (sounds stay on).
        if (st[0] < st[1] || !AntaConfig.FOREST_KILLS.get() || !Director.worldChangesAllowed(player)) return;
        CarcassEntity c;
        try {
            c = kill(player, null, AntaConfig.FOREST_SOUNDS.get(), false);
        } catch (RuntimeException ex) {
            AntaMod.LOGGER.error("Forest kill failed, skipped", ex); // decoration must never take the server down
            c = null;
        }
        st[1] = st[0] + (c != null ? interval(player.getRandom()) : RETRY_TICKS);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATE.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        STATE.clear();
    }

    private static int soundInterval(RandomSource rnd) {
        int min = AntaConfig.FOREST_SOUND_MIN_MINUTES.get() * 1200;
        int max = Math.max(min, AntaConfig.FOREST_SOUND_MAX_MINUTES.get() * 1200);
        return min + rnd.nextInt(max - min + 1);
    }

    private static int interval(RandomSource rnd) {
        int min = AntaConfig.FOREST_INTERVAL_MIN_MINUTES.get() * 1200;
        int max = Math.max(min, AntaConfig.FOREST_INTERVAL_MAX_MINUTES.get() * 1200);
        return min + rnd.nextInt(max - min + 1);
    }

    /**
     * Puts a dead animal somewhere in the forest near the player.
     *
     * @param kind   null = random animal
     * @param sound  steps or a rustle from that side (it is behind you)
     * @param inView debug: right in front of the player, 4-6 blocks away, no forest needed
     * @return the carcass, or null when there is no suitable spot
     */
    public static CarcassEntity kill(ServerPlayer player, CarcassEntity.Kind kind, boolean sound, boolean inView) {
        return kill(player, kind, null, sound, inView);
    }

    /** Same, with a fixed way it was killed (null = random). */
    public static CarcassEntity kill(ServerPlayer player, CarcassEntity.Kind kind, CarcassEntity.Style style,
                                     boolean sound, boolean inView) {
        ServerLevel level = player.serverLevel();
        RandomSource rnd = player.getRandom();
        BlockPos spot = findSpot(level, player, rnd, inView);
        if (spot == null) {
            Director.say(player, "forest: no spot among trees nearby");
            return null;
        }
        CarcassEntity c = ModEntities.CARCASS.get().create(level);
        if (c == null) return null;
        CarcassEntity.Kind[] all = CarcassEntity.Kind.values();
        c.setKind(kind != null ? kind : all[rnd.nextInt(all.length)]);
        CarcassEntity.Style[] styles = CarcassEntity.Style.values();
        c.setStyle(style != null ? style : styles[rnd.nextInt(styles.length)]);
        c.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, rnd.nextFloat() * 360f, 0f);
        level.addFreshEntity(c);
        double dist = Math.sqrt(player.distanceToSqr(c));
        Director.say(player, String.format(java.util.Locale.ROOT, "forest: dead %s at %d %d %d, %.0f m",
                c.getKind().name().toLowerCase(java.util.Locale.ROOT), spot.getX(), spot.getY(), spot.getZ(), dist));
        if (sound) playFrom(player, c.position(), rnd.nextInt(ModSounds.COUNT));
        return c;
    }

    /** Plays steps / a rustle (ModSounds index) for this player from the direction of {@code from}. */
    public static void playFrom(ServerPlayer player, Vec3 from, int which) {
        double dx = from.x - player.getX(), dz = from.z - player.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        AntaNetwork.sendBehindSound(player, yaw, which);
    }

    /** Steps or a rustle from somewhere behind the player (within 40 degrees of straight behind). */
    public static void behindYou(ServerPlayer player) {
        RandomSource rnd = player.getRandom();
        float yaw = player.getYRot() + 180f + (rnd.nextFloat() - 0.5f) * 80f;
        AntaNetwork.sendBehindSound(player, yaw, rnd.nextInt(ModSounds.COUNT));
        Director.say(player, "forest: sound behind you");
    }

    private static BlockPos findSpot(ServerLevel level, ServerPlayer player, RandomSource rnd, boolean inView) {
        Vec3 look = player.getLookAngle();
        double lookYaw = Math.atan2(look.z, look.x);
        double minD = inView ? 4 : AntaConfig.FOREST_MIN_DISTANCE.get();
        double maxD = inView ? 6 : Math.max(minD, AntaConfig.FOREST_MAX_DISTANCE.get());
        for (int i = 0; i < ATTEMPTS; i++) {
            double ang = inView ? lookYaw + (rnd.nextDouble() - 0.5) * Math.toRadians(40) : rnd.nextDouble() * Math.PI * 2;
            if (!inView) {
                double diff = Math.abs(Mth.wrapDegrees(Math.toDegrees(ang - lookYaw)));
                if (diff < OUT_OF_VIEW_DEG) continue;
            }
            double d = minD + rnd.nextDouble() * (maxD - minD);
            int x = Mth.floor(player.getX() + Math.cos(ang) * d);
            int z = Mth.floor(player.getZ() + Math.sin(ang) * d);
            BlockPos column = new BlockPos(x, player.getBlockY(), z);
            if (!level.isLoaded(column)) continue; // never load chunks for this
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - player.getBlockY()) > 10) continue;
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isAreaLoaded(pos, 8)) continue; // the trunk and block-entity checks reach 8 blocks: never load chunks
            if (!suitable(level, pos)) continue;
            if (!inView && logsAround(level, pos) < 2) continue;
            if (nearBlockEntity(level, pos)) continue;
            if (!level.getEntitiesOfClass(CarcassEntity.class, new AABB(pos).inflate(8)).isEmpty()) continue;
            return pos;
        }
        return null;
    }

    private static boolean suitable(ServerLevel level, BlockPos pos) {
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.is(BlockTags.DIRT)) return false; // grass, dirt, podzol, moss, mycelium...
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = pos.above(dy);
            BlockState s = level.getBlockState(p);
            if (s.blocksMotion() || !level.getFluidState(p).isEmpty()) return false;
        }
        return true;
    }

    /** Tree trunks within 4 blocks (stops counting at 2). */
    private static int logsAround(ServerLevel level, BlockPos pos) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getBlockState(m).is(BlockTags.LOGS) && ++n >= 2) return n;
                }
            }
        }
        return n;
    }

    /** Chests, furnaces, beds, signs... within 8 blocks: that is somebody's place, not the wild forest. */
    private static boolean nearBlockEntity(ServerLevel level, BlockPos pos) {
        int r = 8;
        for (int cx = (pos.getX() - r) >> 4; cx <= (pos.getX() + r) >> 4; cx++) {
            for (int cz = (pos.getZ() - r) >> 4; cz <= (pos.getZ() + r) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (BlockPos be : level.getChunk(cx, cz).getBlockEntitiesPos()) {
                    if (be.distManhattan(pos) <= r * 2 && be.closerThan(pos, r)) return true;
                }
            }
        }
        return false;
    }
}
