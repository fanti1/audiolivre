"""The text the test corpus is built from, written here so the expected output is known exactly.

Each document is a list of blocks. A block is (kind, text) where kind is one of:
  heading   a chapter or section title
  para      a paragraph of body text
  scene     a scene break; carries no text
  caption   a figure caption
  note      a footnote, printed at the foot of its page
  list      one item of a list

qa/make_corpus.py lays these out as PDFs with the layouts that break parsers — drop caps, running heads,
two columns, hyphenation across pages — and writes the same blocks out as the expected extraction.
"""

# A commercial novel in modern spelling: dialogue with dashes, speech tags of every shape, named speakers of
# both genders, scene breaks, and the numbers and abbreviations a narrator has to say properly.
NOVEL = [
    ("heading", "Capítulo 1"),
    ("heading", "A casa da esquina"),
    ("para", "Marina chegou à casa da esquina pouco depois das sete da noite, quando a rua já tinha esvaziado "
             "e as janelas dos vizinhos começavam a acender uma a uma. Levava na mão um envelope amassado que "
             "não abrira desde a manhã, e a certeza incômoda de que alguém a observava desde o portão."),
    ("para", "A porta estava encostada. Empurrou-a com a ponta dos dedos e esperou, contando até dez, antes de "
             "entrar. O corredor cheirava a poeira e a alguma coisa doce que não soube nomear."),
    ("para", "— Tem alguém aí? — perguntou ela."),
    ("para", "Ninguém respondeu. O silêncio tinha textura, como se a casa inteira estivesse prendendo a "
             "respiração junto com ela."),
    ("para", "— Você demorou, disse Rafael, saindo da sombra da escada."),
    ("para", "— Você me assustou. — Marina encostou-se na parede. — Podia ter acendido a luz."),
    ("para", "— A luz está cortada desde terça-feira. Vieram cortar às oito da manhã, e eu não estava em casa "
             "para discutir."),
    ("para", "— Que é? perguntou-lhe ela, apontando o envelope."),
    ("para", "— A conta. Mil quinhentos e trinta reais, e eles querem tudo até o dia 15."),
    ("scene", ""),
    ("para", "Passaram o resto da noite na cozinha, com uma vela entre os dois e o envelope aberto sobre a mesa. "
             "Rafael leu o documento três vezes antes de admitir que não entendia metade dos termos."),
    ("para", "— Precisamos de um advogado — murmurou ele."),
    ("para", "— Precisamos de dinheiro — corrigiu Marina. — O advogado vem depois."),
    ("para", "Do lado de fora, um cachorro latiu duas vezes e parou. Eram quase 23h30 quando a vela se apagou "
             "sozinha, sem vento nenhum que justificasse, e os dois ficaram no escuro sem dizer nada."),
    ("heading", "Capítulo 2"),
    ("heading", "O que o Sr. Almeida sabia"),
    ("para", "O escritório do Sr. Almeida ficava no segundo andar de um prédio sem elevador, entre uma "
             "costureira e um consultório odontológico que fechara havia anos. Marina subiu os degraus "
             "devagar, incomodada com o barulho dos próprios sapatos."),
    ("para", "— A senhora é a filha do Joaquim? — perguntou a secretária, sem levantar os olhos."),
    ("para", "— Sou a sobrinha."),
    ("para", "— Dá no mesmo. Ele está esperando desde as duas."),
    ("para", "Almeida era um homem pequeno, de óculos redondos, que falava como se estivesse sempre concluindo "
             "um raciocínio começado antes da chegada do interlocutor. Apontou a cadeira sem cumprimentar."),
    ("para", "— A senhora sabe o que é uma escritura de doação em vida? — disse ele. — Porque o seu tio sabia, "
             "e fez questão de assinar uma, em 1998, na presença de duas testemunhas."),
    ("para", "— Ninguém nunca me falou disso."),
    ("para", "— Ninguém tinha motivo para falar. — Almeida abriu uma gaveta. — Até agora."),
    ("scene", ""),
    ("para", "Marina saiu do prédio com a cópia na bolsa e a sensação de ter entrado numa história que já "
             "estava sendo contada havia muito tempo, sem ela. Atravessou a praça, comprou um café na esquina "
             "e telefonou para Rafael três vezes antes de ele atender."),
    ("para", "— Ele confirmou tudo — disse ela."),
    ("para", "— Tudo mesmo?"),
    ("para", "— Tudo. E tem mais coisa que ele não quis dizer por telefone."),
]

# Popular science: subheadings, figure captions, footnotes, a list, numbers and units.
SCIENCE = [
    ("heading", "3. Como o cérebro mede o tempo"),
    ("para", "Não existe no cérebro humano um relógio único. O que existe é um conjunto de circuitos que "
             "estimam a passagem do tempo em escalas diferentes, de milissegundos a horas, e que discordam "
             "entre si com uma frequência desconfortável para quem gosta de explicações simples."),
    ("para", "A escala mais curta, medida em dezenas de milissegundos, é a que permite entender a fala. Quando "
             "alguém pronuncia as palavras “pato” e “bato”, a diferença física entre as duas está num intervalo "
             "de cerca de 20 ms no início da consoante."),
    ("heading", "3.1 O núcleo supraquiasmático"),
    ("para", "O relógio circadiano fica num aglomerado de aproximadamente 20 mil neurônios no hipotálamo, o "
             "núcleo supraquiasmático. Ele funciona mesmo quando isolado do corpo, num prato de cultura, o que "
             "levou décadas para ser aceito."),
    ("list", "a estimativa de intervalos curtos, abaixo de um segundo;"),
    ("list", "a estimativa de intervalos médios, de segundos a minutos;"),
    ("list", "o ciclo circadiano, de cerca de 24 horas."),
    ("para", "Essas três escalas usam mecanismos distintos, e uma lesão que destrói uma delas costuma poupar as "
             "outras duas."),
    ("caption", "Figura 3.1 — Registro da atividade elétrica de um único neurônio do núcleo supraquiasmático "
                "ao longo de 48 horas."),
    ("note", "Em pacientes com lesão no cerebelo, o erro na estimativa de intervalos curtos chega a 40%."),
    ("para", "O resultado prático é que a sensação de que o tempo passou depressa ou devagar não é uma ilusão "
             "isolada: é o desacordo entre dois sistemas que mediram a mesma duração e chegaram a números "
             "diferentes."),
]

