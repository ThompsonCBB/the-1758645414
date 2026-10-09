"""Generates the mod's sounds with ElevenLabs and converts them for Minecraft.

  * sound effects  -> POST /v1/sound-generation
  * whispers       -> POST /v1/text-to-speech (eleven_v3 with a [whispers] tag)

Raw MP3s go to audio/raw/ (source material, kept in the repo), Minecraft-ready OGG (mono,
Vorbis - positional sounds must be mono) to audio/ogg/. A sound that already exists is NOT
regenerated (credits are limited: Starter plan); delete its MP3 to regenerate it.

The API key is read from the ELEVENLABS_API_KEY environment variable (user env on Windows).
Never write the key into a file.

Usage (from the project root):  python tools/gen_sounds.py [name ...]
"""
import os
import subprocess
import sys

import requests

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RAW = os.path.join(ROOT, "audio", "raw")
OGG = os.path.join(ROOT, "audio", "ogg")
API = "https://api.elevenlabs.io/v1"

WHISPER_VOICE = "N2lVS1w4EtoT3dr4eOWO"  # Callum - husky

# name: (kind, prompt or text, duration seconds for SFX, ffmpeg audio filter or None)
SOUNDS = {
    "steps_behind": ("sfx", "Footsteps: exactly two slow, heavy footsteps crunching on gravel and dry grass, "
                            "clearly audible, close by, then silence. Realistic foley, no music", 3.0, None),
    "rustle": ("sfx", "Subtle rustle of leaves and cloth as someone quickly moves behind a tree, quiet, "
                      "close, realistic, no music", 2.0, None),
    "breath": ("sfx", "Slow, calm, heavy breathing of a person standing right next to the listener in silence, "
                      "eerie, intimate, quiet, no music", 5.0, None),
    "duck": ("sfx", "A single very fast cloth whoosh, someone jerking back behind a corner, short and sharp, "
                    "no music", 0.8, None),
    "stinger": ("sfx", "Short low horror stinger: a deep muffled thud with a dissonant metallic hit that fades fast, "
                       "cinematic, no voice", 2.0, None),
    "drone": ("sfx", "Very low, quiet, ominous ambient drone, distant rumble, unsettling, seamless, no melody",
              8.0, None),
    # No voices: the user rejected the whispers (2026-10-08). Do not add speech back.
    # 2026-10-09: screams were tried and rejected. The mod now uses steps_behind and rustle
    # (copied as forest_steps / forest_rustle). Do not add screams or voices.
}


def api_key():
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key and sys.platform == "win32":
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
            key = winreg.QueryValueEx(k, "ELEVENLABS_API_KEY")[0]
    if not key:
        sys.exit("ELEVENLABS_API_KEY is not set")
    return key


def generate(name, kind, text, duration, headers):
    if kind == "sfx":
        r = requests.post(f"{API}/sound-generation", headers=headers, timeout=120,
                          json={"text": text, "duration_seconds": duration, "prompt_influence": 0.5})
    else:
        r = requests.post(f"{API}/text-to-speech/{WHISPER_VOICE}", headers=headers, timeout=120,
                          json={"text": text, "model_id": "eleven_v3",
                                "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}})
    if r.status_code != 200:
        raise RuntimeError(f"{name}: HTTP {r.status_code} {r.text[:300]}")
    return r.content


PEAK_DB = -3.0  # every sound is peak-normalised to this; quietness is set in-game (sounds.json / play volume)


def peak_db(path):
    out = subprocess.run(["ffmpeg", "-hide_banner", "-i", path, "-af", "volumedetect", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    for line in out.splitlines():
        if "max_volume:" in line:
            return float(line.split("max_volume:")[1].split("dB")[0])
    return 0.0


def to_ogg(src, dst, afilter):
    filters = [afilter] if afilter else []
    # Peak-normalise after the effect filter, so loud and quiet generations end up comparable.
    probe = dst + ".tmp.wav"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-ac", "1", "-ar", "44100"]
                   + (["-af", ",".join(filters)] if filters else []) + [probe], check=True)
    gain = PEAK_DB - peak_db(probe)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", probe, "-af", f"volume={gain:.2f}dB",
                    "-c:a", "libvorbis", "-q:a", "5", dst], check=True)
    os.remove(probe)


def main():
    os.makedirs(RAW, exist_ok=True)
    os.makedirs(OGG, exist_ok=True)
    headers = {"xi-api-key": api_key(), "Content-Type": "application/json"}
    names = sys.argv[1:] or list(SOUNDS)
    for name in names:
        kind, text, duration, afilter = SOUNDS[name]
        mp3 = os.path.join(RAW, name + ".mp3")
        if os.path.exists(mp3):
            print(f"{name}: exists, skipped")
        else:
            with open(mp3, "wb") as f:
                f.write(generate(name, kind, text, duration, headers))
            print(f"{name}: generated")
        to_ogg(mp3, os.path.join(OGG, name + ".ogg"), afilter)
    sub = requests.get(f"{API}/user/subscription", headers=headers, timeout=30).json()
    print(f"credits used {sub.get('character_count')} of {sub.get('character_limit')}")


if __name__ == "__main__":
    main()
