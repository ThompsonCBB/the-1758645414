"""README demo GIF: the 'hero' scenario of SimMain rendered in a night forest.

The watcher's pose comes from the mod's own WatcherPose code (via SimMain hero), the textures from
the local Minecraft jar (tools/animsim/vanilla, run extract_vanilla.py first). Only the scenery,
fog and colour grading are made up here, to look like a dark Minecraft forest.

Usage (from the project root):
    powershell -File tools/animsim/sim.ps1 hero      # writes out/hero.json (+ a plain preview)
    python tools/animsim/hero.py                     # writes docs/media/hero.gif
"""
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render as R  # noqa: E402  (shared rasteriser + vanilla player model)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
VAN = os.path.join(HERE, "vanilla")
OUT_GIF = os.path.join(ROOT, "docs", "media", "hero.gif")
W, H = 640, 360
FOV = 55  # a little zoomed in compared with the game's 70, so the small figure reads at README size
FOG = np.array([20, 24, 32], float)
GRASS_TINT = np.array([0x79, 0xC0, 0x5A]) / 255.0
LEAF_TINT = np.array([0x48, 0xB5, 0x18]) / 255.0


def load(name, tint=None, tiles=1):
    a = np.array(Image.open(os.path.join(VAN, name + ".png")).convert("RGBA")).astype(float)
    a = a[:16, :16]  # first animation frame, if any
    if tint is not None:
        a[..., :3] *= tint
    return np.tile(a, (tiles, tiles, 1))


def box(color, zbuf, cam, b, side, top=None):
    """World box, every face tiled with 16 px per block (texture must be pre-tiled large enough)."""
    x0, y0, z0, x1, y1, z1 = b
    P = lambda x, y, z: np.array([x, y, z], float)
    sx, sy, sz = (x1 - x0) * 16, (y1 - y0) * 16, (z1 - z0) * 16
    faces = [  # corners TL, TR, BR, BL; uv rect sized in texels
        ([P(x0, y1, z1), P(x1, y1, z1), P(x1, y0, z1), P(x0, y0, z1)], (0, 0, sx, sy), side),
        ([P(x1, y1, z0), P(x0, y1, z0), P(x0, y0, z0), P(x1, y0, z0)], (0, 0, sx, sy), side),
        ([P(x0, y1, z0), P(x0, y1, z1), P(x0, y0, z1), P(x0, y0, z0)], (0, 0, sz, sy), side),
        ([P(x1, y1, z1), P(x1, y1, z0), P(x1, y0, z0), P(x1, y0, z1)], (0, 0, sz, sy), side),
        ([P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)], (0, 0, sx, sz), top if top is not None else side),
    ]
    for corners, uv, tex in faces:
        R.draw_quad(color, zbuf, cam, corners, uv, tex)


def forest(eye_xz, seed=17):
    """Tree trunks (x, z, height, kind) around the scene, keeping the line of sight to the hero tree clear."""
    rnd = random.Random(seed)
    eye = np.array(eye_xz, float)
    hero = np.array([-0.15, 1.05])
    trees = []
    for gx in range(-11, 12):
        for gz in range(-9, 6):
            x = gx * 3.4 + rnd.uniform(-1.2, 1.2)
            z = gz * 3.4 + rnd.uniform(-1.2, 1.2)
            p = np.array([x, z])
            if np.linalg.norm(p - eye) < 3.0 or np.linalg.norm(p - hero) < 3.2:
                continue
            d = hero - eye
            t = np.clip((p - eye) @ d / (d @ d), 0, 1.4)
            if np.linalg.norm(eye + d * t - p) < 1.6:
                continue
            if rnd.random() < 0.18:
                continue
            trees.append((math.floor(x) + 0.35, math.floor(z) + 0.55, rnd.randint(5, 8), rnd.random() < 0.35))
    return trees


