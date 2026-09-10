"""
Offline re-implementation of LivingWaxedCopperTooltipRenderer's pixel primitives.
Used to verify pixel alignment without launching Minecraft.

Usage: python _sim_tooltip.py [--old]
  --old  : use the pre-fix drawArc / stem-plot logic (for before/after comparison)
"""
import math
import sys
from PIL import Image

PANEL_W, PANEL_H = 172, 115

BG_COLOR = 0xF00A0E1A
BORDER_COLOR = 0xFF2A2748
GRID_COLOR = 0xFF171C30
AXIS_COLOR = 0xFF2E3552
TRACE_COLOR = 0xFF4CD9E8
LABEL_COLOR = 0xFF8786A8
MARKER_COLOR = 0xFFDCDCEC
UNLOCK_COLOR = 0xFFFFB454
UNLOCK_COLOR_FULL = 0xFFFFE17A
HUB_ACTIVE_COLOR = 0xFFFF7D4D
SPOKE_RING_FULL_COLOR = 0xFF8AF0FF
LEVEL_COLORS = [0xFFE8A165, 0xFFCE7F5C, 0xFF6FB09C, 0xFF4FA6A6]

WHEEL_CX, WHEEL_CY = 41, 40
WHEEL_RING_R, WHEEL_ARC_R = 31, 36
WHEEL_SPOKE_R, WHEEL_HUB_R = 28, 8

LEVEL_BASE_X, LEVEL_Y = 167, 9
LEVEL_BAR_H, LEVEL_GAP, LEVEL_MAX_W = 13, 4, 70

STEM_X, STEM_W, STEM_BASE_Y, STEM_H = 5, 162, 100, 18

OLD = '--old' in sys.argv
OLD_PAL = '--oldpalette' in sys.argv
if OLD_PAL:
    BG_COLOR = 0xF00A0A14
    BORDER_COLOR = 0xFF3A2A5A
    GRID_COLOR = 0xFF20203A
    AXIS_COLOR = 0xFF20203A
    TRACE_COLOR = 0xFF55FFFF
    LABEL_COLOR = 0xFF8A8A9A
    MARKER_COLOR = 0xFF8A8A9A
    UNLOCK_COLOR = 0xFF00CCFF
    UNLOCK_COLOR_FULL = 0xFFFFDD44
    HUB_ACTIVE_COLOR = 0xFFFF6B35
    SPOKE_RING_FULL_COLOR = 0xFF44DDFF
    LEVEL_COLORS = [0xFFE08A4B, 0xFFC07050, 0xFF6FA287, 0xFF4AA8A8]


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[0] * w for _ in range(h)]

    def fill(self, x1, y1, x2, y2, color):
        # Minecraft GuiGraphics.fill: x2/y2 exclusive
        for y in range(max(0, y1), min(self.h, y2)):
            for x in range(max(0, x1), min(self.w, x2)):
                self.px[y][x] = color

    def to_image(self):
        im = Image.new('RGB', (self.w, self.h))
        for y in range(self.h):
            for x in range(self.w):
                c = self.px[y][x]
                im.putpixel((x, y), ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF))
        return im


def plot8(g, cx, cy, x, y, color):
    g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color)
    g.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color)
    g.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color)
    g.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color)
    g.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, color)
    g.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, color)
    g.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, color)
    g.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, color)


def draw_ring(g, cx, cy, r, color):
    px, py, err = r, 0, 1 - r
    while px >= py:
        plot8(g, cx, cy, px, py, color)
        py += 1
        if err < 0:
            err += 2 * py + 1
        else:
            px -= 1
            err += 2 * (py - px) + 1


def jround(v):
    """Java Math.round semantics: floor(v + 0.5), i.e. .5 goes toward +inf."""
    return int(math.floor(v + 0.5))


def sym_round(v):
    """Mirror-symmetric rounding (see Java side): round(-x) == -round(x)."""
    return jround(v) if v >= 0 else -jround(-v)


def draw_arc(g, cx, cy, r, turns, color, dot_radius):
    if turns <= 0 or dot_radius < 0:
        return
    if OLD:
        steps = max(1, math.ceil(2 * math.pi * r * turns))
        rnd = jround
    else:
        # even step count -> i=0, steps/4, steps/2, 3*steps/4 land exactly on
        # 12/3/6/9 o'clock.  With ceil(2*pi*r)=227 (odd) the 6 o'clock slot is
        # i=113.5 -> never sampled, which is what skewed the ring.
        steps = 2 * max(1, math.ceil(math.pi * r * turns))
        rnd = sym_round
    for i in range(steps + 1):
        a = 2.0 * math.pi * turns * i / steps
        px = cx + rnd(r * math.sin(a))
        py = cy - rnd(r * math.cos(a))
        if OLD:
            g.fill(px - dot_radius + 1, py - dot_radius + 1,
                   px + dot_radius + 1, py + dot_radius + 1, color)
        else:
            g.fill(px - dot_radius, py - dot_radius,
                   px + dot_radius + 1, py + dot_radius + 1, color)


def draw_line(g, x0, y0, x1, y1, color):
    dx, dy = abs(x1 - x0), abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx - dy
    x, y = x0, y0
    while True:
        g.fill(x, y, x + 1, y + 1, color)
        if x == x1 and y == y1:
            break
        e2 = err * 2
        if e2 > -dy:
            err -= dy
            x += sx
        if e2 < dx:
            err += dx
            y += sy


