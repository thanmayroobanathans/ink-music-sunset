"""Sunset Drive launcher icon for the themed ink music build.

One drawing, emitted three ways: an SVG (for previews) and Android vector drawables for the
adaptive icon's background (sky), foreground (sun, sea, palms) and monochrome (sun and sea only).
Coordinates are the adaptive icon's 108 x 108 viewport; the safe zone is the circle r=33 at (54, 54).

usage: python3 make_icon.py <out_dir>
"""
import math
import os
import sys

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

W = 108
CX, CY, R = 54.0, 60.0, 23.0        # the sun
HORIZON = 69.0                      # where the sea starts
SKY = [(0.00, "#170A29"), (0.45, "#4A1766"), (0.78, "#B8327F"), (1.00, "#FF6F7D")]
SUN = [(0.00, "#FFE27A"), (0.45, "#FF9A4A"), (1.00, "#FF3D7F")]
SEA = "#170A29"
PALM = "#0E0519"
GLINT = "#FF8FC4"


def f(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def sun_bands():
    """The sun cut into bands, the gaps widening towards the horizon (the 80s sun)."""
    top = CY - R
    gaps = [(52.0, 53.2), (56.2, 57.8), (60.6, 62.6), (65.0, 67.4)]   # (from, to) y of each gap
    edges = [top]
    for a, b in gaps:
        edges += [a, b]
    edges.append(HORIZON)
    bands = [(edges[i], edges[i + 1]) for i in range(0, len(edges), 2)]

    def half(y):
        return math.sqrt(max(R * R - (y - CY) ** 2, 0.0))

    paths = []
    for y1, y2 in bands:
        l1, r1 = CX - half(y1), CX + half(y1)
        l2, r2 = CX - half(y2), CX + half(y2)
        if y1 <= top + 1e-6:   # the cap: an arc over the top
            d = (f"M{f(l2)},{f(y2)} A{f(R)},{f(R)} 0 0 1 {f(r2)},{f(y2)} Z" if y2 <= CY else
                 f"M{f(l2)},{f(y2)} A{f(R)},{f(R)} 0 0 1 {f(CX)},{f(top)} A{f(R)},{f(R)} 0 0 1 {f(r2)},{f(y2)} Z")
        else:
            d = (f"M{f(l1)},{f(y1)} L{f(r1)},{f(y1)} A{f(R)},{f(R)} 0 0 1 {f(r2)},{f(y2)} "
                 f"L{f(l2)},{f(y2)} A{f(R)},{f(R)} 0 0 1 {f(l1)},{f(y1)} Z")
        paths.append(d)
    return " ".join(paths)


def leaf(cx, cy, angle, length, droop, width):
    """One frond: a curved blade from the crown, its tip drooping."""
    a = math.radians(angle)
    tx = cx + length * math.cos(a)
    ty = cy + length * math.sin(a) + droop
    # the blade's midline bows upward; its two edges are quadratic curves either side of that bow
    mx = (cx + tx) / 2
    my = (cy + ty) / 2 - length * 0.22
    nx, ny = -(ty - cy), (tx - cx)
    n = math.hypot(nx, ny) or 1
    nx, ny = nx / n * width, ny / n * width
    return (f"M{f(cx)},{f(cy)} Q{f(mx + nx)},{f(my + ny)} {f(tx)},{f(ty)} "
            f"Q{f(mx - nx * .35)},{f(my - ny * .35)} {f(cx)},{f(cy)} Z")


def palm(base_x, base_y, crown_x, crown_y, trunk_w, scale, flip=False):
    """A palm: a curved, tapering trunk and seven fronds."""
    s = -1 if flip else 1
    bend_x = (base_x + crown_x) / 2 + s * 5 * scale
    bend_y = (base_y + crown_y) / 2
    w0, w1 = trunk_w, trunk_w * 0.45
    trunk = (f"M{f(base_x - w0)},{f(base_y)} Q{f(bend_x - w0 * .7)},{f(bend_y)} {f(crown_x - w1)},{f(crown_y)} "
             f"L{f(crown_x + w1)},{f(crown_y)} Q{f(bend_x + w0 * .7)},{f(bend_y)} {f(base_x + w0)},{f(base_y)} Z")
    fronds = []
    for ang, ln, dr in [(-168, 17, 11), (-135, 15, 6), (-97, 10, 1), (-58, 15, 6), (-14, 17, 11),
                        (22, 13, 9), (155, 13, 9)]:
        fronds.append(leaf(crown_x, crown_y, ang if not flip else 180 - ang, ln * scale, dr * scale, 2.9 * scale))
    # coconuts
    nuts = "".join(
        f"M{f(crown_x + dx * scale - 1.2 * scale)},{f(crown_y + 1.6 * scale)} "
        f"a{f(1.2 * scale)},{f(1.2 * scale)} 0 1 0 {f(2.4 * scale)},0 a{f(1.2 * scale)},{f(1.2 * scale)} 0 1 0 {f(-2.4 * scale)},0 Z "
        for dx in (-1.3, 1.1))
    return trunk + " " + " ".join(fronds) + " " + nuts


SEA_PATH = f"M0,{f(HORIZON)} L{W},{f(HORIZON)} L{W},{W} L0,{W} Z"
GLINTS = " ".join(
    f"M{f(CX - w)},{f(y)} L{f(CX + w)},{f(y)} L{f(CX + w)},{f(y + h)} L{f(CX - w)},{f(y + h)} Z"
    for y, w, h in [(72.0, 15.0, 1.3), (75.6, 10.5, 1.1), (78.8, 6.5, 1.0), (81.6, 3.5, 0.9)])
PALMS = palm(25, 84, 31, 47, 1.9, 1.0) + " " + palm(85, 84, 80, 56, 1.5, 0.78, flip=True)
SUN_PATH = sun_bands()
SKY_PATH = f"M0,0 L{W},0 L{W},{W} L0,{W} Z"


def svg_grad(gid, stops, y1, y2):
    st = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
    return f'<linearGradient id="{gid}" x1="0" y1="{y1}" x2="0" y2="{y2}" gradientUnits="userSpaceOnUse">{st}</linearGradient>'


def svg(mask_circle=True, size=432):
    clip = f'<clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath>' if mask_circle else ""
    g = 'clip-path="url(#m)"' if mask_circle else ""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">
<defs>{svg_grad("sky", SKY, 0, HORIZON)}{svg_grad("sun", SUN, CY - R, HORIZON)}{clip}</defs>
<g {g}>
<path d="{SKY_PATH}" fill="url(#sky)"/>
<path d="{SUN_PATH}" fill="url(#sun)"/>
<path d="{SEA_PATH}" fill="{SEA}"/>
<path d="{GLINTS}" fill="{GLINT}"/>
<path d="{PALMS}" fill="{PALM}"/>
</g></svg>'''


def vd_grad(stops, y1, y2):
    items = "\n".join(f'                <item android:offset="{o}" android:color="#FF{c[1:]}" />' for o, c in stops)
    return f'''            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="54" android:startY="{f(y1)}" android:endX="54" android:endY="{f(y2)}">
{items}
                </gradient>
            </aapt:attr>'''


HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- SUNSET DRIVE theme (an unofficial build): {what} (made by make_icon.py) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
'''

sky_xml = HEAD.format(what="the icon's sky, its background") + f'''    <path android:pathData="{SKY_PATH}">
{vd_grad(SKY, 0, HORIZON)}
    </path>
</vector>
'''

fg_xml = HEAD.format(what="the icon's sun, sea and palms, its foreground") + f'''    <path android:pathData="{SUN_PATH}">
{vd_grad(SUN, CY - R, HORIZON)}
    </path>
    <path android:fillColor="{SEA}" android:pathData="{SEA_PATH}" />
    <path android:fillColor="{GLINT}" android:pathData="{GLINTS}" />
    <path android:fillColor="{PALM}" android:pathData="{PALMS}" />
</vector>
'''

mono_xml = HEAD.format(what="the icon's monochrome (themed icons): the sun and its glints") + f'''    <path android:fillColor="#FFFFFFFF" android:pathData="{SUN_PATH}" />
    <path android:fillColor="#FFFFFFFF" android:pathData="{GLINTS}" />
</vector>
'''

open(os.path.join(OUT, "icon_sky.xml"), "w").write(sky_xml)
open(os.path.join(OUT, "icon_sunset.xml"), "w").write(fg_xml)
open(os.path.join(OUT, "icon_sunset_mono.xml"), "w").write(mono_xml)
open(os.path.join(OUT, "icon_preview.svg"), "w").write(svg())
open(os.path.join(OUT, "icon_preview_square.svg"), "w").write(svg(mask_circle=False))
print("written to", OUT)
