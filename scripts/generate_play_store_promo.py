from pathlib import Path
from typing import Iterable

from PIL import Image, ImageDraw, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parents[1]
SCREENSHOT_DIR = ROOT / "play-store" / "screenshots" / "phone"
OUTPUT_DIR = ROOT / "play-store" / "presentation"
ICON_PATH = ROOT / "play-store" / "icon-512.png"

WIDTH = 1024
HEIGHT = 500
BG = "#0C222B"
BG_2 = "#10313D"
TEXT = "#F3F5F4"
MUTED = "#BBD0D4"
AQUA = "#72D1DE"
AQUA_2 = "#2DA8B7"
AMBER = "#E4A94D"
AMBER_2 = "#C8892F"
CARD = "#132A34"


SLIDES = [
    {
        "output": "01-recherche.png",
        "title": "Trouvez un canyon\nen quelques secondes",
        "subtitle": "Recherche rapide, filtres utiles et niveaux de difficulte visibles au premier coup d'oeil.",
        "eyebrow": "RECHERCHE",
        "screenshot": "01-phone-search-furon.png",
        "accent": AQUA,
    },
    {
        "output": "02-fiche-canyon.png",
        "title": "Consultez la fiche\ncomplete du parcours",
        "subtitle": "Localisation, cotation, temps, altitude et infos terrain dans un seul ecran clair.",
        "eyebrow": "FICHE CANYON",
        "screenshot": "02-phone-canyon-detail-furon.png",
        "accent": AMBER,
    },
    {
        "output": "03-carte.png",
        "title": "Explorez les canyons\nsur la carte",
        "subtitle": "Visualisez rapidement les zones actives et passez de la carte a la fiche en un geste.",
        "eyebrow": "CARTE",
        "screenshot": "04-phone-map.png",
        "accent": AQUA,
    },
    {
        "output": "04-debits.png",
        "title": "Verifiez les debits\navant de partir",
        "subtitle": "Les derniers retours terrain sont accessibles tout de suite pour mieux preparer la sortie.",
        "eyebrow": "CONDITIONS",
        "screenshot": "05-phone-home.png",
        "accent": "#55D56E",
    },
    {
        "output": "05-preparation.png",
        "title": "Preparez vos sorties\navec plus de confiance",
        "subtitle": "Recherche, carte et conditions se combinent pour choisir plus vite le bon canyon.",
        "eyebrow": "DESCENTE-CANYON",
        "screenshot": "01-phone-search-furon.png",
        "accent": AMBER,
    },
]

GLOBAL_SLIDE = {
    "output": "feature-graphic-global.png",
    "eyebrow": "DESCENTE-CANYON",
    "title": "Preparez vos sorties\ncanyon plus vite",
    "subtitle": "Recherche, carte et conditions utiles pour choisir plus facilement le bon parcours.",
    "chips": [
        ("Recherche rapide", AQUA),
        ("Carte interactive", "#82D9E6"),
        ("Derniers debits", "#58D66C"),
    ],
}


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = []
    if bold:
        candidates.extend(
            [
                r"C:\Windows\Fonts\bahnschrift.ttf",
                r"C:\Windows\Fonts\segoeuib.ttf",
                r"C:\Windows\Fonts\arialbd.ttf",
            ]
        )
    else:
        candidates.extend(
            [
                r"C:\Windows\Fonts\segoeui.ttf",
                r"C:\Windows\Fonts\arial.ttf",
            ]
        )

    for path in candidates:
        font_path = Path(path)
        if font_path.exists():
            return ImageFont.truetype(str(font_path), size=size)
    return ImageFont.load_default()


def rounded_rectangle_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle((0, 0, size[0], size[1]), radius=radius, fill=255)
    return mask


def add_gradient_background(canvas: Image.Image) -> None:
    draw = ImageDraw.Draw(canvas)
    for y in range(HEIGHT):
        t = y / max(HEIGHT - 1, 1)
        r = int(12 + (16 - 12) * t)
        g = int(34 + (49 - 34) * t)
        b = int(43 + (61 - 43) * t)
        draw.line((0, y, WIDTH, y), fill=(r, g, b, 255))

    glow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    gdraw.ellipse((-160, -90, 360, 360), fill=(64, 175, 193, 42))
    gdraw.ellipse((590, -140, 1150, 320), fill=(223, 167, 73, 40))
    gdraw.ellipse((700, 260, 1120, 640), fill=(64, 175, 193, 26))
    glow = glow.filter(ImageFilter.GaussianBlur(28))
    canvas.alpha_composite(glow)

    pattern = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    pdraw = ImageDraw.Draw(pattern)
    for offset in range(-120, 580, 48):
        pdraw.arc((40, offset, 560, offset + 220), start=250, end=355, fill=(160, 212, 220, 22), width=2)
    pattern = pattern.filter(ImageFilter.GaussianBlur(0.4))
    canvas.alpha_composite(pattern)


