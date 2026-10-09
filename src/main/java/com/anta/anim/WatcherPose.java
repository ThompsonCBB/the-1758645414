package com.anta.anim;

/**
 * Pure pose math for the watcher model, with NO Minecraft imports, so the very same code is
 * used by the mod ({@code client.WatcherModel}) and by the offline frame simulator
 * ({@code tools/animsim}). Change animation here and both see it.
 *
 * Model space (like vanilla ModelPart): units are pixels (1/16 block), Y points down,
 * Y=0 is the neck, Y=12 the hip, Y=24 the feet; the face looks toward -Z; -X is the
 * character's right. Rotations are radians, applied Z*Y*X like ModelPart.
 */
public final class WatcherPose {
    /** Hip pivot in model units. */
    public static final float HIP_Y = 12f;
    /** Eye height above the feet and hip height, in blocks (rendered model is 2 blocks tall). */
    public static final double EYE_HEIGHT = 1.75;
    public static final double HIP_HEIGHT = 0.75;
    /** How far the head may turn from the body, like a human neck (degrees). */
    public static final float MAX_HEAD_YAW = 80f;
    public static final float MAX_HEAD_PITCH = 60f;
    /** Ticks to straighten up behind the cover when noticed (whole jerk + settle). */
    public static final int HIDE_TICKS = 12;
    /** Half-angle of the "player is looking at it" cone, in degrees (used by WatcherEntity). */
    public static final double NOTICE_HALF_ANGLE = 25.0;

    // --- Peek-out: appears standing upright behind the cover, waits, then slowly leans out. ---
    /** Ticks it stands upright (hidden) after spawning before it starts to lean out. */
    public static final float PEEK_DELAY = 10f;
    /** Ticks the lean-out takes (ease-in-out: starts and ends gently). */
    public static final float PEEK_TICKS = 30f;

    // --- Jerk back: a fast snap that overshoots past upright, then settles. ---
    /** Ticks of the fast snap from the current lean to the overshoot. */
    public static final float SNAP_TICKS = 6f; // 0.3 s: fast, but the eye can still follow it (was 1.5)
    /** Overshoot past upright, as a fraction of the lean (it ducks slightly the other way). */
    public static final float OVERSHOOT = 0.15f;
    /** Backward recoil of the upper body at the end of the snap, degrees. */
    public static final float RECOIL_DEG = 8f;
    /** Arms hang slightly away from the body (vanilla idle pose at rest). */
    private static final float ARM_SPREAD = 0.1f;

    public static final class Part {
        public float x, y, z, xRot, yRot, zRot;

