"""Adds a book in another language through the app, the way a person would, and follows its translation.

    python qa/translate_walkthrough.py BOOK [--out DIR] [--apk app/build/outputs/apk/debug/app-debug.apk]

Uses the emulator (or phone) adb sees and the helpers of qa/screenshots.py. It confirms the translation on the
"Este livro está em …" screen, saves a screenshot of each step (question, translator download, translation,
the translated book and the shelf with its "(pt-BR)" title) and prints how long the translation took.
Needs internet the first time: the translator comes from Mozilla, the narrator voice from GitHub.
"""

import argparse
import re
import time
from pathlib import Path

import screenshots as s


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("book", type=Path)
    parser.add_argument("--out", type=Path, default=s.ROOT / "qa" / "out" / "translation")
    parser.add_argument("--apk", type=Path, default=s.ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    s.wait_boot()
    s.prepare_device()
    s.adb("install", "-r", "-g", str(args.apk))
    s.shell(f"pm grant {s.PACKAGE} android.permission.POST_NOTIFICATIONS")
    s.shell("am force-stop com.google.android.documentsui")
    s.shell(f"am start -n {s.PACKAGE}/.ui.LibraryActivity")
    s.wait_for(r"(Biblioteca|Adicionar livro)")

    name = args.book.name
    s.adb("push", str(args.book), f"{s.DEVICE_DIR}/{name}")
    s.shell(f"touch '{s.DEVICE_DIR}/{name}'")
    s.tap(s.add_book_button())
    nodes = s.dump()
    if not s.find(re.escape(name), nodes):
        roots = s.find(r"(Show roots|Mostrar raízes|Abrir gaveta de navegação|Open navigation drawer)", nodes)
        if roots:
            s.tap(roots)
        s.tap(s.wait_for(r"(Downloads|Download|Transferências)"))
    s.tap(s.wait_for(re.escape(name)))

    question = s.wait_for(r"(Este livro (está|parece estar) em .*|Em que idioma está este livro\?)", timeout=300)
    print("asked:", question.text)
    s.screenshot("1_pergunta", args.out)
    s.tap(s.wait_for(r"Traduzir"))

    started = None
    shot_translating = False
    deadline = time.time() + 4 * 3600
    while time.time() < deadline:
        nodes = s.dump()
        if s.find(r"Página \d+ de \d+", nodes) or s.find(r"Baixando a voz do narrador", nodes):
            break
        if s.find(r"Baixando o tradutor", nodes) and not (args.out / "2_baixando.png").exists():
            s.screenshot("2_baixando", args.out)
        percent = s.find(r"\d+%", nodes)
        if s.find(r"Traduzindo para o português", nodes):
            if started is None:
                started = time.time()
                print("translating…")
            if percent and not shot_translating and int(percent.text[:-1]) >= 15:
                s.screenshot("3_traduzindo", args.out)
                shot_translating = True
                left = s.find(r"Fa(lta|ltam) .*", nodes)
                print(f"  {percent.text}", left.text if left else "")
        error = s.find(r"(Não foi possível|Não deu pra abrir|.*não traz o tradutor|.*erro na tradução).*", nodes)
        if error:
            raise RuntimeError(f"the app showed an error: {error.text}")
        # Back on the shelf without a book open: the reader closed, most likely because the app crashed.
        if s.find(r"Biblioteca", nodes):
            crash = s.adb("logcat", "-d", "-b", "crash", check=False).strip().splitlines()
            raise RuntimeError("the app left the reader during translation:\n" + "\n".join(crash[-15:]))
        time.sleep(5)
    if started is not None:
        print(f"translated in {(time.time() - started) / 60:.1f} min")

    s.wait_reader(timeout=1800)
    time.sleep(2)
    s.screenshot("4_livro_traduzido", args.out)
    s.back()
    s.wait_for(r"Biblioteca")
    time.sleep(3)
    s.screenshot("5_biblioteca", args.out)
    shelf = s.find(r".*\(pt-BR\)")
    print("on the shelf:", shelf.text if shelf else "no (pt-BR) title found")


if __name__ == "__main__":
    main()
