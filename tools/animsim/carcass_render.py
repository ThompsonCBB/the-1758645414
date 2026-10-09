"""Offline carcass simulator, step 2: draws the primitives produced by CarcassSim.java.

The dead animals are drawn with the REAL vanilla models (carcass_models.py, checked against the jar) and
the REAL vanilla textures (extracted from the local client jar by extract_vanilla.py), plus the mod's
own gore textures, exactly where the mod's layout code (com.anta.anim.CarcassLayout) puts them.
Lighting imitates Minecraft's entity lighting (two fixed diffuse lights, ambient 0.4).

Views per carcass: PLAYER (standing 4 blocks away, eye height, FOV 70) and TOP (from above).
Output: tools/animsim/out/carcass_<kind>.png (one row per style) and carcass_all.png (every kind, player view).

Usage: python carcass_render.py <json file> ...   (sim_carcass.ps1 runs everything)
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

import carcass_models as cm

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
VAN = os.path.join(HERE, "vanilla")
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "anta", "textures", "entity")
OUT = os.path.join(HERE, "out")

SKIN, FUR, SOAK = 0, 1, 2
POS_X, NEG_X, POS_Y, NEG_Y, POS_Z, NEG_Z = range(6)
TINT = np.array([0.84, 0.78, 0.76])  # CarcassRenderer.TINT_*
L0 = np.array([0.2, 1.0, -0.7]) / np.linalg.norm([0.2, 1.0, -0.7])
L1 = np.array([-0.2, 1.0, 0.7]) / np.linalg.norm([-0.2, 1.0, 0.7])
NEAR = 0.05


def load(path):
    return np.array(Image.open(path).convert("RGBA")).astype(float)


class Scene:
    def __init__(self):
        self.opaque = []       # (corners world 4x3, uvs 4x2 texel, tex array, tint rgb, lit)
        self.translucent = []

    def quad(self, corners, uvs, tex, tint=(1, 1, 1), lit=True, blend=False, center=None):
        """center: a point inside the box this face belongs to, to orient its normal outward for lighting."""
        (self.translucent if blend else self.opaque).append((np.asarray(corners, float), np.asarray(uvs, float), tex,
                                                             np.asarray(tint, float), lit, center))


class Camera:
    def __init__(self, eye, target, fov_deg, w, h):
        self.eye = np.array(eye, float)
        f = np.array(target, float) - self.eye
        f /= np.linalg.norm(f)
        r = np.cross(f, [0, 1, 0])
        r /= np.linalg.norm(r)
        self.f, self.r, self.u = f, r, np.cross(r, f)
        self.w, self.h = w, h
        self.scale = (h / 2) / math.tan(math.radians(fov_deg) / 2)


def entity_light(n):
    n = n / (np.linalg.norm(n) + 1e-9)
    return min(1.0, 0.4 + 0.6 * (max(0.0, n @ L0) + max(0.0, n @ L1)))


def raster_tri(color, zbuf, pts, uvs, tex, tint, blend):
    H, W = zbuf.shape
    xmin = max(int(math.floor(pts[:, 0].min())), 0)
    xmax = min(int(math.ceil(pts[:, 0].max())), W - 1)
    ymin = max(int(math.floor(pts[:, 1].min())), 0)
    ymax = min(int(math.ceil(pts[:, 1].max())), H - 1)
    if xmin > xmax or ymin > ymax:
        return
    xs, ys = np.meshgrid(np.arange(xmin, xmax + 1) + 0.5, np.arange(ymin, ymax + 1) + 0.5)
    (x0, y0, _), (x1, y1, _), (x2, y2, _) = pts
    den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
    if abs(den) < 1e-12:
        return
    a = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / den
    b = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / den
    c = 1 - a - b
    inside = (a >= -1e-6) & (b >= -1e-6) & (c >= -1e-6)
    if not inside.any():
        return
    iw = a / pts[0, 2] + b / pts[1, 2] + c / pts[2, 2]
    depth = 1.0 / np.where(iw > 0, iw, 1e-9)
    zs = zbuf[ymin:ymax + 1, xmin:xmax + 1]
    mask = inside & ((depth <= zs + 0.0005) if blend else (depth < zs))  # translucent: LEQUAL like Minecraft
    uu = (a * uvs[0, 0] / pts[0, 2] + b * uvs[1, 0] / pts[1, 2] + c * uvs[2, 0] / pts[2, 2]) * depth
    vv = (a * uvs[0, 1] / pts[0, 2] + b * uvs[1, 1] / pts[1, 2] + c * uvs[2, 1] / pts[2, 2]) * depth
    ui = np.clip(np.floor(uu).astype(int), 0, tex.shape[1] - 1)
    vi = np.clip(np.floor(vv).astype(int), 0, tex.shape[0] - 1)
    texel = tex[vi, ui]
    alpha = texel[..., 3] / 255.0
    rgb = texel[..., :3] * tint
    cs = color[ymin:ymax + 1, xmin:xmax + 1]
    if blend:
        mask &= alpha > 0.004
        al = alpha[..., None]
        cs[mask] = (cs * (1 - al) + rgb * al)[mask]
    else:
        mask &= alpha > 0.1  # cutout
        cs[mask] = rgb[mask]
        zs[mask] = depth[mask]


def clip_near(cam_pts, uvs):
    out_p, out_uv = [], []
    n = len(cam_pts)
    for i in range(n):
        p, q = cam_pts[i], cam_pts[(i + 1) % n]
        up, uq = uvs[i], uvs[(i + 1) % n]
        pin, qin = p[2] >= NEAR, q[2] >= NEAR
        if pin:
            out_p.append(p)
            out_uv.append(up)
        if pin != qin:
            t = (NEAR - p[2]) / (q[2] - p[2])
            out_p.append(p + (q - p) * t)
            out_uv.append(up + (uq - up) * t)
    return out_p, out_uv


def draw(scene, cam, sky=(150, 185, 235)):
    color = np.zeros((cam.h, cam.w, 3), float)
    color[:] = sky
    zbuf = np.full((cam.h, cam.w), np.inf)
    for group, blend in ((scene.opaque, False), (scene.translucent, True)):
        for corners, uvs, tex, tint, lit, center in group:
            k = 1.0
            if lit:
                n = np.cross(corners[1] - corners[0], corners[3] - corners[0])
                if center is not None and n @ (corners.mean(axis=0) - center) < 0:
                    n = -n
                k = entity_light(n)
            cam_pts = [np.array([(p - cam.eye) @ cam.r, (p - cam.eye) @ cam.u, (p - cam.eye) @ cam.f]) for p in corners]
            poly, puv = clip_near(cam_pts, list(uvs))
            if len(poly) < 3:
                continue
            scr = np.array([[cam.w / 2 + p[0] / p[2] * cam.scale, cam.h / 2 - p[1] / p[2] * cam.scale, p[2]] for p in poly])
            puv = np.array(puv)
            for i in range(1, len(poly) - 1):
                idx = [0, i, i + 1]
                raster_tri(color, zbuf, scr[idx], puv[idx], tex, tint * k, blend)
    return Image.fromarray(np.clip(color, 0, 255).astype(np.uint8))


def apply(m, p):
    m = np.asarray(m, float).reshape(4, 4)
    return (m @ np.array([p[0], p[1], p[2], 1.0]))[:3]


def add_ground(scene, grass, radius=6):
    tint = np.array([145, 189, 89]) / 255.0  # plains grass colour
    for x in range(-radius, radius):
        for z in range(-radius, radius):
            c = [(x, 0, z), (x, 0, z + 1), (x + 1, 0, z + 1), (x + 1, 0, z)]
            scene.quad(c, [(0, 0), (0, 16), (16, 16), (16, 0)], grass, tint, lit=False)


def add_prims(scene, data, textures):
    kind = cm.KINDS[data["kind"]]
    style = cm.STYLES[data["style"]]
    skin_model, fur_model = cm.SKIN_MODEL[kind], cm.FUR_MODEL.get(kind)
    skin_tex = textures[cm.SKIN_TEX[kind]]
    fur_tex = textures.get(cm.FUR_TEX.get(kind, ""), None)
    soak_tex = textures[f"carcass_soak_{kind}_{style}.png"]
    gore = textures["carcass_gore.png"]
    decals = [textures["carcass_blood.png"], textures["carcass_smear.png"]]
    tw, th = cm.TEX_SIZE
    for p in data["prims"]:
        m = p["m"]
        if p["t"] == "part":
            layer = p["layer"]
            if layer == FUR and fur_model is None:
                continue
            model = fur_model if (layer == FUR or (layer == SOAK and fur_model is not None)) else skin_model
            part = model.get(p["name"])
            if part is None:
                continue
            tex = {SKIN: skin_tex, FUR: fur_tex, SOAK: soak_tex}[layer]
            sx, sy, sz = p["shift"]
            pivot = np.array(part["pivot"], float) - np.array([sx, sy, sz])
            R = cm.rot_zyx(*part["rot"])
            for texoff, origin, size, infl in part["boxes"]:
                ctr = apply(m, (pivot + R @ (np.array(origin, float) + np.array(size, float) / 2)) / 16.0)
                for _, corners, (u, v, w, h) in cm.box_faces(origin, size, infl, texoff):
                    world = [apply(m, (pivot + R @ np.array(c, float)) / 16.0) for c in corners]
                    su, sv = tex.shape[1] / tw, tex.shape[0] / th
                    uvs = [(u * su, v * sv), ((u + w) * su, v * sv), ((u + w) * su, (v + h) * sv), (u * su, (v + h) * sv)]
                    scene.quad(world, uvs, tex, TINT if layer != SOAK else (1, 1, 1), lit=True, blend=(layer == SOAK),
                               center=ctr)
        elif p["t"] == "cube":
            add_cube(scene, m, p, gore)
        elif p["t"] == "decal":
            add_decal(scene, m, p, decals)


def add_cube(scene, m, p, gore):
    cx, cy, cz = p["c"]
    sx, sy, sz = p["s"]
    x0, x1, y0, y1, z0, z1 = cx - sx / 2, cx + sx / 2, cy - sy / 2, cy + sy / 2, cz - sz / 2, cz + sz / 2
    tile, cap, cap_tile = p["tile"], p["cap"], p["capTile"]
    # Same corner order and UV rule as CarcassRenderer.PoseSink.cube / face.
    faces = [
        (POS_Y, sx, sz, [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)]),
        (NEG_Y, sx, sz, [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)]),
        (NEG_Z, sx, sy, [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)]),
        (POS_Z, sx, sy, [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)]),
        (NEG_X, sz, sy, [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)]),
        (POS_X, sz, sy, [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)]),
    ]
    ctr = apply(m, (cx, cy, cz))
    for face, w, h, corners in faces:
        t = cap_tile if face == cap else tile
        if t < 0:
            continue  # CarcassLayout.SKIP
        u0 = t * 16
        capped = face == cap or t == 2  # GUTS: whole tile, like CarcassRenderer
        u1 = u0 + (16.0 if capped else min(16.0, max(1.0, w * 16)))
        v1 = 16.0 if capped else min(16.0, max(1.0, h * 16))
        world = [apply(m, c) for c in corners]
        # corners: a top-left, b bottom-left, c bottom-right, d top-right
        scene.quad([world[0], world[3], world[2], world[1]], [(u0, 0), (u1, 0), (u1, v1), (u0, v1)], gore, (1, 1, 1),
                   center=ctr)


def add_decal(scene, m, p, decals):
    tex = decals[p["tex"] % len(decals)]
    r = math.radians(p["rot"])
    c, s = math.cos(r), math.sin(r)
    hx, hz = p["hx"], p["hz"]
    pts = []
    for lx, lz in ((-hx, -hz), (hx, -hz), (hx, hz), (-hx, hz)):
        # translate(x, lift, z) then rotateY(rot)
        x = p["x"] + lx * c + lz * s
        z = p["z"] - lx * s + lz * c
        pts.append(apply(m, (x, p["lift"], z)))
    th, tw = tex.shape[0], tex.shape[1]
    uvs = [(0, 0), (tw, 0), (tw, th), (0, th)]
    scene.quad(pts, uvs, tex, (1, 1, 1), lit=False, blend=True)


def textures():
    t = {}
    for f in os.listdir(VAN):
        if f.endswith(".png"):
            t[f] = load(os.path.join(VAN, f))
    for f in os.listdir(TEX):
        if f.startswith("carcass") and f.endswith(".png"):
            t[f] = load(os.path.join(TEX, f))
    return t


VIEWS = {
    "PLAYER": dict(eye=(2.7, 1.62, 3.3), target=(-0.1, 0.25, -0.2), fov=70),
    "TOP": dict(eye=(0.0, 6.2, 1.7), target=(0.0, 0.0, 0.0), fov=70),
    "CLOSE": dict(eye=(1.6, 1.2, 1.6), target=(-0.3, 0.3, -0.3), fov=70),
    "DETAIL": dict(eye=(-0.9, 1.35, 1.25), target=(0.05, 0.35, -0.05), fov=50),
}


def render_one(data, tex, view, w=480, h=270):
    scene = Scene()
    add_ground(scene, tex["grass_block_top.png"])
    add_prims(scene, data, tex)
    v = VIEWS[view]
    return draw(scene, Camera(v["eye"], v["target"], v["fov"], w, h))


def main():
    if sys.argv[1] == "--big":
        # One carcass, one view, large: python carcass_render.py --big <json> <VIEW>
        tex = textures()
        data = json.load(open(sys.argv[2], encoding="utf-8-sig"))
        img = render_one(data, tex, sys.argv[3], 960, 540)
        path = os.path.join(OUT, f"big_{cm.KINDS[data['kind']]}_{cm.STYLES[data['style']]}_{sys.argv[3]}.png")
        img.save(path)
        print(path)
        return
    files = sys.argv[1:]
    tex = textures()
    os.makedirs(OUT, exist_ok=True)
    by_kind = {}
    for f in files:
        data = json.load(open(f, encoding="utf-8-sig"))
        by_kind.setdefault(data["kind"], []).append(data)
    w, h = 480, 270
    all_rows = []
    for kind, items in sorted(by_kind.items()):
        items.sort(key=lambda d: d["style"])
        views = ["PLAYER", "CLOSE", "DETAIL", "TOP"]
        sheet = Image.new("RGB", (len(views) * (w + 4), len(items) * (h + 20)), (12, 12, 16))
        d = ImageDraw.Draw(sheet)
        for row, data in enumerate(items):
            for col, view in enumerate(views):
                img = render_one(data, tex, view, w, h)
                sheet.paste(img, (col * (w + 4), row * (h + 20) + 20))
                if view == "PLAYER":
                    all_rows.append((cm.KINDS[kind], cm.STYLES[data["style"]], img))
            d.text((6, row * (h + 20) + 4), f"{cm.KINDS[kind]} / {cm.STYLES[data['style']]}   PLAYER | CLOSE | DETAIL | TOP",
                   fill=(230, 230, 230))
        path = os.path.join(OUT, f"carcass_{cm.KINDS[kind]}.png")
        sheet.save(path)
        print(path)
    print("done")


if __name__ == "__main__":
    main()
