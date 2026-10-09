"""Generate 3 concept skins (64x64, wide/Steve layout) + a comparison preview."""
import os
import random
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.expandvars(r"%USERPROFILE%\workspace\anta")
TEX = os.path.join(OUT, r"src\main\resources\assets\anta\textures\entity")
DOCS = os.path.join(OUT, r"docs\skins")
os.makedirs(TEX, exist_ok=True)
os.makedirs(DOCS, exist_ok=True)

# part name -> (x0, y0, w, h, d) in the 64x64 skin layout
PARTS = {
    "head": (0, 0, 8, 8, 8),
    "body": (16, 16, 8, 12, 4),
    "rarm": (40, 16, 4, 12, 4),
    "larm": (32, 48, 4, 12, 4),
    "rleg": (0, 16, 4, 12, 4),
    "lleg": (16, 48, 4, 12, 4),
}


def faces(part):
    x0, y0, w, h, d = PARTS[part]
    return {
        "top": (x0 + d, y0, w, d),
        "bottom": (x0 + d + w, y0, w, d),
        "right": (x0, y0 + d, d, h),
        "front": (x0 + d, y0 + d, w, h),
        "left": (x0 + d + w, y0 + d, d, h),
        "back": (x0 + 2 * d + w, y0 + d, w, h),
    }


def shade(c, amount, rnd):
    v = rnd.randint(-amount, amount)
    return tuple(max(0, min(255, ch + v)) for ch in c) + (255,)


def fill(img, rect, color, rnd, noise=6):
    x, y, w, h = rect
    for i in range(x, x + w):
        for j in range(y, y + h):
            img.putpixel((i, j), shade(color, noise, rnd))


def fill_part(img, part, color, rnd, noise=6):
    for r in faces(part).values():
        fill(img, r, color, rnd, noise)


def px(img, part, face, x, y, color):
    fx, fy, _, _ = faces(part)[face]
    img.putpixel((fx + x, fy + y), tuple(color) + (255,))


def limb_rows(img, part, top_color, rows_from, color, rnd, noise=6):
    """Recolor rows [rows_from..h) of the four side faces (e.g. sleeves -> hands, pants -> shoes)."""
    for name in ("right", "front", "left", "back"):
        x, y, w, h = faces(part)[name]
        fill(img, (x, y + rows_from, w, h - rows_from), color, rnd, noise)
    if top_color is not None:
        fill(img, faces(part)["bottom"], top_color, rnd, noise)


# ---------------------------------------------------------------- 1. Двойник
def skin_double():
    rnd = random.Random(1)
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    skin = (150, 138, 130)
    shirt, pants, shoes = (62, 84, 82), (44, 48, 66), (70, 70, 72)
    # bald, faceless head: just skin on every side
    fill_part(img, "head", skin, rnd)
    fill_part(img, "body", shirt, rnd)
    for arm in ("rarm", "larm"):
        fill_part(img, arm, shirt, rnd)
        limb_rows(img, arm, skin, 4, skin, rnd)
    for leg in ("rleg", "lleg"):
        fill_part(img, leg, pants, rnd)
        limb_rows(img, leg, shoes, 10, shoes, rnd)
    return img


# ---------------------------------------------------------------- 2. Тень
def skin_shadow():
    rnd = random.Random(2)
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    black = (12, 11, 14)
    for p in PARTS:
        fill_part(img, p, black, rnd, noise=4)
    # tiny white eyes with a faint grey halo
    for ex in (2, 5):
        px(img, "head", "front", ex, 4, (240, 240, 235))
        for (ox, oy) in ((0, -1), (0, 1), (-1, 0), (1, 0)):
            x, y = ex + ox, 4 + oy
            if 0 <= x < 8:
                px(img, "head", "front", x, y, (34, 33, 38))
    return img


# ---------------------------------------------------------------- 3. Безликий
def skin_faceless():
    rnd = random.Random(3)
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    pale, hair = (206, 202, 194), (14, 13, 15)
    coat, pants, feet = (30, 27, 26), (22, 21, 22), (180, 176, 168)
    fill_part(img, "head", pale, rnd, noise=4)
    fill(img, faces("head")["top"], hair, rnd, 3)
    fill(img, faces("head")["back"], hair, rnd, 3)
    for side in ("right", "left"):
        fill(img, faces("head")[side], hair, rnd, 3)   # long hair covers the sides
    fx, fy, _, _ = faces("head")["front"]
    fill(img, (fx, fy, 8, 1), hair, rnd, 3)
    for y in range(8):                                 # hair falls down both edges of the face
        px(img, "head", "front", 0, y, hair)
        px(img, "head", "front", 7, y, hair)
    px(img, "head", "front", 1, 1, hair)
    px(img, "head", "front", 6, 1, hair)
    # faceless: only a barely visible hollow where eyes should be
    for ex in (2, 5):
        px(img, "head", "front", ex, 4, (190, 186, 178))
    fill_part(img, "body", coat, rnd, 5)
    bx, by, _, _ = faces("body")["front"]
    for y in range(12):                                # coat seam
        img.putpixel((bx + 4, by + y), (18, 16, 16, 255))
    fill(img, faces("body")["top"], pale, rnd, 4)      # neck
    for arm in ("rarm", "larm"):
        fill_part(img, arm, coat, rnd, 5)
        limb_rows(img, arm, pale, 9, pale, rnd, 4)     # long pale fingers
    for leg in ("rleg", "lleg"):
        fill_part(img, leg, pants, rnd, 4)
        limb_rows(img, leg, feet, 11, feet, rnd, 4)    # bare feet
    return img


