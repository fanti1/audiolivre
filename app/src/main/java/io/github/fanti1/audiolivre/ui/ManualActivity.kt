package io.github.fanti1.audiolivre.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fanti1.audiolivre.ui.theme.EbookTheme
import io.github.fanti1.audiolivre.ui.theme.enableEdgeToEdgeBars

// How to use the app, for whoever does not find their way on their own. Every step names a button or
// gesture the app really has: change the text when the screens change.
class ManualActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeBars()
        super.onCreate(savedInstanceState)
        setContent {
            EbookTheme {
                InfoScaffold(title = "Como usar", onBack = ::finish) {
                    ManualContent()
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ManualContent() {
    val open = rememberLinkOpener()

    Text(
        "O Audiolivre lê seus livros em voz alta. Você escolhe um arquivo, e ele vira um audiolivro com página, " +
            "voz e marcador de onde você parou.",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp),
    )

    PolicySection("1. Adicionar um livro") {
        Bullet("Na Biblioteca, toque em Adicionar livro e escolha o arquivo no seu celular.")
        Bullet("Funciona com PDF, EPUB, Kindle (MOBI e AZW3), Word (DOCX), ODT, RTF, FB2, HTML, TXT e Markdown.")
        Bullet("A voz do narrador já vem com o app: ele lê sem internet desde o primeiro livro.")
        Bullet("O arquivo continua onde estava no seu celular. O app só guarda o texto pronto para ler.")
    }

    PolicySection("2. Livros em outros idiomas") {
        Bullet("Se o livro não estiver em português, o app pergunta se pode traduzir. A tradução é feita no próprio celular, para o português do Brasil.")
        Bullet("O app reconhece o idioma sozinho. Se errar, toque em “Escolher o idioma”.")
        Bullet(
            "Na primeira vez, ele baixa o tradutor daquele idioma (cerca de 36 MB). Idiomas que não sejam o inglês " +
                "precisam de dois tradutores, porque a tradução passa pelo inglês.",
        )
        Bullet(
            "Traduzir um livro leva alguns minutos. Pode tocar em “Continuar em segundo plano”, sair do app ou apagar a " +
                "tela: a tradução segue, mostra o progresso numa notificação e avisa quando o livro estiver pronto.",
        )
        Bullet("Enquanto isso, a capa do livro na biblioteca mostra “Traduzindo…” e quanto falta.")
        Bullet("O livro traduzido aparece na biblioteca com (pt-BR) no nome. O arquivo original não muda.")
        Bullet("A tradução é automática e pode errar, principalmente em nomes, poesia e trocadilhos.")
    }

    PolicySection("3. Ouvir") {
        Bullet("Toque no meio da página para mostrar os controles. O botão do meio toca e pausa; as setas voltam ou avançam uma frase.")
        Bullet("Para ouvir a partir de um ponto, toque e segure numa frase e escolha “Ler a partir daqui”.")
        Bullet("A frase que está sendo lida fica destacada, e as páginas viram sozinhas.")
        Bullet("Pode bloquear a tela ou abrir outro app: a leitura continua, e os controles ficam na notificação.")
        Bullet("Os botões do fone de ouvido também funcionam: tocar e pausar, e avançar ou voltar uma frase.")
        Bullet("Para dormir ouvindo: toque no relógio, ao lado dos controles, e escolha parar depois de 15, 30, 45 ou 60 minutos, ou no fim do capítulo.")
    }

    PolicySection("4. Ler e navegar") {
        Bullet("Toque na borda direita da página para avançar e na esquerda para voltar, ou arraste para os lados.")
        Bullet("Sumário: vai direto para um capítulo.")
        Bullet("Aparência: fonte, tamanho do texto, espaço entre linhas, margens, alinhamento, tema da página e brilho.")
        Bullet("Com um teclado conectado, as setas viram a página e a barra de espaço toca e pausa.")
    }

    PolicySection("5. Vozes") {
        Bullet("No ícone de voz, no alto da Biblioteca, você escolhe o narrador e as vozes de personagem homem e mulher. Toque em ▶ para ouvir uma amostra.")
        Bullet("Com “Personagens com voz própria” ligado, as falas (com travessão ou entre aspas) ganham a voz do personagem.")
        Bullet("A velocidade vai de mais devagar a mais rápido; 1,0× é o ritmo de um audiolivro.")
        Bullet("Vozes e tradutores que você não usa podem ser apagados ali mesmo, para liberar espaço.")
    }

    PolicySection("6. Acessibilidade") {
        Bullet("Em Aparência há fontes feitas para baixa visão (Atkinson) e para dislexia (OpenDyslexic), letras até o tamanho 44 e o tema de página de alto contraste.")
        Bullet("O destaque da frase lida pode ser suave, forte ou sublinhado. O sublinhado não depende de distinguir cores.")
        Bullet("O modo foco escurece o resto da página e deixa só a frase da vez. Os controles grandes deixam os botões de tocar e pular mais fáceis de acertar.")
        Bullet(
            "Com o TalkBack, cada livro da biblioteca é um item só, com as opções nas ações. Na página, cada parágrafo é " +
                "lido em separado e tem as ações “Ler a partir daqui”, “Próxima página” e “Página anterior”. Os controles de " +
                "leitura ficam sempre na tela.",
        )
        Bullet("Se o celular estiver no modo claro, o app também fica claro.")
    }

    PolicySection("7. Biblioteca") {
        Bullet("“Continuar lendo” leva de volta ao livro em que você parou.")
        Bullet("Toque e segure uma capa, ou toque nos três pontinhos dela, para abrir, importar de novo, recomeçar do início ou remover o livro.")
        Bullet("“Importar de novo” lê o arquivo outra vez. Serve quando o texto saiu estranho.")
    }

    PolicySection("8. Quando algo dá errado") {
        Bullet(
            "A leitura para quando a tela apaga: o Android fechou o app para poupar bateria. Na tela Voz, em “Ler com a " +
                "tela desligada”, toque em “Permitir leitura em segundo plano”. Alguns celulares (Xiaomi, Samsung, Motorola) " +
                "também têm um ajuste próprio, em Bateria, para deixar o app sem restrição.",
        )
        Bullet("“Não foi possível extrair texto”: o PDF é escaneado, só com imagens, e não tem texto para ler.")
        Bullet("Livro com DRM: livros comprados em lojas costumam ter uma trava que só o app da loja abre.")
        Bullet("Arquivo .doc antigo: salve como .docx no Word ou no Google Docs e adicione de novo.")
        Bullet("Se o app fechar sozinho, ele mostra o erro na próxima vez que abrir. Toque em “Copiar erro” e mande para nós.")
        Bullet("Para pedir ajuda com qualquer problema, em Sobre → Relatório de suporte, copie ou compartilhe o relatório junto com a sua descrição. Ele não leva nada dos seus livros.")
    }

    InfoGroup("Ainda com dúvida?") {
        InfoRow("Perguntar no GitHub", "As issues são públicas: não coloque dados pessoais nelas", external = true) {
            open(AppLinks.ISSUES)
        }
    }
}
