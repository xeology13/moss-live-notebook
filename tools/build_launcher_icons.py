#!/usr/bin/env python3
"""把一张 1024x1024 方图源文件生成为 Android 各密度图标资源。

用法：python3 tools/build_launcher_icons.py <source.png>
产物：app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/{ic_launcher,ic_launcher_round,ic_launcher_foreground}.png
并把 values/colors.xml 里 ic_launcher_background 更新为源图边缘平均色。
"""
import sys, os, re
from PIL import Image, ImageDraw, ImageChops

DENS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")


def edge_color(im):
    w, h = im.size
    pts = [(x, y) for x in range(0, w, 16) for y in (2, 6)] + \
          [(x, y) for x, y in [(2, y) for y in range(0, h, 16)] + [(6, y) for y in range(0, h, 16)]]
    pts = [(min(x, w - 1), min(y, h - 1)) for x, y in pts if 0 <= x < w and 0 <= y < h]
    n = len(pts)
    r = sum(im.getpixel(p)[0] for p in pts) // n
    g = sum(im.getpixel(p)[1] for p in pts) // n
    b = sum(im.getpixel(p)[2] for p in pts) // n
    return (r, g, b)


def edge_extend(im, pad, canvas):
    """把 im 放到 canvas×canvas 中心，四周用最近边缘像素延展补齐（保住渐变，不留纯色接缝）。"""
    w, h = im.size
    out = Image.new("RGBA", (canvas, canvas))
    # 四条边各自把 1px 边料拉伸到 pad 宽度
    top = im.crop((0, 0, w, 1)).resize((w, pad), Image.NEAREST)
    bot = im.crop((0, h - 1, w, h)).resize((w, pad), Image.NEAREST)
    left = im.crop((0, 0, 1, h)).resize((pad, h), Image.NEAREST)
    right = im.crop((w - 1, 0, w, h)).resize((pad, h), Image.NEAREST)
    out.paste(top, (pad, 0))
    out.paste(bot, (pad, pad + h))
    out.paste(left, (0, pad))
    out.paste(right, (pad + w, pad))
    # 四角用角点像素填
    for sx, sy, dx, dy in ((0, 0, 0, 0), (w - 1, 0, pad + w, 0), (0, h - 1, 0, pad + h), (w - 1, h - 1, pad + w, pad + h)):
        c = Image.new("RGBA", (pad, pad), im.getpixel((sx, sy)))
        out.paste(c, (dx, dy))
    out.paste(im, (pad, pad))
    return out


def circle_mask(size):
    ss = 4
    m = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main(src):
    im = Image.open(src).convert("RGB")
    bg = edge_color(im)
    print("edge bg =", bg)

    for dens, scale in DENS.items():
        d = os.path.join(RES, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        legacy = round(48 * scale)
        # legacy square icon
        sq = im.resize((legacy, legacy), Image.LANCZOS).convert("RGBA")
        sq.save(os.path.join(d, "ic_launcher.png"))
        # round icon
        rnd = sq.copy()
        rnd.putalpha(circle_mask(legacy))
        rnd.save(os.path.join(d, "ic_launcher_round.png"))
        # adaptive foreground: 108dp canvas, artwork 88/108 居中，四周用「边缘像素延展」补齐，
        # 不用纯色填充 —— 否则渐变边缘与填充色之间会出现可见矩形色差带
        canvas = round(108 * scale)
        art = round(canvas * 88 / 108)
        patch = im.resize((art, art), Image.LANCZOS).convert("RGBA")
        pad = (canvas - art) // 2
        fg = edge_extend(patch, pad, canvas)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        print(f"{dens}: legacy {legacy}px, foreground {canvas}px, art {art}px")

    # update background color resource
    cp = os.path.join(RES, "values", "colors.xml")
    hexc = "#%02X%02X%02X" % bg
    txt = open(cp).read()
    new = re.sub(r'<color name="ic_launcher_background">#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?</color>',
                 f'<color name="ic_launcher_background">{hexc}</color>', txt)
    open(cp, "w").write(new)
    print("ic_launcher_background ->", hexc)


if __name__ == "__main__":
    main(sys.argv[1])
