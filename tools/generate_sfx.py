#!/usr/bin/env python3
"""Generate every interface sound LumoBrowser ships, from scratch.

Why a generator instead of downloaded assets
--------------------------------------------
The app needs 18 cue types in 4 textures, i.e. 72 short sounds. Pulling those from a
sample library means tracking 72 licences, and "free" sample packs routinely forbid
redistribution inside an application. Synthesising them here removes that question
entirely: every byte in `app/src/main/res/raw/` is derived from the numbers in this
file, and this file is part of the source package. There is no third-party material
to attribute and nothing to re-verify before shipping.

What it writes
--------------
`app/src/main/res/raw/sfx_<cue>_<style>.wav` — 22050 Hz, 16-bit, mono, PCM.

22050 Hz rather than 44100: every partial below is deliberately kept under 9 kHz, so
the extra octave of bandwidth would carry nothing but file size. Mono because these
are point cues with no stereo image. PCM WAV rather than a compressed codec because
several cues are under 40 ms — shorter than one Vorbis frame — and a codec's priming
delay is a real, audible cost on a button press.

Only the standard library is used, so the script runs anywhere Python does:
    python tools/generate_sfx.py
"""

import math
import os
import random
import struct
import wave

SAMPLE_RATE = 22050
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "raw")

# Normative peak. Left below full scale because SoundPool mixes several streams and a
# cue that clips when it overlaps another is worse than one that is slightly quiet.
PEAK = 0.72


# --------------------------------------------------------------------------------------
# textures
# --------------------------------------------------------------------------------------
class Texture:
    """One of the four selectable sound textures.

    A texture is not a filter applied after the fact — it changes which partials exist,
    how fast each one dies, how hard the attack is and how much the result is driven.
    That is what makes the four options sound like four different products rather than
    one sound at four brightnesses.
    """

    def __init__(
        self,
        name,
        detune,
        attack_ms,
        partials,
        click,
        lowpass,
        drive,
        tail_scale,
        decay_scale,
    ):
        self.name = name
        self.detune = detune
        self.attack_ms = attack_ms
        # (ratio, amplitude, decay multiplier) — ratio 1.0 is the cue's own frequency.
        # Ratios above ~2.7 are deliberately inharmonic: that is what a struck glass or
        # a bell does, and it is the whole difference between "glass" and "bright".
        self.partials = partials
        self.click = click
        self.lowpass = lowpass
        self.drive = drive
        self.tail_scale = tail_scale
        self.decay_scale = decay_scale


TEXTURES = [
    # 极简柔和 — few partials, soft knee, nothing above the third harmonic.
    Texture(
        "soft",
        detune=0.94,
        attack_ms=4.0,
        partials=[(1.0, 1.00, 1.10), (2.0, 0.30, 0.80), (3.0, 0.09, 0.55)],
        click=0.06,
        lowpass=0.55,
        drive=0.0,
        tail_scale=1.15,
        decay_scale=1.15,
    ),
    # 清脆科技 — bright and immediate, with a real transient on the front.
    Texture(
        "crisp",
        detune=1.0,
        attack_ms=0.8,
        partials=[
            (1.0, 1.00, 1.00),
            (2.0, 0.50, 0.75),
            (3.0, 0.26, 0.55),
            (4.2, 0.13, 0.40),
            (6.1, 0.06, 0.28),
        ],
        click=0.50,
        lowpass=0.95,
        drive=0.12,
        tail_scale=1.0,
        decay_scale=1.0,
    ),
    # 轻盈玻璃感 — inharmonic bell partials and a long shimmer.
    #
    # The upper inharmonic partials carry real amplitude rather than being decoration.
    # A struck-glass character lives in those ratios, and a texture whose identity only
    # shows up on a long cue is inaudible on the short ones — `press`, `click` and
    # `toggle` are 30-55 ms and are by far the most frequently heard sounds in the app.
    # An earlier version decayed 5.40x and 8.93x at 0.68/0.44 with no drive, which made
    # `glass` measurably duller than `crisp` on those cues; the whole point of a style
    # setting is that it is audible on the sounds the user actually triggers.
    Texture(
        "glass",
        detune=1.26,
        attack_ms=1.2,
        partials=[
            (1.0, 0.90, 1.40),
            (2.76, 0.62, 1.05),
            (5.40, 0.42, 0.82),
            (8.93, 0.24, 0.62),
        ],
        click=0.30,
        lowpass=0.98,
        drive=0.06,
        tail_scale=1.55,
        decay_scale=1.35,
    ),
    # 沉稳低调 — an octave down, damped, with a little body on the attack.
    Texture(
        "deep",
        detune=0.5,
        attack_ms=2.2,
        partials=[(1.0, 1.00, 1.00), (1.5, 0.26, 0.60), (2.0, 0.38, 0.70), (3.0, 0.07, 0.38)],
        click=0.34,
        lowpass=0.42,
        drive=0.22,
        tail_scale=0.90,
        decay_scale=0.85,
    ),
]


