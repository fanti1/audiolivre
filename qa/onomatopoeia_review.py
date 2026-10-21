"""Builds the review page for the onomatopoeia catalogue: he marks each sound good or bad, and for a bad one
picks a better spelling among alternatives (or silence). His marks land in the page's database, which
qa can read back to fix the catalogue.

    ./gradlew testDebugUnitTest --tests "*OnomatopoeiaExamplesTest*"
    python qa/onomatopoeia_review.py <page.html>
"""

import base64
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import listen_check as lc  # noqa: E402

ROOT = Path(__file__).parent.parent
EXAMPLES = ROOT / "app" / "build" / "qa" / "onomatopoeia.jsonl"
VOICE = Path.home() / "qa-models" / "vits-piper-pt_BR-faber-medium"
BASE_SPEED = 0.70
SCREAM_SPEED = 0.45  # Prosody.SCREAM_SPEED

# Other ways the narrator could say each sound. The first option on the page is always what the app says
# today; these follow. An empty string is not listed here: "don't read it" is offered on every sound.
ALTERNATIVES = {
    "BUUUM!": ["Buuum!", "Bôm!", "Brum!"],
    "Pou!": ["Pá!", "Pum!", "Pôu!"],
    "Pof!": ["Pof!", "Póf!", "Pum!"],
    "Plaft!": ["Pláfti!", "Plafe!", "Plá!"],
    "Tum.": ["Tum!", "Tuum.", "Tunc."],
    "Créc.": ["Créque.", "Crec.", "Créqui."],
    "Crack!": ["Craque!", "Cráqui!", "Créc!"],
    "Cabrum!": ["Cabruum!", "Cabrúm!", "Ca-brum!"],
    "Zás!": ["Zaz!", "Záz!", "Zás, trás!"],
    "Bang!": ["Bang!", "Béngue!", "Bãg!"],
    "Pá!": ["Pou!", "Pâ!", "Pá, pá!"],
    "Ratatatá!": ["Ratatatatá!", "Tá, tá, tá!", "Ra, ta, ta, tá!"],
    "Nhééééc.": ["Nhééééc.", "Nhéque.", "Rééénc."],
    "Toc-toc-toc.": ["Toc toc toc.", "Tóque, tóque, tóque.", "Toc-toc-toc."],
    "Tic-tac.": ["Tic tac.", "Tique-taque.", "Tic-tac."],
    "Ding-dong.": ["Ding-dong.", "Dim dom.", "Dinnn, donnn."],
    "Trim!": ["Trimm!", "Trrrim!", "Trim, trim!"],
    "Plim.": ["Plin.", "Plim!", "Plííím."],
    "Click.": ["Clic.", "Click.", "Cléc."],
    "Bip.": ["Bip.", "Bííp.", "Bipi."],
    "Vroom!": ["Vruuum!", "Vroom!", "Brum!"],
    "Bzzzz.": ["Zuuum.", "Bzzz.", "Zzzum."],
    "Plic-ploc.": ["Plic ploc.", "Plique, ploque.", "Plic-ploc."],
    "Chuá!": ["Chuááá!", "Xuá!", "Tchuá!"],
    "Splash!": ["Splash!", "Esplash!", "Tchibum!"],
    "Glub.": ["Glub, glub.", "Glúbi.", "Glup."],
    "Au-au!": ["Au au!", "Au-au!", "Áu, áu!"],
    "Auuuuuu!": ["Auuuuuuuuu!", "Auu!", "Uuuuu!"],
    "Miau.": ["Miaaau.", "Miáu.", "Miau!"],
    "Muuuu.": ["Mu.", "Muuuuuu.", "Mú."],
    "Mééééé.": ["Mé.", "Mééé.", "Bééé."],
    "Cocoricó!": ["Cocoricóóó!", "Có, có, ri, có!", "Cocoricó."],
    "Quack!": ["Quá, quá!", "Quéc!", "Quack!"],
    "Oinc.": ["Oinc.", "Óinc.", "Oinque, oinque."],
    "Grrrrr.": ["Grrrrr.", "Grão.", "Rrrrr."],
    "Sssss.": ["Sssss.", "Chhhh.", "Xiii."],
    "Atchim!": ["Atchiiim!", "Atchú!", "Atchim."],
    "Cof-cof.": ["Cof, cof.", "Cof-cof.", "Cóf cóf."],
    "Snif.": ["Snif.", "Snife.", "Snif, snif."],
    "Buááá!": ["Buá!", "Buáááá!", "Uáá!"],
    "Hic!": ["Hic!", "Íque!", "Rique!"],
    "Argh!": ["Argh!", "Arg!", "Aaar!"],
    "Ugh.": ["Ugh.", "Urg.", "Úg."],
    "Aff.": ["Af.", "Aff.", "Afe."],
    "Arf.": ["Arf.", "Árf.", "Arfe."],
    "Pfff.": ["Pfff.", "Pff.", "Pufe."],
    "Brrr.": ["Brrr.", "Brr.", "Bru."],
    "Nham.": ["Nham, nham.", "Nhãm.", "Nhami."],
    "Slurp.": ["Slurp.", "Eslurp.", "Slurpe."],
    "Gulp.": ["Gulp.", "Glup.", "Gulpe."],
    "Shhhh.": ["Shhhh.", "Xiu.", "Chiu."],
    "Psst.": ["Psst.", "Pssiu.", "Ps."],
    "Hmmm.": ["Hummm.", "Hmmm.", "Um."],
    "Mmmm.": ["Mmmm.", "Hummm.", "Humm."],
    "Aham.": ["Aham.", "Arrã.", "Ahã."],
    "Uhum.": ["Umrum.", "Hum-hum.", "Unhum."],
    "Tsc, tsc.": ["Tsc, tsc.", "Tsk tsk.", "Tss, tss."],
    "Hahaha!": ["Hahaha!", "Rá, rá, rá!", "Há há há!"],
    "Hehehe.": ["Hehehe.", "Ré ré ré.", "Rê, rê, rê."],
    "Hihihi.": ["Hihihi.", "Ri ri ri.", "Rí, rí, rí."],
    "Huahuahua!": ["Huahuahua!", "Ruá, ruá, ruá!", "Uá uá uá!"],
    "Kkkkk.": ["Kkkkk.", "Ká ká ká.", "Rá rá."],
    "Rsrs.": ["Rsrs.", "Rs rs.", "Risinhos."],
    "Aaaaaaah!": ["Ah!", "Aaaah!", "Aaaaaaaaaah!"],
    "Aiiii!": ["Ai!", "Aiii!", "Aaai!"],
    "Uuuuuu.": ["Uuuuuu.", "Uuu.", "Uuuuuu!"],
    "Ohhh.": ["Óóóh.", "Ooooh.", "Ô."],
    "Fiuuu!": ["Fiu!", "Fiiiu!", "Fiuuuu!"],
    "Whoosh.": ["Whoosh.", "Vuuush.", "Fuuu."],
    "N-n-não.": ["N... n... não.", "Nã... nã... não.", "Não, não, não."],
    "S-s-sim.": ["S... s... sim.", "Si... si... sim.", "Sim, sim, sim."],
    "E-eu vi.": ["E... eu vi.", "Eu, eu vi.", "E-eu vi."],
}

