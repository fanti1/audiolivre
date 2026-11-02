"""Checks, on an Android emulator, that the reading position survives what people do to the app, and that
"Ler a partir daqui" and leafing through pages put the reading where the reader asked.

    python qa/position_check.py [--avd audiolivre] [--apk app/build/outputs/apk/debug/app-debug.apk] [--repeat 3]

Needs what qa/screenshots.py needs (it borrows its helpers) and a debuggable APK: the saved position is read
straight from the app's DataStore file with run-as (to check a release build, build one with isDebuggable and the
x86_64 libraries). Each check prints PASS or FAIL with the numbers behind it, and --repeat runs everything again
from a cleared app and sums up; the screenshots go to qa/out/position/.

    1. leafing while paused, and coming back to the page, keeps the exact sentence the reading stopped on
    2. "Frase anterior" after a drag that stayed on the page goes back one sentence
    3. a long press in the blank line under a chapter title offers to read the chapter's first paragraph
    4. the "Ler a partir daqui" bubble does not survive a page turn
    5. a long press marks the sentence it will read from, and "Ler a partir daqui" reads from it
    6. back on the page being read, the pages follow the voice again
    7. turning the phone while it reads keeps reading, and the position never goes back
    8. paused in the background, the notification's and a headset's play buttons resume the reading
    9. the app killed while reading (a dead battery, Android ending it) opens again where the voice was
   10. "Recomeçar do início" from the shelf while the book is being read sends it to the start for good
"""

import argparse
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import screenshots as s  # noqa: E402

PACKAGE = s.PACKAGE
OUT = s.ROOT / "qa" / "out" / "position"
RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str) -> None:
    RESULTS.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")


# --- the saved position, from the app's own file --------------------------------------------------------------

