#!/usr/bin/env python3
"""Draws the bundled wallpapers into res/drawable-nodpi/ (1280x720 JPEG, the TV's own resolution).

Everything is generated from code, so the art has no licence questions. Usage: python tools/wallpapers.py
Needs Pillow and numpy. Change SEED or the colours below and run it again for a different set.
"""
import os

import numpy as np
from PIL import Image, ImageFilter

W, H = 1280, 720
SEED = 23
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res", "drawable-nodpi")

ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = xs / W, ys / H


def lerp(a, b, t):
    t = np.clip(t, 0, 1)[..., None]
    return np.array(a, np.float32) * (1 - t) + np.array(b, np.float32) * t


def vgrad(stops):
    """Vertical gradient through (position, rgb) stops."""
    img = np.zeros((H, W, 3), np.float32)
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        m = (v >= p0) & (v <= p1)
        img[m] = lerp(c0, c1, (v[m] - p0) / (p1 - p0))
    return img


def ridge(rng, base, amp, octaves=6, rough=0.5):
    """A 1-D mountain line: height in pixels for each column."""
    h = np.zeros(W, np.float32)
    a, f = amp, 1.5
    for _ in range(octaves):
        phase = rng.uniform(0, 2 * np.pi)
        h += a * np.sin(np.linspace(0, f * 2 * np.pi, W) + phase)
        h += a * 0.5 * np.sin(np.linspace(0, f * 2.7 * np.pi, W) + phase * 1.7)
        a *= rough
        f *= 2.1
    return base - h


def fill_below(img, line, colour, haze=None):
    m = ys >= line[None, :]
    if haze is not None:
        # Lighter near the ridge top: aerial perspective.
        t = np.clip((ys - line[None, :]) / 140.0, 0, 1)
        col = lerp(haze, colour, t)
        img[m] = col[m]
    else:
        img[m] = colour
    return img


def stars(img, rng, n, ymax=1.0, bright=1.0):
    for _ in range(n):
        x, y = rng.integers(0, W), int(rng.uniform(0, ymax) * H)
        b = rng.uniform(0.3, 1.0) * 255 * bright
        img[y, x] = np.maximum(img[y, x], b)
        if rng.random() < 0.08:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                yy, xx = min(H - 1, max(0, y + dy)), min(W - 1, max(0, x + dx))
                img[yy, xx] = np.maximum(img[yy, xx], b * 0.5)
    return img


def glow(img, cx, cy, r, colour, strength):
    d = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2)
    g = np.exp(-(d / r) ** 2)[..., None] * strength
    return img + g * np.array(colour, np.float32)


def blobs(rng, n, colours, rmin, rmax):
    acc = np.zeros((H, W, 3), np.float32)
    for _ in range(n):
        cx, cy = rng.uniform(0, W), rng.uniform(0, H)
        r = rng.uniform(rmin, rmax)
        c = colours[rng.integers(0, len(colours))]
        acc = glow(acc, cx, cy, r, c, rng.uniform(0.25, 0.6))
    return acc


def save(img, name):
    im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    # A touch of grain hides gradient banding on 8-bit TV panels.
    noise = np.random.default_rng(1).normal(0, 2.2, (H, W, 1)).astype(np.float32)
    im = Image.fromarray(np.clip(np.asarray(im, np.float32) + noise, 0, 255).astype(np.uint8))
    path = os.path.join(OUT, name + ".jpg")
    im.save(path, "JPEG", quality=86, optimize=True, progressive=False)
    print(f"{path}: {os.path.getsize(path) // 1024} KB")


def aurora(rng):
    img = vgrad([(0, (4, 8, 22)), (0.6, (8, 22, 40)), (1, (6, 14, 24))])
    img = stars(img, rng, 900, 0.75)
    band = np.zeros((H, W), np.float32)
    for k, (amp, y0, w) in enumerate(((60, 0.30, 0.10), (40, 0.42, 0.07), (80, 0.22, 0.05))):
        centre = y0 * H + amp * np.sin(u[0] * (2.2 + k) * np.pi + k * 1.3) + 25 * np.sin(u[0] * 9 * np.pi + k)
        d = (ys - centre[None, :]) / (w * H)
        # Curtains: sharp lower edge, long fade upwards, with vertical rays.
        curtain = np.where(d > 0, np.exp(-(d * 4) ** 2), np.exp(-(d * 0.8) ** 2))
        rays = 0.65 + 0.35 * np.sin(xs * 0.09 + k * 2 + np.sin(xs * 0.013) * 4)
        band += curtain * rays * (1.0 - 0.3 * k)
    green, violet = np.array((40, 255, 170), np.float32), np.array((150, 80, 255), np.float32)
    mix = np.clip((0.40 * H - ys) / (0.25 * H), 0, 1)[..., None]
    img += band[..., None] * (green * (1 - mix) + violet * mix) * 0.55
    img = fill_below(img, ridge(rng, 0.80 * H, 28), (6, 10, 16), haze=(20, 40, 50))
    img = fill_below(img, ridge(rng, 0.90 * H, 18, rough=0.6), (2, 4, 8))
    return img