# Second round: the sounds no option pleased, plus the ones he asked to recalibrate. Candidates follow his notes
# ("BUM soa VUM", "IZPLEXI", "QUAQUE", "OINQUE OINQUE", "Xiiiiii", "Urrum", laughs "mais rápido"). A "@1.3"
# suffix is a reading speed relative to the book's pace.
ROUND2 = {
    "BUUUM!": ["Bum!", "Bumm!", "Bôum!", "Buum!", "Bamm!", "Bum!@0.8", "Bãum!"],
    "Créc.": ["Créqui.", "Crééc.", "Creque.", "Crêque.", "Créc, créc.", "Crequi!"],
    "Crack!": ["Craque!", "Crac!", "Crááqui!", "Créqui!", "Tchac!", "Cráqui!"],
    "Zás!": ["Zás!", "Zás.", "Zááás!", "Zas!", "Zás!@0.8", "Zazz!"],
    "Bang!": ["Béngui!", "Bengue!", "Bêim!", "Bém!", "Bãn!", "Beng!", "Béin!"],
    "Ratatatá!": ["Ra-ta-tá!", "Tá-tá-tá-tá!", "Ratatá!", "Tratatá!", "Ratatatatatá!", "Rá-tá-tá-tá!"],
    "Nhééééc.": ["Nhééém.", "Rééééc.", "Nhiiiic.", "Nhééé.", "Criiic.", "Nheeeeeque."],
    "Click.": ["Clic!", "Clique!", "Clíc.", "Tlic.", "Tec.", "Clec."],
    "Bip.": ["Bííííp.", "Bip!", "Piiiii.", "Bípi.", "Bííp, bííp.", "Pííp."],
    "Vroom!": ["Vrummm!", "Vrããã!", "Vrum, vrum!", "Brummm!", "Vruuum!@0.8", "Vrrrum!"],
    "Bzzzz.": ["Bziii.", "Bzííí.", "Bizzz.", "Zííí.", "Bsiii.", "Bzíííí!"],
    "Splash!": ["Isplechi!", "Isplexi!", "Esplexi!", "Isplêchi!", "Esplechi!", "Isplésh!"],
    "Glub.": ["Glub, glub.", "Glubi.", "Glúb.", "Glóbi.", "Glu, glu.", "Gulub."],
    "Muuuu.": ["Muuu.", "Mú!", "Mũũũ.", "Múúúú.", "Mu, mu.", "Muuuuuu!"],
    "Quack!": ["Quaque!", "Cuáque!", "Quáque!", "Quá, quá!", "Quéqui!", "Cuáqui!"],
    "Oinc.": ["Óin que.", "Oin-que.", "Óinc, óinc.", "Oinquê.", "Óinque, óinque.", "Oin, quê."],
    "Grrrrr.": ["Grrrã.", "Rããã.", "Grauuu.", "Grrrau.", "Grrrom.", "Grã."],
    "Sssss.": ["Xiiii.", "Siiiii.", "Sssi.", "Xiiiiiii.", "Chiiiii.", "Tsiiii."],
    "Snif.": ["Isnif.", "Snífi.", "Snif, snif.", "Isnife.", "Snéf.", "Fungf."],
    "Buááá!": ["Buáááá.", "Buá, buá!", "Uáááá!", "Buéééé!", "Buaaaa!", "Bué!"],
    "Hic!": ["Ríqui!", "Híc!", "Iq!", "Ic, ic!", "Riic!", "Hic, hic!"],
    "Ugh.": ["Uuugh.", "Uuug.", "Ãaagh.", "Urgh.", "Uuuh.", "Úúg."],
    "Aff.": ["Afff.", "Afe!", "Aff!", "Áf!", "Aaaf.", "Afê."],
    "Pfff.": ["Pffff.", "Pfiiii.", "Fiiiii.", "Pfiu.", "Ffff.", "Pfíííí."],
    "Brrr.": ["Brrrr.", "Brrr!", "Brrrum.", "Brã.", "Bruuu.", "Brrrru!"],
    "Gulp.": ["Gulp!", "Gálpe.", "Glup!", "Gulpi.", "Gólp.", "Gulp, gulp."],
    "Shhhh.": ["Xiiiii.", "Xiiiiiii!", "Shiiiii.", "Xiii.", "Chiiiii.", "Psiiiiu."],
    "Psst.": ["Pssst.", "Psiu!", "Psss.", "Pssiii.", "Pst, pst.", "Psiiit."],
    "Hmmm.": ["Hmmmm.", "Hum...", "Hummmm.", "Humm, humm.", "Ummm.", "Mmmm."],
    "Mmmm.": ["Mmmmm.", "Hummmm!", "Ummm!", "Hmmm, hmmm.", "Mhmmm.", "Nhammm."],
    "Aham.": ["Ahã.", "Arrã!", "Ahan.", "Aham!", "A-hã.", "Ãhã."],
    "Uhum.": ["Urrum.", "Unrrum.", "Hum-rrum.", "Um-rum.", "Hum-hum.", "Urrúm."],
    "Hahaha!": ["Rá rá rá!@1.15", "Rá rá rá!@1.3", "Rá rá rá!@1.5", "Rá rá rá rá!@1.4"],
    "Hehehe.": ["Rê, rê, rê.@1.15", "Rê, rê, rê.@1.3", "Rê, rê, rê.@1.5", "Rê rê rê.@1.3"],
    "Kkkkk.": ["Ká ká ká.@1.0", "Ká ká ká.@1.15", "Ká ká ká.@1.3", "Ká ká ká ká.@1.2"],
    "Auuuuuu!": ["Auu!@0.7", "Auu!@0.8", "Auu!@0.9", "Auuu!@0.8"],
    "Uuuuuu.": ["Uuuuuuu!", "Uuuuu...", "Uuuuuh.", "Uhuuuu.", "Úúúúúú.", "Ôuuuuu."],
    "Whoosh.": ["Fuuuuch.", "Vuuush.", "Fiuuuch.", "Xuuum.", "Vuuummm.", "Fuuu, fuuu."],
}

