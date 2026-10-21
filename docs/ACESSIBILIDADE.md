# Acessibilidade

Um app que lê livros em voz alta já nasce útil para quem não enxerga bem, tem dislexia ou se cansa lendo. O objetivo é que essas pessoas consigam usar **o app inteiro**, e não só ouvir o livro depois que alguém o abriu para elas.

Referências: [WCAG 2.2](https://www.w3.org/TR/WCAG22/) (nível AA), as [diretrizes de acessibilidade do Android](https://developer.android.com/guide/topics/ui/accessibility) e a Lei Brasileira de Inclusão (Lei 13.146/2015).

## Quem queremos atender

| Pessoa | O que ela precisa |
|---|---|
| Cega, usando TalkBack | Ouvir e acionar todos os botões; navegar no livro sem ver a tela |
| Com baixa visão | Letra grande, alto contraste, áreas de toque grandes, destaque forte da frase lida |
| Com dislexia | Fonte e espaçamento que ajudem; acompanhar o texto destacado enquanto ouve |
| Com pouca coordenação motora | Botões grandes, nada que dependa só de gestos precisos ou de "tocar e segurar" |
| Idosa ou com pouca familiaridade com celular | Telas simples, textos claros, o Manual à mão |

## O que o app já faz (16/10/2026)

**Leitor de tela (TalkBack)**
- Cada livro da biblioteca é **um item só** ("Dom Casmurro, 37% lido"). Dois toques abrem o livro, e "Importar de novo", "Recomeçar do início" e "Remover" estão nas ações do TalkBack.
- Na página, **cada parágrafo é um item**, lido na ordem. Cada um tem as ações **"Ler a partir daqui"**, **"Próxima página"** e **"Página anterior"**.
- Com o TalkBack ligado, os **controles de leitura ficam sempre na tela**.
- Títulos de tela e de seção são marcados como **cabeçalho**, para pular entre eles.
- O progresso de importação, download e tradução é **anunciado a cada 10%**.
- Todos os botões têm nome falado. O botão "Adicionar livro" e as vozes da tela Voz, que não tinham, foram corrigidos.

**Baixa visão e leitura**
- Fontes **Atkinson Hyperlegible**, feita para baixa visão, e **OpenDyslexic**, feita para dislexia.
- Letra até o tamanho **44**, respeitando também o tamanho de fonte do sistema.
- Tema de página de **alto contraste**: branco sobre preto, com a frase lida sobre azul (7:1).
- Destaque da frase lida **suave, forte ou sublinhado**. O sublinhado não depende de distinguir cores.
- **Modo foco**: escurece o resto da página e deixa só a frase da vez.
- O app segue o **modo claro ou escuro** do celular.

**Coordenação motora e uso sem tela**
- Todas as áreas de toque têm **pelo menos 48 × 48 dp**. O botão de opções das capas era menor e foi aumentado.
- **Controles grandes** opcionais no leitor (tocar com 96 dp, pular frase com 72 dp).
- Nada depende só de "tocar e segurar": as mesmas opções existem como botão ou como ação de acessibilidade.
- Os **botões do fone de ouvido**, do relógio e da notificação tocam, pausam e pulam frases.
- **Teclado**: as setas e Page Up/Down viram a página, e o espaço toca e pausa.
- **Timer para dormir**: para a leitura depois de 15 a 60 minutos, ou no fim do capítulo.

## O que falta

- [ ] **Testar com o TalkBack num celular de verdade**, seguindo o roteiro abaixo. No emulador, a árvore de acessibilidade foi conferida, mas a fala não.
- [ ] Confirmar que a narração **baixa o volume** quando o TalkBack fala. O Android faz isso sozinho com o áudio de mídia, mas falta ouvir na prática.
- [ ] **Switch Access** (controle por botões externos): conferir a ordem de foco em todas as telas.
- [ ] Gerar os prints do README com **fonte do sistema em 200%** e corrigir o que quebrar.
- [ ] **Testar com pessoas de verdade**: usuários cegos e com baixa visão. Instituições como a Fundação Dorina Nowill, a Laramara e o Instituto Benjamin Constant podem ajudar a encontrar voluntários.
- [ ] Rodar o **Accessibility Scanner** do Google em todas as telas.

## Como conferir antes de cada versão

1. **Auditoria automática**: com o emulador ligado e um livro na biblioteca, rode `python qa/a11y_audit.py`. Ela percorre Biblioteca, Sobre, Privacidade, Como usar, Voz e o leitor, e aponta controles sem nome falado ou menores que 48 dp. Na última rodada, deu 0 problemas.
2. **Roteiro com TalkBack** (Configurações → Acessibilidade → TalkBack):
   1. Na Biblioteca, deslize até "Adicionar livro", toque duas vezes e escolha um arquivo.
   2. De volta à Biblioteca, deslize até o livro. Deve ouvir o título e o progresso. Abra o menu de ações (deslize para cima e para a direita) e confira as opções.
   3. Abra o livro. Os controles devem estar visíveis. Deslize pelos parágrafos da página e use a ação "Ler a partir daqui" num deles.
   4. Pause e toque no botão "Ouvir". Use "Próxima página" nas ações de um parágrafo.
   5. Abra Sumário e vá para outro capítulo. Abra Aparência e troque o tema para "Contraste".
   6. Na tela Voz, escolha outra voz e ouça a amostra.
3. **Fonte grande**: em Configurações → Tela → Tamanho da fonte, use o máximo e passe pelas mesmas telas.

Problemas de acessibilidade podem ser relatados no GitHub, com o modelo **"Problema de acessibilidade"**.

## Como saber que uma tela está pronta

- Todos os botões e controles têm nome falado e área de toque de pelo menos 48 × 48 dp.
- Dá para fazer tudo nela só com TalkBack, só com teclado e com fonte do sistema em 200%.
- Nada depende só de cor para ser entendido.
- A auditoria (`qa/a11y_audit.py`) e o Accessibility Scanner não apontam problemas.
- Mudanças importantes (erro, progresso, conclusão) são anunciadas.
