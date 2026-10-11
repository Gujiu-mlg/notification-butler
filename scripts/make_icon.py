#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""为「通知管家」生成一套图标。

产物：
  ic_launcher.png            传统图标（带圆角）
  ic_launcher_round.png      传统圆形图标
  ic_launcher_foreground.png 自适应图标的前景层（铃铛，留安全区）
  playstore_512.png          应用商店用的 512x512

设计：蓝紫渐变底 + 白色铃铛 + 珊瑚红斜杠
"""
import math
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "app", "res")
PREVIEW = os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "screenshots", "icon-preview.png")

# 各密度：(mipmap 目录名, 传统图标尺寸, 自适应前景尺寸)
DENSITIES = [
    ("mipmap-mdpi", 48, 108),
    ("mipmap-hdpi", 72, 162),
    ("mipmap-xhdpi", 96, 216),
    ("mipmap-xxhdpi", 144, 324),
    ("mipmap-xxxhdpi", 192, 432),
]

# 配色
C_TOP = (43, 78, 255)       # #2B4EFF 蓝
C_BOT = (110, 58, 255)      # #6E3AFF 紫
C_BELL = (255, 255, 255)
C_SLASH = (255, 77, 106)    # #FF4D6A 珊瑚红

SS = 4                      # 超采样倍数（抗锯齿）


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(size, top, bot, diagonal=True):
    """生成对角渐变。"""
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            if diagonal:
                t = (x / max(1, size - 1) + y / max(1, size - 1)) / 2.0
            else:
                t = y / max(1, size - 1)
            px[x, y] = lerp(top, bot, t)
    return img


def bell_mask(size):
    """画一个铃铛的 alpha 蒙版（超采样后缩小）。

    经典铃铛轮廓：
      · 顶部小提手（圆）
      · 圆顶（半球），从窄到宽
      · 铃身（两侧平滑外扩）
      · 铃口（明显比铃身宽的圆角横条）
      · 铃舌（下方小圆）
    """
    S = size * SS
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)

    cx = S * 0.5
    # --- 关键尺寸（全部相对 S） ---
    # 经典铃铛：宽穹顶 + 近乎垂直的铃身 + 明显外突的铃口
    cap_cy, cap_rx, cap_ry = 0.365 * S, 0.256 * S, 0.272 * S
    body_top, body_bot = 0.365 * S, 0.690 * S
    w_top, w_bot = 0.256 * S, 0.300 * S
    rim_cy, rim_hw, rim_hh = 0.712 * S, 0.362 * S, 0.045 * S
    cl_cy, cl_r = 0.806 * S, 0.060 * S

    # 1) 穹顶：竖椭圆，最宽处正好接上铃身顶部
    d.ellipse([cx - cap_rx, cap_cy - cap_ry,
               cx + cap_rx, cap_cy + cap_ry], fill=255)

    # 2) 铃身：平滑外扩
    n = 160
    left, right = [], []
    for i in range(n + 1):
        t = i / float(n)
        y = body_top + (body_bot - body_top) * t
        w = w_top + (w_bot - w_top) * (t ** 0.55)
        left.append((cx - w, y))
        right.append((cx + w, y))
    d.polygon(left + list(reversed(right)), fill=255)

    # 3) 铃口（明显外突的圆角横条）
    d.rounded_rectangle([cx - rim_hw, rim_cy - rim_hh,
                         cx + rim_hw, rim_cy + rim_hh],
                        radius=rim_hh, fill=255)

    # 4) 铃舌
    d.ellipse([cx - cl_r, cl_cy - cl_r, cx + cl_r, cl_cy + cl_r], fill=255)

    return m.resize((size, size), Image.LANCZOS)


def slash_mask(size):
    """斜杠的 alpha 蒙版（从左上到右下）。"""
    S = size * SS
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    pad = S * 0.185
    w = S * 0.088                     # 线宽
    # 斜杠两端点
    x0, y0 = pad, S - pad
    x1, y1 = S - pad, pad
    # 画一条带圆头的粗线：用两条平行线 + 两个圆
    dx, dy = x1 - x0, y1 - y0
    L = math.hypot(dx, dy)
    nx, ny = -dy / L, dx / L          # 法向量
    h = w / 2.0
    d.polygon([
        (x0 + nx * h, y0 + ny * h),
        (x1 + nx * h, y1 + ny * h),
        (x1 - nx * h, y1 - ny * h),
        (x0 - nx * h, y0 - ny * h),
    ], fill=255)
    d.ellipse([x0 - h, y0 - h, x0 + h, y0 + h], fill=255)
    d.ellipse([x1 - h, y1 - h, x1 + h, y1 + h], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def rounded_mask(size, radius_ratio=0.22):
    """圆角矩形蒙版。"""
    S = size * SS
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    r = S * radius_ratio
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    S = size * SS
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    d.ellipse([0, 0, S - 1, S - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def compose_icon(size, with_rounded=True, as_circle=False):
    """合成完整图标：渐变底 + 白铃铛 + 红斜杠。"""
    base = gradient(size, C_TOP, C_BOT).convert("RGBA")
    bell = bell_mask(size)
    slash = slash_mask(size)

    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    white = Image.new("RGBA", (size, size), C_BELL + (255,))
    layer.paste(white, (0, 0), bell)

    red = Image.new("RGBA", (size, size), C_SLASH + (255,))
    # 斜杠只画在铃铛之外一点点，避免糊成一团：这里直接叠在上面
    layer.paste(red, (0, 0), slash)

    base.alpha_composite(layer)

    if as_circle:
        base.putalpha(circle_mask(size))
    elif with_rounded:
        base.putalpha(rounded_mask(size))
    return base


def foreground(size):
    """自适应图标前景：透明底 + 铃铛（缩放至安全区 62%）。"""
    safe = int(size * 0.62)
    inner = Image.new("RGBA", (safe, safe), (0, 0, 0, 0))
    bell = bell_mask(safe)
    slash = slash_mask(safe)
    white = Image.new("RGBA", (safe, safe), C_BELL + (255,))
    inner.paste(white, (0, 0), bell)
    red = Image.new("RGBA", (safe, safe), C_SLASH + (255,))
    inner.paste(red, (0, 0), slash)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - safe) // 2
    out.alpha_composite(inner, (off, off))
    return out


print("=" * 70)
print("生成「通知管家」图标")
print("=" * 70)
for dirname, legacy, fg in DENSITIES:
    d = os.path.join(OUT, dirname)
    os.makedirs(d, exist_ok=True)

    ic = compose_icon(legacy)
    ic.save(os.path.join(d, "ic_launcher.png"))

    icr = compose_icon(legacy, as_circle=True)
    icr.save(os.path.join(d, "ic_launcher_round.png"))

    f = foreground(fg)
    f.save(os.path.join(d, "ic_launcher_foreground.png"))

    print("  %-16s 传统 %3d  前景 %3d" % (dirname, legacy, fg))

# 应用商店用 512
play = compose_icon(512, with_rounded=False)
play.save(os.path.join(os.path.dirname(OUT), "..", "screenshots", "icon-512.png"))
print("  %-16s %d" % ("playstore", 512))

# 预览图（并排展示几种尺寸）
pv = Image.new("RGB", (900, 340), (245, 247, 250))
xs = 40
for sz in (256, 192, 144, 96, 72, 48):
    im = compose_icon(sz)
    pv.paste(im, (xs, 40 + (256 - sz) // 2), im)
    xs += sz + 34
pv.save(PREVIEW)
print("\n预览图: %s" % PREVIEW)
