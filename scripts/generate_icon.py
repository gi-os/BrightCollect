#!/usr/bin/env python3
"""Build BrightCollect's launcher mark.

Part of the unified Bright* icon set. Every mark in the collection is drawn on
the same 108x108 adaptive-icon canvas, inside the same 18..90 safe zone, at the
same two stroke weights, in white on black and nothing else. The Light Phone
III panel is black and white; a mark with a mid-tone in it dithers.

Edit MARK below and re-run. The vector outputs need nothing but the standard
library. The raster outputs need Pillow and cairosvg, and are skipped with a
message if those are missing, because the vectors are what actually ship on
API 26 and up.

    python3 scripts/generate_icon.py
"""

import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---- the mark ---------------------------------------------------------------
# Each entry is (path data, stroke width, even-odd fill). A stroke width of 0
# means the path is filled instead of stroked.

MARK = [
    # The die-cut border: what makes a shape a sticker rather than a picture of one.
    ('M54,21 C59.17,21.19 64.56,24.87 69.18,27.71 C73.8,30.54 79.41,33.61 81.72,38 C84.03,42.38 82.9,48.58 83.04,54 C83.18,59.42 84.94,66.21 82.58,70.5 C80.21,74.79 73.61,77.25 68.85,79.72 C64.09,82.2 59.22,84.87 54,85.35 C48.78,85.83 41.93,85.25 37.5,82.58 C33.07,79.91 30.06,74.11 27.42,69.35 C24.78,64.58 21.47,59.01 21.66,54 C21.85,49 25.81,43.89 28.56,39.31 C31.31,34.74 33.92,29.62 38.16,26.56 C42.4,23.51 48.83,20.81 54,21 Z', 5, False),
    # The object inside it, the same silhouette at two thirds the radius.
    ('M54,32.5 C57.37,32.62 60.88,35.02 63.89,36.87 C66.9,38.72 70.56,40.72 72.06,43.57 C73.57,46.43 72.83,50.47 72.92,54 C73.01,57.53 74.16,61.96 72.62,64.75 C71.08,67.54 66.78,69.15 63.67,70.76 C60.57,72.37 57.4,74.11 54,74.42 C50.6,74.74 46.14,74.36 43.25,72.62 C40.36,70.88 38.4,67.1 36.68,64 C34.96,60.89 32.81,57.26 32.93,54 C33.05,50.74 35.64,47.41 37.43,44.43 C39.22,41.45 40.92,38.11 43.68,36.13 C46.44,34.14 50.63,32.38 54,32.5 Z', 0, False),
]

# Where the mark is written, and at what viewport. 108 is the adaptive-icon
# canvas; 240 is the LightOS splash mark, which is the only place a LightOS
# tool can show a mark of its own.
TARGETS = [
    ('app/src/main/res/drawable/ic_launcher_foreground.xml', 108),
]

# Legacy rasters: (path, pixels, circular mask, inset, transparent plate).
# Inset shrinks the mark inside the plate - a legacy square icon gets no
# launcher mask, so it needs the margin the mask would otherwise have given it.
# A transparent plate is for an adaptive foreground layer, which is composited
# over the plate rather than carrying one of its own.
RASTERS = [
    ('app/src/main/res/mipmap-hdpi/ic_launcher.png', 72, False, 0.72, False),
    ('app/src/main/res/mipmap-hdpi/ic_launcher_round.png', 72, True, 0.72, False),
    ('app/src/main/res/mipmap-mdpi/ic_launcher.png', 48, False, 0.72, False),
    ('app/src/main/res/mipmap-mdpi/ic_launcher_round.png', 48, True, 0.72, False),
    ('app/src/main/res/mipmap-xhdpi/ic_launcher.png', 96, False, 0.72, False),
    ('app/src/main/res/mipmap-xhdpi/ic_launcher_round.png', 96, True, 0.72, False),
    ('app/src/main/res/mipmap-xxhdpi/ic_launcher.png', 144, False, 0.72, False),
    ('app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png', 144, True, 0.72, False),
    ('app/src/main/res/mipmap-xxxhdpi/ic_launcher.png', 192, False, 0.72, False),
    ('app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png', 192, True, 0.72, False),
]

# Files that are the same in every app: the black plate, and the adaptive-icon
# wrapper that points the launcher at the plate and the mark.
STATIC = [
    ('app/src/main/res/drawable/ic_launcher_background.xml', '<?xml version="1.0" encoding="utf-8"?>\n<!-- Solid black plate. The whole set is black and white; nothing else belongs here. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n    <path\n        android:pathData="M0,0 H108 V108 H0 Z"\n        android:fillColor="#000000" />\n</vector>\n'),
    ('app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml', '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n'),
    ('app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml', '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n'),
]

