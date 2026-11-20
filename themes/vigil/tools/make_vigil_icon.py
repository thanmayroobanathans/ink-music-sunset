"""Vigil launcher icon for the themed ink music build: a pale moon over a rain-soaked city at night.

One drawing, emitted three ways: an SVG (for previews) and Android vector drawables for the adaptive icon's
background (the sky), foreground (moon, cloud, skyline, lit windows, rain) and monochrome (moon and skyline).
Coordinates are the adaptive icon's 108 x 108 viewport; the safe zone is the circle r=33 at (54, 54).

usage: python3 make_vigil_icon.py <out_dir>
"""
import os
import random
import sys

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
W = 108
SKY = [(0.00, "#0A0D14"), (0.55, "#161D2B"), (1.00, "#222C3E")]
MOON = [(0.00, "#F4F0E8"), (1.00, "#B9C0CA")]
CITY = "#05070B"
LIT = "#E3A23B"
RAIN = "#B8C6D8"
CLOUD = "#121824"
MX, MY, MR = 63.0, 40.0, 14.0


def f(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def rect(x, y, w, h):
    return f"M{f(x)},{f(y)} L{f(x + w)},{f(y)} L{f(x + w)},{f(y + h)} L{f(x)},{f(y + h)} Z"


SKY_PATH = rect(0, 0, W, W)
MOON_PATH = (f"M{f(MX - MR)},{f(MY)} A{f(MR)},{f(MR)} 0 1 1 {f(MX + MR)},{f(MY)} "
             f"A{f(MR)},{f(MR)} 0 1 1 {f(MX - MR)},{f(MY)} Z")
# a long cloud drawn across the moon's lower half
CLOUD_PATH = ("M30,44.5 Q38,41 46,43 Q53,40.5 60,43.2 Q68,41 77,43.6 Q85,42.4 92,45 "
              "L92,47.4 Q80,46.6 70,47.6 Q58,46.4 48,47.6 Q38,46.8 30,47.4 Z")

# the skyline, left to right: (x, width, top y, roof) - roof: flat, spire, step or point
BUILDINGS = [
    (0, 9, 66, "flat"), (8, 7, 58, "step"), (14, 9, 63, "flat"), (22, 6, 52, "spire"),
    (27, 10, 60, "flat"), (36, 8, 55, "point"), (43, 11, 64, "flat"), (53, 7, 50, "spire"),
    (59, 10, 58, "step"), (68, 9, 62, "flat"), (76, 6, 54, "spire"), (81, 10, 61, "flat"),
    (90, 8, 57, "step"), (97, 11, 65, "flat"),
]


def building(x, w, top, roof):
    base = 108
    if roof == "spire":
        return (f"M{f(x)},{f(base)} L{f(x)},{f(top + 5)} L{f(x + w / 2)},{f(top - 7)} "
                f"L{f(x + w)},{f(top + 5)} L{f(x + w)},{f(base)} Z")
    if roof == "point":
        return (f"M{f(x)},{f(base)} L{f(x)},{f(top + 2)} L{f(x + w / 2)},{f(top - 3)} "
                f"L{f(x + w)},{f(top + 2)} L{f(x + w)},{f(base)} Z")
    if roof == "step":
        return (f"M{f(x)},{f(base)} L{f(x)},{f(top + 3)} L{f(x + w * .25)},{f(top + 3)} L{f(x + w * .25)},{f(top)} "
                f"L{f(x + w * .75)},{f(top)} L{f(x + w * .75)},{f(top + 3)} L{f(x + w)},{f(top + 3)} L{f(x + w)},{f(base)} Z")
    return rect(x, top, w, base - top)


CITY_PATH = " ".join(building(*b) for b in BUILDINGS)

rnd = random.Random(7)
windows = []
for x, w, top, roof in BUILDINGS:
    y = top + 6
    while y < 100:
        cx = x + 1.5
        while cx + 1.6 < x + w - 1:
            if rnd.random() < .22:
                windows.append(rect(cx, y, 1.6, 2.0))
            cx += 3.0
        y += 4.0
WINDOWS = " ".join(windows)

drops = []
for _ in range(34):
    x = rnd.uniform(-4, 112)
    y = rnd.uniform(-6, 100)
    ln = rnd.uniform(7, 15)
    drops.append(f"M{f(x)},{f(y)} L{f(x - ln * .18)},{f(y + ln)}")
RAIN_PATH = " ".join(drops)


def svg_grad(gid, stops, y1, y2):
    st = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
    return f'<linearGradient id="{gid}" x1="0" y1="{y1}" x2="0" y2="{y2}" gradientUnits="userSpaceOnUse">{st}</linearGradient>'


def svg(size=432):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">
<defs>{svg_grad("sky", SKY, 0, W)}{svg_grad("moon", MOON, MY - MR, MY + MR)}<clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath></defs>
<g clip-path="url(#m)">
<path d="{SKY_PATH}" fill="url(#sky)"/>
<path d="{MOON_PATH}" fill="url(#moon)"/>
<path d="{CLOUD_PATH}" fill="{CLOUD}" fill-opacity="0.92"/>
<path d="{CITY_PATH}" fill="{CITY}"/>
<path d="{WINDOWS}" fill="{LIT}"/>
<path d="{RAIN_PATH}" stroke="{RAIN}" stroke-opacity="0.45" stroke-width="0.5" stroke-linecap="round" fill="none"/>
</g></svg>'''


def vd_grad(stops, y1, y2):
    items = "\n".join(f'                <item android:offset="{o}" android:color="#FF{c[1:]}" />' for o, c in stops)
    return f'''            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="54" android:startY="{f(y1)}" android:endX="54" android:endY="{f(y2)}">
{items}
                </gradient>
            </aapt:attr>'''


HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- VIGIL theme (an unofficial build): {what} (made by make_vigil_icon.py) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
'''

sky_xml = HEAD.format(what="the icon's night sky, its background") + f'''    <path android:pathData="{SKY_PATH}">
{vd_grad(SKY, 0, W)}
    </path>
</vector>
'''
fg_xml = HEAD.format(what="the icon's moon, cloud, city and rain, its foreground") + f'''    <path android:pathData="{MOON_PATH}">
{vd_grad(MOON, MY - MR, MY + MR)}
    </path>
    <path android:fillColor="#EB{CLOUD[1:]}" android:pathData="{CLOUD_PATH}" />
    <path android:fillColor="#FF{CITY[1:]}" android:pathData="{CITY_PATH}" />
    <path android:fillColor="#FF{LIT[1:]}" android:pathData="{WINDOWS}" />
    <path android:strokeColor="#73{RAIN[1:]}" android:strokeWidth="0.5" android:strokeLineCap="round" android:pathData="{RAIN_PATH}" />
</vector>
'''
mono_xml = HEAD.format(what="the icon's monochrome (themed icons): the moon over the city") + f'''    <path android:fillColor="#FFFFFFFF" android:pathData="{MOON_PATH}" />
    <path android:fillColor="#FFFFFFFF" android:pathData="{CITY_PATH}" />
</vector>
'''
for name, text in [("icon_sky.xml", sky_xml), ("icon_sunset.xml", fg_xml), ("icon_sunset_mono.xml", mono_xml),
                   ("icon_preview.svg", svg())]:
    open(os.path.join(OUT, name), "w").write(text)
print("written to", OUT)
