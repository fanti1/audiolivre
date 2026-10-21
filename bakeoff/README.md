# Comparativo de vozes (Fase 9)

Gera o mesmo trecho de livro com todas as vozes offline candidatas (Piper, Kokoro, Supertonic 3), usando os mesmos modelos sherpa-onnx 1.13.8 que rodam no celular. Não entra no APK.

```
python bakeoff/generate.py
```

Saída: `bakeoff/out/*.mp3` + `bakeoff/out/manifest.json` (duração, tempo de síntese, RTF, velocidade usada, tom médio).
Calibração a 1.0: Piper ~199 palavras/min, Kokoro ~178, Supertonic ~177 → velocidades 0.76 / 0.84 / 0.85 para ~150 palavras/min.
