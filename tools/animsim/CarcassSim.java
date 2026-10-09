import com.anta.anim.CarcassLayout;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Offline carcass simulator, step 1: runs the mod's real layout code (CarcassLayout) and prints every
 * primitive with its full transform as JSON. carcass_render.py draws them with the vanilla models and
 * textures. Same idea as SimMain for the watcher's animations.
 *
 * Usage: java -cp build CarcassSim <kind 0-3> <style 0-3> <seed> [yawDeg]
 */
public final class CarcassSim implements CarcassLayout.Sink {
    private final Deque<double[]> stack = new ArrayDeque<>();
    private double[] m = identity();
    private final StringBuilder out = new StringBuilder();
    private boolean first = true;

    public static void main(String[] args) {
        int kind = Integer.parseInt(args[0]), style = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        float yaw = args.length > 3 ? Float.parseFloat(args[3]) : 0f;
        CarcassSim s = new CarcassSim();
        s.out.append("{\"kind\":").append(kind).append(",\"style\":").append(style).append(",\"prims\":[");
        // Same as CarcassRenderer.render: the entity yaw first.
        s.rotateY(180f - yaw);
        CarcassLayout.draw(kind, style, seed, s);
        s.out.append("]}");
        System.out.println(s.out);
    }

    private static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    /** m = m * b (row-major 4x4). */
    private void mul(double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double v = 0;
                for (int k = 0; k < 4; k++) v += m[i * 4 + k] * b[k * 4 + j];
                r[i * 4 + j] = v;
            }
        m = r;
    }

    @Override public void push() { stack.push(m.clone()); }
    @Override public void pop() { m = stack.pop(); }

    @Override public void translate(float x, float y, float z) {
        mul(new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1});
    }

    @Override public void rotateX(float deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        mul(new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1});
    }

    @Override public void rotateY(float deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        mul(new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1});
    }

    @Override public void rotateZ(float deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        mul(new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
    }

    @Override public void scale(float x, float y, float z) {
        mul(new double[]{x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1});
    }

    private void begin(String type) {
        if (!first) out.append(',');
        first = false;
        out.append("{\"t\":\"").append(type).append("\",\"m\":[");
        for (int i = 0; i < 16; i++) {
            if (i > 0) out.append(',');
            out.append(String.format(Locale.ROOT, "%.6f", m[i]));
        }
        out.append(']');
    }

    @Override
    public void part(String name, int layer, float shiftX, float shiftY, float shiftZ) {
        begin("part");
        out.append(String.format(Locale.ROOT, ",\"name\":\"%s\",\"layer\":%d,\"shift\":[%.4f,%.4f,%.4f]}",
                name, layer, shiftX, shiftY, shiftZ));
    }

    @Override
    public void cube(float cx, float cy, float cz, float sx, float sy, float sz, int tile, int capFace, int capTile) {
        begin("cube");
        out.append(String.format(Locale.ROOT, ",\"c\":[%.5f,%.5f,%.5f],\"s\":[%.5f,%.5f,%.5f],\"tile\":%d,\"cap\":%d,\"capTile\":%d}",
                cx, cy, cz, sx, sy, sz, tile, capFace, capTile));
    }

    @Override
    public void decal(int texture, float x, float z, float hx, float hz, float rotDeg, float lift) {
        begin("decal");
        out.append(String.format(Locale.ROOT, ",\"tex\":%d,\"x\":%.5f,\"z\":%.5f,\"hx\":%.5f,\"hz\":%.5f,\"rot\":%.3f,\"lift\":%.5f}",
                texture, x, z, hx, hz, rotDeg, lift));
    }
}
