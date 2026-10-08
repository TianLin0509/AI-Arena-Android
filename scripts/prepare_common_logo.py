"""Resize the selected common logo without redrawing its characters.

Usage: python scripts/prepare_common_logo.py PATH_TO_SELECTED_05
The output asset inventory is kept in artifacts, not committed.
"""
import hashlib
import json
from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageOps

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1])
original = Image.open(source).convert("RGB")
assert original.width == original.height
res = root / "app/src/main/res"
inventory = []

def save(image, relative):
    path = res / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    inventory.append({"path": relative, "size": list(image.size),
                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})

save(original.resize((1024, 1024), Image.Resampling.LANCZOS), "drawable-nodpi/ic_logo.png")
# Android adaptive icons have a 108 dp canvas; place the unchanged artwork in
# 60 dp at its centre, protecting ears/tails across circle/squircle masks.
foreground = Image.new("RGB", (648, 648), "white")
art = original.resize((360, 360), Image.Resampling.LANCZOS)
foreground.paste(art, (144, 144))
save(foreground, "drawable-nodpi/ic_launcher_foreground.png")
# Use the original dark outlines as the monochrome alpha mask. This retains
# the three animal profiles and table; the system supplies the actual colour.
alpha = ImageOps.grayscale(foreground).point(lambda value: max(0, min(255, (120 - value) * 5)))
mono = Image.new("RGBA", foreground.size, "white")
mono.putalpha(alpha)
save(mono, "drawable-nodpi/ic_launcher_monochrome.png")
for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    icon = original.resize((size, size), Image.Resampling.LANCZOS)
    save(icon, f"mipmap-{density}/ic_launcher.png")
    save(icon, f"mipmap-{density}/ic_launcher_round.png")

out = root / "artifacts"
out.mkdir(exist_ok=True)
(out / "20261008-common-logo-author-asset-inventory.json").write_text(json.dumps({
    "source": str(source), "sourceSize": list(original.size),
    "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "operation": "LANCZOS resize, white adaptive safe-area padding; monochrome dark-outline alpha",
    "assets": inventory,
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
# A review sheet shows normal/adaptive/monochrome rendering at desktop size.
sheet = Image.new("RGB", (960, 380), "#eeeef4")
sheet.paste(original.resize((300, 300), Image.Resampling.LANCZOS), (10, 40))
for x, themed in [(330, False), (650, True)]:
    crop = foreground.crop((108, 108, 540, 540)).resize((300, 300), Image.Resampling.LANCZOS)
    if themed:
        crop = Image.new("RGB", (300, 300), "#e3d9fa")
        a = alpha.crop((108, 108, 540, 540)).resize((300, 300), Image.Resampling.LANCZOS)
        crop.paste("#342c4c", (0, 0, 300, 300), a)
    mask = Image.new("L", (300, 300))
    ImageDraw.Draw(mask).ellipse((0, 0, 299, 299), fill=255)
    sheet.paste(crop, (x, 40), mask)
sheet.save(out / "20261008-common-logo-author-mask-preview.png")
