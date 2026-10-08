"""The edenOS opening sound, about 4.5 seconds: a small orchestra, made from scratch.

0.0 s   high strings, barely there, and a soft choir: the garden at night.
0.3 s   a harp falls, note by note, as the sunlit leaf drifts down.
1.1 s   timpani roll in under it; a cymbal swells.
1.62 s  the leaf lands: a timpani stroke, and the whole orchestra blooms into a warm D major
        chord (strings, horns, choir) with a sparkle of celesta as the e lights up.
3.4 s   it rings out in a large hall and fades.

Writes a stereo WAV; the app plays it as Ogg Vorbis (see the end). Usage:
    python3 tools/make-intro-sound.py out.wav
"""
import sys
import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve

SR = 44100
LENGTH = 4.6
N = int(SR * LENGTH)
rng = np.random.default_rng(11)
L = np.zeros(N)
R = np.zeros(N)


def note_hz(name):
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    pitch, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names[pitch] + 12 * (octave - 4)) / 12)


def span(t0, t1):
    a = max(0, int(t0 * SR))
    b = min(N, int(t1 * SR))
    return a, b, np.arange(b - a) / SR


def place(sig, a, pan):
    """Adds a mono signal at sample a, panned from -1 (left) to 1 (right) with equal power."""
    angle = (pan + 1) * np.pi / 4
    # Every note eases out at the end of its buffer, so nothing clicks.
    sig = sig.copy()
    ease = min(len(sig), int(0.04 * SR))
    sig[-ease:] *= np.cos(np.linspace(0, np.pi / 2, ease)) ** 2
    n = min(len(sig), N - a)
    L[a:a + n] += sig[:n] * np.cos(angle)
    R[a:a + n] += sig[:n] * np.sin(angle)


def envelope(tt, attack, hold, release, curve=2.0):
    """Rises over `attack`, holds until `hold`, then dies away over `release` (seconds from the note's start)."""
    e = np.ones_like(tt)
    up = tt < attack
    e[up] = (tt[up] / attack) ** curve
    down = tt > hold
    e[down] = np.exp(-(tt[down] - hold) / (release / 3))
    return e


def bowed(f, t0, t1, amp, pan, voices=6, attack=0.5, release=1.0, bright=9.0, vib=5.2):
    """A section of strings: detuned sawtooth voices, softened, with vibrato."""
    a, b, tt = span(t0, t1 + release * 2)
    out = np.zeros(len(tt))
    for v in range(voices):
        cents = rng.uniform(-8, 8)
        fv = f * 2 ** (cents / 1200)
        wobble = 1 + 0.0032 * np.sin(2 * np.pi * (vib + rng.uniform(-0.4, 0.4)) * tt + rng.uniform(0, 6.28)) * np.clip(tt / 0.4, 0, 1)
        phase = 2 * np.pi * np.cumsum(fv * wobble) / SR + rng.uniform(0, 6.28)
        top = max(1, min(40, int(9000 / fv)))
        for k in range(1, top + 1):
            out += np.sin(k * phase) * (1.0 / k) * np.exp(-(k - 1) / bright)
    out *= envelope(tt, attack, t1 - t0, release) * amp / voices
    place(out, a, pan)


def horn(f, t0, t1, amp, pan, attack=0.18, release=1.0):
    """French horns: warm, getting brighter as they swell."""
    a, b, tt = span(t0, t1 + release * 2)
    out = np.zeros(len(tt))
    shine = 2.5 + 4.0 * np.clip(tt / (attack * 3), 0, 1)
    for v in range(3):
        fv = f * 2 ** (rng.uniform(-5, 5) / 1200)
        phase = 2 * np.pi * fv * tt + rng.uniform(0, 6.28)
        for k in range(1, 18):
            out += np.sin(k * phase) * np.exp(-(k - 1) / shine) / k ** 0.6
    out *= envelope(tt, attack, t1 - t0, release, curve=1.5) * amp / 3
    place(out, a, pan)


