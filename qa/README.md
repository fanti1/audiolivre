# Listening QA — does the voice say what the page says?

`listen_check.py` synthesizes sentences exactly like the app does (Supertonic 3 int8 through sherpa-onnx, same
voices, speed, 5 steps, long sentences split like `SpeechChunker`), transcribes the audio with Whisper large-v3
on the GPU and compares, word by word, what the voice was asked to say with what a listener hears.

It found, among others: money with "milhões", dates, ranges, phone numbers, units, acronyms and e-mails read
wrongly, PDF ligatures and soft hyphens garbling words, and the voice repeating or dropping words in long
sentences (fixed by voicing them in pieces of up to 180 characters; more diffusion steps made it worse).

## Setup (once, on the PC)

    pip install sherpa-onnx==1.13.8 faster-whisper jiwer num2words nvidia-cublas-cu12 "nvidia-cudnn-cu12==9.*"
    # Supertonic model, as the app downloads it:
    curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2
    tar xjf sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2   # then point SUPERTONIC_DIR at it

## Inputs

- `stress_corpus.txt`: modern pt-BR sentences with the hard cases. `./gradlew testDebugUnitTest` runs
  `StressCorpusTest`, which normalizes it with the app's code into `app/build/qa/stress_normalized.jsonl`.
- A real book: `QaDumpTest` extracts `dom_casmurro.pdf` on a device or emulator and writes what the app would
  speak for every sentence:

      adb shell am instrument -w -e qaDump true -e class io.github.fanti1.audiolivre.QaDumpTest \
          io.github.fanti1.audiolivre.test/androidx.test.runner.AndroidJUnitRunner
      adb pull /sdcard/Android/data/io.github.fanti1.audiolivre/files/qa_sentences.jsonl

## Run

    python qa/listen_check.py app/build/qa/stress_normalized.jsonl --chunk 180 --out <dir>
    python qa/listen_check.py qa_sentences.jsonl --sample 600 --chunk 180 --out <dir>

Options: `--speed` (the app's multiplier), `--steps`, `--narrator MALE|FEMALE`, `--min-chars`.
It prints the word error rate by sentence length, the inserted/deleted word counts (repeats and skips) and the
worst sentences with what was heard. Whisper writes spelled acronyms back as acronyms ("cê pê éfe" → "CPF"),
so those count as errors in the numbers while being right to the ear.

## Comparing engines (2026-09-20)

Answers "is there a better model for pt-BR than the Piper voice the app uses?". The heavyweight models need
a GPU, so they live in their own environment and never touch the one above:

    python -m venv %USERPROFILE%\qa-models\venv-tts
    venv-tts\Scripts\pip install --index-url https://download.pytorch.org/whl/cu124 torch==2.6.0+cu124 torchaudio==2.6.0+cu124
    venv-tts\Scripts\pip install f5-tts chatterbox-tts

Run, in order:

    python qa/bakeoff_sota.py --out <dir>                  # Piper and Kokoro, 4 threads (a phone-CPU proxy)
    venv-tts\Scripts\python qa/bakeoff_f5.py --out <dir>   # F5-TTS pt-br, cloning the references in <dir>
    venv-tts\Scripts\python qa/reliability_f5.py --out <dir> --count 24
    venv-tts\Scripts\python qa/bakeoff_chatterbox.py --out <dir> --book 24
    python qa/score_wavs.py <dir>                          # naturalness (UTMOS) and word errors, one passage
    python qa/score_book.py <dir>                          # the same on 24 real book sentences
    python qa/build_listen_page.py <dir> <page.html>       # a page with every take embedded

`<dir>` needs reference clips: `ref-<voice>.wav` + `ref.txt` for the app's own voices (made by
`make_voice_samples.py`-style synthesis) and `human-*.wav` + `human-*.txt` for a real narrator. The human
clips come from BRSpeech-TTS, fetched row by row through the Hugging Face datasets server; they exist to
measure the ceiling and to give the cloning models something to copy, not to ship.

The deciding number is **broken sentences**: takes whose word error rate passes 20% are audibly garbled.

# Screenshots for the README

`screenshots.py` drives the app on an emulator the way a person would and saves the README screens to
`docs/screenshots/`. It downloads public-domain classics from Project Gutenberg in different formats (EPUB,
AZW3, TXT, and a PDF it typesets with a cover), adds each through the app's file picker, opens *Dom Casmurro* at
chapter I and reads it aloud. Along the way it captures:

- the library, in dark and light mode;
- the reader, its table of contents and its timer;
- the appearance sheet and the high-contrast look;
- the player in the notification shade;
- the voice screen and the manual.

The two translation screens (`traducao.png`, `traduzindo.png`) are steps 1 and 3 of `translate_walkthrough.py`.

The notification shot shows Android's own interface, so set the emulator to Portuguese first. This needs to be done only once per AVD:

    adb root
    adb shell "setprop persist.sys.locale pt-BR; setprop ctl.restart zygote"

    # once: an emulator (Android 15, Pixel 7)
    sdkmanager "platform-tools" "emulator" "system-images;android-35;google_apis;x86_64"
    avdmanager create avd -n audiolivre -k "system-images;android-35;google_apis;x86_64" -d pixel_7

    ./gradlew assembleDebug
    pip install reportlab pillow
    python qa/screenshots.py              # starts the "audiolivre" emulator if none is running
    python qa/screenshots.py --keep-data  # reuse the books and voice already in the app

