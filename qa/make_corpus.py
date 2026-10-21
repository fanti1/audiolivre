"""Builds the test corpus: PDFs laid out the way real books are, from text whose correct reading is known.

Tuning a parser against one book teaches it that book. These documents carry the features that actually break
extraction — drop caps, running heads that repeat on every page, page numbers, scene breaks, words hyphenated
across a line, a column or a page, footnotes, captions and two-column reading order — over text written here,
so the expected output is not a guess.

    python qa/make_corpus.py

Writes app/src/androidTest/assets/corpus/<name>.pdf and <name>.expected.txt (one block per line, prefixed by
its kind). qa/check_extraction.py compares what the app extracts on the device against those files.
"""

import sys
from pathlib import Path

from reportlab.lib.pagesizes import A5, A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfgen import canvas

sys.path.insert(0, str(Path(__file__).parent))
from corpus_source import DOCUMENTS  # noqa: E402

OUT = Path(__file__).parent.parent / "app" / "src" / "androidTest" / "assets" / "corpus"
# A word longer than this may be broken across a line with a hyphen, the way a typesetter would.
MIN_HYPHEN_WORD = 8


def wrap(text, font, size, width, hyphenate=False):
    """Greedy line breaking, optionally splitting a long word with a hyphen like justified typesetting does."""
    words = text.split()
    lines, line = [], []
    for word in words:
        trial = " ".join(line + [word])
        if pdfmetrics.stringWidth(trial, font, size) <= width or not line:
            line.append(word)
            continue
        if hyphenate and len(word) >= MIN_HYPHEN_WORD:
            room = width - pdfmetrics.stringWidth(" ".join(line) + " ", font, size)
            cut = _hyphen_point(word, font, size, room)
            if cut:
                line.append(word[:cut] + "-")
                lines.append(" ".join(line))
                line = [word[cut:]]
                continue
        lines.append(" ".join(line))
        line = [word]
    if line:
        lines.append(" ".join(line))
    return lines


def _hyphen_point(word, font, size, room):
    # Leave at least two letters on each side, as hyphenation rules require.
    for cut in range(len(word) - 2, 1, -1):
        if pdfmetrics.stringWidth(word[:cut] + "-", font, size) <= room:
            return cut
    return None


class Page:
    """A sheet being filled top to bottom, with the furniture that parsers must learn to drop."""

    def __init__(self, pdf, size, margin, leading, font="Times-Roman", size_pt=11):
        self.pdf, self.width, self.height = pdf, size[0], size[1]
        self.margin, self.leading, self.font, self.size_pt = margin, leading, font, size_pt
        self.number = 1
        self.running_head = ""
        self.footnotes = []
        self.y = 0
        self._start()

    @property
    def text_width(self):
        return self.width - 2 * self.margin

    def _start(self):
        self.y = self.height - self.margin
        self.footnotes = []

    def _finish(self):
        # A running head on every page, alternating sides the way a printed book does, plus a page number:
        # both repeat throughout the file and must never end up inside a paragraph.
        if self.running_head:
            self.pdf.setFont("Times-Italic", 9)
            head_y = self.height - self.margin + 14
            if self.number % 2 == 0:
                self.pdf.drawString(self.margin, head_y, self.running_head)
            else:
                self.pdf.drawRightString(self.width - self.margin, head_y, self.running_head)
        if self.footnotes:
            y = self.margin + 10 + len(self.footnotes) * 11
            self.pdf.setLineWidth(0.4)
            self.pdf.line(self.margin, y + 6, self.margin + 120, y + 6)
            self.pdf.setFont("Times-Roman", 8)
            for n, note in enumerate(self.footnotes, 1):
                for line in wrap(f"{n} {note}", "Times-Roman", 8, self.text_width):
                    self.pdf.drawString(self.margin, y, line)
                    y -= 10
        self.pdf.setFont("Times-Roman", 9)
        self.pdf.drawCentredString(self.width / 2, self.margin - 18, str(self.number))
        self.pdf.showPage()
        self.number += 1

    def space_left(self):
        return self.y - (self.margin + 14 + len(self.footnotes) * 11)

    def need(self, height):
        if self.space_left() < height:
            self._finish()
            self._start()

    def newpage(self):
        self._finish()
        self._start()

    def lines(self, lines, font=None, size=None, indent=0.0, x=None, width=None):
        font, size = font or self.font, size or self.size_pt
        for n, line in enumerate(lines):
            self.need(self.leading)
            self.pdf.setFont(font, size)
            left = (x if x is not None else self.margin) + (indent if n == 0 else 0)
            self.pdf.drawString(left, self.y - size, line)
            self.y -= self.leading

    def gap(self, amount):
        self.y -= amount


def drop_cap(page, text):
    """A large initial letter with the first lines wrapped around it — where parsers lose the first word."""
    cap, rest = text[0], text[1:]
    cap_size = page.size_pt * 3.2
    cap_width = pdfmetrics.stringWidth(cap, "Times-Bold", cap_size) + 4
    page.need(page.leading * 4)
    top = page.y
    page.pdf.setFont("Times-Bold", cap_size)
    page.pdf.drawString(page.margin, top - cap_size * 0.92, cap)

    # The first three lines are short and start to the right of the capital.
    narrow = page.text_width - cap_width
    wrapped = wrap(rest, page.font, page.size_pt, narrow, hyphenate=True)
    beside, after = wrapped[:3], " ".join(wrapped[3:])
    for line in beside:
        page.pdf.setFont(page.font, page.size_pt)
        page.pdf.drawString(page.margin + cap_width, page.y - page.size_pt, line)
        page.y -= page.leading
    if after:
        page.lines(wrap(after, page.font, page.size_pt, page.text_width, hyphenate=True))


