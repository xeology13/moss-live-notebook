#!/usr/bin/env python3
"""SettingsGlyph 几何自证：把 Kotlin 里 SettingsGlyph 的路径公式原样复刻后栅格化。

Kotlin 侧常量（size = 20dp 画布）：
  stroke = 1.35.dp
  s      = size.minDimension
  ro     = s * 0.42f      # 齿顶半径
  ri     = ro * 0.82f     # 齿根半径（齿深 = 0.18 * ro）
  rHole  = s * 0.15f      # 中心孔半径
  teeth  = 8, step = 360/teeth
  tipHalf  = step * 0.20  # 齿顶半角
  rootHalf = step * 0.30  # 齿根半角
  base = -90 度（12 点方向起画）
"""
import math

# ---- 与 Kotlin 一一对应 ----
S_DP = 20.0
STROKE_DP = 1.35
RO_F, RI_F, HOLE_F = 0.42, 0.82, 0.15
TEETH = 8
TIP_F, ROOT_F = 0.20, 0.30
BASE_DEG = -90.0

step = 360.0 / TEETH
tipHalf = step * TIP_F
rootHalf = step * ROOT_F


def polar(cx, cy, deg, r):
    a = math.radians(deg)
    return (cx + r * math.cos(a), cy + r * math.sin(a))


def build_polylines(px_per_dp, n_px):
    """返回描边折线（像素坐标）。画布 n_px x n_px，圆心在正中。"""
    s = S_DP * px_per_dp
    stroke = STROKE_DP * px_per_dp
    cx = cy = n_px / 2.0
    ro = s * RO_F
    ri = ro * RI_F
    rHole = s * HOLE_F

    lines = []  # 每条是折线点列

    # 齿形环：moveTo(第一颗齿的左根点) 然后依次
    #   arcTo(ri, th-step+rootHalf, step-2*rootHalf)  # 齿间齿根弧
    #   lineTo(ri -> ro 齿顶起点)                     # 齿左侧斜面
    #   arcTo(ro, th-tipHalf, 2*tipHalf)              # 齿顶弧
    #   lineTo(ro -> ri 齿右根点)                     # 齿右侧斜面
    # close()
    pts = []
    for i in range(TEETH):
        th = BASE_DEG + i * step
        # 齿根弧（细分 3 度以内）
        a0 = th - step + rootHalf
        a1 = th - rootHalf
        n = max(2, int(abs(a1 - a0) / 2.0) + 1)
        for k in range(n + 1):
            a = a0 + (a1 - a0) * k / n
            pts.append(polar(cx, cy, a, ri))
        # 齿左侧斜面：齿根 -> 齿顶
        pts.append(polar(cx, cy, th - tipHalf, ro))
        # 齿顶弧
        b0 = th - tipHalf
        b1 = th + tipHalf
        n = max(2, int(abs(b1 - b0) / 2.0) + 1)
        for k in range(1, n + 1):
            a = b0 + (b1 - b0) * k / n
            pts.append(polar(cx, cy, a, ro))
        # 齿右侧斜面：齿顶 -> 齿根
        pts.append(polar(cx, cy, th + rootHalf, ri))
    pts.append(pts[0])  # close()
    lines.append(pts)

    # 中心孔：独立 drawCircle(Stroke)
    hole = [polar(cx, cy, t, rHole) for t in range(0, 361, 3)]
    lines.append(hole)
    return lines, stroke


def seg_dist(p, a, b):
    px, py = p
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    L2 = dx * dx + dy * dy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L2))
    qx, qy = ax + t * dx, ay + t * dy
    return math.hypot(px - qx, py - qy)


def raster(px_per_dp, n_px, ss=6):
    """超采样覆盖率栅格化：coverage[i][j] in [0,1]"""
    lines, stroke = build_polylines(px_per_dp, n_px)
    half = stroke / 2.0
    # 1px 宽的像素框做抗锯齿：距离 < half 全实，> half+0.707 全空
    cov = [[0.0] * n_px for _ in range(n_px)]
    for j in range(n_px):
        for i in range(n_px):
            acc = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    p = (i + (sx + 0.5) / ss, j + (sy + 0.5) / ss)
                    d = min(seg_dist(p, a, b) for pl in lines for a, b in zip(pl, pl[1:]))
                    if d <= half - 0.7071:
                        acc += 1.0
                    elif d < half + 0.7071:
                        acc += (half + 0.7071 - d) / 1.4142
            cov[j][i] = acc / (ss * ss)
    return cov


RAMP = " .:-=+*#%@"


def ascii_art(cov):
    out = []
    for row in cov:
        out.append("".join(RAMP[min(len(RAMP) - 1, int(c * (len(RAMP) - 1) + 0.5))] for c in row))
    return "\n".join(out)


def ink_stats(cov):
    n = len(cov)
    texels = [(i, j) for j in range(n) for i in range(n) if cov[j][i] > 0.5]
    full = sum(1 for row in cov for c in row if c > 0.5)
    # 中心 3x3 区域是否留白 -> 证明有孔
    m = n // 2
    center_empty = all(cov[m + dj][m + di] < 0.15 for dj in (-1, 0, 1) for di in (-1, 0, 1))
    # 沿 12 点方向的径向剖面：看齿顶是否比齿根更靠外（齿形起伏）
    return {
        "px_per_dp": None,
        "inked_px": full,
        "total_px": n * n,
        "ink_ratio": round(full / (n * n), 3),
        "center_hole_blank": center_empty,
    }


if __name__ == "__main__":
    print("== 20dp @ 1.25 px/dp = 25px 栅格（工单要求的口径）==")
    c25 = raster(1.25, 25)
    print(ascii_art(c25))
    st = ink_stats(c25)
    print("\n统计:", st)

    print("\n== 径向剖面：半径上的墨迹（证明齿顶/齿根有起伏，不是实心圆饼）==")
    cx = cy = 12.5
    for name, r_dp in (("齿顶方向 r=8.4dp", 8.4), ("齿根方向 r=8.6dp", 6.89), ("孔壁 r=3.0dp", 3.0)):
        print(f"  {name}: ", end="")
        vals = []
        for k in range(-3, 4):
            a = -90 + k * 22.5
            x = int(round(cx + r_dp * 1.25 * math.cos(math.radians(a))))
            y = int(round(cy + r_dp * 1.25 * math.sin(math.radians(a))))
            x = min(24, max(0, x))
            y = min(24, max(0, y))
            vals.append(round(c25[y][x], 2))
        print(vals)

    print("\n== 50px 放大栅格（看清楚齿形/中心孔）==")
    c50 = raster(2.5, 50)
    print(ascii_art(c50))