FORMANTS = ((800, 1.0, 130), (1150, 0.55, 150), (2900, 0.22, 220), (3300, 0.12, 260))  # "ah"


def choir(f, t0, t1, amp, pan, attack=0.45, release=1.2):
    """Voices singing "ah": a rich tone shaped by the formants of the vowel."""
    a, b, tt = span(t0, t1 + release * 2)
    out = np.zeros(len(tt))
    for v in range(5):
        fv = f * 2 ** (rng.uniform(-12, 12) / 1200)
        wobble = 1 + 0.005 * np.sin(2 * np.pi * rng.uniform(4.8, 5.8) * tt + rng.uniform(0, 6.28))
        phase = 2 * np.pi * np.cumsum(fv * wobble) / SR + rng.uniform(0, 6.28)
        for k in range(1, max(2, int(4500 / fv))):
            fk = k * fv
            weight = 0.04 + sum(A * np.exp(-((fk - F) / B) ** 2) for F, A, B in FORMANTS)
            out += np.sin(k * phase) * weight
    out *= envelope(tt, attack, t1 - t0, release, curve=1.6) * amp / 5
    place(out, a, pan)


def harp(f, t0, amp, pan):
    a, b, tt = span(t0, t0 + 2.2)
    out = np.zeros(len(tt))
    for k in range(1, 9):
        out += np.sin(2 * np.pi * f * k * (1 + 0.0004 * k * k) * tt) * np.exp(-tt * (1.0 + 0.9 * k)) / k ** 1.15
    out *= np.clip(tt / 0.003, 0, 1) * amp
    place(out, a, pan)


def celesta(f, t0, amp, pan):
    a, b, tt = span(t0, t0 + 2.6)
    out = np.zeros(len(tt))
    for ratio, level, decay in ((1, 1.0, 1.6), (2.0, 0.35, 0.9), (3.0, 0.12, 0.6), (4.16, 0.1, 0.35)):
        out += level * np.sin(2 * np.pi * f * ratio * tt) * np.exp(-tt / decay)
    out *= np.clip(tt / 0.002, 0, 1) * amp
    place(out, a, pan)


def timpani(f, t0, amp, pan):
    a, b, tt = span(t0, t0 + 2.4)
    glide = 1 + 0.025 * np.exp(-tt / 0.06)
    out = np.zeros(len(tt))
    for ratio, level, decay in ((1, 1.0, 1.1), (1.5, 0.45, 0.6), (1.98, 0.3, 0.45), (2.44, 0.15, 0.3)):
        out += level * np.sin(2 * np.pi * np.cumsum(f * ratio * glide) / SR) * np.exp(-tt / decay)
    mallet = sosfilt(butter(2, 900, "low", fs=SR, output="sos"), rng.standard_normal(len(tt))) * np.exp(-tt / 0.012) * 0.6
    out = (out + mallet) * np.clip(tt / 0.002, 0, 1) * amp
    place(out, a, pan)


def cymbal_swell(peak, amp):
    a, b, tt = span(peak - 0.75, peak + 2.6)
    noise = sosfilt(butter(4, [3500, 12000], "band", fs=SR, output="sos"), rng.standard_normal(len(tt)))
    rise = np.clip(tt / 0.75, 0, 1) ** 2.4
    fall = np.where(tt > 0.75, np.exp(-(tt - 0.75) / 0.45), 1.0)
    noise2 = sosfilt(butter(4, [3500, 12000], "band", fs=SR, output="sos"), rng.standard_normal(len(tt)))
    place(noise * rise * fall * amp, a, -0.35)
    place(noise2 * rise * fall * amp, a, 0.35)


LAND = 1.62
END = 3.35

# The garden at night: high strings and a hush of voices.
bowed(note_hz("D5"), 0.0, END, 0.050, -0.35, attack=1.2, release=1.4, bright=6)
bowed(note_hz("A4"), 0.0, END, 0.050, 0.35, attack=1.2, release=1.4, bright=6)
choir(note_hz("A3"), 0.1, LAND, 0.030, 0.0, attack=1.0, release=0.6)

