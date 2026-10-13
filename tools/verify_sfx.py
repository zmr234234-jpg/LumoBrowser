#!/usr/bin/env python3
"""Verify the generated cue library, and write the numbers into a report.

This is the check that the generator actually produced 72 distinct, audible,
non-clipping sounds rather than 72 files that merely exist. It measures the
things that would be wrong if the synthesis were broken:

  * a file that is silent, or clips, or has a DC offset that would thump;
  * a cue whose first sample is not near zero (a step at the start = an
    audible click that is not part of the design);
  * two textures of the same cue that measure identically, which would mean
    the texture selection does nothing;
  * two different cues that measure identically, which would mean the cue
    table is not actually driving the synthesis.

Run:  python tools/verify_sfx.py
"""

import math
import os
import struct
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(ROOT, "app", "src", "main", "res", "raw")
REPORT = os.path.join(ROOT, "tools", "sfx_verification.txt")

STYLES = ["soft", "crisp", "glass", "deep"]


def read_wav(path):
    with wave.open(path, "rb") as fh:
        assert fh.getnchannels() == 1, path
        assert fh.getsampwidth() == 2, path
        rate = fh.getframerate()
        raw = fh.readframes(fh.getnframes())
    count = len(raw) // 2
    samples = struct.unpack("<{}h".format(count), raw)
    return rate, samples


def measure(samples):
    n = len(samples)
    peak = max(abs(s) for s in samples) / 32768.0
    rms = math.sqrt(sum((s / 32768.0) ** 2 for s in samples) / n)
    dc = sum(samples) / n / 32768.0
    first = abs(samples[0]) / 32768.0
    last = abs(samples[-1]) / 32768.0

    # Zero-crossing rate stands in for brightness without needing an FFT: a
    # signal with more high-frequency content crosses zero more often. It is
    # reported for information, but it is NOT the assertion metric — see hf_ratio.
    crossings = 0
    for i in range(1, n):
        if (samples[i - 1] < 0) != (samples[i] < 0):
            crossings += 1
    zcr = crossings / (n / 22050.0)

    # First-difference RMS relative to signal RMS. `x[i] - x[i-1]` is a +6 dB/octave
    # high-pass, so this ratio rises with harmonic content rather than with the
    # fundamental. That distinction matters: zero-crossing rate tracks the FUNDAMENTAL,
    # so on a pure swept cue every texture scores the same and the metric reports a tie
    # that says nothing about the texture at all. This ratio is what actually separates
    # a two-partial "soft" from a five-partial "crisp".
    diff_sq = 0.0
    for i in range(1, n):
        d = (samples[i] - samples[i - 1]) / 32768.0
        diff_sq += d * d
    diff_rms = math.sqrt(diff_sq / max(1, n - 1))
    hf_ratio = diff_rms / rms if rms > 0 else 0.0

    return {
        "n": n,
        "peak": peak,
        "rms": rms,
        "dc": dc,
        "first": first,
        "last": last,
        "zcr": zcr,
        "hf": hf_ratio,
    }


