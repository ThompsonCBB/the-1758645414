package com.anta.director;

import com.anta.AntaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.tags.BlockTags;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.Tags;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Someone else's traces" in caves (see docs/CAVE_TRACES.md). Server-side only.
 *
 * While the player is underground, now and then the mod places, somewhere the player has not been
 * and cannot see right now:
 *  - TRAIL: a chain of torches that leads away from the player, deeper into the cave, and ends nowhere;
 *  - CAMP: a chest with ordinary miner's things, a furnace with cooked food in it and a torch;
 *  - TRAIL_CAMP: a torch trail that ends at such a camp.
 * Everything is planned first and placed only if the whole plan is valid, so there are no half-built traces.
 * Blocks stay in the world (it is a trace, it should stay). Only air cells are used: nothing is replaced.
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID)
public final class CaveTraces {
    public enum Kind { TRAIL, CAMP, TRAIL_CAMP }

    /** Underground time before the first trace, and pause between traces (counted only underground). */
    public static final int FIRST_CAVE_TICKS = 90 * 20;
    public static final int INTERVAL_MIN = 4 * Director.TICKS_PER_MINUTE;
    public static final int INTERVAL_MAX = 8 * Director.TICKS_PER_MINUTE;
    public static final int RETRY_MIN = 20 * 20;
    public static final int RETRY_MAX = 40 * 20;

    /** Where a trace may start, horizontal distance from the player. */
    public static final int START_MIN = 18;
    public static final int START_MAX = 32;
    public static final int VERTICAL_RANGE = 8;
    /** No placed block closer than this to any player. */
    public static final double MIN_PLAYER_DIST = 12.0;
    /** Only in dark places (also keeps it out of lit player bases). */
    public static final int MAX_BLOCK_LIGHT = 7;
    /** Trail: a wall torch every TORCH_STEP_MIN..MAX steps (like a player's mine), 3..7 torches. */
    public static final int TORCH_STEP_MIN = 7;
    public static final int TORCH_STEP_MAX = 11;
    public static final int TRAIL_MIN_TORCHES = 3;
    public static final int TRAIL_MAX_TORCHES = 7;
    public static final int WALK_MAX_STEPS = 90;
    /** "Visited" memory: cells of 4x4x4 blocks around the player, marked every MARK_EVERY ticks. */
    private static final int MARK_EVERY = 10;
    private static final int VISITED_CAP = 50_000;

    /** /anta traces on|off */
    public static boolean enabled = true;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static final class State {
        int played;
        int caveTicks;
        int nextAt = FIRST_CAVE_TICKS; // value of caveTicks at which the next trace is tried
        final Set<Long> visited = new HashSet<>();
        ResourceKey<Level> dim;        // dimension the visited cells belong to
    }

    /** What was placed, for debug messages. */
    public record Placed(Kind kind, BlockPos start, BlockPos end, int torches, boolean camp, double distance) {}

    private CaveTraces() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        State st = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        st.played++;
        if (!player.level().dimension().equals(st.dim)) { // visited cells are per dimension
            st.visited.clear();
            st.dim = player.level().dimension();
        }
        if (st.played % MARK_EVERY == 0) markVisited(player, st);

        if (!enabled || !com.anta.AntaConfig.CAVE_TRACES.get() || player.isSpectator() || !player.isAlive()) return;
        if (DeadWorld.isActive(player.serverLevel())) return; // the dead day: nothing, nobody
        if (!Director.inCave(player)) return;
        st.caveTicks++;
        if (st.played < Director.graceTicks() || st.caveTicks < st.nextAt) return;

