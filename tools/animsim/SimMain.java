import com.anta.anim.WatcherPose;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline animation simulator, step 1: runs the mod's real pose code (WatcherPose) for a
 * scenario and prints the frames as JSON. render.py turns them into pictures.
 *
 * Scene: the watcher stands with its feet at (0,0,0), body yaw 0 (faces +Z, its right side
 * is -X). A 1x1 cover column stands in front of it. The camera is the player's eye; each
 * frame also says where the player is looking (Minecraft yaw/pitch), for the first-person view.
 *
 * Usage: java -cp out SimMain <scenario>   (scenarios: hide, peek, look, glance, follow, idle)
 */
public final class SimMain {
    private record Frame(String label, double[] cam, float lean, float recoil, float lookYaw, float lookPitch,
                         float[] head, float tilt) {
        Frame(String label, double[] cam, float lean, float recoil, float lookYaw, float lookPitch) {
            this(label, cam, lean, recoil, lookYaw, lookPitch, null, 0f);
        }
    }

    private static final float BASE_LEAN = 30f; // lean to its right, degrees

    public static void main(String[] args) {
        String scenario = args.length > 0 ? args[0] : "hide";
        List<Frame> frames = new ArrayList<>();
        double[] eye = {-3.0, 1.62, 10.0};

        switch (scenario) {
            case "hide" -> {
                // Player looks straight at it; noticed at t=0. Quarter-tick steps (= 80 fps).
                float[] look = lookAtHead(eye, BASE_LEAN);
                for (float t = -1f; t <= 5.001f; t += 0.25f) {
                    float lean = t < 0 ? BASE_LEAN : WatcherPose.hideLean(BASE_LEAN, t);
                    frames.add(new Frame(String.format(Locale.ROOT, "tick %.2f", t), eye, lean, t < 0 ? 0f : WatcherPose.hideRecoil(t), look[0], look[1]));
                }
            }
            case "slowhide" -> {
                // Brain reaction SLOW: looked at, it calmly straightens up over 1.5 s, no jerk. Every 2 ticks.
                float[] look = lookAtHead(eye, BASE_LEAN);
                for (float t = -2f; t <= WatcherPose.SLOW_HIDE_TICKS + 2.001f; t += 2f) {
                    float lean = t < 0 ? BASE_LEAN : WatcherPose.slowHideLean(BASE_LEAN, t);
                    frames.add(new Frame(String.format(Locale.ROOT, "tick %.0f", t), eye, lean, 0f, look[0], look[1]));
                }
            }
            case "return" -> {
                // Brain reaction RETURN: jerks back at t=0, waits 60 ticks hidden, then peeks out again
                // with the normal slow peek. The player keeps looking at the cover. Every 4 ticks.
                float[] look = lookAt(eye, new double[]{-0.4, 1.5, 0.6});
                float back = 60f;
                for (float t = -2f; t <= back + WatcherPose.PEEK_DELAY + WatcherPose.PEEK_TICKS + 2.001f; t += 4f) {
                    float lean, recoil = 0f;
                    if (t < 0) lean = BASE_LEAN;
                    else if (t < back) { lean = WatcherPose.hideLean(BASE_LEAN, t); recoil = WatcherPose.hideRecoil(t); }
                    else lean = WatcherPose.peekLean(BASE_LEAN, t - back);
                    frames.add(new Frame(String.format(Locale.ROOT, "tick %.0f%s", t, t >= back ? "  back" : ""), eye, lean, recoil, look[0], look[1]));
                }
            }
            case "peek" -> {
                // Spawned at t=0 behind the cover; the player is looking at the cover. Every 2 ticks.
                float[] look = lookAt(eye, new double[]{-0.4, 1.5, 0.6});
                for (float t = 0f; t <= 44.001f; t += 2f) {
                    float lean = WatcherPose.peekLean(BASE_LEAN, t);
                    frames.add(new Frame(String.format(Locale.ROOT, "tick %.0f", t), eye, lean, 0f, look[0], look[1]));
                }
            }
            case "look" -> {
                // Player walks sideways in front of it, looking at the cover: the head must follow, the body must not.
                for (int i = 0; i <= 11; i++) {
                    double x = -7 + i * (8.0 / 11);
                    double[] cam = {x, 1.62, 9.0};
                    float[] look = lookAt(cam, new double[]{-0.15, 1.4, 1.0});
                    frames.add(new Frame(String.format(Locale.ROOT, "x %.1f", x), cam, BASE_LEAN, 0f, look[0], look[1]));
                }
            }
            case "glance" -> {
                // Player was looking 80 deg away and turns toward it at 15 deg/tick (a normal mouse turn).
                // The server checks once per tick: when the head is inside the notice cone it hides.
                float[] target = lookAtHead(eye, BASE_LEAN);
                float noticedAt = -1;
                for (float t = 0f; t <= 8.001f; t += 0.5f) {
                    float yaw = target[0] - Math.max(0f, 80f - 15f * t);
                    if (noticedAt < 0 && t == Math.floor(t)
                            && angleToHead(eye, yaw, target[1], BASE_LEAN) <= WatcherPose.NOTICE_HALF_ANGLE) {
                        noticedAt = t;
                    }
                    float lean = noticedAt < 0 ? BASE_LEAN : WatcherPose.hideLean(BASE_LEAN, t - noticedAt);
                    String label = String.format(Locale.ROOT, "tick %.1f  %.0f deg off%s", t,
                            angleToHead(eye, yaw, target[1], BASE_LEAN), noticedAt >= 0 ? "  NOTICED" : "");
                    frames.add(new Frame(label, eye, lean, noticedAt < 0 ? 0f : WatcherPose.hideRecoil(t - noticedAt), yaw, target[1]));
                }
            }
            case "follow" -> {
                // Delayed head: the player steps sideways quickly (0.6 block/tick) for 8 ticks, then stops.
                // The head holds still at first, then turns after him and catches up. Every tick.
                float[] head = null;
                boolean[] ty = {false}, tp = {false};
                for (int t = 0; t <= 22; t++) {
                    double x = -6 + Math.min(t, 8) * 0.6;
                    double[] cam = {x, 1.62, 9.0};
                    double[] h = headPos(BASE_LEAN);
                    float[] target = WatcherPose.lookAt(cam[0] - h[0], cam[1] - h[1], cam[2] - h[2], 0f);
                    if (head == null) head = target.clone();
                    else {
                        head[0] = WatcherPose.followHead(head[0], target[0], 1f, ty);
                        head[1] = WatcherPose.followHead(head[1], target[1], 1f, tp);
                    }
                    float[] look = lookAt(cam, new double[]{-0.15, 1.4, 1.0});
                    frames.add(new Frame(String.format(Locale.ROOT, "tick %d  x %.1f  head %.0f/target %.0f deg",
                            t, x, head[0], target[0]), cam, BASE_LEAN, 0f, look[0], look[1], head.clone(), 0f));
                }
            }
            case "idle" -> {
                // Idle: the player stands still; every 6 ticks over ~22 s. The head sometimes tilts, the body never moves.
                float[] look = lookAtHead(eye, BASE_LEAN);
                int seed = args.length > 1 ? Integer.parseInt(args[1]) : 7;
                for (float t = 40f; t <= 480.001f; t += 12f) {
                    float tilt = WatcherPose.idleTilt(t, seed);
                    frames.add(new Frame(String.format(Locale.ROOT, "tick %.0f  tilt %.0f deg", t, tilt),
                            eye, BASE_LEAN, 0f, look[0], look[1], null, tilt));
                }
            }
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"scenario\":\"").append(scenario).append("\",\"frames\":[");
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            double[] h = headPos(f.lean());
            float[] look = WatcherPose.lookAt(f.cam()[0] - h[0], f.cam()[1] - h[1], f.cam()[2] - h[2], 0f);
            if (f.head() != null) look = f.head();
            WatcherPose p = WatcherPose.compute(f.lean(), f.recoil(), look[0], look[1]);
            p.tiltHead(f.tilt());
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT,
                    "{\"label\":\"%s\",\"lean\":%.2f,\"cam\":[%.3f,%.3f,%.3f],\"look\":[%.3f,%.3f],\"parts\":{",
                    f.label(), f.lean(), f.cam()[0], f.cam()[1], f.cam()[2], f.lookYaw(), f.lookPitch()));
            part(sb, "head", p.head, false);
            part(sb, "body", p.body, false);
            part(sb, "rightArm", p.rightArm, false);
            part(sb, "leftArm", p.leftArm, false);
            part(sb, "rightLeg", p.rightLeg, false);
            part(sb, "leftLeg", p.leftLeg, true);
            sb.append("}}");
        }
        sb.append("]}");
        System.out.println(sb);
    }

    /** Eye position of the watcher in the scene (body yaw 0, so its right side is -X). */
    private static double[] headPos(float lean) {
        double[] off = WatcherPose.eyeOffset(lean);
        return new double[]{-off[0], off[1], 0};
    }

    private static float[] lookAtHead(double[] eye, float lean) {
        return lookAt(eye, headPos(lean));
    }

    /** Minecraft yaw/pitch for looking from `from` to `to`. */
    private static float[] lookAt(double[] from, double[] to) {
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    /** Angle between the player's look direction and the direction to the watcher's head. */
    private static double angleToHead(double[] eye, float yaw, float pitch, float lean) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double lx = -Math.sin(y) * Math.cos(p), ly = -Math.sin(p), lz = Math.cos(y) * Math.cos(p);
        double[] h = headPos(lean);
        double dx = h[0] - eye[0], dy = h[1] - eye[1], dz = h[2] - eye[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cos = (lx * dx + ly * dy + lz * dz) / len;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    private static void part(StringBuilder sb, String name, WatcherPose.Part p, boolean last) {
        sb.append(String.format(Locale.ROOT, "\"%s\":[%.4f,%.4f,%.4f,%.5f,%.5f,%.5f]%s",
                name, p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, last ? "" : ","));
    }
}