def dusk(rng):
    img = vgrad([(0, (28, 22, 64)), (0.35, (120, 60, 110)), (0.58, (245, 140, 90)), (1, (250, 190, 120))])
    img = glow(img, 0.68 * W, 0.56 * H, 70, (255, 230, 170), 0.9)
    img = glow(img, 0.68 * W, 0.56 * H, 260, (255, 150, 90), 0.35)
    layers = [(0.58, 40, (150, 80, 110), (200, 120, 130)), (0.66, 46, (95, 52, 92), (150, 90, 120)),
              (0.75, 50, (55, 32, 70), (95, 60, 95)), (0.86, 40, (26, 16, 40), (50, 32, 62))]
    for base, amp, col, haze in layers:
        img = fill_below(img, ridge(rng, base * H, amp), col, haze=haze)
    return img


def ocean(rng):
    horizon = 0.58
    img = vgrad([(0, (6, 10, 30)), (horizon, (30, 52, 96)), (horizon + 0.001, (10, 20, 42)), (1, (2, 6, 14))])
    img = stars(img, rng, 500, horizon * 0.9, 0.8)
    mx, my = 0.30 * W, 0.24 * H
    img = glow(img, mx, my, 34, (240, 240, 230), 1.6)
    img = glow(img, mx, my, 180, (120, 150, 210), 0.35)
    d = np.sqrt((xs - mx) ** 2 + (ys - my) ** 2)
    img[d < 30] = (235, 238, 228)
    # Moon path on the water: broken horizontal glints.
    sea = ys > horizon * H
    spread = 20 + (ys - horizon * H) * 0.45
    glint = np.exp(-((xs - mx) / spread) ** 2) * (0.5 + 0.5 * np.sin(ys * 1.7 + np.sin(xs * 0.05) * 3)) ** 6
    img += (glint * sea)[..., None] * np.array((200, 210, 230), np.float32) * 0.9
    waves = 0.5 + 0.5 * np.sin(ys * 0.9 + np.sin(xs * 0.02 + ys * 0.05) * 2)
    img += ((waves ** 12) * sea * 0.12)[..., None] * np.array((120, 160, 220), np.float32)
    return img


def galaxy(rng):
    img = vgrad([(0, (4, 3, 12)), (1, (8, 6, 20))])
    neb = blobs(rng, 14, [(120, 40, 200), (30, 120, 220), (220, 60, 140), (40, 200, 200)], 120, 360)
    img += neb * 0.5
    # A tilted band of dense faint stars, like the Milky Way.
    t = (ys - (0.75 * H - 0.5 * xs)) / 110
    img += (np.exp(-t ** 2) * 40)[..., None] * np.array((1.0, 0.9, 1.1), np.float32)
    img = stars(img, rng, 2600, 1.0, 0.9)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.4)), img


def waves(rng):
    img = vgrad([(0, (10, 12, 28)), (1, (4, 5, 12))])
    cols = [(255, 110, 120), (255, 170, 90), (120, 120, 255), (80, 200, 220), (190, 90, 220)]
    for k in range(9):
        base = H * (0.25 + 0.07 * k)
        amp = 50 + 12 * k
        line = base + amp * np.sin(u[0] * (1.4 + 0.15 * k) * np.pi + k * 0.8)
        d = (ys - line[None, :])
        ribbon = np.exp(-(d / (14 + 3 * k)) ** 2) * 0.55 + np.clip(d / 300, 0, 1) * 0.0
        c = np.array(cols[k % len(cols)], np.float32)
        img = img * (1 - ribbon[..., None] * 0.5) + ribbon[..., None] * c * 0.6
    img = glow(img, 0.8 * W, 0.2 * H, 400, (255, 120, 160), 0.18)
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    rng = np.random.default_rng(SEED)
    save(aurora(rng), "wp_aurora")
    save(dusk(rng), "wp_dusk")
    save(ocean(rng), "wp_ocean")
    blurred, _ = galaxy(rng)
    save(np.asarray(blurred, np.float32), "wp_galaxy")
    save(waves(rng), "wp_waves")


if __name__ == "__main__":
    main()
