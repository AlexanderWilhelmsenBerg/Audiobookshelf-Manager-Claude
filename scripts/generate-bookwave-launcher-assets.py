"""Build deterministic Android launcher assets from the approved transparent BookWave master."""

from __future__ import annotations

import argparse
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image


DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
STORE_ICON_SIZE = 512
STORE_MARK_SIZE = 440
STORE_BACKGROUND = (11, 27, 37, 255)


def keep_largest_alpha_component(image: Image.Image) -> Image.Image:
    """Remove detached generation specks without changing the connected logo artwork."""
    rgba = np.asarray(image.convert("RGBA")).copy()
    alpha = rgba[:, :, 3]
    foreground = alpha >= 16
    height, width = foreground.shape
    seen = np.zeros_like(foreground, dtype=bool)
    largest: list[tuple[int, int]] = []

    for start_y, start_x in zip(*np.nonzero(foreground & ~seen), strict=True):
        if seen[start_y, start_x]:
            continue
        component: list[tuple[int, int]] = []
        queue = deque([(int(start_y), int(start_x))])
        seen[start_y, start_x] = True
        while queue:
            y, x = queue.popleft()
            component.append((y, x))
            for next_y, next_x in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if (
                    0 <= next_y < height
                    and 0 <= next_x < width
                    and foreground[next_y, next_x]
                    and not seen[next_y, next_x]
                ):
                    seen[next_y, next_x] = True
                    queue.append((next_y, next_x))
        if len(component) > len(largest):
            largest = component

    keep = np.zeros_like(foreground, dtype=bool)
    if largest:
        ys, xs = zip(*largest, strict=True)
        keep[np.asarray(ys), np.asarray(xs)] = True
    rgba[~keep, 3] = 0
    return Image.fromarray(rgba)


def fitted_layer(source: Image.Image, pixels_per_dp: float, monochrome: bool = False) -> Image.Image:
    layer_size = round(108 * pixels_per_dp)
    safe_size = round(66 * pixels_per_dp)
    alpha_box = source.getchannel("A").getbbox()
    if alpha_box is None:
        raise ValueError("The source has no visible pixels")
    cropped = source.crop(alpha_box)
    scale = min(safe_size / cropped.width, safe_size / cropped.height)
    target = (max(1, round(cropped.width * scale)), max(1, round(cropped.height * scale)))
    fitted = cropped.resize(target, Image.Resampling.LANCZOS)
    if monochrome:
        pixels = np.asarray(fitted).copy()
        pixels[:, :, :3] = 255
        fitted = Image.fromarray(pixels)
    layer = Image.new("RGBA", (layer_size, layer_size), (0, 0, 0, 0))
    layer.alpha_composite(fitted, ((layer_size - target[0]) // 2, (layer_size - target[1]) // 2))
    return layer


def store_icon(source: Image.Image) -> Image.Image:
    """Place the approved mark on its opaque brand field for the Google Play listing."""
    alpha_box = source.getchannel("A").getbbox()
    if alpha_box is None:
        raise ValueError("The source has no visible pixels")
    cropped = source.crop(alpha_box)
    scale = min(STORE_MARK_SIZE / cropped.width, STORE_MARK_SIZE / cropped.height)
    mark = cropped.resize(
        (round(cropped.width * scale), round(cropped.height * scale)),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGBA", (STORE_ICON_SIZE, STORE_ICON_SIZE), STORE_BACKGROUND)
    canvas.alpha_composite(mark, ((STORE_ICON_SIZE - mark.width) // 2, (STORE_ICON_SIZE - mark.height) // 2))
    return canvas


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("resource_root", type=Path)
    parser.add_argument(
        "--store-icon",
        type=Path,
        help="optional path for the separate 512 px Google Play listing PNG",
    )
    args = parser.parse_args()

    source = keep_largest_alpha_component(Image.open(args.source))

    if args.store_icon is not None:
        args.store_icon.parent.mkdir(parents=True, exist_ok=True)
        store_icon(source).save(args.store_icon, format="PNG", optimize=True, compress_level=9)
        if args.store_icon.stat().st_size > 1024 * 1024:
            raise ValueError("The Google Play listing icon exceeds 1 MiB")

    cropped = source.crop(source.getchannel("A").getbbox())
    header_canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    header_scale = min(230 / cropped.width, 230 / cropped.height)
    header = cropped.resize(
        (round(cropped.width * header_scale), round(cropped.height * header_scale)),
        Image.Resampling.LANCZOS,
    )
    header_canvas.alpha_composite(header, ((256 - header.width) // 2, (256 - header.height) // 2))
    header_path = args.resource_root / "drawable-nodpi" / "bookwave_logo_header.webp"
    header_path.parent.mkdir(parents=True, exist_ok=True)
    header_canvas.save(header_path, format="WEBP", lossless=True, method=6)

    for density, pixels_per_dp in DENSITIES.items():
        directory = args.resource_root / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        fitted_layer(source, pixels_per_dp).save(
            directory / "ic_launcher_bookwave_foreground.webp",
            format="WEBP",
            lossless=True,
            method=6,
        )
        fitted_layer(source, pixels_per_dp, monochrome=True).save(
            directory / "ic_launcher_bookwave_themed.webp",
            format="WEBP",
            lossless=True,
            method=6,
        )


if __name__ == "__main__":
    main()
