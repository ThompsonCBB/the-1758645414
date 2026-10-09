"""Extracts what the carcass simulator needs from the local Minecraft jars (nothing is downloaded):
  * vanilla animal textures from client.jar  -> tools/animsim/vanilla/ (local only, gitignored)
  * javap dumps of the vanilla animal models, to check the box numbers in carcass_models.py
"""
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "forge_gradle")
CLIENT = os.path.join(CACHE, "minecraft_repo", "versions", "1.20.1", "client.jar")
MAPPED = os.path.join(CACHE, "minecraft_user_repo", "net", "minecraftforge", "forge", "1.20.1-47.4.10_mapped_official_1.20.1",
                      "forge-1.20.1-47.4.10_mapped_official_1.20.1.jar")
OUT = os.path.join(HERE, "vanilla")
TEXTURES = ["cow/cow", "pig/pig", "sheep/sheep", "sheep/sheep_fur", "chicken"]
MODELS = ["CowModel", "PigModel", "SheepModel", "SheepFurModel", "ChickenModel", "QuadrupedModel"]
# javap lives next to javac in the JDK (javac on PATH is often only a launcher stub).
JAVAP = next((os.path.join(r, "bin", "javap.exe") for r in
              (os.path.join(r"C:\Program Files\Java", d) for d in sorted(os.listdir(r"C:\Program Files\Java"), reverse=True))
              if os.path.exists(os.path.join(r, "bin", "javap.exe"))), "javap") if os.path.isdir(r"C:\Program Files\Java") else "javap"

os.makedirs(OUT, exist_ok=True)
with zipfile.ZipFile(CLIENT) as z:
    for t in TEXTURES:
        data = z.read(f"assets/minecraft/textures/entity/{t}.png")
        with open(os.path.join(OUT, t.replace("/", "_") + ".png"), "wb") as f:
            f.write(data)
    for b in ("grass_block_top", "oak_log", "oak_log_top", "oak_leaves", "dark_oak_log", "dark_oak_log_top"):
        data = z.read(f"assets/minecraft/textures/block/{b}.png")
        with open(os.path.join(OUT, b + ".png"), "wb") as f:
            f.write(data)

if "--javap" in sys.argv:
    cls_dir = os.path.join(OUT, "classes")
    with zipfile.ZipFile(MAPPED) as z:
        for m in MODELS:
            z.extract(f"net/minecraft/client/model/{m}.class", cls_dir)
    for m in MODELS:
        out = subprocess.run([JAVAP, "-c", "-p", "-classpath", cls_dir, f"net.minecraft.client.model.{m}"],
                             capture_output=True, text=True).stdout
        with open(os.path.join(OUT, m + ".javap.txt"), "w", encoding="utf-8") as f:
            f.write(out)
print("ok", OUT)