VOWEL_RUN = re.compile(r"([aeiouáàâãéêíóôõú])\1{2,}", re.IGNORECASE)


def drawn_out(text):
    # Onomatopoeia.isDrawnOut: a vowel written three or more times, in a sentence of at most three words.
    return bool(VOWEL_RUN.search(text)) and len(text.split()) <= 3


def record(tts, text, factor=None):
    speed = BASE_SPEED * (factor if factor is not None else (SCREAM_SPEED if drawn_out(text) else 1.0))
    audio = lc.trim_silence(lc.synth(tts, text, 0, speed, 0), tts.sample_rate)
    with tempfile.TemporaryDirectory() as tmp:
        wav, ogg = Path(tmp) / "a.wav", Path(tmp) / "a.ogg"
        lc.save_wav(wav, audio, tts.sample_rate)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-c:a", "libvorbis",
                        "-q:a", "3", str(ogg)], check=True)
        return "data:audio/ogg;base64," + base64.b64encode(ogg.read_bytes()).decode()


def speed_label(factor):
    if factor is None:
        return ""
    if factor > 1:
        return f"{round((factor - 1) * 100)}% mais rápido"
    return f"{round((1 - factor) * 100)}% mais lento"


def round_two(dest):
    rows = [json.loads(l) for l in EXAMPLES.read_text(encoding="utf-8").splitlines() if l.strip()]
    tts = lc.piper_tts(VOICE, 8)
    groups = {}
    for n, row in enumerate(rows, 1):
        candidates = ROUND2.get(row["written"])
        if not candidates:
            continue
        options = []
        for k, cand in enumerate(candidates, 1):
            text, _, factor = cand.partition("@")
            factor = float(factor) if factor else None
            options.append({"id": f"c{k}", "text": text, "tag": speed_label(factor), "src": record(tts, text, factor)})
        groups.setdefault(row["group"], []).append({"id": f"s{n:02d}", "written": row["written"], "options": options})
        print(f"{n:02d} {row['written']:<14} {len(options)} opções", flush=True)
    data = {"mode": "pick", "collection": "round2", "groups": [{"title": g, "sounds": s} for g, s in groups.items()]}
    template = (ROOT / "qa" / "onomatopoeia_review.html").read_text(encoding="utf-8")
    Path(dest).write_text(template.replace("/*DATA*/null", json.dumps(data, ensure_ascii=False)), encoding="utf-8")
    print(f"{dest}: {Path(dest).stat().st_size // 1024} KB")


