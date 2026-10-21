"""Builds the listening page: every take embedded, so it plays on the phone with no network.

    python qa/build_listen_page.py %USERPROFILE%\\qa-models\\out\\sota <output.html>

Reads the ogg files in <dir>/ogg and the measurements in scores.json and book/summary.json.
"""

import base64
import json
import sys
from pathlib import Path


def audio_uri(path):
    return "data:audio/ogg;base64," + base64.b64encode(path.read_bytes()).decode()


PASSAGE = ("Naquela noite, a cidade parecia diferente. Ele abriu a porta devagar, sem fazer barulho, e ficou "
           "ouvindo a própria respiração. O relógio marcava vinte e três horas e dez minutos. — Você ouviu isso? "
           "— perguntou ela, com a voz presa na garganta. — Ouvi. Não se mexa. O corredor estava frio, e o cheiro "
           "de terra molhada subia pelo assoalho. Ninguém morava ali havia trinta anos.")

# (file stem, display name, what it is) grouped by where it can run.
GROUPS = [
    ("No seu celular, hoje", "roda em tempo real dentro do app, sem internet",
     [("piper-faber", "Faber", "a voz que o app usa agora"),
      ("piper-dii", "Dii", "a feminina do app"),
      ("piper-miro", "Miro", "a mais grave do app"),
      ("kokoro-pf_dora", "Kokoro Dora", "outro modelo que caberia no app"),
      ("kokoro-pm_santa", "Kokoro Santa", "idem, voz masculina")]),
    ("Só na sua RTX 4070", "modelo grande; o celular não dá conta",
     [("f5-faber", "F5 imitando o Faber", "o Faber refeito pelo modelo grande"),
      ("f5-human-male", "F5 com narrador humano", "clonando um locutor brasileiro"),
      ("f5-human-female", "F5 com narradora humana", "clonando uma locutora brasileira"),
      ("cb-short-male", "Chatterbox", "o modelo que venceu a ElevenLabs em teste cego"),
      ("cb-short-female", "Chatterbox, voz feminina", "mesmo modelo, outra referência")]),
    ("Gente de verdade", "gravação humana de audiolivro — é o teto; o texto aqui é outro",
     [("human-male", "Locutor humano", "acervo BRSpeech, domínio público"),
      ("human-female", "Locutora humana", "acervo BRSpeech, domínio público")]),
]


def main():
    src = Path(sys.argv[1])
    dest = Path(sys.argv[2])
    scores = {r["name"]: r for r in json.loads((src / "scores.json").read_text(encoding="utf-8"))}
    book = {r["voice"]: r for r in json.loads((src / "book" / "summary.json").read_text(encoding="utf-8"))}

    tracks = []
    for title, note, items in GROUPS:
        rows = []
        for stem, name, what in items:
            ogg = src / "ogg" / f"{stem}.ogg"
            if not ogg.exists():
                continue
            s = scores.get(stem, {})
            rows.append({"name": name, "what": what, "src": audio_uri(ogg),
                         "utmos": s.get("utmos"), "wer": s.get("wer"),
                         "human": stem.startswith("human-")})
        if rows:
            tracks.append({"title": title, "note": note, "rows": rows})

    payload = {"tracks": tracks, "passage": PASSAGE,
               "book": [book[k] for k in sorted(book) if k in book]}
    html = (Path(__file__).parent / "listen_page.html").read_text(encoding="utf-8")
    dest.write_text(html.replace("/*DATA*/null", json.dumps(payload, ensure_ascii=False)), encoding="utf-8")
    kb = dest.stat().st_size // 1024
    print(f"{dest} — {kb} KB, {sum(len(g['rows']) for g in tracks)} takes")


if __name__ == "__main__":
    main()