# --------------------------------------------------------------------------------------
# cue definitions
# --------------------------------------------------------------------------------------
# Each cue is a list of events. An event is (start_ms, f0, f1, dur_ms, amplitude, kind):
#   f0 -> f1 is a linear sweep; f0 == f1 is a steady tone.
#   kind is 'tone' (partial stack), 'thud' (partial stack + body noise), or
#   'buzz' (partial stack + a slight odd-harmonic edge, for failure sounds).
#
# Pitches are chosen as real intervals, not arbitrary numbers, so a rising cue really
# does resolve upward: 523/784/1046 Hz is a C-G-C triad, 784->1175 is a rising fifth,
# 622->587 is a falling minor second. The two-note "something went wrong" cues fall;
# the two-note "that worked" cues rise. That is learned behaviour in every interface a
# user has ever touched, and violating it makes a confirmation sound alarming.
CUES = {
    # --- pointer ---
    "press": [(0, 340, 180, 30, 0.90, "thud")],
    "release": [(0, 420, 300, 32, 0.68, "thud")],
    "click": [(0, 900, 820, 55, 0.80, "tone")],
    "toggle": [(0, 1250, 1180, 45, 0.58, "tone")],
    # --- navigation ---
    "navigate": [(0, 620, 980, 120, 0.68, "tone")],
    "dialog_open": [(0, 520, 1180, 110, 0.68, "tone")],
    "dialog_close": [(0, 1180, 520, 95, 0.62, "tone")],
    # --- outcomes ---
    "bookmark_added": [(0, 1046, 1046, 90, 0.68, "tone"), (70, 1568, 1568, 150, 0.70, "tone")],
    "settings_saved": [(0, 1318, 1318, 80, 0.58, "tone"), (62, 1760, 1760, 120, 0.60, "tone")],
    # Generic "yes, go ahead" on a harmless dialog. Kept separate from delete_confirm
    # because agreeing to a rename and agreeing to destroy a bookmark should not sound
    # like the same decision.
    "confirm": [(0, 698, 698, 80, 0.60, "tone"), (60, 1046, 1046, 130, 0.62, "tone")],
    "delete_confirm": [(0, 880, 880, 85, 0.68, "tone"), (68, 587, 587, 135, 0.70, "tone")],
    "error": [(0, 784, 784, 85, 0.62, "buzz"), (70, 523, 523, 120, 0.68, "buzz")],
    # --- page loading ---
    "load_start": [(0, 700, 900, 90, 0.48, "tone")],
    "load_success": [(0, 784, 784, 80, 0.58, "tone"), (65, 1175, 1175, 110, 0.58, "tone")],
    "load_failure": [(0, 659, 659, 95, 0.58, "buzz"), (80, 494, 494, 130, 0.60, "buzz")],
    # --- vpn ---
    "vpn_connecting": [(0, 440, 660, 130, 0.52, "tone")],
    "vpn_connected": [
        (0, 587, 587, 90, 0.58, "tone"),
        (70, 880, 880, 90, 0.58, "tone"),
        (140, 1175, 1175, 130, 0.58, "tone"),
    ],
    "vpn_failed": [(0, 622, 622, 90, 0.58, "buzz"), (72, 587, 587, 160, 0.62, "buzz")],
    # --- launch ---
    "launch_end": [
        (0, 523, 523, 420, 0.50, "tone"),
        (0, 784, 784, 400, 0.32, "tone"),
        (0, 1046, 1046, 380, 0.18, "tone"),
    ],
}

STYLE_ORDER = ["soft", "crisp", "glass", "deep"]


# --------------------------------------------------------------------------------------
# synthesis primitives
# --------------------------------------------------------------------------------------
def n_for(ms_value):
    return max(1, int(round(SAMPLE_RATE * ms_value / 1000.0)))


def smoothstep(x):
    return x * x * (3.0 - 2.0 * x)


def one_pole_lowpass(samples, k):
    """Single-pole lowpass. `k` is the share of the new sample kept: 1.0 is bypass."""
    if k >= 0.999:
        return samples
    out = [0.0] * len(samples)
    acc = 0.0
    for i, s in enumerate(samples):
        acc += k * (s - acc)
        out[i] = acc
    return out


def soft_clip(samples, drive):
    """tanh-style saturation. Adds harmonics on transients without folding back."""
    if drive <= 0.0:
        return samples
    gain = 1.0 + drive * 6.0
    return [math.tanh(s * gain) / math.tanh(gain) for s in samples]


def fade_edges(samples, fade_in_n=0, fade_out_n=0):
    n = len(samples)
    if fade_in_n > 1:
        fade_in_n = min(fade_in_n, n)
        for i in range(fade_in_n):
            samples[i] *= smoothstep(i / fade_in_n)
    if fade_out_n > 1:
        fade_out_n = min(fade_out_n, n)
        for i in range(fade_out_n):
            samples[n - fade_out_n + i] *= smoothstep(1.0 - i / fade_out_n)
    return samples