def draw_badge(draw: ImageDraw.ImageDraw, xy: tuple[int, int], text: str, color: str, font: ImageFont.ImageFont) -> None:
    x, y = xy
    pad_x = 16
    pad_y = 9
    bbox = draw.textbbox((0, 0), text, font=font)
    w = bbox[2] - bbox[0] + pad_x * 2
    h = bbox[3] - bbox[1] + pad_y * 2
    draw.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=color)
    draw.text((x + pad_x, y + pad_y - 1), text, font=font, fill=BG)


def fit_screenshot(path: Path, height: int) -> Image.Image:
    screenshot = Image.open(path).convert("RGBA")
    ratio = height / screenshot.height
    new_size = (round(screenshot.width * ratio), round(screenshot.height * ratio))
    return screenshot.resize(new_size, Image.LANCZOS)


def add_phone_mockup(canvas: Image.Image, screenshot_path: Path, x: int, y: int, h: int) -> None:
    screenshot = fit_screenshot(screenshot_path, h - 28)
    phone_w = screenshot.width + 20
    phone_h = screenshot.height + 20

    shadow = Image.new("RGBA", (phone_w + 40, phone_h + 40), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(shadow)
    sdraw.rounded_rectangle((20, 24, phone_w + 12, phone_h + 16), radius=36, fill=(0, 0, 0, 145))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    canvas.alpha_composite(shadow, (x - 18, y - 6))

    body = Image.new("RGBA", (phone_w, phone_h), (0, 0, 0, 0))
    bdraw = ImageDraw.Draw(body)
    bdraw.rounded_rectangle((0, 0, phone_w, phone_h), radius=34, fill="#0A1318")

    screen = Image.new("RGBA", (phone_w - 12, phone_h - 12), (0, 0, 0, 0))
    mask = rounded_rectangle_mask(screen.size, 28)
    screen.alpha_composite(screenshot, ((screen.width - screenshot.width) // 2, (screen.height - screenshot.height) // 2))
    screen.putalpha(mask)
    body.alpha_composite(screen, (6, 6))

    notch_w = 84
    notch_h = 10
    bdraw.rounded_rectangle(
        ((phone_w - notch_w) // 2, 12, (phone_w + notch_w) // 2, 12 + notch_h),
        radius=notch_h // 2,
        fill="#1B2A31",
    )
    canvas.alpha_composite(body, (x, y))


def add_icon_mark(canvas: Image.Image, x: int, y: int) -> None:
    if not ICON_PATH.exists():
        return
    icon = Image.open(ICON_PATH).convert("RGBA").resize((58, 58), Image.LANCZOS)
    bubble = Image.new("RGBA", (80, 80), (0, 0, 0, 0))
    draw = ImageDraw.Draw(bubble)
    draw.rounded_rectangle((0, 0, 80, 80), radius=24, fill=(19, 42, 52, 220), outline=(116, 185, 196, 42), width=2)
    bubble.alpha_composite(icon, (11, 11))
    canvas.alpha_composite(bubble, (x, y))


def wrap_text(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.ImageFont, max_width: int) -> Iterable[str]:
    words = text.split()
    lines: list[str] = []
    current = ""
    for word in words:
        trial = word if not current else f"{current} {word}"
        bbox = draw.textbbox((0, 0), trial, font=font)
        if bbox[2] - bbox[0] <= max_width:
            current = trial
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def draw_text_block(canvas: Image.Image, slide: dict) -> None:
    draw = ImageDraw.Draw(canvas)
    eyebrow_font = load_font(22, bold=True)
    title_font = load_font(56, bold=True)
    body_font = load_font(22)
    meta_font = load_font(20, bold=True)

    left = 72
    top = 56
    add_icon_mark(canvas, left, top)
    draw_badge(draw, (left + 96, top + 14), slide["eyebrow"], slide["accent"], eyebrow_font)

    title_y = top + 106
    for line in slide["title"].split("\n"):
        draw.text((left, title_y), line, font=title_font, fill=TEXT)
        title_y += 66

    paragraph_y = title_y + 16
    for line in wrap_text(draw, slide["subtitle"], body_font, 500):
        draw.text((left, paragraph_y), line, font=body_font, fill=MUTED)
        paragraph_y += 30

    card_y = max(372, paragraph_y + 14)
    draw.rounded_rectangle((left, card_y, left + 440, card_y + 72), radius=24, fill=CARD, outline=(114, 183, 194, 26), width=2)
    draw.text((left + 24, card_y + 16), "Recherche, carte et conditions terrain", font=meta_font, fill=TEXT)
    draw.text((left + 24, card_y + 42), "Concu pour preparer vos sorties canyon.", font=load_font(17), fill=MUTED)


def draw_chip(draw: ImageDraw.ImageDraw, x: int, y: int, text: str, color: str) -> int:
    font = load_font(20, bold=True)
    bbox = draw.textbbox((0, 0), text, font=font)
    w = bbox[2] - bbox[0] + 34
    h = bbox[3] - bbox[1] + 20
    draw.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=(19, 42, 52, 220), outline=(114, 183, 194, 40), width=2)
    draw.ellipse((x + 12, y + h // 2 - 5, x + 22, y + h // 2 + 5), fill=color)
    draw.text((x + 30, y + 9), text, font=font, fill=TEXT)
    return w


def generate_feature_graphic() -> None:
    canvas = Image.new("RGBA", (WIDTH, HEIGHT), BG)
    add_gradient_background(canvas)
    draw = ImageDraw.Draw(canvas)

    right_glow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(right_glow)
    gdraw.rounded_rectangle((560, 28, 984, 472), radius=52, fill=(14, 31, 39, 118), outline=(127, 214, 228, 28), width=2)
    right_glow = right_glow.filter(ImageFilter.GaussianBlur(0.5))
    canvas.alpha_composite(right_glow)

    left = 68
    top = 54
    add_icon_mark(canvas, left, top)
    eyebrow_font = load_font(22, bold=True)
    title_font = load_font(50, bold=True)
    body_font = load_font(24)
    draw_badge(draw, (left + 96, top + 14), GLOBAL_SLIDE["eyebrow"], AMBER, eyebrow_font)

    title_y = top + 104
    for line in GLOBAL_SLIDE["title"].split("\n"):
        draw.text((left, title_y), line, font=title_font, fill=TEXT)
        title_y += 60

    body_y = title_y + 14
    for line in wrap_text(draw, GLOBAL_SLIDE["subtitle"], body_font, 470):
        draw.text((left, body_y), line, font=body_font, fill=MUTED)
        body_y += 34

    chip_x = left
    chip_y = body_y + 26
    for text, color in GLOBAL_SLIDE["chips"]:
        width = draw_chip(draw, chip_x, chip_y, text, color)
        chip_x += width + 12

    add_phone_mockup(canvas, SCREENSHOT_DIR / "01-phone-search-furon.png", 600, 118, 272)
    add_phone_mockup(canvas, SCREENSHOT_DIR / "04-phone-map.png", 720, 52, 360)
    add_phone_mockup(canvas, SCREENSHOT_DIR / "05-phone-home.png", 846, 126, 260)

    out_path = OUTPUT_DIR / GLOBAL_SLIDE["output"]
    canvas.convert("RGB").save(out_path, format="PNG", optimize=True)


def generate_slide(slide: dict) -> None:
    canvas = Image.new("RGBA", (WIDTH, HEIGHT), BG)
    add_gradient_background(canvas)

    panel = Image.new("RGBA", (340, 436), (0, 0, 0, 0))
    pdraw = ImageDraw.Draw(panel)
    pdraw.rounded_rectangle((0, 0, 340, 436), radius=42, fill=(14, 31, 39, 146), outline=(127, 214, 228, 24), width=2)
    panel = panel.filter(ImageFilter.GaussianBlur(0.5))
    canvas.alpha_composite(panel, (628, 30))

    draw_text_block(canvas, slide)
    add_phone_mockup(canvas, SCREENSHOT_DIR / slide["screenshot"], 694, 34, 430)

    out_path = OUTPUT_DIR / slide["output"]
    canvas.convert("RGB").save(out_path, format="PNG", optimize=True)


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    generate_feature_graphic()
    print(f"Generated {OUTPUT_DIR / GLOBAL_SLIDE['output']}")


if __name__ == "__main__":
    main()
