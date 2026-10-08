"""The EdenOS opening sound: a sweep of fire (a filtered rush that rises and turns), then a warm,
low chord with a bright bell on top, ringing out. About 2.2 seconds, mono, 44.1 kHz."""
import numpy as np
from scipy.signal import butter, sosfilt
import wave, sys

SR = 44100
D = 2.3
t = np.arange(int(SR * D)) / SR
rng = np.random.default_rng(7)

def env(t, a, peak, release):
    """Rises from a to peak (seconds), then falls away over `release` seconds."""
    e = np.zeros_like(t)
    rise = (t >= a) & (t < peak)
    e[rise] = ((t[rise] - a) / (peak - a)) ** 2
    fall = t >= peak
    e[fall] = np.exp(-(t[fall] - peak) / release)
    return e

# 1. The sword igniting: noise through a band-pass filter whose centre sweeps up, in short overlapping pieces.
noise = rng.standard_normal(len(t))
whoosh = np.zeros_like(t)
block = 1024
for start in range(0, len(t), block // 2):
    seg = slice(start, min(start + block, len(t)))
    centre = 250 + 2600 * min(1.0, t[start] / 0.55) ** 1.6 if t[start] < 0.75 else 2850 - 1800 * min(1.0, (t[start] - 0.75) / 0.8)
    lo, hi = max(60, centre * 0.55), min(SR / 2 - 100, centre * 1.6)
    sos = butter(2, [lo, hi], btype="band", fs=SR, output="sos")
    piece = sosfilt(sos, noise[seg]) * np.hanning(seg.stop - seg.start)
    whoosh[seg] += piece
whoosh *= env(t, 0.0, 0.52, 0.28) * 0.55
# A low rumble under it, like a flame catching.
sos = butter(2, 140, btype="low", fs=SR, output="sos")
rumble = sosfilt(sos, rng.standard_normal(len(t))) * env(t, 0.05, 0.5, 0.35) * 2.2

# 2. The chord when the fire settles around the tree: a warm low fifth and a bell on top.
def tone(freq, start, decay, amp, partials=((1, 1.0), (2, 0.35), (3, 0.12))):
    out = np.zeros_like(t)
    on = t >= start
    tt = t[on] - start
    attack = np.minimum(1.0, tt / 0.012)
    for mult, a in partials:
        out[on] += a * np.sin(2 * np.pi * freq * mult * tt + 0.3 * mult) * np.exp(-tt / (decay / mult ** 0.5)) * attack
    return out * amp

chord = (tone(130.81, 0.50, 1.10, 0.30) + tone(196.00, 0.50, 1.00, 0.22) + tone(261.63, 0.52, 0.95, 0.16)
         + tone(783.99, 0.55, 0.85, 0.10, ((1, 1.0), (2.76, 0.25), (5.4, 0.08)))   # the bell
         + tone(1174.66, 0.58, 0.60, 0.05, ((1, 1.0), (2.76, 0.2))))
# A soft swell into the chord.
chord *= 1.0

mix = whoosh + rumble + chord
# Fade the very end to silence.
fade = np.ones_like(t)
tail = t > D - 0.35
fade[tail] = np.cos((t[tail] - (D - 0.35)) / 0.35 * np.pi / 2) ** 2
mix *= fade
mix /= np.max(np.abs(mix))
mix *= 10 ** (-1.5 / 20)  # peak at -1.5 dB

data = (mix * 32767).astype(np.int16)
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(data.tobytes())
print("written", len(data) / SR, "s")
