"""Vanilla 1.20.1 animal models, exactly as in the game jar (checked with javap, see extract_vanilla.py).

Each part: pivot (px), rotation (radians, applied Z*Y*X like ModelPart), boxes.
Each box: (texOffs (u, v), origin (px), size (px), inflate (px)).
Model space: pixels, Y down, Y=24 the feet, the face toward -Z, +X is the animal's left.
Textures are 64x32.
"""
import math

import numpy as np

HALF_PI = math.pi / 2


def _quad_legs(pivots, tex, size, inflate=0.0):
    names = ["right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg"]
    return {n: {"pivot": p, "rot": (0, 0, 0), "boxes": [(tex, (-2, 0, -2), size, inflate)]} for n, p in zip(names, pivots)}


COW = {
    "head": {"pivot": (0, 4, -8), "rot": (0, 0, 0), "boxes": [((0, 0), (-4, -4, -6), (8, 8, 6), 0),
                                                              ((22, 0), (-5, -5, -4), (1, 3, 1), 0),
                                                              ((22, 0), (4, -5, -4), (1, 3, 1), 0)]},
    "body": {"pivot": (0, 5, 2), "rot": (HALF_PI, 0, 0), "boxes": [((18, 4), (-6, -10, -7), (12, 18, 10), 0),
                                                                   ((52, 0), (-2, 2, -8), (4, 6, 1), 0)]},
    **_quad_legs([(-4, 12, 7), (4, 12, 7), (-4, 12, -6), (4, 12, -6)], (0, 16), (4, 12, 4)),
}

PIG = {
    "head": {"pivot": (0, 12, -6), "rot": (0, 0, 0), "boxes": [((0, 0), (-4, -4, -8), (8, 8, 8), 0),
                                                               ((16, 16), (-2, 0, -9), (4, 3, 1), 0)]},
    "body": {"pivot": (0, 11, 2), "rot": (HALF_PI, 0, 0), "boxes": [((28, 8), (-5, -10, -7), (10, 16, 8), 0)]},
    **_quad_legs([(-3, 18, 7), (3, 18, 7), (-3, 18, -5), (3, 18, -5)], (0, 16), (4, 6, 4)),
}

SHEEP = {
    "head": {"pivot": (0, 6, -8), "rot": (0, 0, 0), "boxes": [((0, 0), (-3, -4, -6), (6, 6, 8), 0)]},
    "body": {"pivot": (0, 5, 2), "rot": (HALF_PI, 0, 0), "boxes": [((28, 8), (-4, -10, -7), (8, 16, 6), 0)]},
    **_quad_legs([(-3, 12, 7), (3, 12, 7), (-3, 12, -5), (3, 12, -5)], (0, 16), (4, 12, 4)),
}

SHEEP_FUR = {
    "head": {"pivot": (0, 6, -8), "rot": (0, 0, 0), "boxes": [((0, 0), (-3, -4, -4), (6, 6, 6), 0.6)]},
    "body": {"pivot": (0, 5, 2), "rot": (HALF_PI, 0, 0), "boxes": [((28, 8), (-4, -10, -7), (8, 16, 6), 1.75)]},
    **_quad_legs([(-3, 12, 7), (3, 12, 7), (-3, 12, -5), (3, 12, -5)], (0, 16), (4, 6, 4), 0.5),
}

CHICKEN = {
    "head": {"pivot": (0, 15, -4), "rot": (0, 0, 0), "boxes": [((0, 0), (-2, -6, -2), (4, 6, 3), 0)]},
    "beak": {"pivot": (0, 15, -4), "rot": (0, 0, 0), "boxes": [((14, 0), (-2, -4, -4), (4, 2, 2), 0)]},
    "red_thing": {"pivot": (0, 15, -4), "rot": (0, 0, 0), "boxes": [((14, 4), (-1, -2, -3), (2, 2, 2), 0)]},
    "body": {"pivot": (0, 16, 0), "rot": (HALF_PI, 0, 0), "boxes": [((0, 9), (-3, -4, -3), (6, 8, 6), 0)]},
    "right_leg": {"pivot": (-2, 19, 1), "rot": (0, 0, 0), "boxes": [((26, 0), (-1, 0, -3), (3, 5, 3), 0)]},
    "left_leg": {"pivot": (1, 19, 1), "rot": (0, 0, 0), "boxes": [((26, 0), (-1, 0, -3), (3, 5, 3), 0)]},
    "right_wing": {"pivot": (-4, 13, 0), "rot": (0, 0, 0), "boxes": [((24, 13), (0, 0, -3), (1, 4, 6), 0)]},
    "left_wing": {"pivot": (4, 13, 0), "rot": (0, 0, 0), "boxes": [((24, 13), (-1, 0, -3), (1, 4, 6), 0)]},
}

# Same order as CarcassLayout / CarcassEntity.Kind: SHEEP, COW, CHICKEN, PIG.
KINDS = ["sheep", "cow", "chicken", "pig"]
STYLES = ["decapitated", "gutted", "scattered", "dragged"]
SKIN_MODEL = {"sheep": SHEEP, "cow": COW, "chicken": CHICKEN, "pig": PIG}
FUR_MODEL = {"sheep": SHEEP_FUR}
SKIN_TEX = {"sheep": "sheep_sheep.png", "cow": "cow_cow.png", "chicken": "chicken.png", "pig": "pig_pig.png"}
FUR_TEX = {"sheep": "sheep_sheep_fur.png"}
TEX_SIZE = (64, 32)


def rot_zyx(xr, yr, zr):
    cx, sx = math.cos(xr), math.sin(xr)
    cy, sy = math.cos(yr), math.sin(yr)
    cz, sz = math.cos(zr), math.sin(zr)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


def box_faces(origin, size, inflate, tex):
    """Six faces of a model box as in vanilla ModelPart.Cube: (name, 4 corners px TL TR BR BL, uv rect u v w h)."""
    u, v = tex
    w, h, d = size
    x0, y0, z0 = origin[0] - inflate, origin[1] - inflate, origin[2] - inflate
    x1, y1, z1 = origin[0] + w + inflate, origin[1] + h + inflate, origin[2] + d + inflate
    return [
        ("front", [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (u + d, v + d, w, h)),
        ("back", [(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)], (u + 2 * d + w, v + d, w, h)),
        ("right", [(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)], (u, v + d, d, h)),
        ("left", [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], (u + d + w, v + d, d, h)),
        ("top", [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], (u + d, v, w, d)),
        ("bottom", [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], (u + d + w, v, w, d)),  # vanilla UP polygon: v flipped
    ]


def part_point(part, p_px):
    """A point of a part's box (px, part space) -> model space (px)."""
    return np.array(part["pivot"], float) + rot_zyx(*part["rot"]) @ np.array(p_px, float)
