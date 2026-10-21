# Vozes (Piper via sherpa-onnx)

O Audiolivre usa vozes **Piper** (modelos VITS) em português do Brasil, executadas no celular pelo [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Cada voz é baixada sob demanda da release `tts-models` do sherpa-onnx e guardada em `filesDir/tts/<voz>/`. Um arquivo `.complete` marca que o download terminou. Os dados de pronúncia do eSpeak NG (~18 MB), que vêm dentro de cada pacote de voz, são guardados uma vez só e compartilhados entre todas as vozes. Depois do download, o app funciona sem internet.

O catálogo fica em `tts/VoiceModel.kt`:

| voz | estilo | pacote | taxa |
|---|---|---|---|
| Faber | masculina, clara e firme (narrador padrão) | `vits-piper-pt_BR-faber-medium` | 22 050 Hz |
| Cadu | masculina, mais grave (personagens masculinos) | `vits-piper-pt_BR-cadu-medium` | 22 050 Hz |
| Dii | feminina (personagens femininas) | `vits-piper-pt_BR-dii-high` | 22 050 Hz |
| Jeff | masculina, jovem | `vits-piper-pt_BR-jeff-medium` | 22 050 Hz |
| Miro | masculina, grave e pausada | `vits-piper-pt_BR-miro-high` | 22 050 Hz |
| Edresson | masculina, gravação mais simples | `vits-piper-pt_BR-edresson-low` | 16 000 Hz (convertida para 22 050 Hz) |

Ao abrir o primeiro livro, só a voz do narrador é baixada. As vozes dos personagens são baixadas na tela **Voz**. Até lá, os personagens falam com a voz do narrador.

## Ajustes

- `noiseScale = 0.25` e `noiseScaleW = 0.65`, medidos em 24 frases de *Dom Casmurro* (`qa/tune_piper.py`). Abaixo disso a voz fica monótona; acima disso, instável.
- Cada voz tem um ritmo próprio (`pace`), calibrado para ~140 palavras por minuto na velocidade 1,0×.

## Histórico

Versões anteriores testaram o Supertonic 3 e o Kokoro (`bakeoff/`). O Piper ficou por soar mais natural em pt-BR e ser rápido no celular. O app apaga o modelo do Supertonic baixado por versões antigas (`VoiceModels.LEGACY_IDS`).

## Licenças

- Faber, Cadu e Jeff: dados de treino CC0 ([OHF-Voice/voice-datasets](https://github.com/OHF-Voice/voice-datasets)).
- Edresson: CC BY 4.0 ([TTS-Portuguese Corpus](https://github.com/Edresson/TTS-Portuguese-Corpus), Edresson Casanova).
- Miro e Dii: veja o `MODEL_CARD` dentro de cada pacote.
- eSpeak NG (pronúncia, embutido no sherpa-onnx): GPL-3.0. Por isso o Audiolivre também é GPL-3.0.

# Tradutores (Firefox Translations via slimt)

Livros que não estão em português são traduzidos no celular com os modelos do tradutor do **Firefox** (projeto Bergamot, da Mozilla). Quem roda esses modelos é o **slimt**, compilado com o NDK a partir de `app/src/main/cpp/third_party` (veja `fetch_third_party.sh`).

- **Origem:** o app lê a mesma lista que o Firefox usa, a coleção `translations-models` do Remote Settings da Mozilla. Ela é guardada em `filesDir/translation/catalog.json` e renovada uma vez por semana. Cada direção (por exemplo `en-pt`) baixa um modelo, um vocabulário e uma lista de palavras prováveis, cerca de 36 MB no total, conferidos pelo SHA-256.
- **Versões:** de cada direção, o app usa a versão mais nova com número principal 1 (modelos "tiny") ou 2 ("base"), ignorando pré-lançamentos e builds só para desktop.
- **Rota:** quase todos os modelos vão de um idioma para o inglês e do inglês para outro. Por isso espanhol, francês etc. passam pelo inglês: dois modelos, um depois do outro.
- **Frases:** o slimt espera uma frase por vez. O app separa os parágrafos em frases (`translate/SourceSentences.kt`) e corta frases com mais de 50 palavras em vírgulas ou ponto e vírgula.
- **Retomada:** cada bloco traduzido é gravado em `filesDir/translation/journal/`. Se o app fechar no meio, a tradução continua de onde parou.
- **Velocidade medida no emulador** (x86_64, 4 núcleos): Kafka, *Die Verwandlung* (19.500 palavras, alemão → inglês → português), em cerca de 25 s; *Pride and Prejudice* (126.000 palavras, inglês → português), em cerca de 1 min. Num celular ainda não foi medido.
- **Licenças:** modelos MPL 2.0 e slimt GPL-2.0 ou posterior. SentencePiece e ruy são Apache 2.0, e cpuinfo é BSD.