def main():
    data = json.load(open(os.path.join(HERE, "out", "hero.json"), encoding="utf-8-sig"))
    skin = np.array(Image.open(R.SKIN).convert("RGBA")).astype(float)
    grass = load("grass_block_top", GRASS_TINT, 16)
    oak, oak_top = load("oak_log", None, 8), load("oak_log_top", None, 8)
    dark, dark_top = load("dark_oak_log", None, 8), load("dark_oak_log_top", None, 8)
    leaves = load("oak_leaves", LEAF_TINT, 8)
    trees = forest((data["frames"][0]["cam"][0], data["frames"][0]["cam"][2]))

    # Static scenery is identical in every frame except for the camera; render per frame (camera turns).
    frames = []
    yy, xx = np.mgrid[0:H, 0:W]
    r2 = ((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2
    vignette = (1 - 0.55 * np.clip(r2, 0, 1.6) / 1.6)[..., None]
    sel = os.environ.get("HERO_FRAMES")
    todo = data["frames"][34:] if not sel else [data["frames"][int(k)] for k in sel.split(",")]
    for i, fr in enumerate(todo):
        cam = R.Camera.from_look(fr["cam"], fr["look"][0], fr["look"][1], FOV, W, H)
        color = np.zeros((H, W, 3), float)
        zbuf = np.full((H, W), np.inf)
        for gx in range(-48, 48, 16):
            for gz in range(-40, 24, 16):
                g = (gx, -1.0, gz, gx + 16, 0.0, gz + 16)
                R.draw_quad(color, zbuf, cam, [np.array(p, float) for p in
                            [(g[0], 0, g[2]), (g[3], 0, g[2]), (g[3], 0, g[5]), (g[0], 0, g[5])]],
                            (0, 0, 256, 256), grass)
        for x, z, h, is_dark in trees:
            box(color, zbuf, cam, (x - 0.5, 0, z - 0.5, x + 0.5, h, z + 0.5),
                dark if is_dark else oak, dark_top if is_dark else oak_top)
            box(color, zbuf, cam, (x - 2.5, h - 2, z - 2.5, x + 2.5, h + 1, z + 2.5), leaves)
            box(color, zbuf, cam, (x - 1.5, h - 1.5, z - 1.5, x + 1.5, h + 2, z + 1.5), leaves)
        c = R.COVER
        box(color, zbuf, cam, (c[0], 0, c[2], c[3], 7, c[5]), oak, oak_top)
        box(color, zbuf, cam, (c[0] - 2, 5, c[2] - 2, c[3] + 2, 8, c[5] + 2), leaves)
        box(color, zbuf, cam, (c[0] - 1, 5.5, c[2] - 1, c[3] + 1, 9, c[5] + 1), leaves)
        for name, (texoff, origin, size, overlay, infl) in R.PARTS.items():
            px, py, pz, xr, yr, zr = fr["parts"][name]
            rot = R.rot_zyx(xr, yr, zr)
            for tex_uv, gr in ((texoff, 0.0), (overlay, infl)):
                for corners, uv in R.box_faces(origin, size, gr, tex_uv):
                    cw = [R.model_to_world(q, (px, py, pz), rot) for q in corners]
                    R.draw_quad(color, zbuf, cam, cw, uv, skin)

        # Night grade: dark, cold, linear fog toward the horizon colour, sky gradient, vignette.
        sky = np.linspace(8, 26, H)[:, None, None] * np.array([0.9, 1.0, 1.35]) / 26 * 26
        sky = np.broadcast_to(sky, (H, W, 3))
        lit = color * np.array([0.52, 0.55, 0.66])
        f = np.clip((zbuf - 8.0) / 30.0, 0, 1)[..., None] ** 0.8
        img = np.where(np.isinf(zbuf)[..., None], sky, lit * (1 - f) + FOG * f)
        img = img * vignette
        im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
        d = ImageDraw.Draw(im)
        cx, cy = W // 2, H // 2
        d.line((cx - 6, cy, cx + 6, cy), fill=(200, 200, 200))
        d.line((cx, cy - 6, cx, cy + 6), fill=(200, 200, 200))
        frames.append(im)
        print(f"\r{i + 1}/{len(data['frames'])}", end="", flush=True)
    print()

    # Fade to black, then the title card.
    try:
        big = ImageFont.truetype("consola.ttf", 46)
        small = ImageFont.truetype("consola.ttf", 17)
    except OSError:
        big = small = ImageFont.load_default()
    last = frames[-1]
    for k in range(1, 9):
        frames.append(Image.blend(last, Image.new("RGB", (W, H)), k / 8))
    for k in range(26):
        im = Image.new("RGB", (W, H), (0, 0, 0))
        d = ImageDraw.Draw(im)
        a = min(1.0, k / 6)
        d.text((W / 2, H / 2 - 12), "THE ONLOOKER", font=big, anchor="mm", fill=tuple(int(v * a) for v in (205, 200, 195)))
        d.text((W / 2, H / 2 + 30), "something is watching. it never attacks.", font=small, anchor="mm",
               fill=tuple(int(v * a) for v in (120, 40, 40)))
        frames.append(im)

    pal = [f.quantize(colors=96, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE) for f in frames]
    dur = [50] * len(pal)
    dur[-1] = 2500
    os.makedirs(os.path.dirname(OUT_GIF), exist_ok=True)
    pal[0].save(OUT_GIF, save_all=True, append_images=pal[1:], duration=dur, loop=0, optimize=True, disposal=1)
    print(OUT_GIF, os.path.getsize(OUT_GIF) // 1024, "KB")


if __name__ == "__main__":
    main()
