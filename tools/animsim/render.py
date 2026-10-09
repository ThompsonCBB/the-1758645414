"""Offline animation simulator, step 2: draws the frames produced by SimMain.java.

Renders the real skin on the vanilla player-model boxes, posed exactly as the mod poses them
(the pose numbers come from com.anta.anim.WatcherPose via SimMain), plus the cover column.

Three views per frame:
  * FIRST PERSON - the player's actual screen: their eye, where they look, FOV 70, 16:9, crosshair;
  * ZOOM         - from the player's eye, zoomed on the cover, to see small details;
  * SIDE         - a helper camera behind and to the side, to see the whole body behind the cover.

Output (in tools/animsim/out/): <scenario>_sheet.png (all frames on one picture) and
<scenario>.gif (first-person view, 854x480, slowed down).

Usage: python render.py <frames.json>
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SKIN = os.path.join(ROOT, "src", "main", "resources", "assets", "anta", "textures", "entity", "watcher_double.png")
OUT = os.path.join(HERE, "out")

# Cover column in world blocks (x0, y0, z0, x1, y1, z1). The watcher's feet are at (0,0,0).
COVER = (-0.65, 0.0, 0.55, 0.35, 3.0, 1.55)

# Vanilla PlayerModel (wide arms): texOffs, box origin, box size, overlay texOffs, overlay inflate.
PARTS = {
    "head": ((0, 0), (-4, -8, -4), (8, 8, 8), (32, 0), 0.5),
    "body": ((16, 16), (-4, 0, -2), (8, 12, 4), (16, 32), 0.25),
    "rightArm": ((40, 16), (-3, -2, -2), (4, 12, 4), (40, 32), 0.25),
    "leftArm": ((32, 48), (-1, -2, -2), (4, 12, 4), (48, 48), 0.25),
    "rightLeg": ((0, 16), (-2, 0, -2), (4, 12, 4), (0, 32), 0.25),
    "leftLeg": ((16, 48), (-2, 0, -2), (4, 12, 4), (0, 48), 0.25),
}

W = H = 260
FP_W = 462  # first-person view is 16:9 at height H
GROUND = (-40.0, -0.02, -40.0, 40.0, 0.0, 50.0)


def rot_zyx(xr, yr, zr):
    cx, sx = math.cos(xr), math.sin(xr)
    cy, sy = math.cos(yr), math.sin(yr)
    cz, sz = math.cos(zr), math.sin(zr)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx  # same order as ModelPart (Quaternionf.rotationZYX)


def box_faces(origin, size, inflate, tex):
    """Six textured quads of a model box: (4 corners in model pixels, uv rect)."""
    u, v = tex
    w, h, d = size
    x0, y0, z0 = (origin[0] - inflate, origin[1] - inflate, origin[2] - inflate)
    x1, y1, z1 = (origin[0] + w + inflate, origin[1] + h + inflate, origin[2] + d + inflate)
    P = lambda x, y, z: (x, y, z)
    # Corners listed TL, TR, BR, BL of the texture rect. Model: Y down, front is -Z, character's right is -X.
    return [
        ([P(x0, y0, z0), P(x1, y0, z0), P(x1, y1, z0), P(x0, y1, z0)], (u + d, v + d, w, h)),            # front
        ([P(x1, y0, z1), P(x0, y0, z1), P(x0, y1, z1), P(x1, y1, z1)], (u + 2 * d + w, v + d, w, h)),    # back
        ([P(x0, y0, z1), P(x0, y0, z0), P(x0, y1, z0), P(x0, y1, z1)], (u, v + d, d, h)),                # right side
        ([P(x1, y0, z0), P(x1, y0, z1), P(x1, y1, z1), P(x1, y1, z0)], (u + d + w, v + d, d, h)),        # left side
        ([P(x0, y0, z1), P(x1, y0, z1), P(x1, y0, z0), P(x0, y0, z0)], (u + d, v, w, d)),                # top
        ([P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)], (u + d + w, v, w, d)),            # bottom
    ]


def model_to_world(p_pixels, pivot, rot):
    m = np.array(pivot) / 16.0 + rot @ (np.array(p_pixels) / 16.0)
    # LivingEntityRenderer for body yaw 0: rotate 180 about Y, scale(-1,-1,1), translate -1.501.
    return np.array([m[0], 1.501 - m[1], -m[2]])


class Camera:
    def __init__(self, eye, target, fov_deg, w=260, h=260):
        self.eye = np.array(eye, float)
        f = np.array(target, float) - self.eye
        f /= np.linalg.norm(f)
        r = np.cross(f, [0, 1, 0])
        r /= np.linalg.norm(r)
        u = np.cross(r, f)
        self.f, self.r, self.u = f, r, u
        self.w, self.h = w, h
        self.scale = (h / 2) / math.tan(math.radians(fov_deg) / 2)  # fov is vertical, like Minecraft

    @classmethod
    def from_look(cls, eye, yaw_deg, pitch_deg, fov_deg, w, h):
        """Camera looking where a Minecraft player with this yaw/pitch looks."""
        y, p = math.radians(yaw_deg), math.radians(pitch_deg)
        d = np.array([-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p)])
        return cls(eye, np.array(eye, float) + d, fov_deg, w, h)

    def project(self, p):
        d = np.asarray(p) - self.eye
        z = d @ self.f
        x = d @ self.r
        y = d @ self.u
        return np.array([self.w / 2 + x / z * self.scale, self.h / 2 - y / z * self.scale, z])


def shade_for(normal):
    n = normal / (np.linalg.norm(normal) + 1e-9)
    ax = np.argmax(np.abs(n))
    if ax == 1:
        return 1.0 if n[1] > 0 else 0.5
    return 0.8 if ax == 2 else 0.6


def raster_tri(color, zbuf, pts, uvs, tex, flat_rgb, shade):
    """pts: 3x3 screen (x, y, depth). uvs: 3x2 texel coords or None for flat colour."""
    if np.any(pts[:, 2] < NEAR * 0.999):
        return
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
    if abs(den) < 1e-9:
        return
    a = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / den
    b = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / den
    c = 1 - a - b
    inside = (a >= -1e-6) & (b >= -1e-6) & (c >= -1e-6)
    if not inside.any():
        return
    iw = a / pts[0, 2] + b / pts[1, 2] + c / pts[2, 2]  # perspective-correct weights
    depth = 1.0 / np.where(iw > 0, iw, 1e-9)
    zslice = zbuf[ymin:ymax + 1, xmin:xmax + 1]
    mask = inside & (depth < zslice)
    if uvs is not None:
        uu = (a * uvs[0, 0] / pts[0, 2] + b * uvs[1, 0] / pts[1, 2] + c * uvs[2, 0] / pts[2, 2]) * depth
        vv = (a * uvs[0, 1] / pts[0, 2] + b * uvs[1, 1] / pts[1, 2] + c * uvs[2, 1] / pts[2, 2]) * depth
        ui = np.clip(np.floor(uu).astype(int), 0, tex.shape[1] - 1)
        vi = np.clip(np.floor(vv).astype(int), 0, tex.shape[0] - 1)
        texel = tex[vi, ui]
        mask &= texel[..., 3] > 0
        rgb = texel[..., :3] * shade
    else:
        rgb = np.broadcast_to(np.array(flat_rgb, float) * shade, mask.shape + (3,))
    cslice = color[ymin:ymax + 1, xmin:xmax + 1]
    cslice[mask] = rgb[mask]
    zslice[mask] = depth[mask]


NEAR = 0.05


def clip_near(cam_pts, uvs):
    """Sutherland-Hodgman clip of a polygon (camera-space points) against z >= NEAR."""
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


def draw_quad(color, zbuf, cam, corners_world, uv_rect, tex, flat_rgb=None):
    normal = np.cross(corners_world[1] - corners_world[0], corners_world[3] - corners_world[0])
    shade = shade_for(normal)
    if uv_rect is not None:
        u, v, w, h = uv_rect
        uvs = np.array([[u, v], [u + w, v], [u + w, v + h], [u, v + h]], float)
    else:
        uvs = np.zeros((4, 2))
    cam_pts = []
    for p in corners_world:
        d = np.asarray(p) - cam.eye
        cam_pts.append(np.array([d @ cam.r, d @ cam.u, d @ cam.f]))
    poly, puv = clip_near(cam_pts, list(uvs))
    if len(poly) < 3:
        return
    scr = np.array([[cam.w / 2 + p[0] / p[2] * cam.scale, cam.h / 2 - p[1] / p[2] * cam.scale, p[2]] for p in poly])
    puv = np.array(puv)
    for i in range(1, len(poly) - 1):
        idx = [0, i, i + 1]
        raster_tri(color, zbuf, scr[idx], None if uv_rect is None else puv[idx], tex, flat_rgb, shade)


def draw_world_box(color, zbuf, cam, b, rgb):
    x0, y0, z0, x1, y1, z1 = b
    P = lambda x, y, z: np.array([x, y, z], float)
    faces = [
        [P(x0, y0, z0), P(x1, y0, z0), P(x1, y1, z0), P(x0, y1, z0)],
        [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)],
        [P(x0, y0, z0), P(x0, y0, z1), P(x0, y1, z1), P(x0, y1, z0)],
        [P(x1, y0, z0), P(x1, y0, z1), P(x1, y1, z1), P(x1, y1, z0)],
        [P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)],
        [P(x0, y0, z0), P(x1, y0, z0), P(x1, y0, z1), P(x0, y0, z1)],
    ]
    for f in faces:
        draw_quad(color, zbuf, cam, f, None, None, rgb)


def render(frame, cam, tex, crosshair=False):
    color = np.zeros((cam.h, cam.w, 3), float)
    color[:] = (24, 26, 34)
    zbuf = np.full((cam.h, cam.w), np.inf)
    draw_world_box(color, zbuf, cam, GROUND, (70, 90, 60))
    draw_world_box(color, zbuf, cam, COVER, (120, 95, 70))
    for name, (texoff, origin, size, overlay, infl) in PARTS.items():
        px, py, pz, xr, yr, zr = frame["parts"][name]
        rot = rot_zyx(xr, yr, zr)
        for tex_uv, g in ((texoff, 0.0), (overlay, infl)):
            for corners, uv in box_faces(origin, size, g, tex_uv):
                cw = [model_to_world(c, (px, py, pz), rot) for c in corners]
                draw_quad(color, zbuf, cam, cw, uv, tex)
    img = Image.fromarray(np.clip(color, 0, 255).astype(np.uint8))
    if crosshair:
        d = ImageDraw.Draw(img)
        cx, cy = cam.w // 2, cam.h // 2
        d.line((cx - 5, cy, cx + 5, cy), fill=(230, 230, 230))
        d.line((cx, cy - 5, cx, cy + 5), fill=(230, 230, 230))
    return img


def main():
    data = json.load(open(sys.argv[1], encoding="utf-8-sig"))
    tex = np.array(Image.open(SKIN).convert("RGBA")).astype(float)
    os.makedirs(OUT, exist_ok=True)
    side_cam = Camera((2.6, 2.6, -3.2), (-0.3, 1.1, 0.4), 55)

    cells, gif = [], []
    for fr in data["frames"]:
        # FIRST PERSON: exactly the player's screen - Minecraft default FOV 70, 16:9, where the player looks.
        fp_cam = Camera.from_look(fr["cam"], fr["look"][0], fr["look"][1], 70, FP_W, H)
        zoom_cam = Camera(fr["cam"], (-0.65, 1.3, 0.55), 26)
        fp = render(fr, fp_cam, tex, crosshair=True)
        a = render(fr, zoom_cam, tex)
        b = render(fr, side_cam, tex)
        cell = Image.new("RGB", (FP_W + W * 2 + 12, H + 22), (10, 10, 14))
        cell.paste(fp, (0, 22))
        cell.paste(a, (FP_W + 6, 22))
        cell.paste(b, (FP_W + W + 12, 22))
        d = ImageDraw.Draw(cell)
        d.text((4, 4), f'{fr["label"]}  lean {fr["lean"]:.1f}   FIRST PERSON (FOV 70)', fill=(230, 230, 230))
        d.text((FP_W + 10, 4), "ZOOM on cover", fill=(150, 150, 150))
        d.text((FP_W + W + 16, 4), "SIDE (helper)", fill=(150, 150, 150))
        cells.append(cell)
        gif.append(render(fr, Camera.from_look(fr["cam"], fr["look"][0], fr["look"][1], 70, 854, 480),
                          tex, crosshair=True))

    cols = 2
    rows = (len(cells) + cols - 1) // cols
    cw, ch = cells[0].size
    sheet = Image.new("RGB", (cols * cw + (cols - 1) * 4, rows * ch + (rows - 1) * 4), (0, 0, 0))
    for i, c in enumerate(cells):
        sheet.paste(c, ((i % cols) * (cw + 4), (i // cols) * (ch + 4)))
    name = data["scenario"]
    sheet_path = os.path.join(OUT, f"{name}_sheet.png")
    gif_path = os.path.join(OUT, f"{name}.gif")
    sheet.save(sheet_path)
    gif[0].save(gif_path, save_all=True, append_images=gif[1:], duration=120, loop=0)
    print(sheet_path)
    print(gif_path)


if __name__ == "__main__":
    main()