def build_novel(blocks, path):
    pdf = canvas.Canvas(str(path), pagesize=A5)
    page = Page(pdf, A5, margin=48, leading=15.5, size_pt=10.5)
    chapter_title = ""
    first_para_of_chapter = False

    for kind, text in blocks:
        if kind == "heading":
            if text.lower().startswith("capítulo"):
                if page.number > 1:
                    page.newpage()
                page.gap(40)
                page.lines([text], font="Times-Bold", size=13)
                page.gap(6)
                first_para_of_chapter = True
            else:
                chapter_title = text
                page.running_head = text
                page.lines([text], font="Times-Italic", size=11.5)
                page.gap(12)
        elif kind == "scene":
            page.gap(8)
            page.lines(["* * *"], font="Times-Roman", size=11)
            page.gap(8)
        else:
            if first_para_of_chapter:
                drop_cap(page, text)
                first_para_of_chapter = False
            else:
                # A paragraph opens with an indent, which is how the parser tells one from a wrapped line.
                page.lines(wrap(text, page.font, page.size_pt, page.text_width, hyphenate=True), indent=14)
    page._finish()
    pdf.save()
    return chapter_title


def build_science(blocks, path):
    pdf = canvas.Canvas(str(path), pagesize=A5)
    page = Page(pdf, A5, margin=50, leading=15, size_pt=10.5)
    page.running_head = "Como o cérebro mede o tempo"
    for kind, text in blocks:
        if kind == "heading":
            page.gap(10)
            page.lines(wrap(text, "Times-Bold", 12, page.text_width), font="Times-Bold", size=12)
            page.gap(4)
        elif kind == "list":
            page.lines(wrap("• " + text, page.font, page.size_pt, page.text_width - 14), x=page.margin + 14)
        elif kind == "caption":
            page.gap(6)
            page.lines(wrap(text, "Times-Italic", 9, page.text_width), font="Times-Italic", size=9)
            page.gap(6)
        elif kind == "note":
            page.footnotes.append(text)
        else:
            page.lines(wrap(text, page.font, page.size_pt, page.text_width, hyphenate=True), indent=14)
    page._finish()
    pdf.save()


def build_article(blocks, path):
    """Two columns on A4: the reading order a naive extractor gets wrong."""
    pdf = canvas.Canvas(str(path), pagesize=A4)
    margin, gutter = 56, 22
    column_width = (A4[0] - 2 * margin - gutter) / 2
    page = Page(pdf, A4, margin=margin, leading=12.5, size_pt=9.5)
    page.running_head = "Leitor automático de PDF — avaliação"

    column = 0
    top = A4[1] - margin
    bottom = margin + 16
    page.y = top

    def column_x():
        return margin + column * (column_width + gutter)

    def ensure(height):
        nonlocal column
        if page.y - height >= bottom:
            return
        if column == 0:
            column = 1
            page.y = top
        else:
            column = 0
            page.newpage()
            page.y = top

    def put(lines, font, size, indent=0.0):
        for n, line in enumerate(lines):
            ensure(page.leading)
            pdf.setFont(font, size)
            pdf.drawString(column_x() + (indent if n == 0 else 0), page.y - size, line)
            page.y -= page.leading

    # The title spans both columns, as article titles do.
    for line in wrap(blocks[0][1], "Times-Bold", 14, A4[0] - 2 * margin):
        pdf.setFont("Times-Bold", 14)
        pdf.drawCentredString(A4[0] / 2, page.y - 14, line)
        page.y -= 18
    page.y -= 14
    top = page.y

    for kind, text in blocks[1:]:
        if kind == "heading":
            page.y -= 6
            put(wrap(text, "Times-Bold", 10.5, column_width), "Times-Bold", 10.5)
            page.y -= 3
        else:
            put(wrap(text, "Times-Roman", 9.5, column_width, hyphenate=True), "Times-Roman", 9.5, indent=12)
    page._finish()
    pdf.save()


def expected(blocks):
    """What a correct extraction returns: the blocks themselves, with the page furniture gone.

    A footnote is set at the foot of its page, so a reader reaches it after the page's body text, not at the
    point of the marker; the expected order puts notes last.
    """
    out = []
    for kind, text in blocks if not any(k == "note" for k, _ in blocks) else (
            [b for b in blocks if b[0] != "note"] + [b for b in blocks if b[0] == "note"]):
        if kind == "scene":
            out.append("scene\t* * *")
        elif kind == "list":
            out.append(f"para\t• {text}")
        else:
            out.append(f"{'heading' if kind == 'heading' else 'para'}\t{text}")
    return "\n".join(out) + "\n"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    builders = {"romance": build_novel, "ciencia": build_science, "artigo": build_article}
    for name, blocks in DOCUMENTS.items():
        pdf_path = OUT / f"{name}.pdf"
        builders[name](blocks, pdf_path)
        (OUT / f"{name}.expected.txt").write_text(expected(blocks), encoding="utf-8")
        print(f"{pdf_path.name}: {pdf_path.stat().st_size // 1024} KB, {len(blocks)} blocos esperados")


if __name__ == "__main__":
    main()