The first run waits for the narrator voice to download (~80 MB). The status bar is put in demo mode, so every
run shows the same clock and a full battery.

## Translation

Two checks run against the real engine. Both need the engine's sources (`bash app/src/main/cpp/fetch_third_party.sh`) and internet the first time, to download Mozilla's models (~36 MB per direction).

- **Engine on the device**: translates English and Spanish book openings and logs each translation and the speed under the `TranslationEngineTest` tag:

      adb shell am instrument -w -e class io.github.fanti1.audiolivre.TranslationEngineTest \
          io.github.fanti1.audiolivre.test/androidx.test.runner.AndroidJUnitRunner
      adb logcat -d -s TranslationEngineTest:I

- **The whole flow, through the app**: adds a book in another language through the file picker and confirms the translation. It saves a screenshot of each step to `qa/out/translation/` and prints how long the translation took:

      python qa/translate_walkthrough.py "Die Verwandlung.epub"

On the emulator (x86_64, 4 cores), *Die Verwandlung* (19,500 words, German → English → Portuguese) took about 25 s. *Pride and Prejudice* (126,000 words, English → Portuguese) took about 1 min. Phones are slower; this has not been measured yet.

## Accessibility

With the emulator on and a book in the library:

    python qa/a11y_audit.py

The script goes through the Biblioteca, Sobre, Privacidade, Como usar and Voz screens, then the reader with and without its menu. It reads each screen's accessibility tree and lists every control that has no name for TalkBack or is smaller than 48 × 48 dp. It exits with 1 when it finds anything.

The manual TalkBack walkthrough and the open items are in [`docs/ACESSIBILIDADE.md`](../docs/ACESSIBILIDADE.md).

## Performance

Three instrumented tests measure the costly steps on the device. They log under `PerformanceProbe`, `TranslationSpeed` and `VoiceMemory`.

    adb shell am instrument -w -e class io.github.fanti1.audiolivre.PerformanceProbeTest io.github.fanti1.audiolivre.test/androidx.test.runner.AndroidJUnitRunner
    adb push book.epub /sdcard/Android/data/io.github.fanti1.audiolivre/files/bench.epub
    adb shell am instrument -w -e class io.github.fanti1.audiolivre.TranslationSpeedTest io.github.fanti1.audiolivre.test/androidx.test.runner.AndroidJUnitRunner
    adb shell am instrument -w -e class io.github.fanti1.audiolivre.VoiceMemoryTest io.github.fanti1.audiolivre.test/androidx.test.runner.AndroidJUnitRunner

**Reference numbers** (emulator x86_64, 4 cores, October 2026):

| What | Before | After |
|---|---|---|
| Open the saved *Dom Quixote* (6,383 paragraphs) | 156 ms, +28 MB of garbage, 5.0 MB on disk (JSON) | 12 ms, +8 MB, 2.3 MB (binary `BookStore`) |
| Translate 15,000 words en→pt | 2,000 words/s (2,500-char chunks) | 5,700 words/s (40,000-char chunks), with the same text word for word |
| Reopen a book with the voice already loaded | the voice synthesized "Olá." on every opening | about 100 ms, without the extra synthesis |
| Native memory with the reading paused for 2 min | ~750 MB held by the voice | ~50 MB (the voice is released and loads again on play) |
| Whole app while reading, on a phone with less than 6 GB of RAM | ~860 MB | ~240 MB (`VoiceMemoryMode.LEAN`), audio in real time, no stalls |
| Voice synthesis on a phone with 6 GB or more | 0.0203 s of work per s of speech, ~800 MB | 0.0179 s, ~620 MB (`VoiceMemoryMode.FAST`) |

The app also logs, in `events.log` (shown in the support report), how long each book took to open, which memory mode the voice engine chose, and when each voice was loaded.

**Voice memory.** sherpa-onnx reads ONNX Runtime session settings from a file named in its provider (`cpu:<file>`). [`VoiceMemoryMode`](../app/src/main/java/io/github/fanti1/audiolivre/tts/VoiceMemoryMode.kt) uses that to choose how the voice engine handles its working memory. `VoiceMemoryTest` compares four combinations, and the audio was identical sample for sample in all of them:

| ONNX Runtime setting | Memory while speaking | Processor time per second of speech |
|---|---|---|
| memory pool and memory pattern (the default) | ~800 MB | 0.0203 s |
| pool off, pattern off (LEAN) | ~60 MB | 0.0329 s |
| pool off, pattern on | ~77 MB | 0.0325 s |
| pool on, pattern off (FAST) | ~620 MB | 0.0179 s |

FAST beats the default on both counts. LEAN is chosen on phones with less than 6 GB of RAM, as long as the measured synthesis pace leaves room for its extra ~60% of processor time. Otherwise, a voice that can't keep up would be worse than a fuller memory.
