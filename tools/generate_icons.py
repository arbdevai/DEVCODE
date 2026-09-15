#!/usr/bin/env python3
"""Generate DEVCODE launcher icons (legacy PNG mipmaps + round variants).

Pure standard library (no Pillow needed). Design: dark rounded square with a
cyan terminal prompt ">_" and a green block cursor.

Usage:
    python3 tools/generate_icons.py

Output:
    app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
        ic_launcher.png / ic_launcher_round.png
"""
import math
import os
import struct
import zlib

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "res")

BASE = 512  # master render size
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Palette
BG_TOP = (26, 38, 52, 255)
BG_BOTTOM = (11, 15, 20, 255)
BORDER = (42, 59, 79, 255)
CYAN = (125, 211, 252, 255)
GREEN = (74, 222, 128, 255)


def new_canvas(size):
    return [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]


def lerp(a, b, t):
    return tuple(int(round(x + (y - x) * t)) for x, y in zip(a, b))


def fill_rounded_rect(px, x0, y0, x1, y1, radius, top, bottom):
    n = len(px)
    for y in range(max(0, y0), min(n, y1)):
        t = (y - y0) / max(1, y1 - y0 - 1)
        color = lerp(top, bottom, t)
        for x in range(max(0, x0), min(n, x1)):
            cx = min(max(x, x0 + radius), x1 - radius)
            cy = min(max(y, y0 + radius), y1 - radius)
            if (x - cx) ** 2 + (y - cy) ** 2 <= radius * radius:
                px[y][x] = color


def stroke_rounded_rect(px, x0, y0, x1, y1, radius, width, color):
    n = len(px)
    hw = width / 2.0
    for y in range(max(0, int(y0 - hw) - 1), min(n, int(y1 + hw) + 1)):
        for x in range(max(0, int(x0 - hw) - 1), min(n, int(x1 + hw) + 1)):
            cx = min(max(x, x0 + radius), x1 - radius)
            cy = min(max(y, y0 + radius), y1 - radius)
            d = math.hypot(x - cx, y - cy)
            if abs(d - radius) <= hw:
                px[y][x] = color


def dot(px, cx, cy, r, color):
    n = len(px)
    for y in range(max(0, int(cy - r) - 1), min(n, int(cy + r) + 2)):
        for x in range(max(0, int(cx - r) - 1), min(n, int(cx + r) + 2)):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                px[y][x] = color


def thick_line(px, x0, y0, x1, y1, width, color):
    length = math.hypot(x1 - x0, y1 - y0)
    steps = max(1, int(length))
    r = width / 2.0
    for i in range(steps + 1):
        t = i / steps
        dot(px, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, r, color)


def downsample(src, target):
    n = len(src)
    scale = n / target
    out = [[(0, 0, 0, 0) for _ in range(target)] for _ in range(target)]
    for oy in range(target):
        for ox in range(target):
            rs = gs = bs = a_sum = 0
            count = 0
            for sy in range(int(oy * scale), int((oy + 1) * scale)):
                for sx in range(int(ox * scale), int((ox + 1) * scale)):
                    r, g, b, a = src[sy][sx]
                    rs += r
                    gs += g
                    bs += b
                    a_sum += a
                    count += 1
            if count:
                out[oy][ox] = (rs // count, gs // count, bs // count, a_sum // count)
    return out


def apply_circle_mask(px):
    n = len(px)
    c = n / 2.0
    for y in range(n):
        for x in range(n):
            if math.hypot(x - c + 0.5, y - c + 0.5) > c:
                px[y][x] = (0, 0, 0, 0)
    return px


def write_png(path, px):
    h = len(px)
    w = len(px[0])
    raw = b"".join(
        b"\x00" + b"".join(struct.pack("4B", *pix) for pix in row) for row in px
    )

    def chunk(ctype, data):
        c = ctype + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def render_master():
    px = new_canvas(BASE)
    # Background
    fill_rounded_rect(px, 8, 8, BASE - 8, BASE - 8, 112, BG_TOP, BG_BOTTOM)
    stroke_rounded_rect(px, 14, 14, BASE - 14, BASE - 14, 106, 6, BORDER)
    # Chevron ">"
    thick_line(px, 168, 192, 244, 256, 38, CYAN)
    thick_line(px, 244, 256, 168, 320, 38, CYAN)
    # Underscore "_"
    thick_line(px, 276, 320, 356, 320, 38, CYAN)
    # Block cursor
    fill_rounded_rect(px, 372, 276, 406, 320, 6, GREEN, GREEN)
    return px


def main():
    master = render_master()
    for density, size in DENSITIES.items():
        d = os.path.join(RES_DIR, f"mipmap-{density}")
        write_png(os.path.join(d, "ic_launcher.png"), downsample(master, size))
        circ = [row[:] for row in master]
        write_png(
            os.path.join(d, "ic_launcher_round.png"),
            downsample(apply_circle_mask(circ), size),
        )
        print(f"mipmap-{density}: {size}x{size} launcher + round written")


if __name__ == "__main__":
    main()
