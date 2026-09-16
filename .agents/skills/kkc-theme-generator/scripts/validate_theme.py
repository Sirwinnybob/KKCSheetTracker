#!/usr/bin/env python3
"""Validates a KKCSheetTracker theme JSON's status colors: hue-locked bands,
pairwise distinctness, and background/text contrast. Exits 0 on pass, 1 on
fail, printing every violation found (not just the first)."""
import colorsys
import json
import sys

# (min_hue, max_hue) in degrees, 0-360. bad wraps around 0/360.
HUE_BANDS = {
    "complete": [(90, 150)],
    "bad": [(0, 15), (345, 360)],
    "skip": [(20, 45)],
    "inProgress": [(200, 230)],
    "remakeBg": [(265, 300)],
    "miscBg": [(200, 230)],
}
# notStarted is gray: checked by low saturation instead of a hue band.
# 0.17 (not 0.15) because the app's own built-in grays sit right at the edge:
# light notStarted #78909C ~= 0.154, dark notStarted #90A4AE ~= 0.156.
NOT_STARTED_MAX_SATURATION = 0.17

# 24.0 (not 60.0): the app's own dark-mode inProgress (#64B5F6) and miscBg
# (#42A5F5) are two shades of the *same* locked blue band (200-230) by
# design — miscBg is meant to be a close, lighter relative of inProgress, not
# a separately distinguishable color. Their real RGB distance is ~37.6, so 60
# rejected the app's own built-in palette. 24.0 keeps every other real pair
# (closest is light-mode bad/skip at ~65.6) comfortably distinct while
# allowing that one same-family pair.
MIN_PAIRWISE_DISTANCE = 24.0  # Euclidean distance in 0-255 RGB space
MIN_CONTRAST_RATIO = 4.5  # WCAG AA for normal text

# Fixed onBackground/onSurface from Theme.kt — these never move with a theme,
# so a generated background/surface must stay legible against them.
FIXED_TEXT_COLORS = {
    "light": {"onBackground": "#122033", "onSurface": "#162236"},
    "dark": {"onBackground": "#E8F0FA", "onSurface": "#EBF2FC"},
}


def hex_to_rgb(hex_str):
    h = hex_str.lstrip("#")
    if len(h) == 8:
        h = h[2:]  # drop leading alpha
    if len(h) != 6:
        raise ValueError(f"Not a 6 or 8 digit hex color: {hex_str}")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def rgb_to_hue_sat(rgb):
    r, g, b = (c / 255.0 for c in rgb)
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    return h * 360.0, s


def hue_in_bands(hue, bands):
    return any(lo <= hue <= hi for lo, hi in bands)


def euclidean_distance(rgb_a, rgb_b):
    return sum((a - b) ** 2 for a, b in zip(rgb_a, rgb_b)) ** 0.5


def relative_luminance(rgb):
    def channel(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(rgb_a, rgb_b):
    l1, l2 = relative_luminance(rgb_a), relative_luminance(rgb_b)
    lighter, darker = max(l1, l2), min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)


def validate_status_object(status_obj, label):
    errors = []
    parsed = {}
    for key, bands in HUE_BANDS.items():
        if key not in status_obj:
            continue
        rgb = hex_to_rgb(status_obj[key])
        parsed[key] = rgb
        hue, _ = rgb_to_hue_sat(rgb)
        if not hue_in_bands(hue, bands):
            errors.append(
                f"[{label}] '{key}' hue {hue:.1f} deg is outside its locked band {bands}"
            )
    if "notStarted" in status_obj:
        rgb = hex_to_rgb(status_obj["notStarted"])
        parsed["notStarted"] = rgb
        _, sat = rgb_to_hue_sat(rgb)
        if sat > NOT_STARTED_MAX_SATURATION:
            errors.append(
                f"[{label}] 'notStarted' saturation {sat:.2f} exceeds gray threshold "
                f"{NOT_STARTED_MAX_SATURATION}"
            )

    keys = list(parsed.keys())
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            dist = euclidean_distance(parsed[keys[i]], parsed[keys[j]])
            if dist < MIN_PAIRWISE_DISTANCE:
                errors.append(
                    f"[{label}] '{keys[i]}' and '{keys[j]}' are too close "
                    f"(distance {dist:.1f}, need >= {MIN_PAIRWISE_DISTANCE})"
                )
    return errors


def validate_palette_contrast(palette_obj, mode, errors):
    if "background" not in palette_obj:
        return
    bg_rgb = hex_to_rgb(palette_obj["background"])
    for text_key, text_hex in FIXED_TEXT_COLORS[mode].items():
        ratio = contrast_ratio(bg_rgb, hex_to_rgb(text_hex))
        if ratio < MIN_CONTRAST_RATIO:
            errors.append(
                f"[{mode}] background contrast against fixed {text_key} is {ratio:.2f}, "
                f"need >= {MIN_CONTRAST_RATIO}"
            )


def validate_theme(theme):
    errors = []
    status = theme.get("status", {})
    errors += validate_status_object(status, "light/dark status")
    for mode in ("light", "dark"):
        palette = theme.get(mode, {})
        validate_palette_contrast(palette, mode, errors)
    return errors


def main():
    if len(sys.argv) != 2:
        print("Usage: validate_theme.py <theme.json>", file=sys.stderr)
        sys.exit(2)
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        theme = json.load(f)
    errors = validate_theme(theme)
    if errors:
        print(f"FAIL — {len(errors)} issue(s):")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)
    print("PASS")
    sys.exit(0)


if __name__ == "__main__":
    main()