# A two-column article: the layout that breaks reading order, plus citations and a references section.
ARTICLE = [
    ("heading", "Avaliação de um leitor automático de PDF para português brasileiro"),
    ("para", "Resumo — Este trabalho avalia a extração de texto de documentos em PDF para fins de leitura em "
             "voz alta. São considerados três tipos de documento: romance comercial, divulgação científica e "
             "artigo em duas colunas. A métrica adotada é a taxa de erro de caractere em relação ao texto de "
             "origem."),
    ("heading", "1. Introdução"),
    ("para", "A conversão de PDF para texto contínuo é tratada com frequência como um problema resolvido, o "
             "que não corresponde à prática [1]. Documentos diagramados em duas colunas, com cabeçalho "
             "corrente e notas de rodapé, produzem sequências de linhas cuja ordem de leitura não é a ordem "
             "em que aparecem no arquivo [2], [3]."),
    ("para", "Silva et al. (2020) demonstraram que a taxa de erro cresce com o número de elementos flutuantes "
             "na página. O presente trabalho reproduz esse resultado e acrescenta uma análise por tipo de "
             "defeito."),
    ("heading", "2. Método"),
    ("para", "Foram gerados documentos sintéticos a partir de um texto de origem conhecido, o que permite medir "
             "o erro sem anotação manual. Cada documento recebeu uma diagramação distinta, mantendo o conteúdo "
             "constante."),
    ("para", "A extração foi executada no próprio dispositivo de destino, um aparelho Android, com a mesma "
             "biblioteca usada em produção, de modo que o resultado medido é o resultado entregue ao usuário."),
    ("heading", "3. Resultados"),
    ("para", "A taxa de erro de caractere ficou abaixo de 1% para o romance e para a divulgação científica, e "
             "acima desse valor para o artigo em duas colunas, concentrada nas transições entre colunas."),
    ("para", "O corpus é composto por documentos gerados automaticamente, de modo que o texto de origem "
             "permanece disponível para comparação. Essa escolha elimina a necessidade de anotação humana e "
             "torna o experimento reprodutível por terceiros, bastando executar o gerador."),
    ("para", "Cada documento foi diagramado com os elementos que a literatura aponta como causadores de erro: "
             "cabeçalho corrente alternando entre páginas pares e ímpares, numeração de página, capitular no "
             "início de capítulo, quebra de cena sinalizada por asteriscos e hifenização no fim de linha."),
    ("para", "A comparação entre o texto extraído e o texto de origem foi feita bloco a bloco, com "
             "alinhamento por distância de edição. Um bloco é considerado perdido quando não encontra "
             "correspondente com similaridade superior a oitenta por cento."),
    ("heading", "4. Discussão"),
    ("para", "Os erros observados concentram-se em três categorias. A primeira é a fusão de blocos distintos "
             "num único parágrafo, que ocorre quando o cabeçalho corrente não é removido antes da montagem e "
             "acaba inserido entre duas linhas de texto corrido."),
    ("para", "A segunda categoria é a perda da primeira letra de um capítulo, causada pela capitular, que o "
             "extrator interpreta como um elemento gráfico independente por estar em corpo muito maior que o "
             "restante da linha."),
    ("para", "A terceira é a inversão da ordem de leitura em documentos de duas colunas, na qual linhas da "
             "coluna da direita são intercaladas com linhas da coluna da esquerda, produzindo um texto "
             "sintaticamente impossível."),
    ("para", "Nenhuma das três categorias exige aprendizado de máquina para ser corrigida. Todas dependem de "
             "informação geométrica já disponível no arquivo, desde que o extrator a preserve em vez de "
             "descartá-la ao concatenar as linhas."),
    ("heading", "5. Conclusão"),
    ("para", "A extração de texto para leitura em voz alta exige critérios mais rigorosos do que a extração "
             "para indexação. Um erro que passa despercebido numa busca textual torna-se imediatamente "
             "audível quando o texto é pronunciado, e por isso a métrica adotada penaliza igualmente "
             "supressões e inserções."),
    ("heading", "Referências"),
    ("para", "[1] A. Pereira, “Extração de texto em documentos digitalizados”, Revista Brasileira de "
             "Computação, v. 12, n. 3, p. 45-61, 2019."),
    ("para", "[2] M. Souza e R. Lima, “Ordem de leitura em documentos multicoluna”, Anais do Simpósio de "
             "Documentos Digitais, p. 112-120, 2021."),
    ("para", "[3] J. Silva, C. Costa e F. Dias, “Layout analysis revisited”, Journal of Document Engineering, "
             "v. 8, p. 1-17, 2020."),
]

DOCUMENTS = {"romance": NOVEL, "ciencia": SCIENCE, "artigo": ARTICLE}
