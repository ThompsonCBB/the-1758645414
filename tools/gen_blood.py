"""Generates the dead-animal textures: procedural pixel art in Minecraft's own style (16 texels per block,
small palettes, no smooth gradients). No AI, no external images.

  carcass_blood.png              32x32  pool on the ground
  carcass_smear.png              32x32  drag smear (long axis = V)
  carcass_gore.png              128x16  8 tiles: meat, bone, guts, clot, neck section, leg section, open cavity, wool/feathers
  carcass_soak_<kind>_<style>.png 64x32  blood soaked into the animal's own skin, laid out on the VANILLA UV
                                        map of that animal, concentrated around that style's wounds

Usage (from the project root): python tools/gen_blood.py
The look is checked with the carcass simulator: tools/animsim/sim_carcass.ps1
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "animsim"))
import carcass_models as cm  # noqa: E402

TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "anta", "textures", "entity")

# Blood palette: dark, slightly brown reds (fresh blood in Minecraft lighting reads much brighter than it is).
B_DARK = (70, 6, 9)
B_MID = (96, 10, 13)
B_WET = (122, 16, 19)
B_GLINT = (150, 34, 34)


def blob_mask(n, rnd, blobs, base_r):
    yy, xx = np.mgrid[0:n, 0:n] + 0.5
    v = np.zeros((n, n))
    for _ in range(blobs):
        cx, cy = n / 2 + rnd.uniform(-n * 0.18, n * 0.18), n / 2 + rnd.uniform(-n * 0.18, n * 0.18)
        r = base_r * rnd.uniform(0.55, 1.0)
        v = np.maximum(v, 1 - np.hypot(xx - cx, yy - cy) / r)
    return v


def pool():
    rnd = random.Random(11)
    n = 32
    v = blob_mask(n, rnd, 6, 11)
    img = np.zeros((n, n, 4), np.uint8)
    for y in range(n):
        for x in range(n):
            if v[y, x] <= 0.02 + rnd.uniform(0, 0.08):
                continue
            c = B_MID if v[y, x] > 0.35 else B_DARK
            if v[y, x] > 0.55 and rnd.random() < 0.08:
                c = B_WET
            if v[y, x] > 0.6 and rnd.random() < 0.02:
                c = B_GLINT
            img[y, x] = (*c, 230 if v[y, x] > 0.15 else 205)
    # A few separate drops.
    for _ in range(5):
        x, y = rnd.randrange(1, n - 1), rnd.randrange(1, n - 1)
        if img[y, x, 3] == 0:
            img[y, x] = (*B_DARK, 215)
    return Image.fromarray(img, "RGBA")


def smear():
    rnd = random.Random(12)
    n = 32
    img = np.zeros((n, n, 4), np.uint8)
    for y in range(n):
        # Width narrows and wobbles along the drag; streaks (drag lines) along V.
        half = 9 + 3 * math.sin(y * 0.45) * rnd.uniform(0.6, 1.0)
        cx = 16 + 2 * math.sin(y * 0.2)
        for x in range(n):
            d = abs(x + 0.5 - cx)
            if d > half:
                continue
            if rnd.random() < 0.04:
                continue  # a rare dry speck
            c = B_MID if d < half * 0.55 else B_DARK
            if x % 6 == 0:
                c = B_DARK  # drag lines: darker streaks, not holes
            a = 220 if 2 < y < n - 3 else 160
            img[y, x] = (*c, a)
    return Image.fromarray(img, "RGBA")


# ------------------------------------------------------------------ gore atlas (8 tiles of 16x16)

def tile_meat(rnd):
    t = np.zeros((16, 16, 3), np.uint8)
    for y in range(16):
        for x in range(16):
            band = ((x + y) // 3) % 3  # diagonal muscle fibres, 3 px wide
            c = [(150, 36, 38), (130, 26, 30), (168, 52, 52)][band]
            if (x + y) % 9 == 0:
                c = (104, 16, 20)  # seam between fibre bundles
            t[y, x] = c
    # One thin fat streak, like vanilla raw meat.
    for x in range(16):
        y = (x // 2 + 11) % 16
        if rnd.random() < 0.8:
            t[y, x] = (214, 186, 168)
    return t


def tile_bone(rnd):
    t = np.zeros((16, 16, 3), np.uint8)
    t[:] = (226, 220, 198)
    t[12:, :] = (204, 196, 170)
    t[:, 12:] = (210, 202, 178)
    t[0:2, 0:12] = (242, 238, 222)
    for _ in range(6):
        t[rnd.randrange(2, 12), rnd.randrange(1, 12)] = (190, 178, 150)
    return t


def tile_guts(rnd):
    t = np.zeros((16, 16, 3), np.uint8)
    for y in range(16):
        shift = (y // 4) * 2 + (1 if (y // 2) % 2 else 0)  # creases wander from row to row: coils, not a grille
        for x in range(16):
            xx = (x + shift) % 16
            seg = (xx // 5) % 2
            c = (158, 70, 84) if seg == 0 else (146, 62, 78)
            if xx % 5 == 0:
                c = (110, 42, 56)  # crease between loops
            if y in (0, 15):
                c = (124, 50, 64)  # the underside of the tube, in shadow
            if y in (3, 4, 10) and xx % 5 in (2, 3):
                c = (196, 120, 128)  # wet highlight on the bulge
            t[y, x] = c
    for _ in range(7):
        t[rnd.randrange(16), rnd.randrange(16)] = (110, 18, 22)  # blood on it
    return t


def tile_clot(rnd):
    t = np.zeros((16, 16, 3), np.uint8)
    t[:] = (80, 10, 14)
    for _ in range(18):
        x, y = rnd.randrange(15), rnd.randrange(15)
        t[y:y + 2, x:x + 2] = (60, 6, 10)
    for _ in range(4):
        t[rnd.randrange(16), rnd.randrange(16)] = (124, 24, 26)
    return t


def ragged(rnd, round_corners):
    """Alpha mask of a torn patch: the border pixels are partly missing (cutout), corners rounded."""
    a = np.full((16, 16), 255, np.uint8)
    for i in range(16):
        for e in (0, 15):
            if rnd.random() < 0.55:
                a[e, i] = 0
            if rnd.random() < 0.55:
                a[i, e] = 0
    for i in range(1, 15):
        for e in (1, 14):
            if rnd.random() < 0.15:
                a[e, i] = 0
            if rnd.random() < 0.15:
                a[i, e] = 0
    if round_corners:
        for y in range(16):
            for x in range(16):
                if math.hypot(x - 7.5, y - 7.5) > 8.2:
                    a[y, x] = 0
    return a


def tile_section(rnd, hole):
    """Torn cross-section (neck / leg): raw meat with fibres and clots, a small bone, maybe the windpipe."""
    t = tile_meat(rnd)
    t[t[..., 0] > 200] = (150, 36, 38)  # no fat streak here
    for _ in range(10):
        x, y = rnd.randrange(1, 14), rnd.randrange(1, 14)
        t[y:y + 2, x:x + 2] = (98, 14, 18)  # clotted spots
    by, bx = (4, 7) if hole else (6, 6)
    t[by:by + 3, bx:bx + 3] = (226, 220, 196)  # bone (spine / leg bone)
    t[by + 2, bx:bx + 3] = (198, 188, 160)
    t[by + 1, bx + 1] = (186, 150, 130)  # marrow
    if hole:
        t[10:12, 7:9] = (44, 6, 8)  # windpipe
        t[9, 7:9] = (176, 96, 100)
    return t


def tile_cavity(rnd):
    t = np.zeros((16, 16, 3), np.uint8)
    t[:] = (48, 6, 9)
    for _ in range(20):
        x, y = rnd.randrange(1, 14), rnd.randrange(1, 14)
        t[y:y + 2, x:x + 2] = (32, 3, 5)
    for _ in range(7):
        t[rnd.randrange(2, 14), rnd.randrange(2, 14)] = (120, 22, 26)
    for i in range(16):  # torn edge
        for e in (0, 15):
            if rnd.random() < 0.8:
                t[e, i] = (138, 30, 32)
                t[i, e] = (138, 30, 32)
    return t


def tile_fluff(rnd):
    t = np.zeros((16, 16, 3), np.uint8)
    t[:] = (236, 236, 232)
    for _ in range(14):
        t[rnd.randrange(16), rnd.randrange(16)] = (212, 212, 206)
    for _ in range(3):
        t[rnd.randrange(16), rnd.randrange(16)] = (150, 30, 30)  # a speck of blood
    return t


def gore():
    rnd = random.Random(1758645414)
    full = np.full((16, 16), 255, np.uint8)
    tiles = [(tile_meat(rnd), full), (tile_bone(rnd), full), (tile_guts(rnd), full), (tile_clot(rnd), full),
             (tile_section(rnd, True), ragged(rnd, False)), (tile_section(rnd, False), ragged(rnd, True)),
             (tile_cavity(rnd), ragged(rnd, False)), (tile_fluff(rnd), full)]
    atlas = np.zeros((16, 128, 4), np.uint8)
    for i, (t, a) in enumerate(tiles):
        atlas[:, i * 16:(i + 1) * 16, :3] = t
        atlas[:, i * 16:(i + 1) * 16, 3] = a
    return Image.fromarray(atlas, "RGBA")


# ------------------------------------------------------------------ blood soaked into the skin

# Wound geometry per animal, standing model space (px, Y down, +X = the side it lies on).
# neck: centre of the torn neck on the body front; belly: middle of the belly; rear: back end; half: half width.
WOUNDS = {
    # flank: centre of the opening of the gutted style, on the side that faces the sky (-X), see CarcassLayout.gutted
    "cow": dict(neck=(0, 5, -8), flank=(-6, 7.8, 1), rear=(0, 7, 10), half=6, head_back=(0, 4, -8)),
    "pig": dict(neck=(0, 13, -8), flank=(-5, 14.6, 0), rear=(0, 14, 8), half=5, head_back=(0, 12, -6)),
    "sheep": dict(neck=(0, 6, -9.75), flank=(-5.75, 9.8, 0), rear=(0, 9, 9.75), half=5.75, head_back=(0, 6, -8)),
    "chicken": dict(neck=(0, 14, -4), flank=(-3, 16.5, 0), rear=(0, 16, 4), half=3, head_back=(0, 15, -4)),
}
LEG_NAMES = ("right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg", "right_leg", "left_leg",
             "right_wing", "left_wing")


def gauss(p, c, r):
    d = np.linalg.norm(np.asarray(p, float) - np.asarray(c, float))
    return math.exp(-(d / r) ** 2)


def soak_weight(kind, style, part_name, part, p):
    """How bloody this point of the model is (0 = clean, >1 = soaked)."""
    w = WOUNDS[kind]
    size = w["half"] / 6.0  # chicken wounds are smaller
    ground = max(0.0, (p[0] - (w["half"] - 3 * size)) / (3 * size))  # the side lying in the blood
    if part_name in ("head", "beak", "red_thing"):
        if style == "gutted":
            return 0.15 * ground
        # The torn neck end of the head: its back face, near the pivot.
        return 1.6 * gauss(p, w["head_back"], 4.5 * size) + 0.3 * ground
    if part_name in LEG_NAMES:
        top = np.asarray(part["pivot"], float)
        if style == "scattered":
            return 1.5 * gauss(p, top, 3.5 * size) + 0.1
        return 0.35 * ground + 0.12
    # torso
    v = 0.0
    if style in ("decapitated", "scattered", "dragged"):
        v += 1.7 * gauss(p, w["neck"], 7 * size)
    if style == "gutted":
        f = np.asarray(w["flank"], float)
        # Around the opening on the upper flank, running down the sides toward the ground.
        d = math.hypot((p[0] - f[0]) / (5 * size), math.hypot(p[1] - f[1], p[2] - f[2]) / (6.5 * size))
        v += 1.7 * math.exp(-d * d) + 0.4 * gauss(p, w["neck"], 4 * size)
    if style == "scattered":
        v += 1.4 * gauss(p, w["rear"], 6 * size) + 0.1
    if style == "dragged":
        v += 0.9 * ground * (1.0 - min(1.0, abs(p[2] - w["neck"][2]) / (24 * size))) + 0.35 * ground
    else:
        v += 0.45 * ground
    return v


def soak(kind, style):
    rnd = random.Random(hash((kind, style)) & 0xffff)
    tw, th = cm.TEX_SIZE
    weight = np.zeros((th, tw))
    model = cm.FUR_MODEL.get(kind, cm.SKIN_MODEL[kind])  # the soak is drawn over the outermost layer
    for name, part in model.items():
        for texoff, origin, size, infl in part["boxes"]:
            for _, corners, (u, v, fw, fh) in cm.box_faces(origin, size, infl, texoff):
                c = [np.array(q, float) for q in corners]  # TL TR BR BL
                for j in range(int(fh)):
                    for i in range(int(fw)):
                        s, t = (i + 0.5) / fw, (j + 0.5) / fh
                        top = c[0] + (c[1] - c[0]) * s
                        bot = c[3] + (c[2] - c[3]) * s
                        p = cm.part_point(part, top + (bot - top) * t)
                        x, y = int(u) + i, int(v) + j
                        if 0 <= x < tw and 0 <= y < th:
                            weight[y, x] = max(weight[y, x], soak_weight(kind, style, name, part, p))
    # Blocky noise (2x2 cells) so the edge is ragged pixel art, not a smooth gradient.
    noise = np.kron(np.array([[rnd.random() for _ in range(tw // 2)] for _ in range(th // 2)]), np.ones((2, 2)))
    coarse = np.kron(np.array([[rnd.random() for _ in range(tw // 4)] for _ in range(th // 4)]), np.ones((4, 4)))
    fine = np.array([[rnd.random() for _ in range(tw)] for _ in range(th)])
    val = weight + 0.45 * (coarse - 0.5) + 0.35 * (noise - 0.5) + 0.05 * (fine - 0.5)
    img = np.zeros((th, tw, 4), np.uint8)
    for y in range(th):
        for x in range(tw):
            e = val[y, x]
            if e < 0.55:
                if e > 0.42 and fine[y, x] < 0.18:
                    img[y, x] = (*B_DARK, 200)  # a few loose specks around the edge
                continue
            if e > 1.25:
                c, a = B_DARK, 245
            elif e > 0.85:
                c, a = B_MID, 235
            else:
                c, a = B_WET, 205
            if e > 0.9 and fine[y, x] > 0.95:
                c = B_GLINT
            img[y, x] = (*c, a)
    return Image.fromarray(img, "RGBA")


def main():
    os.makedirs(TEX, exist_ok=True)
    pool().save(os.path.join(TEX, "carcass_blood.png"))
    smear().save(os.path.join(TEX, "carcass_smear.png"))
    gore().save(os.path.join(TEX, "carcass_gore.png"))
    for kind in cm.KINDS:
        for style in cm.STYLES:
            soak(kind, style).save(os.path.join(TEX, f"carcass_soak_{kind}_{style}.png"))
    print("ok")


if __name__ == "__main__":
    main()
