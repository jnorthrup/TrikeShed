#!/usr/bin/env python3
"""Finds single-frame pops in a rendered film: a frame-to-frame change far above both of its neighbours.

  python3 qa.py ascension.mp4 [fps=60]

Prints the ten frames whose change stands out most against their neighbours. Fast zooms raise the change
of a whole run of frames together and score near 1; a pop scores well above it.
"""
import subprocess
import sys

import numpy as np

W, H = 960, 540
fps = float(sys.argv[2]) if len(sys.argv) > 2 else 60.0
ff = subprocess.Popen(['ffmpeg', '-loglevel', 'error', '-i', sys.argv[1], '-vf', f'scale={W}:{H}', '-f', 'rawvideo', '-pix_fmt', 'gray', '-'],
                      stdout=subprocess.PIPE)
prev, change = None, []
while len(b := ff.stdout.read(W * H)) == W * H:
    frame = np.frombuffer(b, np.uint8).astype(np.int16)
    if prev is not None:
        change.append(float(np.abs(frame - prev).mean()))
    prev = frame
d = np.array(change)
ratio = d[1:-1] / (np.maximum(d[:-2], d[2:]) + 0.3)
print(f'frames {len(d) + 1}  median change {np.median(d):.3f}  max {d.max():.2f}')
for i in sorted(np.argsort(ratio)[::-1][:10] + 1):
    print(f't={(i + 1) / fps:7.2f}s change={d[i]:.2f} neighbours={d[i - 1]:.2f},{d[i + 1]:.2f} ratio={ratio[i - 1]:.2f}')
