package com.anta.anim;

import java.util.Random;

/**
 * Pure layout of a dead animal, with NO Minecraft imports, so the very same code is used by the mod
 * ({@code client.CarcassRenderer}) and by the offline carcass simulator ({@code tools/animsim}).
 * Change the look here and both see it.
 *
 * The layout talks to a {@link Sink} with PoseStack-like calls; the mod's sink is a real PoseStack
 * plus the baked vanilla model, the simulator's sink a matrix stack writing primitives to JSON.
 *
 * Carcass frame (blocks, Y up), after the carcass yaw: origin on the ground. The body lies on its
 * LEFT side: its back toward -X, its legs toward +X, its front (neck) toward -Z.
 * Inside a model frame (after {@code scale(-1,-1,1)}) coordinates are vanilla model space in BLOCKS:
 * Y down, the face toward -Z.
 */
public final class CarcassLayout {
    // Kinds and styles: same order as CarcassEntity.Kind / CarcassEntity.Style.
    public static final int SHEEP = 0, COW = 1, CHICKEN = 2, PIG = 3;
    public static final int DECAPITATED = 0, GUTTED = 1, SCATTERED = 2, DRAGGED = 3;

    // Model layers for Sink.part.
    public static final int SKIN = 0, FUR = 1, SOAK = 2;

    // Gore atlas tiles (carcass_gore.png: 8 tiles of 16x16 in a row).
    public static final int MEAT = 0, BONE = 1, GUTS = 2, CLOT = 3, NECK = 4, STUMP = 5, CAVITY = 6, FLUFF = 7;
    /** As the main tile of a cube: draw only its cap face (a torn patch lying on a surface). */
    public static final int SKIP = -1;
    // Cube faces for a "cap" (cross-section) tile.
    public static final int NONE = -1, POS_X = 0, NEG_X = 1, POS_Y = 2, NEG_Y = 3, POS_Z = 4, NEG_Z = 5;
    // Ground decals.
    public static final int POOL = 0, SMEAR = 1;

    /** What the layout draws with. Angles in degrees; transforms post-multiply, like PoseStack. */
    public interface Sink {
        void push();

        void pop();

        void translate(float x, float y, float z);

        void rotateX(float deg);

        void rotateY(float deg);

        void rotateZ(float deg);

        void scale(float x, float y, float z);

        /**
         * Draws one vanilla model part (a root child of the animal's layer) with the current pose,
         * its pivot moved by (-shiftX, -shiftY, -shiftZ) pixels (to place a part around its own pivot).
         * layer: SKIN, FUR (sheep wool; skipped by animals without), SOAK (blood soaked into the outer surface).
         */
        void part(String name, int layer, float shiftX, float shiftY, float shiftZ);

        /** A box centred at (cx,cy,cz), sizes (sx,sy,sz), all faces {@code tile} except {@code capFace} which shows {@code capTile}. */
        void cube(float cx, float cy, float cz, float sx, float sy, float sz, int tile, int capFace, int capTile);

        /** A flat decal on the ground (y = lift) centred at (x, z), half-sizes (hx, hz), turned by rotDeg. */
        void decal(int texture, float x, float z, float hx, float hz, float rotDeg, float lift);
    }

    /** Vanilla model numbers (px, model space), checked against the 1.20.1 jar (javap). */
    private record Animal(String torso, String[] head, String[] limbs, boolean fur,
                          float hw,                 // centre height of the lying body = half its width, blocks
                          float[] headPivot,        // head pivot
                          float[] headMin, float[] headSize, // main head box (relative to its pivot)
                          float bodyTop, float bodyBottom, float bodyFront, float bodyBack, float bodyHalfX, // outer surface
                          float neckY, float neckH, float neckW, // neck cross-section on the body front
                          float[][] limbPivots, float[] limbMin, float[] limbSize, // limb boxes (all the same size)
                          float size) {             // overall gore scale (cow = 1)
    }

    private static final String[] QUAD_LEGS = {"right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg"};

    private static final Animal[] ANIMALS = new Animal[4];

