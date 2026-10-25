# Films

Two films of the kernel algebra. Every frame is drawn by the code in these directories and rendered in headless
Chromium; ffmpeg encodes the frames, and the ascension score is synthesized by `score.py`. Nothing is hand-animated.

| directory | film |
|---|---|
| `ascension/` | 1:44 loop. A spiral zoom out from one `Join` through Series, α, ↻, Cursor, ISAM, Confix, Couch, CCEK, SQL/facet/graph, io_uring, blackboard, p2p and the ouroboros to models served on the whole stack, then back to the opening dot. No narration; one caption line of real names per level. |
| `schoolhouse/` | 2:45 narrated cut with code panels. |

## Requirements

- Node 22 with Playwright and a Chromium it can launch (`npm i -g playwright`, `npx playwright install chromium`)
- ffmpeg with libx264 and the native AAC encoder
- Python 3 with NumPy, for the ascension score

## ascension/

| file | role |
|---|---|
| `film.js` | the film: canvas drawing engine, the fifteen levels, the spiral camera, the HUD, and the score's events |
| `index.html` | player page; `?capture` hides the controls for rendering, `?w=960` sets the canvas width |
| `score.py` | synthesizes the soundtrack from the film's events and ascent curve |
| `render.mjs` | builds `score.m4a` and the film |
| `qa.py` | finds single-frame pops in a rendered film |
| `fonts/` | KaTeX 0.16.11 fonts (MIT, `LICENSE-KaTeX`) |
| `ascension.mp4` | the rendered film (Git LFS) |
| `score.m4a` | the soundtrack, for the player page |

    node render.mjs ascension.mp4 60 4 1920    # output, fps, browser workers, width
    node render.mjs --stills stills 12.7 64.3  # PNG stills at the given seconds
    python3 qa.py ascension.mp4 60

## schoolhouse/

| file | role |
|---|---|
| `scenes.js` | the film: SVG scenes, narration and code panels |
| `index.html` | player page; `?capture` for rendering |
| `render.mjs` | renders the film and its `.srt` captions |
| `fonts/` | KaTeX math italic, for α and λ |
| `compositional-density.mp4`, `.srt` | the rendered film (Git LFS) and its captions |

    node render.mjs compositional-density.mp4 30 4    # output, fps, browser workers
    node render.mjs --stills stills 34