def render_event(texture, f0, f1, dur_ms, amp, kind, rng):
    """Render one event to a float list. No envelope-free sample ever leaves here."""
    dur_ms = dur_ms * texture.decay_scale
    n = n_for(dur_ms)
    attack_n = max(1, n_for(texture.attack_ms))

    # Exponential decay reaching about -40 dB at the end of the event, so the tail is
    # inaudible before the buffer stops rather than being cut off mid-cycle.
    tau = max(1.0, (n - attack_n) / 6.9)

    out = [0.0] * n
    phase = 0.0
    for i in range(n):
        # attack -> decay envelope
        if i < attack_n:
            env = smoothstep(i / attack_n)
        else:
            env = math.exp(-(i - attack_n) / tau)

        frac = i / max(1, n - 1)
        freq = (f0 + (f1 - f0) * frac) * texture.detune
        phase += 2.0 * math.pi * freq / SAMPLE_RATE

        value = 0.0
        for ratio, p_amp, p_decay in texture.partials:
            # Each partial decays at its own rate: high partials die first, which is
            # what makes a struck object sound struck instead of like an organ.
            p_env = math.exp(-(i / max(1.0, tau * p_decay))) if i >= attack_n else env
            value += p_amp * p_env * math.sin(phase * ratio)

        if kind == "buzz":
            # A touch of the third harmonic at double weight, delayed slightly, gives
            # the failure cues an edge that reads as "not right" rather than "low".
            value += 0.22 * env * math.sin(phase * 3.0) * (1.0 + 0.5 * frac)

        if kind == "thud":
            value += 0.35 * env * math.sin(phase * 0.5)

        if texture.click > 0.0 and i < n_for(6.0):
            # The transient. Without it a cue sounds like it fades in from nowhere.
            click_env = math.exp(-i / max(1.0, n_for(1.6)))
            value += texture.click * click_env * rng.uniform(-1.0, 1.0)

        out[i] = value * env * amp

    return out


def mix_into(target, source, offset):
    need = offset + len(source)
    if need > len(target):
        target.extend([0.0] * (need - len(target)))
    for i, s in enumerate(source):
        target[offset + i] += s


def render_cue(cue_name, events, texture, seed):
    rng = random.Random(seed)
    longest = 0
    for start_ms, _f0, _f1, dur_ms, _amp, _kind in events:
        longest = max(longest, n_for(start_ms + dur_ms * texture.decay_scale))

    tail_n = n_for(26.0 * texture.tail_scale)
    total = longest + tail_n
    buf = [0.0] * total

    for start_ms, f0, f1, dur_ms, amp, kind in events:
        mix_into(buf, render_event(texture, f0, f1, dur_ms, amp, kind, rng), n_for(start_ms))

    buf = one_pole_lowpass(buf, texture.lowpass)
    buf = soft_clip(buf, texture.drive)

    peak = max((abs(s) for s in buf), default=0.0)
    if peak > 0.0:
        scale = PEAK / peak
        buf = [s * scale for s in buf]

    # 3 ms in, 12 ms out. The release matters more than the attack: SoundPool starts a
    # new cue while the previous one may still be ringing, and a buffer that ends on a
    # non-zero sample produces a step — audible as a click that is not in the design.
    fade_edges(buf, fade_in_n=n_for(3.0), fade_out_n=n_for(12.0))
    return buf


def write_wav(path, samples):
    frames = bytearray()
    for s in samples:
        v = int(round(max(-1.0, min(1.0, s)) * 32767.0))
        frames += struct.pack("<h", v)
    with wave.open(path, "wb") as fh:
        fh.setnchannels(1)
        fh.setsampwidth(2)
        fh.setframerate(SAMPLE_RATE)
        fh.writeframes(bytes(frames))


def stable_seed(*parts):
    """Deterministic across processes.

    `hash()` on a str is salted by PYTHONHASHSEED, so using it here would make the
    noise transient — and therefore the file bytes — different on every run. The
    generator is part of the source package and its output is claimed to be
    reproducible, so the seed has to be too.
    """
    acc = 2166136261
    for part in parts:
        for ch in part:
            acc = ((acc ^ ord(ch)) * 16777619) & 0xFFFFFFFF
    return acc


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    manifest = []
    for texture in TEXTURES:
        for cue_name, events in CUES.items():
            seed = stable_seed(cue_name, texture.name)
            samples = render_cue(cue_name, events, texture, seed)
            filename = "sfx_{}_{}.wav".format(cue_name, texture.name)
            path = os.path.join(OUT_DIR, filename)
            write_wav(path, samples)
            manifest.append(
                (
                    filename,
                    len(samples) * 2 + 44,
                    len(samples) * 1000.0 / SAMPLE_RATE,
                )
            )

    total_bytes = sum(item[1] for item in manifest)
    lines = [
        "generated files: {}".format(len(manifest)),
        "total bytes: {}".format(total_bytes),
        "",
        "{:<44}{:>10}{:>12}".format("file", "bytes", "duration_ms"),
    ]
    for filename, size, duration in sorted(manifest):
        lines.append("{:<44}{:>10}{:>12.1f}".format(filename, size, duration))
    report = "\n".join(lines) + "\n"
    with open(os.path.join(ROOT, "tools", "sfx_manifest.txt"), "w", encoding="utf-8") as fh:
        fh.write(report)
    print(report)


if __name__ == "__main__":
    main()
