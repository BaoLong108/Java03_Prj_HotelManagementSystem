# Noto Serif

Self-hosted original WOFF2 subsets provided by Google Fonts, downloaded 2026-10-16.

- Family: Noto Serif; variable weight range 400–600; normal and genuine italic faces.
- Subsets: Vietnamese, Latin Extended and Latin. Keep their `unicode-range` declarations in `src/fonts.css`, including combining tone marks and the Vietnamese đồng sign.
- Font CSS source: https://fonts.googleapis.com/css2?family=Noto+Serif:ital,wght@0,400..600;1,400..600&display=swap
- Upstream family/license: https://github.com/google/fonts/tree/main/ofl/notoserif
- License: SIL Open Font License 1.1, bundled in `OFL-NotoSerif.txt`. Font files are unchanged.

Vite bundles these files locally. The website does not request fonts from Google or a CDN at runtime. The variable files cover all existing serif heading weights; preserve the italic files used in the hero.

The prior Georgia stack fell back to Times New Roman for individual Vietnamese glyphs (verified through browser platform-font inspection). Use the shared `--serif` token for every display heading to avoid mixed glyph shapes and metrics.