        Part(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    // Default pivots of the vanilla (wide-arm) player model.
    public final Part head = new Part(0, 0, 0);
    public final Part body = new Part(0, 0, 0);
    public final Part rightArm = new Part(-5, 2, 0);
    public final Part leftArm = new Part(5, 2, 0);
    public final Part rightLeg = new Part(-1.9f, 12, 0);
    public final Part leftLeg = new Part(1.9f, 12, 0);

    /**
     * @param leanDeg      lean from the waist, degrees, positive = to the character's right
     * @param recoilDeg    upper body tilted backward (away from where it faces) from the waist, degrees
     * @param headYawDeg   head turn relative to the body (vanilla netHeadYaw convention)
     * @param headPitchDeg head pitch (positive = looking down)
     */
    public static WatcherPose compute(float leanDeg, float recoilDeg, float headYawDeg, float headPitchDeg) {
        WatcherPose p = new WatcherPose();
        p.head.yRot = clamp(headYawDeg, -MAX_HEAD_YAW, MAX_HEAD_YAW) * DEG;
        p.head.xRot = clamp(headPitchDeg, -MAX_HEAD_PITCH, MAX_HEAD_PITCH) * DEG;
        p.rightArm.zRot = ARM_SPREAD;
        p.leftArm.zRot = -ARM_SPREAD;
        Part[] upper = {p.head, p.body, p.rightArm, p.leftArm};

        // In the entity's render frame a positive Z rotation tilts toward its left,
        // so leaning right is a negative angle.
        float angle = -leanDeg * DEG;
        if (angle != 0f) {
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            for (Part part : upper) {
                float dx = part.x, dy = part.y - HIP_Y;
                part.x = dx * cos - dy * sin;
                part.y = HIP_Y + dx * sin + dy * cos;
                part.zRot += angle;
            }
        }
        // Backward tilt around the hip (model +Z is the back): a negative X rotation moves the top backward.
        float back = -recoilDeg * DEG;
        if (back != 0f) {
            float cos = (float) Math.cos(back), sin = (float) Math.sin(back);
            for (Part part : upper) {
                float dy = part.y - HIP_Y, dz = part.z;
                part.y = HIP_Y + dy * cos - dz * sin;
                part.z = dy * sin + dz * cos;
                part.xRot += back;
            }
        }
        return p;
    }

    /** Lean during the peek-out, {@code ticks} after spawning: upright, then a slow ease-in-out lean. */
    public static float peekLean(float targetDeg, float ticks) {
        float p = clamp((ticks - PEEK_DELAY) / PEEK_TICKS, 0f, 1f);
        float eased = p * p * (3f - 2f * p); // smoothstep
        return targetDeg * eased;
    }

    /**
     * Lean while hiding, {@code ticksSinceHide} after it was noticed: a fast ease-out snap from
     * {@code fromDeg} past upright to the overshoot, then a gentle settle back to 0.
     */
    public static float hideLean(float fromDeg, float ticksSinceHide) {
        float t = Math.max(0f, ticksSinceHide);
        float over = -fromDeg * OVERSHOOT;
        if (t < SNAP_TICKS) {
            float left = 1f - t / SNAP_TICKS;
            float eased = 1f - left * left; // quadratic ease-out: quick, but the movement itself is visible
            return fromDeg + (over - fromDeg) * eased;
        }
        float p = clamp((t - SNAP_TICKS) / (HIDE_TICKS - SNAP_TICKS), 0f, 1f);
        float eased = p * p * (3f - 2f * p);
        return over * (1f - eased);
    }

    // --- Slow retreat: no jerk at all, it calmly straightens up behind the cover, as if it is not afraid of you. ---
    /** Ticks of the slow retreat (1.5 s). */
    public static final int SLOW_HIDE_TICKS = 30;

    /** Lean during the slow retreat, {@code ticksSinceHide} after it started: ease-in-out from {@code fromDeg} to 0. */
    public static float slowHideLean(float fromDeg, float ticksSinceHide) {
        return fromDeg * (1f - smooth(ticksSinceHide / SLOW_HIDE_TICKS));
    }

    /** Backward recoil while hiding: rises during the snap, eases back to 0 by {@link #HIDE_TICKS}. */
    public static float hideRecoil(float ticksSinceHide) {
        float t = Math.max(0f, ticksSinceHide);
        if (t < SNAP_TICKS) {
            float left = 1f - t / SNAP_TICKS;
            return RECOIL_DEG * (1f - left * left);
        }
        float p = clamp((t - SNAP_TICKS) / (HIDE_TICKS - SNAP_TICKS), 0f, 1f);
        return RECOIL_DEG * (1f - p * p * (3f - 2f * p));
    }

    // --- Head follows with a delay: it "notices" that you moved, then turns (exponential catch-up). ---
    /** Time constant of the head catching up with the viewer, ticks (bigger = lazier). */
    public static final float HEAD_LAG_TICKS = 4f;
    /** Below this difference (degrees) the head does not bother to turn: it holds still, then snaps along. */
    public static final float HEAD_DEADZONE = 6f;

    /**
     * One step of the delayed head: from the current angle toward the target over {@code dtTicks}.
     * Inside the dead zone it stays put, so small movements of the viewer are ignored and the head
     * moves in short deliberate turns instead of tracking like a camera.
     */
    public static float followHead(float current, float target, float dtTicks, boolean[] turning) {
        float diff = target - current;
        float abs = Math.abs(diff);
        if (!turning[0] && abs < HEAD_DEADZONE) return current;
        turning[0] = abs > 0.5f; // once it starts, it turns all the way
        float k = 1f - (float) Math.exp(-Math.max(0f, dtTicks) / HEAD_LAG_TICKS);
        return current + diff * k;
    }

    // --- Idle: now and then a slow tilt of the head to one side, like a dog listening. Body stays still. ---
    /** Length of one idle slot, ticks; in about half of the slots it tilts. */
    public static final float IDLE_PERIOD = 120f;
    public static final float TILT_IN = 18f, TILT_HOLD = 50f, TILT_OUT = 22f;
    public static final float TILT_MIN_DEG = 9f, TILT_MAX_DEG = 16f;

    /**
     * Head tilt (roll) in degrees, {@code ticks} after spawning; 0 until the peek-out is done.
     * {@code seed} makes each watcher tilt at its own moments. Positive = toward its right.
     */
    public static float idleTilt(float ticks, int seed) {
        float t = ticks - (PEEK_DELAY + PEEK_TICKS);
        if (t <= 0f) return 0f;
        int slot = (int) Math.floor(t / IDLE_PERIOD);
        float phase = t - slot * IDLE_PERIOD;
        int h = hash(seed * 31 + slot);
        if ((h & 1) == 0) return 0f;
        float sign = (h & 2) == 0 ? 1f : -1f;
        float amp = TILT_MIN_DEG + ((h >>> 2) % 100) / 100f * (TILT_MAX_DEG - TILT_MIN_DEG);
        float env;
        if (phase < TILT_IN) env = smooth(phase / TILT_IN);
        else if (phase < TILT_IN + TILT_HOLD) env = 1f;
        else if (phase < TILT_IN + TILT_HOLD + TILT_OUT) env = 1f - smooth((phase - TILT_IN - TILT_HOLD) / TILT_OUT);
        else env = 0f;
        return sign * amp * env;
    }

    /** Adds a head roll on top of a computed pose (degrees, positive = toward its right). */
    public void tiltHead(float deg) {
        head.zRot += -deg * DEG;
    }

    private static float smooth(float p) {
        p = clamp(p, 0f, 1f);
        return p * p * (3f - 2f * p);
    }

    private static int hash(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x & 0x7fffffff;
    }

    /**
     * Eye position relative to the feet for a lean, in blocks: {sideways toward the
     * character's right, up}.
     */
    public static double[] eyeOffset(float leanDeg) {
        double rad = Math.toRadians(leanDeg);
        double a = EYE_HEIGHT - HIP_HEIGHT;
        return new double[]{a * Math.sin(rad), HIP_HEIGHT + a * Math.cos(rad)};
    }

    /**
     * Head angles that make the face look at a target.
     *
     * @param dx,dy,dz   target minus eye position, world blocks (Minecraft axes)
     * @param bodyYawDeg body yaw (Minecraft convention: 0 faces +Z)
     * @return {netHeadYaw, headPitch} in degrees, already clamped to the neck limits
     */
    public static float[] lookAt(double dx, double dy, double dz, float bodyYawDeg) {
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float worldYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float yaw = clamp(wrapDegrees(worldYaw - bodyYawDeg), -MAX_HEAD_YAW, MAX_HEAD_YAW);
        float pitch = clamp((float) -Math.toDegrees(Math.atan2(dy, horiz)), -MAX_HEAD_PITCH, MAX_HEAD_PITCH);
        return new float[]{yaw, pitch};
    }

    private static final float DEG = (float) (Math.PI / 180.0);

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private WatcherPose() {}
}
