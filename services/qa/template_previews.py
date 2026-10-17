"""Regenerate the template previews the gateway embeds.

    python services/qa/template_previews.py <folder of compiled PDFs>

The PDFs are the ones CI compiles in the production image (the
``qa-reports-staging`` artifact, ``templates/<id>.pdf``), so a preview shows
exactly what a user's first compile shows. Each becomes
``services/backend-go/internal/templates/previews/<id>.jpg``, 720 pixels
wide. Page 1 is used unless it is nearly empty (a title page of a skeleton
template); then the fullest of pages 1 to 3, judged by JPEG size. Needs
poppler's ``pdftoppm``.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "backend-go" / "internal" / "templates" / "previews"
WIDTH = 720
QUALITY = 80
# A page 1 JPEG smaller than this is mostly white.
FULL_ENOUGH = 25_000


def render(pdf: Path, page: int, work: Path) -> Path | None:
    stem = work / f"{pdf.stem}-{page}"
    result = subprocess.run(
        ["pdftoppm", "-jpeg", "-jpegopt", f"quality={QUALITY}", "-f", str(page), "-l", str(page),
         "-scale-to", str(WIDTH), "-singlefile", str(pdf), str(stem)],
        capture_output=True, timeout=60,
    )
    image = stem.with_suffix(".jpg")
    return image if result.returncode == 0 and image.exists() else None


def main() -> int:
    if len(sys.argv) != 2 or not Path(sys.argv[1]).is_dir():
        print(__doc__)
        return 2
    if shutil.which("pdftoppm") is None:
        print("pdftoppm (poppler) is not installed", file=sys.stderr)
        return 1
    pdfs = sorted(Path(sys.argv[1]).glob("*.pdf"))
    if not pdfs:
        print("no PDFs found", file=sys.stderr)
        return 1
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        for pdf in pdfs:
            first = render(pdf, 1, work)
            if first is None:
                print(f"{pdf.stem}: could not render", file=sys.stderr)
                failed += 1
                continue
            chosen, page = first, 1
            if first.stat().st_size < FULL_ENOUGH:
                for n in (2, 3):
                    other = render(pdf, n, work)
                    if other is not None and other.stat().st_size > chosen.stat().st_size:
                        chosen, page = other, n
            shutil.copyfile(chosen, OUT / f"{pdf.stem}.jpg")
            print(f"{pdf.stem}: page {page}, {chosen.stat().st_size // 1000} kB")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
