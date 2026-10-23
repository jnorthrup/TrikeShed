#!/usr/bin/env python3
"""Score for Ascension, rendered from the film's own clock.

  python3 score.py film-meta.json score.wav

A Shepard-Risset glissando rises one octave per level, locked to the camera's ascent curve, so the
pitch climbs exactly as fast as the zoom. A chord pad follows the levels; every pop, landing and
arrival in the film is a marimba, bell, tick or thump. Every tail wraps around the end, so the
audio loops as seamlessly as the picture.
"""
import json
import sys
import wave

import numpy as np

SR = 48000
meta = json.load(open(sys.argv[1]))
DUR = meta['duration']
N = int(round(DUR * SR))
rng = np.random.default_rng(7)

curve = np.array(meta['curve'])
tc = np.arange(len(curve)) / 100.0
t_all = np.arange(N) / SR
L = np.interp(t_all, tc, curve)
speed = np.convolve(np.gradient(curve) * 100.0, np.ones(21) / 21, mode='same')
v = np.interp(t_all, tc, np.clip(speed / speed.max(), 0, 1))

mix = np.zeros((2, N))
send = np.zeros((2, N))


def add(buf, start, sig, pan=0.0):
    """Add a mono signal at sample `start`, equal-power panned; whatever runs past the end wraps."""
    a = (pan + 1) * np.pi / 4
    gains = (np.cos(a), np.sin(a))
    idx = (start + np.arange(len(sig))) % N
    for ch in (0, 1):
        np.add.at(buf[ch], idx, sig * gains[ch])


# ── Shepard-Risset rise ─────────────────────────────────────────
shep = np.zeros(N)
for i in range(9):
    f = 27.5 * 2.0 ** ((i + L) % 9)
    amp = np.exp(-0.5 * (np.log2(f / 330.0) / 1.3) ** 2)
    shep += amp * np.sin(2 * np.pi * np.cumsum(f) / SR)
edge = np.minimum(1, np.minimum(t_all, DUR - t_all) / 0.25)
shep *= (0.012 + 0.10 * v ** 1.3) * edge
mix += 0.9 * shep

