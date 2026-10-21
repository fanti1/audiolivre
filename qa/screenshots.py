"""Takes the README screenshots on an Android emulator, the way a person would use the app.

    python qa/screenshots.py [--avd audiolivre] [--apk app/build/outputs/apk/debug/app-debug.apk] [--keep-data]

Needs the Android SDK (adb, the emulator and an AVD; see CONTRIBUTING.md), the debug APK
(./gradlew assembleDebug), internet (books from Project Gutenberg, the narrator voice from GitHub) and
reportlab + Pillow. It downloads public-domain Brazilian classics in different formats (EPUB, Kindle, plain
text, and a PDF built here with a cover), adds each one through the app's own file picker, reads Dom Casmurro
aloud and saves the README screens to docs/screenshots/: the library (dark and light), the reader, its table of
contents, timer, appearance sheet and high-contrast look, the player in the notification shade, the voices and
the manual. The two translation screens come from qa/translate_walkthrough.py.
"""

import argparse
import io
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "io.github.fanti1.audiolivre"
CACHE = Path.home() / "qa-models" / "screenshot-books"
DEVICE_DIR = "/sdcard/Download"
SHOT_WIDTH = 720

# Added in this order; the library lists the most recently opened first, and Dom Casmurro is read last.
BOOKS = [
    ("Papeis Avulsos.txt", "https://www.gutenberg.org/ebooks/57001.txt.utf-8"),
    ("Policarpo Quaresma.epub", "https://www.gutenberg.org/ebooks/67535.epub3.images"),
    ("Quincas Borba.pdf", "https://www.gutenberg.org/ebooks/55682.txt.utf-8"),
    ("Iracema.azw3", "https://www.gutenberg.org/ebooks/67740.kf8.images"),
    ("Bras Cubas.epub", "https://www.gutenberg.org/ebooks/54829.epub3.images"),
    ("Dom Casmurro.epub", "https://www.gutenberg.org/ebooks/55752.epub3.images"),
]


def sdk_dir() -> Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return Path(os.environ[var])
    props = ROOT / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    sys.exit("Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties")


SDK = sdk_dir()
ADB = str(SDK / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))