def front_doll(skin, view="front"):
    """Flat 16x32 front (or back) view of the figure."""
    doll = Image.new("RGBA", (16, 32), (0, 0, 0, 0))

    def crop(part, face):
        x, y, w, h = faces(part)[face]
        return skin.crop((x, y, x + w, y + h))

    if view == "front":
        doll.paste(crop("head", "front"), (4, 0))
        doll.paste(crop("body", "front"), (4, 8))
        doll.paste(crop("rarm", "front"), (0, 8))
        doll.paste(crop("larm", "front"), (12, 8))
        doll.paste(crop("rleg", "front"), (4, 20))
        doll.paste(crop("lleg", "front"), (8, 20))
    else:
        doll.paste(crop("head", "back"), (4, 0))
        doll.paste(crop("body", "back"), (4, 8))
        doll.paste(crop("larm", "back"), (0, 8))
        doll.paste(crop("rarm", "back"), (12, 8))
        doll.paste(crop("lleg", "back"), (4, 20))
        doll.paste(crop("rleg", "back"), (8, 20))
    return doll


SCENE_W, SCENE_H = 280, 360


def peek_scene(skin, bg):
    """Figure stands behind a stone wall and leans 35 degrees to the right, out past its edge."""
    s = 8
    feet = (95, 345)
    wall_w = 130
    scene = Image.new("RGBA", (SCENE_W, SCENE_H), bg)
    doll = front_doll(skin).resize((16 * s, 32 * s), Image.NEAREST)
    # Put the feet at the centre of a big canvas, rotate around that centre (negative = clockwise).
    big = Image.new("RGBA", (700, 700), (0, 0, 0, 0))
    big.alpha_composite(doll, (350 - 8 * s, 350 - 32 * s))
    big = big.rotate(-35, resample=Image.NEAREST, center=(350, 350))
    scene.alpha_composite(big, (feet[0] - 350, feet[1] - 350))
    wall = Image.new("RGBA", (wall_w, SCENE_H), (92, 92, 96, 255))
    rnd = random.Random(9)
    d = ImageDraw.Draw(wall)
    for gy in range(0, SCENE_H, 20):
        for gx in range(0, wall_w, 20):
            c = 80 + rnd.randint(-10, 14)
            d.rectangle((gx, gy, gx + 19, gy + 19), fill=(c, c, c + 3, 255))
    scene.alpha_composite(wall, (0, 0))
    return scene


def main():
    skins = [
        ("watcher_double", "1. Двойник", skin_double()),
        ("watcher_shadow", "2. Тень", skin_shadow()),
        ("watcher_faceless", "3. Безликий", skin_faceless()),
    ]
    for key, _, img in skins:
        img.save(os.path.join(TEX, key + ".png"))

    s = 10
    col_w, pad = 16 * s + 8 * s + 20 + SCENE_W + 40, 30
    W, H = 3 * col_w + pad, 80 + SCENE_H + 20
    bg = (24, 24, 28, 255)
    sheet = Image.new("RGBA", (W, H), bg)
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("arial.ttf", 28)
    except OSError:
        font = ImageFont.load_default()
    for i, (_, title, img) in enumerate(skins):
        x = pad + i * col_w
        d.text((x, 20), title, fill=(230, 230, 230), font=font)
        sheet.alpha_composite(front_doll(img, "front").resize((16 * s, 32 * s), Image.NEAREST), (x, 80))
        sheet.alpha_composite(front_doll(img, "back").resize((8 * s, 16 * s), Image.NEAREST), (x + 16 * s + 10, 80))
        sheet.alpha_composite(peek_scene(img, (8, 8, 10, 255)), (x + 16 * s + 10 + 8 * s + 10, 80))
    sheet.convert("RGB").save(os.path.join(DOCS, "preview.png"))
    print("ok")


if __name__ == "__main__":
    main()