def render(period=16, phase_count=16, preferred_period=16,
           level_power=(1000, 0, 0, 0), unlock_permille=1000,
           phase_offsets=None, phase_deltas=None):
    if phase_offsets is None:
        phase_offsets = list(range(16))
    if phase_deltas is None:
        phase_deltas = [100] * 16

    g = Canvas(PANEL_W, PANEL_H)
    g.fill(0, 0, PANEL_W, PANEL_H, BG_COLOR)
    g.fill(0, 0, PANEL_W, 1, BORDER_COLOR)
    g.fill(0, PANEL_H - 1, PANEL_W, PANEL_H, BORDER_COLOR)
    g.fill(0, 0, 1, PANEL_H, BORDER_COLOR)
    g.fill(PANEL_W - 1, 0, PANEL_W, PANEL_H, BORDER_COLOR)

    cx, cy = WHEEL_CX, WHEEL_CY
    draw_ring(g, cx, cy, WHEEL_RING_R, GRID_COLOR)

    # unlock arc
    if unlock_permille > 0:
        u = min(1.0, unlock_permille / 1000.0)
        col = UNLOCK_COLOR_FULL if unlock_permille >= 1000 else UNLOCK_COLOR
        draw_arc(g, cx, cy, WHEEL_ARC_R, u, col, 1)

    max_delta = max(phase_deltas)
    n = min(len(phase_offsets), len(phase_deltas))
    for i in range(n):
        amp = math.sqrt(phase_deltas[i]) / math.sqrt(max_delta) if max_delta > 0 else 1.0
        r = WHEEL_HUB_R + int(round(amp * (WHEEL_SPOKE_R - WHEEL_HUB_R)))
        r = max(WHEEL_HUB_R + 2, r)
        a = 2.0 * math.pi * phase_offsets[i] / period
        s, c = math.sin(a), math.cos(a)
        rnd = jround if OLD else sym_round
        draw_line(g,
                  cx + rnd(WHEEL_HUB_R * s), cy - rnd(WHEEL_HUB_R * c),
                  cx + rnd(r * s), cy - rnd(r * c),
                  TRACE_COLOR)

    if phase_count >= period:
        for r in range(WHEEL_SPOKE_R + 1, WHEEL_RING_R):
            draw_ring(g, cx, cy, r, SPOKE_RING_FULL_COLOR)
    draw_ring(g, cx, cy, WHEEL_SPOKE_R, GRID_COLOR)

    draw_ring(g, cx, cy, WHEEL_HUB_R, GRID_COLOR)
    if preferred_period >= 2 and period > 0:
        tick_error = abs(period - preferred_period)
        eff = (1.0 + math.cos((tick_error / preferred_period) * 2.0 * math.pi)) / 2.0
        if eff > 0:
            draw_arc(g, cx, cy, WHEEL_HUB_R, min(1.0, eff), HUB_ACTIVE_COLOR, 1 if OLD else 0)

    # phi=0 marker  (OLD = drawn before spokes, NEW = drawn last)
    if OLD:
        pass  # handled below via a second pass; simpler: draw now
    g.fill(cx - 1, cy - WHEEL_RING_R - 1, cx + 2, cy - WHEEL_RING_R + 2, MARKER_COLOR)

    # level bars
    max_v = max(level_power)
    if max_v > 0:
        base_x = LEVEL_BASE_X
        for i, v in enumerate(level_power):
            if v <= 0:
                continue
            w = max(2, int(round(v * (LEVEL_MAX_W - 2) / max_v)))
            row = len(LEVEL_COLORS) - 1 - i
            by = LEVEL_Y + row * (LEVEL_BAR_H + LEVEL_GAP)
            g.fill(base_x - w, by, base_x, by + LEVEL_BAR_H, LEVEL_COLORS[i])

    # stem plot
    left = STEM_X
    base = STEM_BASE_Y
    g.fill(left, base, left + STEM_W, base + 1, AXIS_COLOR)
    if period > 0 and n > 0:
        max_d = max(phase_deltas[:n])
        if OLD:
            slot_w = STEM_W / period
            bar_w = int(slot_w) - 1 if slot_w >= 3.0 else 1
            origin_x = left
            use_int = False
        else:
            slot_w = max(1, STEM_W // period)
            bar_w = max(1, slot_w - 1)
            used_w = min(slot_w * period, STEM_W)
            origin_x = left + (STEM_W - used_w) // 2
            use_int = True
        for i in range(n):
            amp = math.sqrt(phase_deltas[i]) / math.sqrt(max_d) if max_d > 0 else 1.0
            h = max(2, int(round(amp * STEM_H)))
            if use_int:
                bx = origin_x + phase_offsets[i] * slot_w
            else:
                bx = left + int(round(phase_offsets[i] * slot_w))
            if bx < left:
                bx = left
            if bx + bar_w > left + STEM_W:
                bx = left + STEM_W - bar_w
            g.fill(bx, base - h, bx + bar_w, base, TRACE_COLOR)
    # text row placeholder (font glyphs are ~8px tall at labelY)
    label_y = PANEL_H - 12
    g.fill(STEM_X, label_y, STEM_X + 60, label_y + 8, LABEL_COLOR)
    g.fill(PANEL_W - 5 - 34, label_y, PANEL_W - 5, label_y + 8, LABEL_COLOR)
    return g


if __name__ == '__main__':
    g = render()
    name = 'tooltip_sim_old.png' if OLD else 'tooltip_sim_new.png'
    if OLD_PAL:
        name = name.replace('.png', '_oldpal.png')
    g.to_image().save('C:/Users/AI-777hi/.workbuddy-ai/clipboard-images/' + name)
    print('saved', name)