STROKE = ('        android:fillColor="#00000000"\n'
          '        android:strokeColor="#FFFFFF"\n'
          '        android:strokeWidth="%g"\n'
          '        android:strokeLineCap="round"\n'
          '        android:strokeLineJoin="round" />')

HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  BrightCollect launcher mark. One of the unified Bright* set: 108 canvas, 18..90
  safe zone, white on black, no greys and no colour anywhere.

  Generated by scripts/generate_icon.py - edit the geometry there, not here.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="%(vp)sdp"
    android:height="%(vp)sdp"
    android:viewportWidth="%(vp)s"
    android:viewportHeight="%(vp)s">
%(paths)s
</vector>
'''


def scale_path(d, k):
    """Multiply every number in a path by k.

    Safe on this data because every path is absolute and uniformly scaled, so
    arc rx/ry scale with everything else. The large-arc and sweep flags are 0
    or 1 and a naive pass would scale them into nonsense, so each arc command
    is matched whole and its three flag fields copied through untouched."""
    if k == 1.0:
        return d
    num = re.compile(r'-?\d*\.?\d+')
    arc = re.compile(r'A\s*(-?[\d.]+)\s*,?\s*(-?[\d.]+)\s+(-?[\d.]+)\s+([01])\s*,?\s*([01])\s+')

    def one(s):
        return ('%.3f' % (float(s) * k)).rstrip('0').rstrip('.')

    def plain(s):
        return num.sub(lambda m: one(m.group(0)), s)

    out, i = [], 0
    for m in arc.finditer(d):
        out.append(plain(d[i:m.start()]))
        out.append('A%s,%s %s %s %s ' % (one(m.group(1)), one(m.group(2)),
                                         m.group(3), m.group(4), m.group(5)))
        i = m.end()
    out.append(plain(d[i:]))
    return ''.join(out)


def render(vp):
    k = vp / 108.0
    body = []
    for d, w, even in MARK:
        pd = scale_path(d, k)
        if w == 0:
            ft = '\n        android:fillType="evenOdd"' if even else ''
            body.append('    <path\n        android:pathData="%s"\n'
                        '        android:fillColor="#FFFFFF"%s />' % (pd, ft))
        else:
            body.append('    <path\n        android:pathData="%s"\n%s'
                        % (pd, STROKE % (w * k)))
    return HEADER % {'vp': vp, 'paths': '\n'.join(body)}


def svg(inset=1.0, transparent=False):
    m = (1 - inset) * 54
    s = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">']
    if not transparent:
        s.append('<rect width="108" height="108" fill="#000000"/>')
    s += [
         '<g transform="translate(%.3f,%.3f) scale(%s)">' % (m, m, inset)]
    for d, w, even in MARK:
        if w == 0:
            fr = ' fill-rule="evenodd"' if even else ''
            s.append('<path d="%s" fill="#FFFFFF"%s/>' % (d, fr))
        else:
            s.append('<path d="%s" fill="none" stroke="#FFFFFF" stroke-width="%s" '
                     'stroke-linecap="round" stroke-linejoin="round"/>' % (d, w))
    s.append('</g></svg>')
    return ''.join(s)


def write(rel, text):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, 'w').write(text)
    print('wrote', rel)


def rasters():
    try:
        import io
        import cairosvg
        from PIL import Image, ImageDraw
    except ImportError:
        print('Pillow/cairosvg not installed - skipped the rasters. The adaptive '
              'icon is what ships on API 26 and up.')
        return
    for rel, px, round_, inset, transparent in RASTERS:
        raw = cairosvg.svg2png(bytestring=svg(inset, transparent).encode(),
                               output_width=px * 4, output_height=px * 4)
        im = Image.open(io.BytesIO(raw)).convert('RGBA')
        if round_:
            mask = Image.new('L', im.size, 0)
            ImageDraw.Draw(mask).ellipse([0, 0, im.size[0] - 1, im.size[1] - 1], fill=255)
            im.putalpha(mask)
        im = im.resize((px, px), Image.LANCZOS)
        p = os.path.join(ROOT, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        im.save(p, 'WEBP' if rel.endswith('.webp') else 'PNG')
        print('wrote', rel)


if __name__ == '__main__':
    for rel, vp in TARGETS:
        write(rel, render(vp))
    for rel, text in STATIC:
        write(rel, text)
    rasters()