def varint(data: bytes, i: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        b = data[i]
        i += 1
        value |= (b & 0x7F) << shift
        shift += 7
        if b < 0x80:
            return value, i


def fields(data: bytes):
    i = 0
    while i < len(data):
        key, i = varint(data, i)
        number, kind = key >> 3, key & 7
        if kind == 0:
            value, i = varint(data, i)
        elif kind == 2:
            length, i = varint(data, i)
            value, i = data[i:i + length], i + length
        elif kind == 5:
            value, i = data[i:i + 4], i + 4
        elif kind == 1:
            value, i = data[i:i + 8], i + 8
        else:
            raise ValueError(f"unexpected wire type {kind}")
        yield number, value


def saved_offset() -> int | None:
    """The book's saved character offset (the shelf holds one book), or None when there is none."""
    import subprocess
    raw = subprocess.run(
        [s.ADB, "exec-out", "run-as", PACKAGE, "cat", "files/datastore/resume_position.preferences_pb"],
        capture_output=True,
    ).stdout
    if not raw or b"No such file" in raw:
        return None
    for number, entry in fields(raw):
        if number != 1:
            continue
        key, value = None, None
        for n, v in fields(entry):
            if n == 1:
                key = v.decode()
            elif n == 2:
                value = v
        if key and key.endswith("_offset") and value is not None:
            for n, v in fields(value):
                if n == 3:
                    return v - (1 << 32) if v >= 1 << 31 else v
    return None


# --- screen -------------------------------------------------------------------------------------------------

def page_number() -> int | None:
    for node in s.dump():
        match = re.fullmatch(r"Página (\d+) de \d+", node.text)
        if match:
            return int(match.group(1))
    return None


def is_playing() -> bool:
    """From the app's media session, the one the notification and the headset buttons see."""
    dump = s.shell("dumpsys media_session")
    at = dump.find(f"package={PACKAGE}")
    if at < 0:
        return False
    match = re.search(r"state=PlaybackState \{state=\w+\((\d+)\)", dump[at:])
    return bool(match) and match.group(1) == "3"


def watch_offsets(seconds: float) -> list[tuple[float, int | None]]:
    """The saved position, read over and over while the book plays."""
    start = time.time()
    seen = []
    while time.time() - start < seconds:
        seen.append((time.time() - start, saved_offset()))
        time.sleep(0.4)
    return seen


def hide_controls(width: int, height: int) -> None:
    if s.find(r"Aparência"):
        s.tap((width // 2, height // 2))


def swipe_page(width: int, height: int, forward: bool) -> None:
    y = int(height * 0.55)
    a, b = int(width * 0.85), int(width * 0.15)
    x1, x2 = (a, b) if forward else (b, a)
    s.shell(f"input swipe {x1} {y} {x2} {y} 250")
    time.sleep(2.5)


def options_button(title: str) -> s.Node:
    """The "..." button on a shelf tile: uiautomator merges it into the tile, so it is tapped by where it sits."""
    s.shell("uiautomator dump /sdcard/ui.xml")
    raw = s.adb("exec-out", "cat /sdcard/ui.xml", check=False)
    for element in ET.fromstring(raw).iter("node"):
        # The cover tile reads "<title>, <progress>"; the "Continuar lendo" card above it has the title alone.
        if element.get("content-desc", "").startswith(title + ","):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", element.get("bounds")))
            node = s.Node(element)
            node.center = (x2 - 50, y1 + 50)
            return node
    raise TimeoutError(f"no tile for {title}")


def boxes() -> list[tuple[str, tuple[int, int, int, int]]]:
    """Every labelled node with its bounds: the page lays one per paragraph over the text, for TalkBack."""
    s.shell("uiautomator dump /sdcard/ui.xml")
    raw = s.adb("exec-out", "cat /sdcard/ui.xml", check=False)
    if not raw.startswith("<?xml"):
        return []
    out = []
    for element in ET.fromstring(raw).iter("node"):
        label = element.get("content-desc", "") or element.get("text", "")
        if label:
            out.append((label, tuple(map(int, re.findall(r"\d+", element.get("bounds"))))))
    return out


def ensure_paused(width: int, height: int) -> None:
    if is_playing():
        s.pause(width, height)
    hide_controls(width, height)
    time.sleep(1)


# --- checks -------------------------------------------------------------------------------------------------

def open_book() -> None:
    s.tap(s.wait_for(r"Dom Casmurro, .*"))
    s.wait_reader()


def go_to_first_chapter(width: int, height: int) -> None:
    s.show_controls(width, height)
    s.tap(s.wait_for(r"Sumário"))
    time.sleep(1)
    s.tap(s.wait_for(r"(I — )?Do titulo\.?"))
    s.wait_reader()


def leafing_while_paused(width: int, height: int) -> None:
    s.play_for(14, width, height)
    s.pause(width, height)
    hide_controls(width, height)
    time.sleep(1.5)
    stopped = saved_offset()
    page = page_number()
    swipe_page(width, height, forward=True)
    leafed = saved_offset()
    swipe_page(width, height, forward=False)
    back_again = saved_offset()
    check(
        "leafing while paused moves the bookmark, and coming back restores the sentence",
        stopped is not None and leafed is not None and leafed > stopped and back_again == stopped
        and page_number() == page,
        f"stopped at {stopped} (page {page}), next page {leafed}, back on the page {back_again}",
    )


def previous_sentence_after_a_drag(width: int, height: int) -> None:
    """Paused, two pages leafed forward, a drag that lets go on the same page, then "Frase anterior"."""
    ensure_paused(width, height)
    swipe_page(width, height, forward=True)
    first_of_a = saved_offset()
    swipe_page(width, height, forward=True)
    first_of_b = saved_offset()
    page = page_number()
    y = int(height * 0.55)
    s.shell(f"input swipe {width // 2} {y} {width // 2 + 90} {y} 400")
    time.sleep(2)
    same_page = page_number() == page
    s.show_controls(width, height)
    s.tap(s.wait_for(r"Frase anterior"))
    time.sleep(3)
    after = saved_offset()
    hide_controls(width, height)
    check(
        "\"Frase anterior\" after a drag goes back one sentence, not to the top of the previous page",
        same_page and first_of_a is not None and first_of_b is not None and after is not None
        and first_of_a < after < first_of_b,
        f"top of previous page {first_of_a}, sentence before {first_of_b} expected; got {after} "
        f"(drag stayed on the page: {same_page})",
    )


def long_press_under_a_heading(width: int, height: int) -> None:
    """A long press in the blank line between a chapter title and its first paragraph."""
    go_to_first_chapter(width, height)
    ensure_paused(width, height)
    labelled = boxes()
    heading = next((b for label, b in labelled if label.startswith("Do titulo")), None)
    body = next((b for label, b in labelled if label.startswith("Uma noite destas")), None)
    if not heading or not body:
        check("a long press under a chapter title reads its first paragraph", False, "title or paragraph not found")
        return
    y = (heading[3] + body[1]) // 2
    x = width // 2
    s.shell(f"input swipe {x} {y} {x} {y} 1200")
    time.sleep(1.5)
    s.screenshot("long-press-under-heading", OUT)
    s.tap((x, y - int(height * 0.052)))
    time.sleep(4)
    playing = is_playing()
    offset = saved_offset()
    check(
        "a long press under a chapter title offers \"Ler a partir daqui\" (its first paragraph)",
        playing,
        f"gap between y={heading[3]} and y={body[1]}, pressed at {y}; reading started={playing}, position {offset}",
    )
    ensure_paused(width, height)


def bubble_after_turning_the_page(width: int, height: int) -> None:
    """The bubble open, the page turned: a tap where the bubble was must not start reading the page left behind."""
    ensure_paused(width, height)
    page = page_number()
    x, y = width // 2, int(height * 0.7)
    s.shell(f"input swipe {x} {y} {x} {y} 1200")
    time.sleep(1.5)
    swipe_page(width, height, forward=True)
    turned = page_number()
    s.tap((x, y - int(height * 0.052)))
    time.sleep(4)
    playing = is_playing()
    now = page_number() if not playing else None
    s.screenshot("bubble-after-turn", OUT)
    check(
        "the \"Ler a partir daqui\" bubble goes away when the page turns",
        not playing,
        f"pressed on page {page}, turned to {turned}; a tap where the bubble was started reading={playing}",
    )
    ensure_paused(width, height)
    if now is None:
        swipe_page(width, height, forward=True)


def pages_follow_after_coming_back(width: int, height: int) -> None:
    """Reading; a page forward and back again by hand: the pages must follow the voice again."""
    if not is_playing():
        s.play_for(3, width, height)
    hide_controls(width, height)
    swipe_page(width, height, forward=True)
    away = s.find(r"Voltar ao áudio.*") is not None
    swipe_page(width, height, forward=False)
    time.sleep(1)
    back_pill = s.find(r"Voltar ao áudio.*") is not None
    check(
        "back on the page being read, the \"Voltar ao áudio\" button goes and the pages follow the voice",
        away and not back_pill,
        f"button shown on the other page={away}, still shown back on the page being read={back_pill}",
    )


def play_from_notification_in_background(width: int, height: int) -> None:
    """Paused, the app in the background (home screen), "Tocar" pressed in the notification and on a headset."""
    ensure_paused(width, height)
    s.shell("input keyevent KEYCODE_HOME")
    time.sleep(6)
    # Android 15 shows the player among the quick settings, with the system's own label on its play button.
    s.shell("cmd statusbar expand-settings")
    time.sleep(3)
    button = s.find(r"(Tocar|Iniciar|Reproduzir|Play)")
    if button:
        s.tap(button)
    time.sleep(5)
    from_notification = is_playing()
    s.shell("cmd statusbar collapse")
    time.sleep(1)
    if from_notification:
        s.shell("input keyevent KEYCODE_MEDIA_PAUSE")
        time.sleep(6)
    s.shell("input keyevent KEYCODE_MEDIA_PLAY")
    time.sleep(5)
    from_headset = is_playing()
    check(
        "paused in the background, \"Tocar\" in the notification and a headset's play button resume the reading",
        button is not None and from_notification and from_headset,
        f"notification button found={button is not None}, played from it={from_notification}, from headset={from_headset}",
    )
    s.shell("input keyevent KEYCODE_MEDIA_PAUSE")
    time.sleep(1)
    s.shell(f"am start -n {PACKAGE}/.ui.LibraryActivity")
    time.sleep(2)
    if s.find(r"Biblioteca"):
        open_book()


def long_press_reads_from_there(width: int, height: int) -> None:
    hide_controls(width, height)
    before = saved_offset()
    x, y = width // 2, int(height * 0.7)
    s.shell(f"input swipe {x} {y} {x} {y} 1200")
    time.sleep(1.5)
    s.screenshot("long-press-marked", OUT)
    # The bubble is a popup window of its own, which uiautomator does not list: it sits just above the finger.
    s.tap((x, y - int(height * 0.052)))
    time.sleep(6)
    after = saved_offset()
    playing = is_playing()
    s.screenshot("long-press-reading", OUT)
    check(
        "\"Ler a partir daqui\" reads from the pressed sentence, further down the same page",
        playing and before is not None and after is not None and after > before,
        f"position before {before}, after {after}, playing={playing}",
    )


def rotating_while_reading(width: int, height: int) -> None:
    if not is_playing():
        s.play_for(4, width, height)
        hide_controls(width, height)
    before = watch_offsets(6)
    s.shell("settings put system accelerometer_rotation 0")
    s.shell("settings put system user_rotation 1")
    after = watch_offsets(14)
    playing = is_playing()
    s.screenshot("rotated-reading", OUT)
    s.shell("settings put system user_rotation 0")
    time.sleep(4)
    still_playing = is_playing()
    values = [v for _, v in before + after if v is not None]
    never_back = all(b >= a for a, b in zip(values, values[1:]))
    moved = len(values) > 1 and values[-1] > values[0]
    check(
        "turning the phone keeps reading, and the position never goes back",
        playing and still_playing and never_back and moved,
        f"playing after turning={playing}, after turning back={still_playing}, positions {sorted(set(values))}",
    )


def killed_while_reading(width: int, height: int) -> None:
    if not is_playing():
        s.play_for(4, width, height)
    seen = watch_offsets(20)
    last = seen[-1][1]
    changes = [t for (t, v), (_, w) in zip(seen, seen[1:]) if v != w]
    # A dead battery: no callback, no chance to save anything.
    s.shell(f"am force-stop {PACKAGE}")
    time.sleep(2)
    on_disk = saved_offset()
    s.shell(f"am start -n {PACKAGE}/.ui.LibraryActivity")
    s.wait_for(r"Biblioteca")
    open_book()
    time.sleep(2)
    reopened = saved_offset()
    s.screenshot("reopened-after-kill", OUT)
    gaps = [b - a for a, b in zip(changes, changes[1:])]
    check(
        "killed while reading, the book opens again where the voice was",
        # The voice may reach the next sentence (and save it) between the last sample and the kill.
        last is not None and on_disk is not None and on_disk >= last and reopened == on_disk,
        f"last saved while playing {last}, on disk after the kill {on_disk}, after reopening {reopened}; "
        f"saved {len(changes)} times in 20 s, longest gap {max(gaps, default=0):.1f} s",
    )


def restart_while_reading(width: int, height: int) -> None:
    s.play_for(5, width, height)
    s.back()
    if not s.find(r"Biblioteca"):
        s.back()
    s.wait_for(r"Biblioteca")
    time.sleep(2)
    s.tap(options_button("Dom Casmurro"))
    s.tap(s.wait_for(r"Recomeçar do início"))
    time.sleep(1)
    after = watch_offsets(10)
    values = {v for _, v in after}
    open_book()
    page = page_number()
    check(
        "\"Recomeçar do início\" while the book plays sends it to the start for good",
        values == {None} and page == 1,
        f"saved positions after restarting {values}, page on reopening {page}",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--avd", default="audiolivre")
    parser.add_argument("--apk", type=Path, default=s.ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--repeat", type=int, default=1, help="runs, each from a freshly cleared app")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    book = next(p for p in s.prepare_books() if p.name.startswith("Dom Casmurro"))
    s.ensure_device(args.avd)
    s.wait_boot()
    s.prepare_device()
    s.shell("settings put system accelerometer_rotation 0")
    s.shell("settings put system user_rotation 0")
    s.adb("uninstall", PACKAGE, check=False)
    s.adb("install", "-r", "-g", str(args.apk))
    width, height = s.screen_size()

    steps = [
        leafing_while_paused,
        previous_sentence_after_a_drag,
        long_press_under_a_heading,
        bubble_after_turning_the_page,
        long_press_reads_from_there,
        pages_follow_after_coming_back,
        rotating_while_reading,
        play_from_notification_in_background,
        killed_while_reading,
        restart_while_reading,
    ]
    for run in range(args.repeat):
        print(f"\n--- run {run + 1} of {args.repeat}")
        s.shell("cmd statusbar collapse")
        s.shell("settings put system user_rotation 0")
        s.shell(f"am force-stop {PACKAGE}")
        s.shell(f"pm clear {PACKAGE}")
        s.shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
        s.shell("am force-stop com.google.android.documentsui")
        s.shell(f"am start -n {PACKAGE}/.ui.LibraryActivity")
        s.wait_for(r"(Biblioteca|Adicionar livro)")
        s.add_book(book)
        open_book()
        go_to_first_chapter(width, height)
        for step in steps:
            try:
                step(width, height)
            except Exception as error:  # one broken step must not hide the others' results
                check(step.__name__, False, f"the step itself failed: {error!r}")
                s.shell("settings put system user_rotation 0")

    print("\nsummary (passed / runs):")
    names = list(dict.fromkeys(name for name, _, _ in RESULTS))
    for name in names:
        runs = [ok for n, ok, _ in RESULTS if n == name]
        print(f"  {sum(runs)}/{len(runs)}  {name}")
    sys.exit(0 if all(ok for _, ok, _ in RESULTS) else 1)


if __name__ == "__main__":
    main()