# ── whoosh: noise whose band climbs within each zoom ────────────
hop, n = 1024, 2048
win = np.hanning(n)
freqs = np.fft.rfftfreq(n, 1 / SR)
for ch in (0, 1):
    noise = rng.standard_normal(N + n)
    out = np.zeros(N + n)
    for m in range(0, N, hop):
        j = min(m + n // 2, N - 1)
        if v[j] < 0.02:
            continue
        fc = 180.0 * 2 ** (3 * (L[j] % 1.0))
        H = np.exp(-0.5 * (np.log2((freqs + 1) / fc) / 0.7) ** 2)
        out[m:m + n] += np.fft.irfft(np.fft.rfft(noise[m:m + n] * win) * H) * win * (0.06 * v[j] ** 2)
    mix[ch] += out[:N]

# ── pads: one chord per level, the last wraps into the first ────
CHORDS = {'D': [50, 57, 62, 66], 'Bm': [47, 54, 59, 62], 'G': [43, 50, 55, 59], 'A': [45, 52, 57, 61]}
ORDER = ['D', 'Bm', 'G', 'A']
levels = meta['levels']
last = len(levels) - 1
hz = lambda m: 440.0 * 2 ** ((m - 69) / 12)


def pad(chord, t0, t1, rise=1.8, fall=2.2):
    s0, s1 = int(t0 * SR), int(t1 * SR)
    t = np.arange(s1 - s0) / SR
    env = np.minimum(1, np.minimum(t / rise, (t[-1] - t) / fall))
    env = 0.5 - 0.5 * np.cos(np.pi * np.clip(env, 0, 1))
    trem = 1 + 0.08 * np.sin(2 * np.pi * 0.21 * t)
    for k, m in enumerate(CHORDS[chord]):
        f = hz(m)
        voice = sum(np.sin(2 * np.pi * f * d * t + k) + 0.3 * np.sin(4 * np.pi * f * d * t) + 0.1 * np.sin(6 * np.pi * f * d * t) for d in (0.9988, 1.0012))
        sig = 0.011 * voice * env * trem
        add(mix, s0, sig, pan=-0.35 if k % 2 else 0.35)
        add(send, s0, sig, pan=-0.35 if k % 2 else 0.35)


for k in range(1, last):
    pad(ORDER[k % 4], max(0.0, levels[k]['zs'] - 0.6), levels[k + 1]['zs'] + 1.0)
# the closing D runs on through the seam and is the opening D
pad('D', levels[last]['zs'] - 0.6, DUR + levels[1]['zs'] + 1.0)

# ── events ──────────────────────────────────────────────────────
PENT = [0, 2, 4, 7, 9]


def midi(p, level):
    q = p + level % 5
    m = 62 + 12 * (q // 5) + PENT[q % 5]
    while m > 93:
        m -= 12
    while m < 38:
        m += 12
    return m


def tone(f, parts, dur):
    t = np.arange(int(dur * SR)) / SR
    s = sum(a * np.sin(2 * np.pi * f * r * t) * np.exp(-t / d) for r, a, d in parts)
    return s * (1 - np.exp(-t / 0.003))


def synth(e):
    kind, vel = e['kind'], e['vel']
    f = hz(midi(int(e['p']), e['level']))
    if kind == 'pluck':
        return vel * tone(f, [(1, 1.0, 0.42), (4, 0.28, 0.06), (2, 0.12, 0.2)], 1.6)
    if kind == 'bell':
        return vel * tone(f, [(1, 1.0, 1.6), (2.0, 0.45, 1.0), (2.76, 0.35, 0.8), (5.4, 0.15, 0.35), (8.9, 0.07, 0.18)], 3.5)
    if kind == 'chord':
        b = synth({**e, 'kind': 'bell'})
        for dp in (2, 4):
            p = synth({**e, 'kind': 'pluck', 'p': e['p'] + dp, 'vel': vel * 0.7})
            b[:len(p)] += p
        return b
    t = np.arange(int(0.8 * SR)) / SR
    if kind == 'thump':
        ph = 2 * np.pi * np.cumsum(42 + 55 * np.exp(-t / 0.08)) / SR
        return vel * np.sin(ph) * np.exp(-t / 0.35)
    if kind == 'kick':
        ph = 2 * np.pi * np.cumsum(45 + 90 * np.exp(-t / 0.03)) / SR
        return vel * (np.sin(ph) * np.exp(-t / 0.18) + 0.2 * rng.standard_normal(len(t)) * np.exp(-t / 0.004))
    if kind == 'tick':
        t = t[:int(0.05 * SR)]
        return vel * 0.6 * np.diff(rng.standard_normal(len(t) + 1)) * np.exp(-t / 0.008)
    if kind == 'hat':
        t = t[:int(0.08 * SR)]
        return vel * 0.5 * np.diff(rng.standard_normal(len(t) + 2), 2) * np.exp(-t / 0.02)
    if kind == 'gliss':
        t = np.arange(int(1.2 * SR)) / SR
        fr = 220 * 2 ** (2 * t / 1.2)
        return vel * 0.5 * np.sin(2 * np.pi * np.cumsum(fr) / SR) * np.sin(np.pi * t / 1.2)
    raise ValueError(kind)


for e in meta['events']:
    sig = synth(e)
    s0 = int(round(e['t'] * SR))
    add(mix, s0, sig, e.get('pan', 0))
    add(send, s0, sig * (0.6 if e['kind'] in ('kick', 'hat', 'tick') else 1.0), e.get('pan', 0))

# ── circular reverb on the send: the tail of the end rings into the start ──
for ch in (0, 1):
    t = np.arange(int(2.4 * SR)) / SR
    ir = rng.standard_normal(len(t)) * np.exp(-t / 0.55)
    ir = np.convolve(ir, np.ones(4) / 4, mode='same')
    ir[:int(0.015 * SR)] = 0
    ir /= np.sqrt(np.sum(ir ** 2))
    pad_ir = np.zeros(N)
    pad_ir[:len(ir)] = ir
    mix[ch] += 0.32 * np.fft.irfft(np.fft.rfft(send[ch]) * np.fft.rfft(pad_ir), n=N)

out = np.tanh(1.3 * mix) / np.tanh(1.3)
out *= 0.9 / np.max(np.abs(out))
pcm = (np.clip(out.T, -1, 1) * 32767).astype('<i2')
with wave.open(sys.argv[2], 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print(f'wrote {sys.argv[2]}: {DUR:.2f}s, {len(meta["events"])} events')