    static {
        ANIMALS[SHEEP] = new Animal("body", new String[]{"head"}, QUAD_LEGS, true, 0.36f,
                new float[]{0, 6, -8}, new float[]{-3, -4, -6}, new float[]{6, 6, 8},
                4.25f, 13.75f, -9.75f, 9.75f, 5.75f,
                7f, 5f, 6f,
                new float[][]{{-3, 12, 7}, {3, 12, 7}, {-3, 12, -5}, {3, 12, -5}}, new float[]{-2, 0, -2}, new float[]{4, 12, 4},
                0.85f);
        ANIMALS[COW] = new Animal("body", new String[]{"head"}, QUAD_LEGS, false, 0.38f,
                new float[]{0, 4, -8}, new float[]{-4, -4, -6}, new float[]{8, 8, 6},
                2f, 12f, -8f, 10f, 6f,
                6f, 6f, 8f,
                new float[][]{{-4, 12, 7}, {4, 12, 7}, {-4, 12, -6}, {4, 12, -6}}, new float[]{-2, 0, -2}, new float[]{4, 12, 4},
                1f);
        ANIMALS[PIG] = new Animal("body", new String[]{"head"}, QUAD_LEGS, false, 0.32f,
                new float[]{0, 12, -6}, new float[]{-4, -4, -8}, new float[]{8, 8, 8},
                10f, 18f, -8f, 8f, 5f,
                13f, 6f, 8f,
                new float[][]{{-3, 18, 7}, {3, 18, 7}, {-3, 18, -5}, {3, 18, -5}}, new float[]{-2, 0, -2}, new float[]{4, 6, 4},
                0.9f);
        ANIMALS[CHICKEN] = new Animal("body", new String[]{"head", "beak", "red_thing"},
                new String[]{"right_leg", "left_leg", "right_wing", "left_wing"}, false, 0.25f,
                new float[]{0, 15, -4}, new float[]{-2, -6, -2}, new float[]{4, 6, 3},
                13f, 19f, -4f, 4f, 3f,
                14f, 3f, 4f,
                new float[][]{{-2, 19, 1}, {1, 19, 1}, {-4, 13, 0}, {4, 13, 0}}, new float[]{-1, 0, -3}, new float[]{3, 5, 3},
                0.45f);
    }

    /** Draws the dead animal. {@code seed} fixes the layout of one carcass. */
    public static void draw(int kind, int style, long seed, Sink s) {
        Animal a = ANIMALS[Math.floorMod(kind, 4)];
        L l = new L(a, s, new Random(seed));
        s.push();
        s.translate(-l.neckX * 0.5f, 0f, 0f); // centre the lying body on the entity position
        switch (Math.floorMod(style, 4)) {
            case GUTTED -> l.gutted();
            case SCATTERED -> l.scattered();
            case DRAGGED -> l.dragged();
            default -> l.decapitated();
        }
        s.pop();
    }

    /** One drawing pass. */
    private static final class L {
        final Animal a;
        final Sink s;
        final Random rnd;
        /** Neck of the lying body, carcass frame. */
        final float neckX, neckY, neckZ;
        int decals;

        L(Animal a, Sink s, Random rnd) {
            this.a = a;
            this.s = s;
            this.rnd = rnd;
            neckX = X(a.neckY());
            neckY = a.hw();
            neckZ = a.bodyFront() / 16f;
        }

        // Model px -> carcass frame, for a point of the lying body.
        static float X(float yModel) {
            return yModel / 16f - 1.501f;
        }

        float Y(float xModel) {
            return a.hw() - xModel / 16f;
        }

        float r(float min, float max) {
            return min + rnd.nextFloat() * (max - min);
        }

        void decal(int tex, float x, float z, float hx, float hz) {
            s.decal(tex, x, z, hx, hz, rnd.nextFloat() * 360f, 0.010f + 0.0011f * (decals++ % 14));
        }

        void pool(float x, float z, float half) {
            decal(POOL, x, z, half, half * r(0.75f, 1f));
        }

        // ------------------------------------------------------------ the four ways

        /** Head torn off: body, a pool at the neck, the head a little apart. */
        void decapitated() {
            float headX = neckX - r(0.25f, 0.6f);
            float headZ = neckZ - r(0.35f, 0.75f);
            pool(neckX + 0.1f, neckZ - 0.15f, 0.7f * a.size() + 0.2f);
            pool(headX, headZ, 0.35f * a.size() + 0.08f);
            drops(neckX, neckZ - 0.3f, 1.4f, 6);
            lyingBody(allBody(), true);
            neckCap();
            head(headX, headZ, true);
        }