        RandomSource rnd = player.getRandom();
        Placed p;
        try {
            p = place(player, randomKind(rnd));
        } catch (RuntimeException ex) {
            AntaMod.LOGGER.error("Cave trace failed, skipped", ex); // a trace must never take the server down
            p = null;
        }
        if (p == null) {
            st.nextAt = st.caveTicks + between(rnd, RETRY_MIN, RETRY_MAX);
            return;
        }
        st.nextAt = st.caveTicks + between(rnd, INTERVAL_MIN, INTERVAL_MAX);
        Director.say(player, describe(p));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    /** /anta traces on|off lasts until the server stops; a new world starts from the config. */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        STATES.clear();
        enabled = true;
    }

    private static Kind randomKind(RandomSource rnd) {
        int r = rnd.nextInt(100);
        return r < 45 ? Kind.TRAIL : r < 80 ? Kind.TRAIL_CAMP : Kind.CAMP;
    }

    // ---------------------------------------------------------------- planning + placing

    /** Plans and places one trace of the given kind near the player. Returns null if no valid spot was found. */
    public static Placed place(ServerPlayer player, Kind kind) {
        ServerLevel level = player.serverLevel();
        if (!level.dimensionType().hasSkyLight()) return null; // Nether/End: "no sky light" means nothing there
        RandomSource rnd = player.getRandom();
        for (int attempt = 0; attempt < 12; attempt++) {
            BlockPos start = findStart(level, player, rnd);
            if (start == null) continue;
            Plan plan = switch (kind) {
                case TRAIL -> planTrail(level, player, start, rnd, false);
                case TRAIL_CAMP -> planTrail(level, player, start, rnd, true);
                case CAMP -> planCamp(level, start, rnd, new HashSet<>());
            };
            if (plan == null) continue;
            plan.apply(level, rnd);
            double dist = Math.sqrt(player.blockPosition().distSqr(plan.first()));
            return new Placed(kind, plan.first(), plan.last(), plan.torches.size(), plan.chest != null, dist);
        }
        return null;
    }

    /** One planned trace: torch placements, optional camp. */
    private static final class Plan {
        final List<BlockPos> torches = new ArrayList<>();
        final List<BlockState> torchStates = new ArrayList<>();
        BlockPos chest, furnace, campTorch;
        Direction chestFacing, furnaceFacing;

        BlockPos first() { return !torches.isEmpty() ? torches.get(0) : chest; }
        BlockPos last() { return chest != null ? chest : torches.get(torches.size() - 1); }

        void addTorch(BlockPos p, BlockState s) {
            torches.add(p);
            torchStates.add(s);
        }

        void apply(ServerLevel level, RandomSource rnd) {
            for (int i = 0; i < torches.size(); i++) level.setBlock(torches.get(i), torchStates.get(i), 3);
            if (chest == null) return;
            level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, chestFacing), 3);
            level.setBlock(furnace, Blocks.FURNACE.defaultBlockState()
                    .setValue(AbstractFurnaceBlock.FACING, furnaceFacing), 3);
            if (campTorch != null) level.setBlock(campTorch, Blocks.TORCH.defaultBlockState(), 3);
            fillChest(level.getBlockEntity(chest), rnd);
            BlockEntity f = level.getBlockEntity(furnace);
            if (f instanceof Container c) {
                c.setItem(2, new ItemStack(rnd.nextBoolean() ? Items.COOKED_MUTTON : Items.COOKED_COD, 1 + rnd.nextInt(2)));
                if (rnd.nextBoolean()) c.setItem(1, new ItemStack(Items.CHARCOAL, 1 + rnd.nextInt(3)));
            }
        }
    }

    /** Ordinary miner's things, as if someone lived here: never anything valuable. */
    private static void fillChest(BlockEntity be, RandomSource rnd) {
        if (!(be instanceof Container c)) return;
        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(Items.COAL, 2 + rnd.nextInt(7)));
        if (rnd.nextFloat() < 0.7f) loot.add(new ItemStack(Items.TORCH, 2 + rnd.nextInt(5)));
        if (rnd.nextFloat() < 0.6f) loot.add(new ItemStack(Items.BREAD, 1 + rnd.nextInt(3)));
        if (rnd.nextFloat() < 0.5f) loot.add(new ItemStack(Items.BONE, 1 + rnd.nextInt(3)));
        if (rnd.nextFloat() < 0.4f) loot.add(new ItemStack(Items.ROTTEN_FLESH, 1 + rnd.nextInt(4)));
        if (rnd.nextFloat() < 0.4f) loot.add(new ItemStack(Items.STRING, 1 + rnd.nextInt(3)));
        if (rnd.nextFloat() < 0.3f) {
            ItemStack pick = new ItemStack(Items.STONE_PICKAXE);
            pick.setDamageValue(pick.getMaxDamage() - 1 - rnd.nextInt(8)); // almost broken
            loot.add(pick);
        }
        int size = c.getContainerSize();
        Set<Integer> used = new HashSet<>();
        for (ItemStack s : loot) {
            int slot;
            do { slot = rnd.nextInt(size); } while (!used.add(slot));
            c.setItem(slot, s);
        }
    }

    /** Random dark, unvisited, unseen standable cell at START_MIN..START_MAX from the player. */
    private static BlockPos findStart(ServerLevel level, ServerPlayer player, RandomSource rnd) {
        BlockPos feet = player.blockPosition();
        for (int i = 0; i < 40; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = START_MIN + rnd.nextDouble() * (START_MAX - START_MIN);
            BlockPos col = feet.offset((int) Math.round(Math.cos(ang) * d), 0, (int) Math.round(Math.sin(ang) * d));
            BlockPos p = findStandable(level, col);
            if (p != null && goodCell(level, p)) return p;
        }
        return null;
    }

    /**
     * Torch trail from {@code start}, the way players light a mine: walk through the cave, mostly away
     * from the player, and every 7-11 steps put a torch on the wall at head height - always on the same
     * side if possible (on the floor only where there is no wall). With {@code withCamp} the trail must
     * end at a valid camp.
     */
    private static Plan planTrail(ServerLevel level, ServerPlayer player, BlockPos start, RandomSource rnd,
                                  boolean withCamp) {
        Plan plan = new Plan();
        Set<BlockPos> path = new HashSet<>();
        Vec3 from = player.position();
        BlockPos cur = start;
        path.add(cur);
        boolean leftSide = rnd.nextBoolean(); // the side this "miner" keeps his torches on
        int nextTorch = 0;                    // first torch right at the start of the trail
        int wanted = between(rnd, TRAIL_MIN_TORCHES, TRAIL_MAX_TORCHES);
        Direction walk = null;

        for (int step = 0; step <= WALK_MAX_STEPS && plan.torches.size() < wanted; step++) {
            if (step > 0) {
                BlockPos next = nextStep(level, cur, path, from, rnd);
                if (next == null) break; // dead end
                walk = Direction.getNearest(next.getX() - cur.getX(), 0, next.getZ() - cur.getZ());
                path.add(next);
                cur = next;
            }
            if (--nextTorch <= 0) {
                if (placeTorch(level, plan, cur, walk, leftSide)) {
                    nextTorch = between(rnd, TORCH_STEP_MIN, TORCH_STEP_MAX);
                } else {
                    nextTorch = 1; // try the very next cell instead
                }
            }
        }
        if (plan.torches.size() < TRAIL_MIN_TORCHES) return null;
        if (withCamp) {
            // the camp goes a couple of steps past the last torch
            for (int i = 0; i < 3; i++) {
                BlockPos next = nextStep(level, cur, path, from, rnd);
                if (next == null) break;
                path.add(next);
                cur = next;
            }
            Plan camp = planCamp(level, cur, rnd, path);
            if (camp == null) return null;
            plan.chest = camp.chest;
            plan.furnace = camp.furnace;
            plan.campTorch = camp.campTorch;
            plan.chestFacing = camp.chestFacing;
            plan.furnaceFacing = camp.furnaceFacing;
        }
        return plan;
    }

    /**
     * One torch of the trail at walking cell {@code feet}: on the wall at head height, on the chosen side of
     * the walking direction first, then the other side, then any wall; on the floor if there is no wall.
     */
    private static boolean placeTorch(ServerLevel level, Plan plan, BlockPos feet, Direction walk, boolean leftSide) {
        BlockPos head = feet.above();
        List<Direction> sides = new ArrayList<>();
        if (walk != null) {
            Direction left = walk.getCounterClockWise(), right = walk.getClockWise();
            sides.add(leftSide ? left : right);
            sides.add(leftSide ? right : left);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) if (!sides.contains(d)) sides.add(d);
        if (goodCell(level, head)) {
            for (Direction toWall : sides) {
                BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState()
                        .setValue(WallTorchBlock.FACING, toWall.getOpposite());
                if (wallTorch.canSurvive(level, head)) {
                    plan.addTorch(head, wallTorch);
                    return true;
                }
            }
        }
        BlockState floor = Blocks.TORCH.defaultBlockState();
        if (goodCell(level, feet) && floor.canSurvive(level, feet)) {
            plan.addTorch(feet, floor);
            return true;
        }
        return false;
    }

    /** Next cell of the walk: a standable neighbour (step up/down allowed), preferring "away from the player". */
    private static BlockPos nextStep(ServerLevel level, BlockPos cur, Set<BlockPos> path, Vec3 from, RandomSource rnd) {
        BlockPos best = null;
        double bestScore = -1e9;
        double curDist = horizDist(cur, from);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos n = cur.relative(dir).above(dy);
                if (path.contains(n) || !level.isLoaded(n) || !isStandable(level, n)) continue;
                if (dy == 1 && !isFree(level, cur.above(2))) continue; // head room for the step up
                double gain = horizDist(n, from) - curDist;
                if (gain < -0.5) continue; // never walk back toward the player
                double score = gain + rnd.nextDouble() * 1.2;
                if (score > bestScore) { bestScore = score; best = n; }
            }
        }
        return best;
    }

    /**
     * Camp around an open cell O near {@code near}: chest and furnace on two perpendicular sides of O,
     * both facing O; a torch on a third side if possible.
     */
    private static Plan planCamp(ServerLevel level, BlockPos near, RandomSource rnd, Set<BlockPos> avoid) {
        List<BlockPos> centers = new ArrayList<>();
        centers.add(near);
        for (Direction d : Direction.Plane.HORIZONTAL) centers.add(near.relative(d));
        for (BlockPos o : centers) {
            if (!isStandable(level, o) || !goodCell(level, o)) continue;
            List<Direction> dirs = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
            shuffle(dirs, rnd);
            for (Direction a : dirs) {
                Direction b = a.getClockWise();
                BlockPos chest = o.relative(a), furnace = o.relative(b);
                if (avoid.contains(chest) || avoid.contains(furnace)) continue;
                if (!campCell(level, chest) || !campCell(level, furnace)) continue;
                Plan p = new Plan();
                p.chest = chest;
                p.furnace = furnace;
                p.chestFacing = a.getOpposite();
                p.furnaceFacing = b.getOpposite();
                BlockPos t = o.relative(a.getOpposite());
                if (!avoid.contains(t) && goodCell(level, t) && level.getBlockState(t).isAir()
                        && Blocks.TORCH.defaultBlockState().canSurvive(level, t)) p.campTorch = t;
                return p;
            }
        }
        return null;
    }

    private static boolean campCell(ServerLevel level, BlockPos p) {
        BlockPos below = p.below();
        if (!level.isLoaded(p)) return false;
        return level.getBlockState(p).isAir()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && goodCell(level, p);
    }

    // ---------------------------------------------------------------- rules for every placed block

    /**
     * Underground, dark, loaded, not visited by any player, not seen by any player, not near any player,
     * not in tunnels a player dug, and surrounded only by natural cave blocks (so never in a base, a mob
     * farm, a storage room or a mineshaft).
     */
    private static boolean goodCell(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        if (level.getBrightness(LightLayer.SKY, p) > 0) return false;
        if (level.getBrightness(LightLayer.BLOCK, p) > MAX_BLOCK_LIGHT) return false;
        if (!level.getBlockState(p).isAir()) return false;
        long cell = cellKey(p);
        for (ServerPlayer pl : level.players()) {
            State st = STATES.get(pl.getUUID());
            if (st != null && st.visited.contains(cell)) return false;
        }
        if (MineMemory.get(level).isDugNear(p)) return false;
        if (!naturalAround(level, p)) return false;
        return hiddenFromPlayers(level, p, MIN_PLAYER_DIST);
    }

    /** Radius around a placed block that must contain only natural cave blocks. */
    public static final int NATURAL_RADIUS = 2;

    private static final Set<Block> NATURAL_EXTRA = Set.of(
            Blocks.BEDROCK, Blocks.GLOW_LICHEN, Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET, Blocks.POINTED_DRIPSTONE,
            Blocks.DRIPSTONE_BLOCK, Blocks.HANGING_ROOTS, Blocks.SPORE_BLOSSOM, Blocks.COBWEB, Blocks.AMETHYST_BLOCK,
            Blocks.BUDDING_AMETHYST, Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
            Blocks.AMETHYST_CLUSTER, Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK,
            Blocks.CLAY, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.BIG_DRIPLEAF,
            Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF, Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN,
            Blocks.POWDER_SNOW, Blocks.ICE, Blocks.PACKED_ICE, Blocks.SNOW, Blocks.SNOW_BLOCK);

    /** Every block within {@link #NATURAL_RADIUS} is natural: stone, dirt, ores, cave plants, air, water, lava. */
    private static boolean naturalAround(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -NATURAL_RADIUS; dx <= NATURAL_RADIUS; dx++)
            for (int dy = -NATURAL_RADIUS; dy <= NATURAL_RADIUS; dy++)
                for (int dz = -NATURAL_RADIUS; dz <= NATURAL_RADIUS; dz++) {
                    m.setWithOffset(p, dx, dy, dz);
                    if (!level.isLoaded(m) || !isNatural(level.getBlockState(m))) return false;
                }
        return true;
    }

    private static boolean isNatural(BlockState s) {
        if (s.isAir()) return true; // air and cave air
        if (s.hasBlockEntity()) return false; // chests, spawners, signs, furnaces, hoppers... = someone's
        if (s.getBlock() instanceof LiquidBlock) return true;
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
                || s.is(Tags.Blocks.GRAVEL) || s.is(Tags.Blocks.ORES) || s.is(BlockTags.SCULK_REPLACEABLE)
                || s.is(BlockTags.CAVE_VINES) || NATURAL_EXTRA.contains(s.getBlock());
    }

    /** The block can't be seen from any player's eyes (two points per block) and is far enough. */
    public static boolean hiddenFromPlayers(ServerLevel level, BlockPos p, double minDist) {
        Vec3[] points = {Vec3.atCenterOf(p), new Vec3(p.getX() + 0.5, p.getY() + 0.9, p.getZ() + 0.5)};
        for (ServerPlayer pl : level.players()) {
            if (pl.isSpectator()) continue;
            Vec3 eye = pl.getEyePosition();
            for (Vec3 pt : points) {
                double d = eye.distanceTo(pt);
                if (d < minDist) return false;
                if (d > 128) continue;
                BlockHitResult hit = level.clip(new ClipContext(eye, pt, ClipContext.Block.VISUAL,
                        ClipContext.Fluid.NONE, pl));
                if (hit.getType() == HitResult.Type.MISS) return false;
                if (hit.getLocation().distanceTo(eye) >= d - 0.6) return false; // nothing really in between
            }
        }
        return true;
    }

    private static void markVisited(ServerPlayer player, State st) {
        if (st.visited.size() > VISITED_CAP) st.visited.clear();
        BlockPos f = player.blockPosition();
        int cx = f.getX() >> 2, cy = f.getY() >> 2, cz = f.getZ() >> 2;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    st.visited.add(BlockPos.asLong(cx + dx, cy + dy, cz + dz));
    }

    private static long cellKey(BlockPos p) {
        return BlockPos.asLong(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
    }

    // ---------------------------------------------------------------- small helpers

    private static BlockPos findStandable(ServerLevel level, BlockPos column) {
        for (int i = 0; i <= VERTICAL_RANGE * 2; i++) {
            int off = (i % 2 == 0) ? i / 2 : -(i + 1) / 2;
            BlockPos p = column.above(off);
            if (level.isLoaded(p) && isStandable(level, p)) return p;
        }
        return null;
    }

    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet)) return false;
        BlockPos below = feet.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        return isFree(level, feet) && isFree(level, feet.above());
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return st.getCollisionShape(level, pos).isEmpty() && st.getFluidState().isEmpty();
    }

    private static double horizDist(BlockPos p, Vec3 from) {
        double dx = p.getX() + 0.5 - from.x, dz = p.getZ() + 0.5 - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static <T> void shuffle(List<T> list, RandomSource rnd) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            T t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }

    private static int between(RandomSource random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    // ---------------------------------------------------------------- used by /anta traces

    public static String describe(Placed p) {
        String what = switch (p.kind()) {
            case TRAIL -> String.format(Locale.ROOT, "torch trail (%d torches)", p.torches());
            case TRAIL_CAMP -> String.format(Locale.ROOT, "torch trail (%d torches) to a camp", p.torches());
            case CAMP -> "camp (chest + furnace)";
        };
        return String.format(Locale.ROOT, "traces: %s, starts at %d %d %d (%.0f m), ends at %d %d %d",
                what, p.start().getX(), p.start().getY(), p.start().getZ(), p.distance(),
                p.end().getX(), p.end().getY(), p.end().getZ());
    }

    public static String status(ServerPlayer player) {
        State st = STATES.get(player.getUUID());
        if (st == null) return "traces: no data yet";
        int wait = Math.max(Math.max(st.nextAt - st.caveTicks, Director.graceTicks() - st.played), 0);
        return String.format(Locale.ROOT, "traces %s | underground %d s | next try after %d s more underground%s",
                enabled ? "ON" : "OFF", st.caveTicks / 20, wait / 20,
                Director.inCave(player) ? "" : " (you are not underground now)");
    }
}
