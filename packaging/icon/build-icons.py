#!/usr/bin/env python3
"""DearNotes のアイコンを作る。

    python3 packaging/icon/build-icons.py

macOS で動かす（.icns は iconutil で作る）。要るもの: rsvg-convert と ImageMagick（brew install librsvg imagemagick）。
作るもの:
  packaging/icon/DearNotes.svg          元の絵（macOS の形）
  packaging/mac/DearNotes.icns          jpackage の --icon（macOS）
  packaging/win/DearNotes.ico           jpackage の --icon（Windows）
  src/main/resources/app/dearnotes/icon-*.png   ウィンドウのアイコン

小さいサイズ（32 以下）は線を太く少なくした絵にする。
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 色は src/main/resources/app/dearnotes/ui/dearnotes.css に合わせる
INK = "#2f3437"
ACCENT = "#2e6fd8"
LINE = "#cfccc5"
HEART = "#e5574f"


def svg(tile, detail, shadow):
    """tile: (x, 幅, 角の丸み)。detail: 'full' か 'small'。shadow: 角丸の正方形に影を付けるか。"""
    x, w, r = tile
    # 紙とハートは、角丸の正方形の大きさに合わせて置く（元の絵は x=100, w=824）
    s = w / 824
    t = f'translate({x} {x}) scale({s}) translate(-100 -100)'
    tile_filter = ' filter="url(#tile-shadow)"' if shadow else ''
    if detail == 'full':
        body = f'''
    <rect x="252" y="200" width="520" height="624" rx="56" fill="url(#paper)" filter="url(#paper-shadow)"/>
    <rect x="322" y="290" width="300" height="52" rx="26" fill="{INK}"/>
    <circle cx="340" cy="430" r="18" fill="{ACCENT}"/>
    <rect x="384" y="414" width="318" height="32" rx="16" fill="{LINE}"/>
    <circle cx="340" cy="520" r="18" fill="{ACCENT}"/>
    <rect x="384" y="504" width="270" height="32" rx="16" fill="{LINE}"/>
    <circle cx="340" cy="610" r="18" fill="{ACCENT}"/>
    <rect x="384" y="594" width="200" height="32" rx="16" fill="{LINE}"/>
    <path filter="url(#heart-shadow)" fill="{HEART}" stroke="#ffffff" stroke-width="22" stroke-linejoin="round"
          d="M700 812 C 620 752 560 702 560 640 C 560 598 592 568 632 568 C 662 568 686 584 700 608
             C 714 584 738 568 768 568 C 808 568 840 598 840 640 C 840 702 780 752 700 812 Z"/>'''
    else:
        # 16〜32 px: 影なし。見出しと2本の太い行、大きめのハート
        body = f'''
    <rect x="232" y="180" width="560" height="664" rx="64" fill="#ffffff"/>
    <rect x="320" y="290" width="380" height="92" rx="46" fill="{INK}"/>
    <rect x="320" y="450" width="380" height="72" rx="36" fill="{LINE}"/>
    <rect x="320" y="590" width="200" height="72" rx="36" fill="{LINE}"/>
    <path fill="{HEART}" stroke="#ffffff" stroke-width="34" stroke-linejoin="round"
          d="M690 850 C 600 782 534 726 534 656 C 534 608 570 574 616 574 C 650 574 676 592 690 618
             C 704 592 730 574 764 574 C 810 574 846 608 846 656 C 846 726 780 782 690 850 Z"/>'''
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
  <!-- build-icons.py が作る。手で直さない -->
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#4a8ae6"/>
      <stop offset="1" stop-color="#2459b8"/>
    </linearGradient>
    <linearGradient id="paper" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#ffffff"/>
      <stop offset="1" stop-color="#f4f2ed"/>
    </linearGradient>
    <filter id="tile-shadow" x="-10%" y="-10%" width="120%" height="125%">
      <feDropShadow dx="0" dy="12" stdDeviation="14" flood-color="#000" flood-opacity="0.28"/>
    </filter>
    <filter id="paper-shadow" x="-15%" y="-15%" width="130%" height="130%">
      <feDropShadow dx="0" dy="10" stdDeviation="16" flood-color="#0b2a66" flood-opacity="0.35"/>
    </filter>
    <filter id="heart-shadow" x="-20%" y="-20%" width="140%" height="140%">
      <feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#5a1410" flood-opacity="0.30"/>
    </filter>
  </defs>
  <rect x="{x}" y="{x}" width="{w}" height="{w}" rx="{r}" fill="url(#bg)"{tile_filter}/>
  <g transform="{t}">{body}
  </g>
</svg>
'''


# macOS: Apple の格子どおり、1024 の中に 824 の角丸の正方形と影
MAC = (100, 824, 185)
# Windows とウィンドウのアイコン: 影なしで大きく描く
WIN = (40, 944, 200)


def render(svg_text, size, out, work):
    src = work / f'{abs(hash((svg_text, size)))}.svg'
    src.write_text(svg_text)
    subprocess.run(['rsvg-convert', '-w', str(size), '-h', str(size), str(src), '-o', str(out)], check=True)


def main():
    for tool in ('rsvg-convert', 'magick', 'iconutil'):
        if shutil.which(tool) is None:
            sys.exit(f'{tool} が見つからない。brew install librsvg imagemagick（iconutil は macOS に入っている）')

    (ROOT / 'packaging/icon/DearNotes.svg').write_text(svg(MAC, 'full', True))

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)

        # macOS: .iconset を作って iconutil で .icns にする
        iconset = work / 'DearNotes.iconset'
        iconset.mkdir()
        for size in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                px = size * scale
                detail = 'small' if px <= 32 else 'full'
                name = f'icon_{size}x{size}' + ('@2x' if scale == 2 else '') + '.png'
                render(svg(MAC, detail, px > 32), px, iconset / name, work)
        (ROOT / 'packaging/mac').mkdir(parents=True, exist_ok=True)
        subprocess.run(['iconutil', '-c', 'icns', str(iconset), '-o', str(ROOT / 'packaging/mac/DearNotes.icns')],
                       check=True)

        # Windows: 1つの .ico に 16〜256 を入れる
        pngs = []
        for px in (16, 20, 24, 32, 40, 48, 64, 128, 256):
            out = work / f'win-{px}.png'
            render(svg(WIN, 'small' if px <= 32 else 'full', False), px, out, work)
            pngs.append(str(out))
        (ROOT / 'packaging/win').mkdir(parents=True, exist_ok=True)
        subprocess.run(['magick', *pngs, str(ROOT / 'packaging/win/DearNotes.ico')], check=True)

        # ウィンドウのアイコン（Windows のタイトルバーとタスクバー）
        res = ROOT / 'src/main/resources/app/dearnotes'
        for px in (16, 32, 48, 64, 128, 256):
            render(svg(WIN, 'small' if px <= 32 else 'full', False), px, res / f'icon-{px}.png', work)

    print('作った: packaging/mac/DearNotes.icns, packaging/win/DearNotes.ico, '
          'src/main/resources/app/dearnotes/icon-*.png')


if __name__ == '__main__':
    main()