# The leaf falls: a harp, note by note, drifting from left to right.
falling = ["F#6", "D6", "A5", "F#5", "E5", "D5", "A4"]
times = [0.30, 0.47, 0.64, 0.83, 1.02, 1.22, 1.43]
for i, (n, at) in enumerate(zip(falling, times)):
    harp(note_hz(n), at, 0.11 - 0.006 * i, -0.6 + 1.2 * i / (len(falling) - 1))

# Timpani roll in, and a cymbal swells to the landing.
roll = np.arange(1.10, LAND - 0.03, 0.055)
for i, at in enumerate(roll):
    timpani(note_hz("A2"), at, 0.020 + 0.11 * (i / len(roll)) ** 2, 0.2 + 0.1 * (i % 2))
cymbal_swell(LAND + 0.02, 0.012)

# The landing, and the bloom.
timpani(note_hz("D2"), LAND, 0.34, 0.1)
for n, pan in (("D2", 0.3), ("A2", 0.25), ("D3", 0.2)):
    bowed(note_hz(n), LAND, END, 0.16, pan, voices=5, attack=0.09, release=1.6, bright=7)
for n, pan in (("F#3", -0.05), ("A3", 0.05)):
    bowed(note_hz(n), LAND + 0.02, END, 0.11, pan, voices=5, attack=0.14, release=1.6, bright=8)
for n, pan in (("D4", -0.4), ("F#4", -0.3), ("A4", -0.35), ("D5", -0.45), ("E5", -0.25)):
    bowed(note_hz(n), LAND + 0.04, END, 0.075 if n != "E5" else 0.035, pan, attack=0.22, release=1.6, bright=10)
for n, pan in (("A3", -0.15), ("D4", -0.1), ("F#4", -0.05)):
    horn(note_hz(n), LAND + 0.03, END - 0.2, 0.085, pan, attack=0.2, release=1.3)
for n, pan in (("D4", -0.6), ("F#4", 0.6), ("A4", -0.5), ("D5", 0.5)):
    choir(note_hz(n), LAND + 0.05, END, 0.055, pan, attack=0.38, release=1.5)
for n, at, pan in (("A6", LAND + 0.10, 0.3), ("D7", LAND + 0.24, 0.4), ("F#6", LAND + 0.38, 0.2), ("A6", LAND + 0.55, 0.35)):
    celesta(note_hz(n), at, 0.05, pan)

# A large hall.
def hall(seed):
    g = np.random.default_rng(seed)
    n = int(SR * 3.0)
    tt = np.arange(n) / SR
    ir = g.standard_normal(n) * np.exp(-tt / 0.42)
    ir = sosfilt(butter(2, 5200, "low", fs=SR, output="sos"), ir)
    ir[: int(SR * 0.022)] = 0  # pre-delay
    return ir / np.sqrt(np.sum(ir ** 2))

wetL = fftconvolve(L, hall(1))[:N]
wetR = fftconvolve(R, hall(2))[:N]
mixL = L * 0.78 + wetL * 0.42
mixR = R * 0.78 + wetR * 0.42

# Fade the very end, then level it: peaks just under full scale, softened rather than clipped.
tail = np.arange(N) / SR > LENGTH - 0.6
fade = np.ones(N)
fade[tail] = np.cos((np.arange(N)[tail] / SR - (LENGTH - 0.6)) / 0.6 * np.pi / 2) ** 2
stereo = np.stack([mixL * fade, mixR * fade], axis=1)
stereo /= np.max(np.abs(stereo))
stereo = np.tanh(stereo * 1.15) / np.tanh(1.15) * 10 ** (-1.0 / 20)

import wave
data = (stereo * 32767).astype(np.int16)
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(data.tobytes())
print("written", LENGTH, "s")
# Then: ffmpeg -i out.wav -c:a libvorbis -q:a 5 app/src/main/res/raw/intro.ogg