def main():
    files = sorted(f for f in os.listdir(RAW_DIR) if f.startswith("sfx_") and f.endswith(".wav"))
    rows = []
    problems = []

    for name in files:
        rate, samples = read_wav(os.path.join(RAW_DIR, name))
        if rate != 22050:
            problems.append("{}: sample rate {}".format(name, rate))
        m = measure(samples)
        m["name"] = name
        rows.append(m)

        if m["rms"] < 0.001:
            problems.append("{}: effectively silent (rms {:.5f})".format(name, m["rms"]))
        if m["peak"] > 0.999:
            problems.append("{}: clips (peak {:.4f})".format(name, m["peak"]))
        if abs(m["dc"]) > 0.02:
            problems.append("{}: DC offset {:.4f}".format(name, m["dc"]))
        if m["first"] > 0.05:
            problems.append("{}: starts at {:.3f}, a step".format(name, m["first"]))
        if m["last"] > 0.05:
            problems.append("{}: ends at {:.3f}, a step".format(name, m["last"]))

    # Texture ordering: every cue, measured across its four textures, must put
    # deep < soft < crisp < glass in harmonic content. If a texture were a no-op
    # this ordering would collapse, which is the failure this assertion exists for.
    cues = sorted({r["name"].rsplit("_", 1)[0].replace("sfx_", "") for r in rows})
    order_failures = []
    for cue in cues:
        per_style = {}
        for style in STYLES:
            key = "sfx_{}_{}.wav".format(cue, style)
            for r in rows:
                if r["name"] == key:
                    per_style[style] = r["hf"]
        if len(per_style) != 4:
            order_failures.append("{}: missing textures ({})".format(cue, sorted(per_style)))
            continue
        if not (
            per_style["deep"] < per_style["soft"] < per_style["crisp"] < per_style["glass"]
        ):
            order_failures.append(
                "{}: deep={:.3f} soft={:.3f} crisp={:.3f} glass={:.3f}".format(
                    cue,
                    per_style["deep"],
                    per_style["soft"],
                    per_style["crisp"],
                    per_style["glass"],
                )
            )

    for f in order_failures:
        problems.append("brightness order: " + f)

    # Distinctness: no two files in the library may be byte-identical.
    digests = {}
    for name in files:
        with open(os.path.join(RAW_DIR, name), "rb") as fh:
            digests.setdefault(fh.read(), []).append(name)
    for blob, names in digests.items():
        if len(names) > 1:
            problems.append("identical files: " + ", ".join(names))

    total_bytes = sum(os.path.getsize(os.path.join(RAW_DIR, f)) for f in files)
    total_ms = sum(r["n"] for r in rows) * 1000.0 / 22050.0

    lines = []
    lines.append("cue library verification")
    lines.append("=" * 78)
    lines.append("files            : {}".format(len(files)))
    lines.append("cues             : {}".format(len(cues)))
    lines.append("textures         : {}".format(len(STYLES)))
    lines.append("total bytes      : {}".format(total_bytes))
    lines.append("total audio      : {:.1f} ms".format(total_ms))
    lines.append("peak (all files) : {:.4f} .. {:.4f}".format(
        min(r["peak"] for r in rows), max(r["peak"] for r in rows)))
    lines.append("")
    lines.append("{:<38}{:>7}{:>8}{:>8}{:>9}{:>8}{:>9}".format(
        "file", "ms", "peak", "rms", "dc", "hf_ratio", "zcr"))
    lines.append("-" * 78)
    for r in rows:
        lines.append("{:<38}{:>7.1f}{:>8.4f}{:>8.4f}{:>9.5f}{:>8.3f}{:>9.0f}".format(
            r["name"],
            r["n"] * 1000.0 / 22050.0,
            r["peak"],
            r["rms"],
            r["dc"],
            r["hf"],
            r["zcr"],
        ))

    lines.append("")
    lines.append("texture separation (mean over all cues)")
    lines.append("-" * 78)
    lines.append("  hf_ratio = first-difference RMS / RMS; rises with harmonic content.")
    lines.append("  zcr      = zero crossings per second; tracks the FUNDAMENTAL, so it is")
    lines.append("             listed for information only and is not asserted on.")
    for style in STYLES:
        subset = [r for r in rows if r["name"].endswith("_{}.wav".format(style))]
        lines.append("  {:<8} mean hf_ratio = {:.3f}   mean zcr = {:.0f}".format(
            style,
            sum(r["hf"] for r in subset) / len(subset),
            sum(r["zcr"] for r in subset) / len(subset),
        ))

    lines.append("")
    lines.append("problems: {}".format(len(problems)))
    for p in problems:
        lines.append("  - " + p)
    if not problems:
        lines.append("  (none)")

    report = "\n".join(lines) + "\n"
    with open(REPORT, "w", encoding="utf-8") as fh:
        fh.write(report)
    print("problems={} files={} bytes={}".format(len(problems), len(files), total_bytes))


if __name__ == "__main__":
    main()
