#!/usr/bin/env python3
"""Self-tests for validate_theme.py — run with: python3 test_validate_theme.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from validate_theme import validate_theme, hex_to_rgb, contrast_ratio, euclidean_distance


GOOD_THEME = {
    "light": {"primary": "#1E5FAF", "background": "#FFFFFF", "surface": "#FFFFFF"},
    "dark": {"primary": "#79B2FF", "background": "#000000", "surface": "#162438"},
    "status": {
        "complete": "#388E3C",
        "bad": "#C62828",
        "skip": "#E65100",
        "inProgress": "#1565C0",
        "notStarted": "#78909C",
        "remakeBg": "#8E24AA",
        # #2A78D1 (not the fabricated #1976D2): this is the app's own
        # progressGradientStart/End base color (KKCColors.kt), and it sits
        # ~33 RGB units from inProgress — the same same-band-but-close
        # relationship as the real dark-mode inProgress/miscBg pair
        # (#64B5F6/#42A5F5, ~37.6), which is what MIN_PAIRWISE_DISTANCE is
        # calibrated against.
        "miscBg": "#2A78D1",
    },
}


class ValidateThemeTest(unittest.TestCase):
    def test_good_theme_passes(self):
        self.assertEqual([], validate_theme(GOOD_THEME))

    def test_bad_status_hue_outside_locked_band_fails(self):
        theme = {**GOOD_THEME, "status": {**GOOD_THEME["status"], "bad": "#00FF00"}}
        errors = validate_theme(theme)
        self.assertTrue(any("'bad' hue" in e for e in errors))

    def test_two_status_colors_too_close_fails(self):
        theme = {**GOOD_THEME, "status": {**GOOD_THEME["status"], "skip": GOOD_THEME["status"]["bad"]}}
        errors = validate_theme(theme)
        self.assertTrue(any("too close" in e for e in errors))

    def test_notstarted_too_saturated_fails(self):
        theme = {**GOOD_THEME, "status": {**GOOD_THEME["status"], "notStarted": "#FF0000"}}
        errors = validate_theme(theme)
        self.assertTrue(any("saturation" in e for e in errors))

    def test_low_contrast_background_fails(self):
        # #DDDDDD is deceptively close to white and still has ~12:1 contrast
        # against the dark fixed text colors, so it can never trigger this
        # check; #808080 (mid-gray, ~4.15:1 against onBackground) actually
        # drops below the 4.5 WCAG AA floor.
        theme = {**GOOD_THEME, "light": {**GOOD_THEME["light"], "background": "#808080"}}
        errors = validate_theme(theme)
        self.assertTrue(any("contrast" in e for e in errors))

    def test_hex_to_rgb_handles_argb_alpha_prefix(self):
        self.assertEqual((0x33, 0x44, 0x55), hex_to_rgb("#AA334455"))

    def test_contrast_ratio_is_symmetric(self):
        a, b = hex_to_rgb("#FFFFFF"), hex_to_rgb("#000000")
        self.assertAlmostEqual(contrast_ratio(a, b), contrast_ratio(b, a))

    def test_euclidean_distance_zero_for_identical_colors(self):
        rgb = hex_to_rgb("#123456")
        self.assertEqual(0.0, euclidean_distance(rgb, rgb))


if __name__ == "__main__":
    unittest.main()
