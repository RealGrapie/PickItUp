# Store badges for the README in the style of "Get it on Google Play": black, grey border, white icon + text.
# Text is converted to paths, so GitHub needs no fonts. Needs fonttools and, in <fonts dir>,
# GoogleSansFlex.ttf + Roboto.ttf (google/fonts, ofl/). Usage: python badges.py <fonts dir> docs/badges
import sys, os
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

fonts, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)

def load(path, **axes):
    f = TTFont(path)
    return instantiateVariableFont(f, axes) if "fvar" in f else f

BIG = load(os.path.join(fonts, "GoogleSansFlex.ttf"), wght=500, wdth=100, opsz=18, ROND=0, slnt=0)
SMALL = load(os.path.join(fonts, "Roboto.ttf"), wght=500, wdth=100)

def text_path(font, text, x, baseline, cap):
    """SVG path of `text` with its cap height = `cap` units; returns (d, advance width)."""
    gs = font.getGlyphSet(); cmap = font.getBestCmap()
    scale = cap / font["OS/2"].sCapHeight
    pen = SVGPathPen(gs)
    pos = 0
    for ch in text:
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (scale, 0, 0, -scale, x + pos * scale, baseline)))
        pos += font["hmtx"][g][0]
    return pen.getCommands(), pos * scale

# Core Devices (Pebble App Store) mark, from apps.repebble.com/favicon.ico (256 px): ring, red dot, red dash
def core_icon(x, y, size):
    s = size / 210  # content spans 26..235 of the 256 px icon
    t = lambda v: v * s
    ox, oy = x - t(25), y - t(26)
    return (f'<g transform="translate({ox:.3f} {oy:.3f}) scale({s:.5f})">'
            '<circle cx="127.5" cy="127.5" r="71" fill="none" stroke="#fff" stroke-width="12"/>'
            '<circle cx="208.5" cy="46.5" r="16.3" fill="none" stroke="#FB5C4A" stroke-width="9.5"/>'
            '<rect x="109" y="225" width="38" height="11" rx="5.5" fill="#FB5C4A"/></g>')

# Rebble mark, from apps.rebble.io/images/favicon.svg (512 px): black -> white, white background dropped
REBBLE_PATHS = [
    ("#fff", "M 6.62486,208.00062 C 2.29356,223.25822 0,239.37772 0,256.00062 0,310.51244 24.93866,359.3135 63.9998,391.62686 V 512 H 287.99938 V 391.62686 c 4.3008,-3.55944 8.4305,-7.3041 12.37546,-11.25012 l 35.6243,35.62453 v -95.99978 h -95.99956 l 37.875,37.8752 c -26.02884,26.04139 -62.01476,42.12458 -101.875,42.12458 C 96.28033,400.00127 32,335.72096 32,256.0017 c 0,-16.83308 2.8527,-33.00373 8.12502,-48 H 6.62528 Z M 95.9998,412.74686 c 24.01792,12.31468 51.21576,19.25016 79.99978,19.25016 28.78379,0 55.98377,-6.93482 79.9998,-19.25016 v 67.24996 H 95.9998 Z"),
    ("#fff", "M 63.9955,0.001073 V 120.37418 c -4.3008,3.55798 -8.4305,7.30624 -12.37546,11.25016 L 15.9955,95.9998 v 95.99978 h 95.9998 L 74.05844,154.0625 c 26.03627,-26.03496 62.07341,-42.0627 101.93686,-42.0627 79.71925,0 143.99958,64.28031 143.99958,143.99958 0,16.85224 -2.90346,32.98878 -8.1875,48 h 33.49971 c 4.34069,-15.27363 6.68737,-31.35789 6.68737,-48 0,-54.51182 -24.93868,-103.31288 -63.99978,-135.62624 V 0 H 63.99508 Z m 32,32 H 255.99508 V 99.251 C 231.97696,86.93631 204.7791,80.00086 175.9953,80.00086 c -28.78824,0 -55.98399,6.93291 -79.9998,19.25014 z"),
    ("#FF4700", "m 263.42974,183.12597 -87.24992,53.99977 -51.56265,-33.37472 -17.37494,26.81262 68.56214,44.37544 104.43689,-64.62484 -16.81256,-27.1872 z"),
]
def rebble_icon(x, y, size):
    s = size / 512  # 352 x 512 units (x 0..352 inside the translated group)
    paths = "".join(f'<path fill="{c}" d="{d}"/>' for c, d in REBBLE_PATHS)
    return f'<g transform="translate({x + (size - 352 * s) / 2:.3f} {y:.3f}) scale({s:.5f})">{paths}</g>'

# GitHub mark (simple-icons 13.0.0, 24 px)
GITHUB = "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
def github_icon(x, y, size):
    return f'<g transform="translate({x:.3f} {y:.3f}) scale({size / 24:.5f})"><path fill="#fff" d="{GITHUB}"/></g>'

H = 40
def badge(icon, caption, name, file, label):
    cap_d, cap_w = text_path(SMALL, caption, 41, 13.6, 5.6)
    name_d, name_w = text_path(BIG, name, 40.6, 30.4, 12.4)
    w = round(41 + max(cap_w, name_w) + 10, 1)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {H}" width="{w * 4.5:.0f}" height="{H * 4.5:.0f}" role="img" aria-label="{label}">'
           f'<title>{label}</title>'
           f'<rect x="0.5" y="0.5" width="{w - 1}" height="{H - 1}" rx="5.5" fill="#000" stroke="#A6A6A6"/>'
           f'{icon}<path fill="#fff" d="{cap_d}"/><path fill="#fff" d="{name_d}"/></svg>')
    open(os.path.join(out, file), "w", encoding="utf-8").write(svg)
    print(file, w)

for lang, caption, avail, gh in (("en", "GET IT ON", "Get it on", "GET IT ON"), ("ru", "ДОСТУПНО В", "Доступно в", "ДОСТУПНО НА")):
    badge(github_icon(9.5, 7.5, 25), gh, "GitHub", f"github-{lang}.svg", f"{avail} GitHub" if lang == "en" else "Доступно на GitHub")
    badge(core_icon(9.5, 7.5, 25), caption, "Pebble App Store", f"pebble-appstore-{lang}.svg", f"{avail} Pebble App Store")
    badge(rebble_icon(9.5, 7, 26), caption, "Rebble App Store", f"rebble-{lang}.svg", f"{avail} Rebble App Store")
