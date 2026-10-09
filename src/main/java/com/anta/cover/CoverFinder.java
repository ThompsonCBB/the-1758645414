package com.anta.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds a spot where the watcher can hide behind cover and peek at the player by
 * leaning sideways from the waist. See docs/COVER_SYSTEM.md for the full description.
 *
 * A candidate spot S is accepted when:
 *  1. it is standable (solid floor, 2 free blocks of air, no fluid);
 *  2. standing upright, the whole body is hidden from the player's eyes;
 *  3. the occluder that hides the head is close to S (it is a real corner, not a far pillar);
 *  4. after an optional shift toward the edge of the cover (up to {@link #MAX_SHIFT}),
 *     leaning to the left or right by an angle in [MIN_LEAN, MAX_LEAN] puts the eyes in
 *     the player's line of sight, and the space it leans into is free.
 *
 * Body geometry matches the rendered Steve model (2 blocks tall, no scaling):
 * hip 0.75, shoulders 1.5, eyes ~1.75, top of head 2.0; body half-width 0.25, arms reach 0.5.
 */
public final class CoverFinder {
    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 24;
    public static final int VERTICAL_RANGE = 6;
    public static final double PREFERRED_DISTANCE = 16.0;
    public static final float MIN_LEAN = 15f;
    public static final float MAX_LEAN = 35f;
    public static final float LEAN_STEP = 5f;
    /** Sideways shifts from the block centre toward the lean side that are tried. */
    public static final double[] SHIFTS = {0.0, 0.125, 0.25};
    public static final double MAX_SHIFT = 0.25;
    /** Max distance between the upright head and the block that hides it. */
    public static final double MAX_COVER_GAP = 2.0;
    /** Half-angle of the "player is looking there" cone, in degrees. */
    public static final double VIEW_HALF_ANGLE = 60.0;
    /** Out-of-view spots within this angle of the look direction (just off screen) get a bonus. */
    public static final double NEAR_EDGE_MAX = 110.0, NEAR_EDGE_BONUS = 4.0;

    /** Max time one search may take on the server thread (the rest of the ring is skipped). */
    public static final long SEARCH_BUDGET_NANOS = 20_000_000L;

    /** Score penalty when the shoulder on the lean side is visible. */
    public static final double SHOULDER_PENALTY = 6.0;

    private static final double HIP_HEIGHT = 0.75;
    private static final double EYE_HEIGHT = 1.75;
    private static final double SHOULDER_HEIGHT = 1.4;
    private static final double HEAD_TOP = 2.0;
    private static final double BODY_HALF_WIDTH = 0.25;
    private static final double ARM_REACH = 0.45;

    public record Result(Vec3 pos, float yaw, float lean, double shift, double distance, int light,
                         boolean onlyHeadVisible, boolean shoulderVisible, boolean inView, double score) {}

    public record Stats(int columns, int standable, int hidden, int accepted, long millis) {}

    public record Search(Optional<Result> best, Stats stats) {}

    private CoverFinder() {}

    /**
     * @param requireOutOfView when true, spots whose peek point lies inside the player's view
     *                         cone are rejected (real-game rule: it never appears in front of you)
     */
    public static Search find(ServerLevel level, ServerPlayer player, boolean requireOutOfView) {
        return find(level, player, requireOutOfView, PREFERRED_DISTANCE);
    }

    /** Same, with a custom preferred distance ("closer than before"); the search ring is widened inward to fit it. */
    public static Search find(ServerLevel level, ServerPlayer player, boolean requireOutOfView, double preferredDist) {
        Vec3 eye = player.getEyePosition();
        int minRadius = Math.max(5, Math.min(MIN_RADIUS, (int) Math.floor(preferredDist) - 3));
        return search(level, player, eye, player.getLookAngle(), null, requireOutOfView,
                minRadius, MAX_RADIUS, preferredDist);
    }

    // --- Cave ambush: placed around the next corner ahead, unseen now, peeking at where the player will be. ---
    /** How far ahead along the look direction the player's future eye is predicted (stops at walls). */
    public static final double AMBUSH_AHEAD = 5.0;
    public static final int AMBUSH_MIN_RADIUS = 4;
    public static final int AMBUSH_MAX_RADIUS = 16;
    public static final double AMBUSH_PREFERRED_DISTANCE = 7.0;
    /** Bonus when, from the future eye, the head is at the edge of the screen (peripheral glimpse). */
    public static final double PERIPHERY_MIN = 25.0, PERIPHERY_MAX = 55.0, PERIPHERY_BONUS = 4.0;

    /**
     * Cave ambush: a spot that cannot be seen from where the player is NOW (not even the leaning
     * head), but whose leaning head becomes visible from where the player will be after walking
     * a few blocks ahead - so turning the next corner, they catch it at the edge of the screen.
     */
    public static Search findAmbush(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 future = predictEye(level, player, eye);
        if (future.distanceTo(eye) < 2.0) {
            return new Search(Optional.empty(), new Stats(0, 0, 0, 0, 0));
        }
        Vec3 look = future.subtract(eye).normalize();
        return search(level, player, future, look, eye, false,
                AMBUSH_MIN_RADIUS, AMBUSH_MAX_RADIUS, AMBUSH_PREFERRED_DISTANCE);
    }

    /** Steps along the horizontal look direction while a player-sized column is free. */
    public static Vec3 predictEye(ServerLevel level, ServerPlayer player, Vec3 eye) {
        Vec3 look = player.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z);
        if (dir.lengthSqr() < 1e-4) return eye;
        dir = dir.normalize();
        Vec3 last = eye;
        for (double d = 0.5; d <= AMBUSH_AHEAD; d += 0.5) {
            Vec3 p = eye.add(dir.scale(d));
            if (!isFree(level, p) || !isFree(level, p.add(0, -1, 0))) break;
            last = p;
        }
        return last;
    }

    /**
     * @param eye        where the peek must be seen from
     * @param hiddenFrom when not null, the spot is rejected if the leaning head is visible from here
     */
    private static Search search(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 look, Vec3 hiddenFrom,
                                 boolean requireOutOfView, int minRadius, int maxRadius, double preferredDist) {
        long start = System.nanoTime();
        BlockPos origin = BlockPos.containing(eye.x, player.getY(), eye.z);
        RandomSource random = player.getRandom();

        int columns = 0, standable = 0, hidden = 0, accepted = 0;
        Result best = null;

        // Columns in random order, under a time budget: in dense forests the full ring can be expensive,
        // and cutting a randomly ordered list short still samples the whole ring evenly.
        List<int[]> ring = new ArrayList<>();
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 >= minRadius * minRadius && d2 <= maxRadius * maxRadius) ring.add(new int[]{dx, dz});
            }
        }
        for (int i = ring.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] t = ring.get(i);
            ring.set(i, ring.get(j));
            ring.set(j, t);
        }
        long deadline = start + SEARCH_BUDGET_NANOS;

        for (int[] off : ring) {
            {
                if (System.nanoTime() > deadline) break;
                int dx = off[0], dz = off[1];
                columns++;

                BlockPos feet = findStandable(level, origin.offset(dx, 0, dz));
                if (feet == null) continue;
                standable++;

                Vec3 s = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);

                // Facing: horizontal direction from S to the player.
                Vec3 forward = new Vec3(eye.x - s.x, 0, eye.z - s.z);
                if (forward.lengthSqr() < 1e-6) continue;
                forward = forward.normalize();
                Vec3 right = new Vec3(-forward.z, 0, forward.x);

                // Cheap pre-filter at the block centre; shifted variants are re-checked in tryPeek.
                if (!isHiddenUpright(level, player, eye, s, right)) continue;
                if (hiddenFrom != null && !isHiddenUpright(level, player, hiddenFrom, s, right, false)) continue;
                hidden++;

                Result r = tryPeek(level, player, eye, look, s, forward, right, requireOutOfView, random,
                        hiddenFrom, preferredDist);
                if (r == null) continue;
                accepted++;
                if (best == null || r.score() > best.score()) best = r;
            }
        }

        long ms = (System.nanoTime() - start) / 1_000_000L;
        return new Search(Optional.ofNullable(best), new Stats(columns, standable, hidden, accepted, ms));
    }

    /** Nearest standable Y in the column, searching outward from the player's feet level. */
    private static BlockPos findStandable(ServerLevel level, BlockPos column) {
        if (!level.isLoaded(column)) return null; // never load or generate chunks just to look for cover
        for (int i = 0; i <= VERTICAL_RANGE * 2; i++) {
            int off = (i % 2 == 0) ? i / 2 : -(i + 1) / 2; // 0, -1, 1, -2, 2 ...
            BlockPos p = column.above(off);
            if (isStandable(level, p)) return p;
        }
        return null;
    }

    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        return isFree(level, feet) && isFree(level, feet.above());
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return st.getCollisionShape(level, pos).isEmpty() && st.getFluidState().isEmpty();
    }

    private static boolean isFree(ServerLevel level, Vec3 point) {
        return isFree(level, BlockPos.containing(point));
    }

    /** Upright body: legs, arms and head (3 lateral points each) must be hidden; head occluder must be close. */
    private static boolean isHiddenUpright(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 s, Vec3 right) {
        return isHiddenUpright(level, player, eye, s, right, true);
    }

    /**
     * @param closeCover also require the block hiding the head to be near it (a real corner it peeks
     *                   around). False for "just completely out of sight from here" - e.g. from the
     *                   player's current position in a tunnel, where the rock in between can be anywhere.
     */
    private static boolean isHiddenUpright(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 s, Vec3 right,
                                           boolean closeCover) {
        Vec3 head = s.add(0, EYE_HEIGHT, 0);
        BlockHitResult headHit = firstOccluder(level, player, eye, head);
        if (headHit == null) return false;
        if (closeCover && headHit.getLocation().distanceTo(head) > MAX_COVER_GAP) return false;

        double[][] points = {
                {0.3, BODY_HALF_WIDTH}, // legs
                {1.1, ARM_REACH},       // arms
                {HEAD_TOP - 0.05, BODY_HALF_WIDTH} // top of head
        };
        for (double[] hp : points) {
            for (double side : new double[]{-hp[1], 0, hp[1]}) {
                Vec3 p = s.add(right.scale(side)).add(0, hp[0], 0);
                if (firstOccluder(level, player, eye, p) == null) return false;
            }
        }
        return true;
    }

    private static Result tryPeek(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 look,
                                  Vec3 center, Vec3 forward, Vec3 right, boolean requireOutOfView,
                                  RandomSource random, Vec3 hiddenFrom, double preferredDist) {
        Result best = null;
        float yaw = (float) (Mth.atan2(forward.z, forward.x) * Mth.RAD_TO_DEG) - 90f;

        for (int sideSign : new int[]{1, -1}) {
            Vec3 dir = right.scale(sideSign);
            for (double shift : SHIFTS) {
                Vec3 s = center.add(dir.scale(shift));
                if (shift > 0) {
                    // The outer arm now reaches into the next block: it must be free there,
                    // and the shifted body must still be fully hidden when upright.
                    if (!isFree(level, s.add(dir.scale(ARM_REACH)).add(0, 1.1, 0))) break;
                    if (!isHiddenUpright(level, player, eye, s, right)) break; // shifting further only exposes more
                    if (hiddenFrom != null && !isHiddenUpright(level, player, hiddenFrom, s, right, false)) break;
                }

                for (float lean = MIN_LEAN; lean <= MAX_LEAN; lean += LEAN_STEP) {
                    double rad = Math.toRadians(lean);
                    Vec3 h = leanPoint(s, dir, EYE_HEIGHT, 0, rad);
                    Vec3 shoulder = leanPoint(s, dir, SHOULDER_HEIGHT, ARM_REACH, rad);
                    Vec3 torso = leanPoint(s, dir, 1.1, 0, rad);

                    // Space the upper body leans into must be free.
                    if (!isFree(level, torso)
                            || !isFree(level, shoulder)
                            || !isFree(level, h)
                            || !isFree(level, leanPoint(s, dir, HEAD_TOP, 0, rad))
                            || !isFree(level, leanPoint(s, dir, HEAD_TOP - 0.1, BODY_HALF_WIDTH, rad))) {
                        break; // leaning further this way only goes deeper into the wall
                    }
                    if (firstOccluder(level, player, eye, h) != null) continue; // still hidden, lean more

                    boolean inView = angleDeg(look, h.subtract(eye)) < VIEW_HALF_ANGLE;
                    if (requireOutOfView && inView) break;
                    // Ambush: nothing of the leaning head may be visible from where the player is now.
                    if (hiddenFrom != null && (firstOccluder(level, player, hiddenFrom, h) == null
                            || firstOccluder(level, player, hiddenFrom, leanPoint(s, dir, HEAD_TOP - 0.05, 0, rad)) == null)) {
                        break;
                    }

                    boolean shoulderVisible = firstOccluder(level, player, eye, shoulder) == null;
                    boolean torsoVisible = firstOccluder(level, player, eye, torso) == null;
                    boolean onlyHead = !shoulderVisible && !torsoVisible;
                    double dist = Math.sqrt(eye.distanceToSqr(s));
                    int light = level.getMaxLocalRawBrightness(BlockPos.containing(s));

                    double score = 0;
                    score -= Math.abs(dist - preferredDist);        // prefer a medium distance
                    score -= light * 0.8;                           // prefer darkness
                    score -= (lean - MIN_LEAN) * 0.1;               // prefer a small peek
                    if (shoulderVisible) score -= SHOULDER_PENALTY; // shoulder/arm sticks out
                    if (torsoVisible) score -= SHOULDER_PENALTY;    // half the body sticks out
                    if (onlyHead) score += 3;                       // body stays behind cover
                    if (hiddenFrom != null) {                       // ambush: glimpsed at the edge of the screen
                        double a = angleDeg(look, h.subtract(eye));
                        if (a >= PERIPHERY_MIN && a <= PERIPHERY_MAX) score += PERIPHERY_BONUS;
                    } else if (requireOutOfView) {                  // just past the edge of view: a small turn finds it
                        double a = angleDeg(look, h.subtract(eye));
                        if (a <= NEAR_EDGE_MAX) score += NEAR_EDGE_BONUS;
                    }
                    score += random.nextDouble() * 2;               // a bit of variety

                    Result r = new Result(s, yaw, lean * sideSign, shift, dist, light,
                            onlyHead, shoulderVisible, inView, score);
                    if (best == null || r.score() > best.score()) best = r;
                    break; // first (smallest) working angle for this side and shift
                }
            }
        }
        return best;
    }

    /**
     * Where a body point ends up when leaning from the waist.
     *
     * @param height  height of the point above the feet when standing upright
     * @param lateral sideways offset of the point toward {@code dir} when standing upright
     * @param rad     lean angle; points below the hip do not move
     */
    private static Vec3 leanPoint(Vec3 s, Vec3 dir, double height, double lateral, double rad) {
        if (height <= HIP_HEIGHT) return s.add(dir.scale(lateral)).add(0, height, 0);
        double a = height - HIP_HEIGHT;
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double side = lateral * cos + a * sin;
        double up = HIP_HEIGHT - lateral * sin + a * cos;
        return s.add(dir.scale(side)).add(0, up, 0);
    }

    private static double angleDeg(Vec3 a, Vec3 b) {
        double cos = a.normalize().dot(b.normalize());
        return Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
    }

    /** True when nothing that blocks sight lies between the two points (glass, leaves... are see-through). */
    public static boolean canSee(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
        return firstOccluder(level, player, from, to) == null;
    }

    /**
     * First block between from and to that really blocks sight. Blocks that do not occlude
     * (glass, leaves, fences, flowers...) are passed through.
     */
    private static BlockHitResult firstOccluder(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
        Vec3 cur = from;
        Vec3 dir = to.subtract(from).normalize();
        for (int i = 0; i < 16; i++) {
            BlockHitResult hit = level.clip(new ClipContext(cur, to, ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) return null;
            BlockState st = level.getBlockState(hit.getBlockPos());
            if (st.canOcclude()) return hit;
            cur = hit.getLocation().add(dir.scale(0.05));
            if (cur.distanceToSqr(from) >= to.distanceToSqr(from)) return null;
        }
        return null;
    }
}