        /**
         * Belly ripped open: the flank that faces the sky is torn open (dark cavity, ribs across it), the guts
         * spill over the belly edge and lie in a heap on the ground between the legs. The head is still on.
         */
        void gutted() {
            float back = X(a.bodyTop()), belly = X(a.bodyBottom());
            float z0 = a.bodyFront() / 16f, z1 = a.bodyBack() / 16f;
            float top = 2f * a.hw();                     // the flank facing up
            float k = a.size();
            float ox = back + (belly - back) * 0.58f;    // opening centre, a bit toward the belly
            float oz = z0 + (z1 - z0) * 0.5f;
            float ow = (belly - back) * 0.62f, ol = (z1 - z0) * 0.5f;
            pool(belly + 0.3f * k + 0.1f, oz, 0.55f * k + 0.25f);
            pool(back - 0.1f, oz + 0.2f, 0.35f * k + 0.1f);
            drops(belly + 0.3f, oz, 1.3f, 4);
            lyingBody(concat(allBody(), a.head()), true);

            // The opening: a dark wet hole with raw edges, on top of the flank.
            s.cube(ox, top + 0.006f, oz, ow, 0.01f, ol, SKIP, POS_Y, CAVITY);
            // Ribs across it, from the spine side toward the belly.
            int ribs = k < 0.6f ? 2 : 4;
            for (int i = 0; i < ribs; i++) {
                float z = oz - ol * 0.36f + i * (ol * 0.72f / Math.max(1, ribs - 1));
                s.cube(ox - ow * 0.1f, top + 0.016f, z, ow * 0.66f, 0.022f * k + 0.01f, 0.03f * k + 0.012f, BONE, NONE, 0);
            }
            // Guts: a loop lying on the flank, one hanging over the belly edge, a heap on the ground.
            float g = 0.09f + 0.07f * k; // gut thickness
            for (int i = 0; i < 2; i++) {
                s.push();
                s.translate(ox + ow * (0.05f + 0.25f * i), top + g * 0.3f, oz + ol * (i == 0 ? 0.12f : -0.15f));
                s.rotateY(r(-35f, 35f));
                s.cube(0f, 0f, 0f, g * 1.6f, g * 0.7f, g * 0.9f, GUTS, NONE, 0);
                s.pop();
            }
            s.cube(belly + g * 0.5f, top * 0.55f, oz + 0.02f, g, top * 0.9f, g * 1.05f, GUTS, NONE, 0); // hanging down
            float hx = belly + g * 1.6f, hz = oz;
            int n = k < 0.6f ? 3 : 6;
            for (int i = 0; i < n; i++) {
                double ang = rnd.nextDouble() * Math.PI * 2;
                float rr = r(0f, 0.12f + 0.18f * k);
                float x = hx + (float) Math.cos(ang) * rr * 0.8f, z = hz + (float) Math.sin(ang) * rr;
                s.push();
                s.translate(x, 0f, z);
                s.rotateY(rnd.nextFloat() * 180f);
                s.cube(0f, g * 0.45f + (i % 3 == 2 ? g * 0.7f : 0f), 0f, g * r(1.8f, 2.6f), g * 0.9f, g, GUTS, NONE, 0);
                s.pop();
            }
            meat(hx + 0.25f * k + 0.1f, hz + r(-0.3f, 0.3f), 1);
        }

        /** Torn apart: trunk in the middle, head, legs and pieces scattered over 3-4 blocks. */
        void scattered() {
            pool(neckX * 0.5f, 0f, 0.85f * a.size() + 0.3f);
            lyingBody(new String[]{a.torso()}, true);
            neckCap();
            // Leg sockets: where the legs were torn off, on the belly side.
            for (int i = 0; i < Math.min(4, a.limbPivots().length); i++) {
                float[] p = a.limbPivots()[i];
                if (a.limbs()[i].contains("wing")) continue;
                float wpx = a.limbSize()[0] + 0.5f;
                s.cube(X(a.bodyBottom()) + 0.006f, Y(p[0]), p[2] / 16f, 0.01f, wpx / 16f, wpx / 16f, SKIP, POS_X, STUMP);
            }
            // Rear end torn open.
            s.cube(X((a.bodyTop() + a.bodyBottom()) / 2f), a.hw(), a.bodyBack() / 16f + 0.006f,
                    (a.bodyBottom() - a.bodyTop()) / 16f * 0.8f, a.bodyHalfX() / 8f * 0.8f, 0.01f, SKIP, POS_Z, NECK);

            double ang = rnd.nextDouble() * Math.PI * 2;
            float rr = r(1.3f, 2.8f);
            float hx = (float) Math.cos(ang) * rr, hz = (float) Math.sin(ang) * rr;
            pool(hx, hz, 0.3f * a.size() + 0.08f);
            head(hx, hz, false);
            for (int i = 0; i < a.limbs().length; i++) {
                ang = rnd.nextDouble() * Math.PI * 2;
                rr = r(0.9f, 3.3f);
                float lx = (float) Math.cos(ang) * rr, lz = (float) Math.sin(ang) * rr;
                pool(lx, lz, 0.18f * a.size() + 0.08f);
                limb(i, lx, lz);
            }
            for (int i = 0; i < 6; i++) {
                ang = rnd.nextDouble() * Math.PI * 2;
                rr = r(0.6f, 3.5f);
                meat((float) Math.cos(ang) * rr, (float) Math.sin(ang) * rr, 1);
            }
            for (int i = 0; i < 3; i++) {
                ang = rnd.nextDouble() * Math.PI * 2;
                rr = r(0.7f, 3.2f);
                bone((float) Math.cos(ang) * rr, (float) Math.sin(ang) * rr);
            }
            if (a.fur() || a == ANIMALS[CHICKEN]) fluff(0f, 0f, 3.5f, 12);
            drops(0f, 0f, 3.6f, 6);
        }