def adb(*args: str, check: bool = True) -> str:
    result = subprocess.run([ADB, *args], capture_output=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace')}")
    return result.stdout.decode("utf-8", errors="replace")


def shell(command: str) -> str:
    return adb("shell", command, check=False)


# --- device -------------------------------------------------------------------------------------------------

def ensure_device(avd: str) -> None:
    if "\tdevice" in adb("devices"):
        return
    emulator = SDK / "emulator" / ("emulator.exe" if os.name == "nt" else "emulator")
    print(f"starting emulator {avd}...")
    subprocess.Popen(
        [str(emulator), "-avd", avd, "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot",
         "-gpu", "swiftshader_indirect", "-memory", "4096"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    adb("wait-for-device")


def wait_boot() -> None:
    for _ in range(180):
        if shell("getprop sys.boot_completed").strip() == "1":
            return
        time.sleep(2)
    sys.exit("the emulator did not finish booting")


def prepare_device() -> None:
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        shell(f"settings put global {setting} 0")
    shell("svc power stayon true")
    shell("cmd uimode night yes")
    # The reader hides the system bars, and Android would cover the first page with a "Viewing full screen" tip.
    shell("settings put secure immersive_mode_confirmations confirmed")
    shell("settings put system screen_off_timeout 1800000")
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard")
    demo_mode()


def demo_mode() -> None:
    """A tidy status bar: fixed clock, full battery and signal, no notification icons. Opening the notification
    shade undoes part of it, so it is set again afterwards."""
    shell("settings put global sysui_demo_allowed 1")
    demo = [
        "-e command enter",
        "-e command clock -e hhmm 0930",
        "-e command battery -e level 100 -e plugged false",
        "-e command network -e wifi show -e level 4",
        "-e command network -e mobile show -e datatype none -e level 4",
        "-e command notifications -e visible false",
    ]
    for extra in demo:
        shell(f"am broadcast -a com.android.systemui.demo {extra}")


# --- books --------------------------------------------------------------------------------------------------

def download(url: str, target: Path) -> None:
    if target.exists() and target.stat().st_size > 0:
        return
    print(f"downloading {target.name}...")
    request = urllib.request.Request(url, headers={"User-Agent": "Audiolivre screenshots (github.com/fanti1/audiolivre)"})
    with urllib.request.urlopen(request, timeout=120) as response:
        target.write_bytes(response.read())


def gutenberg_body(text: str) -> list[str]:
    start = re.search(r"\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG[^\n]*\n", text)
    end = re.search(r"\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG", text)
    body = text[start.end() if start else 0: end.start() if end else len(text)]
    paragraphs = [" ".join(line.strip() for line in p.splitlines()).strip() for p in re.split(r"\n\s*\n", body)]
    return [p for p in paragraphs if p]


def build_pdf(txt: Path, target: Path, title: str, author: str, year: str) -> None:
    """A small book typeset from a Gutenberg text, with a cloth-bound cover on its first page."""
    from reportlab.lib.colors import HexColor
    from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
    from reportlab.lib.pagesizes import A5
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer

    if target.exists():
        return
    body = gutenberg_body(txt.read_text(encoding="utf-8"))

    def cover(canvas, doc):
        width, height = A5
        canvas.saveState()
        canvas.setFillColor(HexColor("#2F4A3A"))
        canvas.rect(0, 0, width, height, stroke=0, fill=1)
        canvas.setStrokeColor(HexColor("#D9C089"))
        canvas.setLineWidth(1.2)
        canvas.rect(10 * mm, 10 * mm, width - 20 * mm, height - 20 * mm)
        canvas.setLineWidth(0.5)
        canvas.rect(12 * mm, 12 * mm, width - 24 * mm, height - 24 * mm)
        canvas.setFillColor(HexColor("#F1E3C2"))
        canvas.setFont("Times-Roman", 13)
        canvas.drawCentredString(width / 2, height * 0.72, author.upper())
        canvas.setFont("Times-Bold", 34)
        words = title.upper().split()
        for i, word in enumerate(words):
            canvas.drawCentredString(width / 2, height * 0.58 - i * 40, word)
        canvas.setFont("Times-Italic", 12)
        canvas.drawCentredString(width / 2, height * 0.58 - len(words) * 40 - 10, "romance")
        canvas.setFont("Times-Roman", 11)
        canvas.drawCentredString(width / 2, 22 * mm, year)
        canvas.restoreState()

    def page_number(canvas, doc):
        canvas.saveState()
        canvas.setFont("Times-Roman", 9)
        canvas.drawCentredString(A5[0] / 2, 10 * mm, str(doc.page - 1))
        canvas.restoreState()

    text_style = ParagraphStyle("text", fontName="Times-Roman", fontSize=10.5, leading=14, alignment=TA_JUSTIFY,
                                firstLineIndent=5 * mm)
    heading_style = ParagraphStyle("heading", fontName="Times-Bold", fontSize=13, leading=16, alignment=TA_CENTER,
                                   spaceBefore=6 * mm, spaceAfter=4 * mm)

    def escape(value: str) -> str:
        value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return re.sub(r"_([^_]+)_", r"<i>\1</i>", value)

    story = [PageBreak()]
    for paragraph in body:
        is_heading = len(paragraph) < 60 and (re.fullmatch(r"[IVXLC]+\.?", paragraph) or paragraph.isupper())
        if is_heading and re.fullmatch(r"[IVXLC]+\.?", paragraph):
            story.append(PageBreak())
        story.append(Paragraph(escape(paragraph), heading_style if is_heading else text_style))
    doc = SimpleDocTemplate(str(target), pagesize=A5, leftMargin=16 * mm, rightMargin=16 * mm, topMargin=16 * mm,
                            bottomMargin=18 * mm, title=title, author=author)
    doc.build(story, onFirstPage=cover, onLaterPages=page_number)


def prepare_books() -> list[Path]:
    CACHE.mkdir(parents=True, exist_ok=True)
    files = []
    for name, url in BOOKS:
        target = CACHE / name
        if name.endswith(".pdf"):
            source = CACHE / (Path(name).stem + ".txt")
            download(url, source)
            build_pdf(source, target, title=Path(name).stem, author="Machado de Assis", year="1891")
        else:
            download(url, target)
        files.append(target)
    return files


# --- screen -------------------------------------------------------------------------------------------------

class Node:
    def __init__(self, element: ET.Element):
        self.text = element.get("text", "")
        self.desc = element.get("content-desc", "")
        self.package = element.get("package", "")
        self.cls = element.get("class", "")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", element.get("bounds", "[0,0][0,0]")))
        self.center = ((x1 + x2) // 2, (y1 + y2) // 2)


def dump() -> list[Node]:
    for _ in range(5):
        shell("uiautomator dump /sdcard/ui.xml")
        raw = adb("exec-out", "cat /sdcard/ui.xml", check=False)
        if raw.startswith("<?xml"):
            return [Node(e) for e in ET.fromstring(raw).iter("node")]
        time.sleep(1)
    return []


def find(pattern: str, nodes: list[Node] | None = None) -> Node | None:
    regex = re.compile(pattern)
    for node in nodes if nodes is not None else dump():
        if regex.fullmatch(node.text) or regex.fullmatch(node.desc):
            return node
    return None


def wait_for(pattern: str, timeout: float = 30) -> Node:
    deadline = time.time() + timeout
    while time.time() < deadline:
        node = find(pattern)
        if node:
            return node
        time.sleep(1.5)
    raise TimeoutError(f"'{pattern}' did not appear")


def tap(node_or_point) -> None:
    x, y = node_or_point.center if isinstance(node_or_point, Node) else node_or_point
    shell(f"input tap {x} {y}")
    time.sleep(1.2)


def back() -> None:
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.2)


def screen_size() -> tuple[int, int]:
    match = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return int(match.group(1)), int(match.group(2))


def screenshot(name: str, out: Path) -> None:
    png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    image = Image.open(io.BytesIO(png)).convert("RGB")
    height = round(image.height * SHOT_WIDTH / image.width)
    image.resize((SHOT_WIDTH, height), Image.LANCZOS).save(out / f"{name}.png", optimize=True)
    print(f"saved {name}.png")


# --- the walk through the app -------------------------------------------------------------------------------

def wait_reader(timeout: float = 900) -> None:
    """Until a page is on screen: the first book also waits for the narrator voice to download."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = dump()
        if find(r"Página \d+ de \d+", nodes):
            return
        error = find(r"(Não foi possível|Não deu pra abrir).*", nodes)
        if error:
            raise RuntimeError(f"the reader showed an error: {error.text}")
        tip = find(r"(Got it|Entendi|OK)", nodes)
        if tip:
            tap(tip)
        time.sleep(3)
    raise TimeoutError("the book did not open")


def add_book_button() -> Node:
    """The empty shelf's "Adicionar livro" button, or the floating one once there are books.

    uiautomator reports the floating button's label as empty, so it is found by where it sits: the button in the
    bottom-right corner of the screen.
    """
    width, height = screen_size()
    deadline = time.time() + 30
    while time.time() < deadline:
        nodes = dump()
        labelled = find(r"Adicionar livro", nodes)
        if labelled:
            return labelled
        corner = [n for n in nodes if n.cls == "android.widget.Button" and n.center[0] > width / 2 and n.center[1] > height * 0.8]
        if corner and find(r"Biblioteca", nodes):
            return corner[-1]
        time.sleep(1.5)
    raise TimeoutError("the add-book button did not appear")


def add_book(book: Path) -> None:
    name = book.name
    print(f"adding {name}...")
    # The picker lists Downloads newest first, so a file copied just now is the first one on screen.
    adb("push", str(book), f"{DEVICE_DIR}/{name}")
    shell(f"touch '{DEVICE_DIR}/{name}'")
    tap(add_book_button())
    nodes = dump()
    if not find(re.escape(name), nodes):
        roots = find(r"(Show roots|Mostrar raízes|Abrir gaveta de navegação|Open navigation drawer)", nodes)
        if roots:
            tap(roots)
        tap(wait_for(r"(Downloads|Download|Transferências)"))
    tap(wait_for(re.escape(name)))
    wait_reader()
    back()
    wait_for(r"Biblioteca")


def swipe(width: int, height: int, up: bool = True) -> None:
    top, bottom = int(height * 0.35), int(height * 0.8)
    start, end = (bottom, top) if up else (top, bottom)
    shell(f"input swipe {width // 2} {start} {width // 2} {end} 400")
    time.sleep(1)


def scroll_to(pattern: str, width: int, height: int) -> Node:
    """Scrolls the sheet on screen up until something matching `pattern` shows."""
    for _ in range(6):
        node = find(pattern)
        if node:
            return node
        swipe(width, height)
    raise TimeoutError(f"'{pattern}' did not appear while scrolling")


def show_controls(width: int, height: int) -> None:
    """The reader hides its menu while reading; a tap in the middle of the page brings it back."""
    if not find(r"Aparência"):
        tap((width // 2, height // 2))
    wait_for(r"Aparência")


def play_for(seconds: float, width: int, height: int) -> None:
    """Plays and pauses again, leaving a few sentences read and the current one highlighted. No dumps while
    it plays: the screen keeps changing."""
    show_controls(width, height)
    play = wait_for(r"Ouvir")
    tap(play)
    time.sleep(seconds)


def pause(width: int, height: int) -> None:
    show_controls(width, height)
    tap(wait_for(r"Pausar"))


def read_aloud(out: Path) -> None:
    width, height = screen_size()
    # Each book on the shelf is a single item, named "<title>, <progress>".
    tap(wait_for(r"Dom Casmurro, .*"))
    wait_reader()
    show_controls(width, height)
    tap(wait_for(r"Sumário"))
    time.sleep(1)
    screenshot("sumario", out)
    # The EPUB's own table of contents names chapter I just "Do titulo."
    tap(wait_for(r"(I — )?Do titulo\.?"))
    wait_reader()

    play_for(9, width, height)
    screenshot("leitura", out)
    # The same playback in the quick settings, where the lock screen shows it too. Fully expanded, they cover the
    # emulator's own notifications.
    shell("cmd statusbar expand-settings")
    time.sleep(2)
    screenshot("notificacao", out)
    shell("cmd statusbar collapse")
    time.sleep(1)
    demo_mode()
    pause(width, height)

    tap(wait_for(r"Timer.*"))
    time.sleep(1)
    screenshot("timer", out)
    back()

    tap(wait_for(r"Aparência"))
    time.sleep(1)
    screenshot("aparencia", out)
    # The look for low vision: high contrast, Atkinson Hyperlegible and the large controls.
    tap(scroll_to(r".*Atkinson.*", width, height))
    tap(scroll_to(r"Contraste", width, height))
    tap(scroll_to(r".*Controles grandes.*", width, height))
    back()
    play_for(6, width, height)
    screenshot("acessibilidade", out)
    pause(width, height)
    # Back to the usual look, so a --keep-data run starts from it.
    tap(wait_for(r"Aparência"))
    tap(scroll_to(r".*Literata.*", width, height))
    tap(scroll_to(r"Escuro", width, height))
    tap(scroll_to(r".*Controles grandes.*", width, height))
    back()
    # Further into the book, so "Continuar lendo" on the shelf shows some progress: the last chapter on screen
    # after scrolling the table of contents a little (Dom Casmurro's chapter titles end in a period).
    show_controls(width, height)
    tap(wait_for(r"Sumário"))
    for _ in range(4):
        swipe(width, height)
    chapters = [n for n in dump() if re.fullmatch(r"[^\d].*[.!?]", n.text) and n.center[1] < height * 0.9]
    if chapters:
        tap(chapters[-1])
        wait_reader()
        play_for(6, width, height)
        pause(width, height)
    else:
        back()
    back()
    if not find(r"Biblioteca"):
        back()
    wait_for(r"Biblioteca")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--avd", default="audiolivre")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--out", type=Path, default=ROOT / "docs/screenshots")
    parser.add_argument("--keep-data", action="store_true", help="keep the app's books and voices from a previous run")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    books = prepare_books()
    ensure_device(args.avd)
    wait_boot()
    prepare_device()
    adb("install", "-r", "-g", str(args.apk))
    if not args.keep_data:
        shell(f"pm clear {PACKAGE}")
    shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
    shell("am force-stop com.google.android.documentsui")
    shell(f"am start -n {PACKAGE}/.ui.LibraryActivity")
    wait_for(r"(Biblioteca|Adicionar livro)")

    # Adding a book that is already on the shelf just opens it again, so a --keep-data run adds whatever is missing.
    for book in books:
        add_book(book)
    read_aloud(args.out)
    time.sleep(3)  # covers render in the background
    screenshot("biblioteca", args.out)
    # The app follows the phone's light or dark mode.
    shell("cmd uimode night no")
    time.sleep(3)
    wait_for(r"Biblioteca")
    time.sleep(2)
    screenshot("modo-claro", args.out)
    shell("cmd uimode night yes")
    time.sleep(3)
    tap(wait_for(r"Voz"))
    wait_for(r"Escolher voz para")
    screenshot("vozes", args.out)
    back()
    tap(wait_for(r"Como usar"))
    time.sleep(2)
    screenshot("manual", args.out)
    back()


if __name__ == "__main__":
    main()