def main():
    if sys.argv[1] == "--round2":
        return round_two(sys.argv[2])
    rows = [json.loads(l) for l in EXAMPLES.read_text(encoding="utf-8").splitlines() if l.strip()]
    tts = lc.piper_tts(VOICE, 8)
    groups = {}
    for n, row in enumerate(rows, 1):
        options = [{"id": "atual", "text": row["spoken"], "src": record(tts, row["spoken"])}]
        for k, alt in enumerate(ALTERNATIVES.get(row["written"], []), 1):
            if alt == row["spoken"]:
                continue
            options.append({"id": f"alt{k}", "text": alt, "src": record(tts, alt)})
        groups.setdefault(row["group"], []).append({"id": f"s{n:02d}", "written": row["written"], "options": options})
        print(f"{n:02d} {row['written']:<14} {len(options)} opções", flush=True)

    data = {"mode": "verdict", "collection": "marks", "groups": [{"title": g, "sounds": s} for g, s in groups.items()]}
    template = (ROOT / "qa" / "onomatopoeia_review.html").read_text(encoding="utf-8")
    dest = Path(sys.argv[1])
    dest.write_text(template.replace("/*DATA*/null", json.dumps(data, ensure_ascii=False)), encoding="utf-8")
    print(f"{dest}: {dest.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