        /** Headless body and a long smear leading away: the rest was dragged off. */
        void dragged() {
            pool(neckX + 0.05f, neckZ - 0.1f, 0.55f * a.size() + 0.2f);
            lyingBody(allBody(), true);
            neckCap();
            double dir = -Math.PI / 2 + (rnd.nextDouble() - 0.5) * 1.2; // away from the neck, toward -Z
            float x = neckX, z = neckZ - 0.2f;
            for (int i = 0; i < 9; i++) {
                dir += (rnd.nextDouble() - 0.5) * 0.3;
                float step = 0.42f;
                x += (float) Math.cos(dir) * step;
                z += (float) Math.sin(dir) * step;
                float half = Math.max(0.16f, (0.38f - i * 0.025f) * (0.6f + 0.4f * a.size()));
                s.decal(SMEAR, x, z, half * 0.75f, step * 0.75f, (float) Math.toDegrees(-dir) + 90f,
                        0.010f + 0.0011f * (decals++ % 14));
                if (i == 3 || i == 7) meat(x + r(-0.15f, 0.15f), z + r(-0.15f, 0.15f), 1);
            }
            if (a.fur() || a == ANIMALS[CHICKEN]) fluff(neckX, neckZ - 1.0f, 1.4f, 6);
            drops(neckX, neckZ - 0.5f, 1.4f, 5);
        }

        // ------------------------------------------------------------ pieces

        String[] allBody() {
            String[] b = new String[a.limbs().length + 1];
            b[0] = a.torso();
            System.arraycopy(a.limbs(), 0, b, 1, a.limbs().length);
            return b;
        }

        /** The given parts, lying on the side, with blood soaked into the skin. */
        void lyingBody(String[] parts, boolean soaked) {
            s.push();
            s.translate(0f, a.hw(), 0f);
            s.rotateZ(90f);
            s.scale(-1f, -1f, 1f);
            s.translate(0f, -1.501f, 0f);
            for (String p : parts) s.part(p, SKIN, 0, 0, 0);
            if (a.fur()) for (String p : parts) s.part(p, FUR, 0, 0, 0);
            if (soaked) for (String p : parts) s.part(p, SOAK, 0, 0, 0);
            s.pop();
        }

        /** The torn neck on the body's front: a cross-section with the spine and the windpipe. */
        void neckCap() {
            s.cube(neckX, neckY, neckZ - 0.006f, a.neckH() / 16f * 1.0f, a.neckW() / 16f * 0.9f, 0.01f, SKIP, NEG_Z, NECK);
        }

        /** The head alone, on one side, turned at random; the torn neck end shows. */
        void head(float x, float z, boolean nearBody) {
            float[] hp = a.headPivot(), mn = a.headMin(), sz = a.headSize();
            float cx = mn[0] + sz[0] / 2f, cy = mn[1] + sz[1] / 2f, cz = mn[2] + sz[2] / 2f;
            s.push();
            s.translate(x, sz[0] / 32f, z);
            s.rotateY(nearBody ? r(-60f, 60f) + 180f : rnd.nextFloat() * 360f);
            s.rotateZ(rnd.nextBoolean() ? 90f : -90f);
            s.scale(-1f, -1f, 1f);
            s.translate(-cx / 16f, -cy / 16f, -cz / 16f);
            for (String p : a.head()) s.part(p, SKIN, hp[0], hp[1], hp[2]);
            if (a.fur()) for (String p : a.head()) s.part(p, FUR, hp[0], hp[1], hp[2]);
            for (String p : a.head()) s.part(p, SOAK, hp[0], hp[1], hp[2]);
            // Neck end of the head: its back face (+Z in model space).
            float back = (mn[2] + sz[2]) / 16f + 0.006f;
            s.cube(cx / 16f, cy / 16f, back, sz[0] / 16f * 0.9f, sz[1] / 16f * 0.9f, 0.01f, SKIP, POS_Z, NECK);
            s.pop();
        }

