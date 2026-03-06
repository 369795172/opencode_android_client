#!/usr/bin/env python3
"""Generate Android launcher icons from iOS AppIcon.png."""
import os

try:
    from PIL import Image
except ImportError:
    print("Run: pip install Pillow")
    raise

# Paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
SOURCE = os.path.join(
    PROJECT_ROOT, "..", "opencode_ios_client",
    "OpenCodeClient", "OpenCodeClient", "Assets.xcassets", "AppIcon.appiconset", "AppIcon.png"
)
RES_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "res")

# Android mipmap sizes: canvas and logo (logo ~66% to match adaptive icon safe zone)
MIPMAP_SIZES = {
    "mipmap-mdpi": (48, 32),
    "mipmap-hdpi": (72, 48),
    "mipmap-xhdpi": (96, 64),
    "mipmap-xxhdpi": (144, 96),
    "mipmap-xxxhdpi": (192, 128),
}

# Adaptive icon foreground: 108dp canvas, 66dp safe zone (logo centered at 66dp)
FOREGROUND_SIZES = {
    "drawable-mdpi": (108, 66),
    "drawable-hdpi": (162, 99),
    "drawable-xhdpi": (216, 132),
    "drawable-xxhdpi": (324, 198),
    "drawable-xxxhdpi": (432, 264),
}


def resize_icons():
    if not os.path.exists(SOURCE):
        print(f"Source not found: {SOURCE}")
        return False

    with Image.open(SOURCE) as img:
        if img.mode != "RGBA":
            img = img.convert("RGBA")

        # Mipmap launcher icons: logo centered at ~66% size
        for folder, (canvas_size, logo_size) in MIPMAP_SIZES.items():
            out_dir = os.path.join(RES_DIR, folder)
            os.makedirs(out_dir, exist_ok=True)
            out_path = os.path.join(out_dir, "ic_launcher.png")
            logo = img.resize((logo_size, logo_size), Image.Resampling.LANCZOS)
            canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
            paste_x = (canvas_size - logo_size) // 2
            paste_y = (canvas_size - logo_size) // 2
            canvas.paste(logo, (paste_x, paste_y), logo)
            canvas.save(out_path, "PNG")
            print(f"Saved {out_path} ({canvas_size}x{canvas_size}, logo {logo_size}px)")

            # Round icon (same as regular for now)
            round_path = os.path.join(out_dir, "ic_launcher_round.png")
            canvas.save(round_path, "PNG")
            print(f"Saved {round_path}")

        # Adaptive icon foreground: logo at safe zone (66dp), centered in 108dp canvas
        for folder, (canvas_size, logo_size) in FOREGROUND_SIZES.items():
            out_dir = os.path.join(RES_DIR, folder)
            os.makedirs(out_dir, exist_ok=True)
            out_path = os.path.join(out_dir, "ic_launcher_foreground.png")
            logo = img.resize((logo_size, logo_size), Image.Resampling.LANCZOS)
            canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
            paste_x = (canvas_size - logo_size) // 2
            paste_y = (canvas_size - logo_size) // 2
            canvas.paste(logo, (paste_x, paste_y), logo)
            canvas.save(out_path, "PNG")
            print(f"Saved {out_path} ({canvas_size}x{canvas_size}, logo {logo_size}dp)")

    return True


if __name__ == "__main__":
    resize_icons()
