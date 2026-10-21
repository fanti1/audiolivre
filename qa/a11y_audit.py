"""Walks the app's screens on the emulator and flags what a screen reader user or an unsteady finger would trip on.

    python qa/a11y_audit.py

Run it before each release. For every screen it reads the accessibility tree (uiautomator) and reports:
  - controls you can tap with no name for TalkBack to say (no text, no content description);
  - controls smaller than 48 x 48 dp, the minimum size for a fingertip.
It needs a book in the library for the reader screens. It exits with 1 when it finds something.
"""

import re
import sys
import time
import xml.etree.ElementTree as ET

import screenshots as s

MIN_DP = 48


def density() -> float:
    match = re.search(r"(\d+)", s.shell("wm density").split("Override")[-1])
    return int(match.group(1)) / 160 if match else 2.625


def audit(screen: str, scale: float) -> list[str]:
    s.shell("uiautomator dump /sdcard/ui.xml")
    raw = s.adb("exec-out", "cat /sdcard/ui.xml", check=False)
    root = ET.fromstring(raw)
    screen_height = s.screen_size()[1]
    problems = []

    def named(node: ET.Element) -> bool:
        if node.get("text") or node.get("content-desc"):
            return True
        return any(child.get("text") or child.get("content-desc") for child in node.iter("node"))

    for node in root.iter("node"):
        if node.get("package") != s.PACKAGE:
            continue
        if node.get("clickable") != "true" and node.get("long-clickable") != "true":
            continue
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]")))
        # Half scrolled off the screen: its size and children are cut, so it can't be judged here.
        if y1 <= 0 or y2 >= screen_height - 1:
            continue
        width, height = (x2 - x1) / scale, (y2 - y1) / scale
        label = node.get("content-desc") or node.get("text") or node.get("class", "").split(".")[-1]
        if not named(node):
            problems.append(f"{screen}: control at [{x1},{y1}] has no name ({node.get('class')})")
        if width < MIN_DP - 0.5 or height < MIN_DP - 0.5:
            problems.append(f"{screen}: '{label}' is {width:.0f}x{height:.0f} dp (minimum {MIN_DP})")
    print(f"{screen}: {'ok' if not problems else f'{len(problems)} problem(s)'}")
    for problem in problems:
        print("   ", problem)
    return problems


def find_scrolling(pattern: str, swipes: int = 6) -> s.Node:
    """Scrolls down until the pattern is on screen."""
    for _ in range(swipes):
        node = s.find(pattern)
        if node:
            return node
        width, height = s.screen_size()
        s.shell(f"input swipe {width // 2} {height * 3 // 4} {width // 2} {height // 4} 300")
        time.sleep(1)
    return s.wait_for(pattern)


def main() -> None:
    s.wait_boot()
    s.prepare_device()
    s.shell(f"am start -n {s.PACKAGE}/.ui.LibraryActivity")
    s.wait_for(r"(Biblioteca|Adicionar livro)")
    scale = density()
    problems = audit("Biblioteca", scale)

    for icon, title, screen in [("Sobre", "Sobre", "Sobre"), ("Como usar", "Como usar", "Como usar"), ("Voz", "Voz", "Voz")]:
        s.tap(s.wait_for(icon))
        s.wait_for(title)
        problems += audit(screen, scale)
        if screen == "Sobre":
            s.tap(find_scrolling(r"Privacidade e LGPD"))
            s.wait_for(r"Em resumo")
            problems += audit("Privacidade", scale)
            s.back()
        s.back()
        s.wait_for(r"Biblioteca")

    # The first book on the shelf, opened as TalkBack would (the card is one item with an "Abrir" action).
    book = next((n for n in s.dump() if re.search(r", (\d+% lido|Não começado|Concluído|Em leitura)$", n.desc)), None)
    if book:
        s.tap(book)
        s.wait_reader()
        problems += audit("Leitor", scale)
        width, height = s.screen_size()
        s.tap((width // 2, height // 2))
        time.sleep(1)
        problems += audit("Leitor com menu", scale)
        s.back()
        s.back()
    else:
        print("no book on the shelf: reader screens skipped")

    print()
    print(f"{len(problems)} problem(s) in total")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