        /** A leg or wing on its own, lying on the ground, the torn end showing. */
        void limb(int i, float x, float z) {
            String name = a.limbs()[i];
            float[] pv = a.limbPivots()[i], mn = a.limbMin(), sz = a.limbSize();
            boolean wing = name.contains("wing");
            float[] wmn = wing ? new float[]{name.startsWith("left") ? -1 : 0, 0, -3} : mn;
            float[] wsz = wing ? new float[]{1, 4, 6} : sz;
            float cx = wmn[0] + wsz[0] / 2f, cy = wmn[1] + wsz[1] / 2f, cz = wmn[2] + wsz[2] / 2f;
            s.push();
            s.translate(x, Math.min(wsz[0], wsz[2]) / 32f, z);
            s.rotateY(rnd.nextFloat() * 360f);
            s.rotateX(90f);
            s.scale(-1f, -1f, 1f);
            s.translate(-cx / 16f, -cy / 16f, -cz / 16f);
            s.part(name, SKIN, pv[0], pv[1], pv[2]);
            if (a.fur()) s.part(name, FUR, pv[0], pv[1], pv[2]);
            s.part(name, SOAK, pv[0], pv[1], pv[2]);
            if (!wing) {
                // Torn top of the leg: the box's top face (-Y in model space).
                s.cube(cx / 16f, wmn[1] / 16f - 0.006f, cz / 16f, wsz[0] / 16f, 0.01f, wsz[2] / 16f,
                        SKIP, NEG_Y, STUMP);
            }
            s.pop();
        }

        /** Chunks of meat, each in its own little pool. */
        void meat(float x, float z, int n) {
            for (int i = 0; i < n; i++) {
                float px = x + (i == 0 ? 0 : r(-0.25f, 0.25f)), pz = z + (i == 0 ? 0 : r(-0.25f, 0.25f));
                float sz = r(0.11f, 0.2f) * (0.55f + 0.45f * a.size());
                s.push();
                s.translate(px, 0f, pz);
                s.rotateY(rnd.nextFloat() * 360f);
                s.cube(0f, sz * 0.35f, 0f, sz * r(1f, 1.5f), sz * 0.7f, sz, MEAT, POS_Y, rnd.nextInt(3) == 0 ? CLOT : MEAT);
                s.pop();
                pool(px, pz, sz * 1.3f + 0.05f);
            }
        }

        /** A bone: a shaft with knobs at both ends. */
        void bone(float x, float z) {
            float len = 0.28f * a.size() + 0.1f, t = 0.05f * a.size() + 0.025f;
            s.push();
            s.translate(x, 0f, z);
            s.rotateY(rnd.nextFloat() * 360f);
            s.cube(0f, t * 0.5f, 0f, len, t, t, BONE, NONE, 0);
            s.cube(-len / 2f, t * 0.75f, 0f, t * 1.8f, t * 1.5f, t * 1.8f, BONE, NONE, 0);
            s.cube(len / 2f, t * 0.75f, 0f, t * 1.8f, t * 1.5f, t * 1.8f, BONE, NONE, 0);
            s.pop();
        }

        /** Wool tufts or feathers, flat on the ground. */
        void fluff(float cx, float cz, float radius, int n) {
            for (int i = 0; i < n; i++) {
                double ang = rnd.nextDouble() * Math.PI * 2;
                float rr = r(0.3f, radius);
                float sz = r(0.07f, 0.13f);
                s.push();
                s.translate(cx + (float) Math.cos(ang) * rr, 0f, cz + (float) Math.sin(ang) * rr);
                s.rotateY(rnd.nextFloat() * 360f);
                s.cube(0f, 0.015f, 0f, sz, 0.025f, sz * r(0.6f, 1.3f), FLUFF, NONE, 0);
                s.pop();
            }
        }

        /** Small drops around a point. */
        void drops(float cx, float cz, float radius, int n) {
            for (int i = 0; i < n; i++) {
                double ang = rnd.nextDouble() * Math.PI * 2;
                float rr = r(0.5f, radius);
                float h = r(0.035f, 0.08f);
                decal(POOL, cx + (float) Math.cos(ang) * rr, cz + (float) Math.sin(ang) * rr, h, h);
            }
        }

        static String[] concat(String[] x, String[] y) {
            String[] out = new String[x.length + y.length];
            System.arraycopy(x, 0, out, 0, x.length);
            System.arraycopy(y, 0, out, x.length, y.length);
            return out;
        }
    }

    private CarcassLayout() {}
}
