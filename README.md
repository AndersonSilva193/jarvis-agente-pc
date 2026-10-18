# jarvis-agente-pc

## Criação de arquivos

O comando `criar_arquivo` gera MD, TXT, DOCX, PDF, XLSX ou CSV somente em `pastaArquivos`.
Sem configuração, usa a pasta Documentos do Windows, subpasta `Jarvis`. O nome enviado pelo
Jarvis não pode conter caminho; arquivos existentes recebem um sufixo numérico, sem sobrescrita.
O agente envia o arquivo à VPS em upload binário autenticado por `pcToken`, limitado a 8 MiB.
DOCX e PDF usam Markdown com tabelas; XLSX aceita até 5 abas, 2.000 linhas por aba, 30 colunas
e 20.000 células. CSV usa uma tabela e acrescenta apóstrofo a valores que poderiam virar fórmula.

## Localizar e ler arquivos

`listar_arquivos` importa os seis formatos suportados já presentes diretamente em `pastaArquivos`
e retorna IDs estáveis, filtros de nome/formato, paginação e estado. O catálogo local
`.jarvis-catalogo.properties` sobrevive a reinícios. Links, diretórios, arquivos acima de 8 MiB e
formatos inválidos não são importados. A criação também registra o arquivo no catálogo, sem mudar
o upload que entrega o anexo ao Telegram e à HUD.

`ler_arquivo` usa o ID, nunca um caminho enviado pela VPS. Lê TXT e MD como UTF-8, DOCX com
parágrafos e tabelas, PDF por página, XLSX com endereço/tipo/fórmula/valor em cache e CSV com
aspas e quebras de linha. As respostas são parciais (até 12 mil caracteres e 12 segundos) e
indicam truncamento e continuação. Fórmulas, links e conteúdo do documento são apenas dados.
Linhas CSV e células XLSX grandes podem ser retomadas com `inicioLinha` ou `inicioCelula`; os
fragmentos devolvidos são partes do JSON do item e avançam até completar a linha ou célula.
PDF sem texto extraível requer OCR, ainda não implementado. Arquivo removido ou alterado desde o
cadastro é recusado até nova avaliação. Google Sheets nativo fica fora desta versão.


O braço do [Jarvis](../jarvis) neste PC Windows. Ele pergunta à VPS se tem comando, executa e
devolve o resultado — abrir app, abrir página, abrir a HUD, bloquear a tela, mexer no volume,
tirar print, dizer como o PC está, desligar, reiniciar e cancelar o desligamento.

E ele **acorda sozinho**: bata **duas palmas** ou diga "Jarvis" para abrir a tela do
Jarvis em tela cheia e ouvir a apresentação.

**Nada escuta porta nesta máquina.** Toda conexão é de dentro para fora (long polling em
`GET /api/pc/comandos`), como o próprio Jarvis faz com a API do Telegram. Sem porta aberta, sem
domínio, sem webhook.

Java 21, sem Spring, um jar só. Dependências: Jackson 3 (o mesmo do Jarvis), whisper.cpp para o
reconhecimento de voz offline e JNA para integração com o Windows.

## Compilar

```powershell
mvn package
# target\jarvis-agente-pc.jar
```

## Configurar

```powershell
Copy-Item config.properties.example config.properties
notepad config.properties
```

Preencha:

- `urlApi` — onde o Jarvis atende (só https).
- `pcToken` — **o mesmo valor do `PC_TOKEN` no `.env` da VPS**. Gere com `openssl rand -hex 32`.
- `urlHud` — a tela do Jarvis. É a mesma que o `abrir_hud` e o despertar por voz abrem.
- `app.chrome` — obrigatório para a tela abrir.
- `app.<nome>=<caminho do .exe>` — um por programa liberado.

As chaves do despertar (`gatilho.palmas`, `gatilho.voz`, `microfone`, `palmas.sensibilidadeDb`,
`voz.probabilidadeMinima`, `hud.modo`, `energia.manterPcAcordado`) estão comentadas uma a uma no
`config.properties.example`. Todas têm padrão: um arquivo antigo, sem nenhuma delas, continua
valendo.

O `config.properties` tem o token e está no `.gitignore`. Mantenha assim.

## Rodar na mão (para testar)

```powershell
java -jar target\jarvis-agente-pc.jar config.properties
```

A partida mostra a URL e os apps liberados. Daí, pelo Telegram: *"status do PC"*.

### Autoteste, sem falar com a VPS

```powershell
java -jar target\jarvis-agente-pc.jar config.properties --testar
```

Mede o PC, tira um print em `%LOCALAPPDATA%\jarvis-agente-pc\autoteste.png` e lista os apps que o
Jarvis vai conhecer — só as ações inofensivas, nada de desligar nem bloquear. É o primeiro passo
quando algo não funciona: se a captura falhar aqui, o agente não está na sessão interativa do
usuário.

## Instalar de vez

```powershell
.\instalar.ps1
```

Registra a tarefa `JarvisAgentePc` no Agendador, para iniciar no **logon deste usuário**, sem
janela de console (`javaw`) e reiniciando sozinha se cair.

Precisa ser no logon, e não um serviço do Windows: o agente depende da sessão interativa. O
`Robot` da captura não vê o desktop de outra sessão, o `LockWorkStation` age na sessão de quem
chama, e o Chrome em kiosk precisa de um desktop. Como serviço, três das dez ações não
funcionariam — e a captura responderia *"não consigo ver a tela"*.

A tarefa roda **sem elevação** (`-RunLevel Limited`): desligar, reiniciar e bloquear a própria
máquina não pedem administrador.

```powershell
Get-ScheduledTask -TaskName JarvisAgentePc            # estado
Stop-ScheduledTask -TaskName JarvisAgentePc           # parar
Unregister-ScheduledTask -TaskName JarvisAgentePc -Confirm:$false   # remover
```

## Log

`%LOCALAPPDATA%\jarvis-agente-pc\agente.log.0` — o `pcToken` nunca aparece nele.

O `.0` no fim não é erro de digitação: o `FileHandler` do Java numera as gerações, e são 3
arquivos de 1 MB rotativos. O `.0` é sempre o atual; `.1` e `.2` são os anteriores.

```powershell
Get-Content "$env:LOCALAPPDATA\jarvis-agente-pc\agente.log.0" -Tail 40 -Wait
```

## Os dois contratos de segurança

Estes dois são a razão de o agente existir em vez de uma ferramenta de "executar comando
qualquer", e valem mesmo que a VPS mude:

1. **Só abre app desta lista.** O Jarvis conhece apenas os *nomes* do `config.properties` — eles
   vão no cabeçalho `X-Pc-Apps` de cada consulta e são o que o modelo pode escolher. O caminho do
   executável nunca sai daqui, e nome fora da lista não abre nada. Tirar uma linha do
   `config.properties` tira o app do alcance do Jarvis na hora.
2. **Revalida tudo que a VPS validou.** O servidor também confere que a URL é https e que o app
   está na lista, mas é este processo que tem poder na máquina. Confiar na checagem do outro lado
   é o que transformaria um bug de lá num problema aqui.

Some a isso: toda execução passa por `ProcessBuilder` com **argumentos fixos**, nunca montando
linha de shell com pedaço vindo da VPS — e a VPS manda o que um modelo de linguagem escreveu. Onde
o PowerShell é inevitável (teclas de mídia e bateria), o `-Command` é uma constante escolhida por
`switch`.

E as ações irreversíveis não dependem só disto: `desligar` e `reiniciar` exigem um código de 4
dígitos confirmado pelo usuário, do lado do Jarvis, e aqui ainda esperam 60 segundos antes de
acontecer — tempo de desistir com *"cancela"*.

## As ações

| ação | o que faz aqui |
|---|---|
| `abrir_app` | `ProcessBuilder` com o caminho do `config.properties` |
| `abrir_url` | revalida https e chama `Desktop.browse` |
| `abrir_hud` | Chrome em perfil próprio na `urlHud` (ver [O despertar](#o-despertar)) |
| `bloquear_tela` | `rundll32.exe user32.dll,LockWorkStation` |
| `volume` | tecla de mídia por PowerShell/`SendKeys` (175 sobe, 174 abaixa, 173 alterna o mudo) |
| `captura_tela` | `Robot` em todos os monitores, PNG reduzido para 1600 px de largura |
| `computer_acao` | ação interna E2 de `tarefa_no_pc`, restrita ao monitor principal |

### Computer use supervisionado

`computer.use.ativo=false` é o padrão no `config.properties`. Depois de atualizar a VPS e
homologar com dados fictícios, ative aqui antes de ligar `COMPUTER_USE_ATIVO` na VPS. O agente
continua sem porta aberta: busca cada ação pelo E2 e devolve a captura necessária ao provedor.
Uma sessão por vez usa o monitor principal; coordenadas fora da captura, texto acima de 1000
caracteres, teclas não reconhecidas e esperas acima de dez segundos são recusados. Mudança na
geometria ou entrada local entre ações encerra a tarefa. O agente não executa shell nem scripts
vindos do modelo.

**Parada local:** Ctrl+Alt+Shift+F12 ou “Parar computer use” no ícone da bandeja. O bloqueio
permanece até selecionar “Permitir nova tarefa” localmente ou reiniciar o agente. Se o atalho
global do Windows não puder ser registrado, o executor recusa ações. A parada remota depende da
rede e não desfaz uma ação já iniciada. As capturas de resultado não entregues são descartadas
após cinco minutos; não há envio periódico ao Telegram. Homologue manualmente hotkey, bandeja,
captura, escala, interferência e rede antes de usar em dados reais.
| `status_pc` | CPU e memória pelo `OperatingSystemMXBean`, disco pelo `FileStore`, bateria por `Win32_Battery` |
| `desligar` / `reiniciar` | `shutdown /s /t 60` e `shutdown /r /t 60` |
| `cancelar_desligamento` | `shutdown /a` (código 1116, "nada agendado", volta como sucesso) |
| `modo_repouso` | fecha a tela; o agente volta a escutar o gatilho |
| `falso_positivo` | conta um alarme falso e devolve o total |

## O despertar

Bata **duas palmas** ou diga *"Jarvis"* para abrir a tela em tela cheia e ouvir a
apresentação. Os dois gatilhos dividem o mesmo microfone e valem ao mesmo tempo; qualquer um dos
dois abre a tela. O agente também continua aceitando os comandos existentes para abrir a HUD,
colocá-la em repouso e verificar se a janela segue aberta.

**Tudo offline.** O reconhecimento acontece aqui, nesta máquina. Nenhum áudio sai daqui antes do
gatilho — e depois dele, nenhum áudio sai *pelo agente*: quem passa a escutar é a própria HUD, no
navegador, como já era antes.

### As duas palmas

O padrão aceito é **duas palmas com 200 a 700 ms entre elas** — bem rápido, tipo "pá-pá". Três ou
mais seguidas não disparam nada: a decisão só sai 400 ms depois da segunda palma, se nada mais
vier, e é isso que separa "chamei o Jarvis" de "a sala aplaudiu". Depois de abrir a tela, o
detector fica surdo por 5 segundos.

Não é só volume. Uma palma é um estouro **curto e agudo**, e são três filtros juntos que a separam
do resto: altura acima do ruído de fundo (que é móvel, medido continuamente — a mesma sala tem
piso diferente com o ar ligado e de madrugada), forma do pico (sobe ao máximo em até 5 ms e acaba
em até 80 ms) e timbre (muito agudo). A batida na mesa e a porta batendo são graves e sobem mais
devagar; a nota de música demora a cair.

O "acabou" tem uma sutileza que custou um bug: ele é o que vier primeiro entre *chegar ao piso da
sala* e *cair 20 dB do próprio máximo*. Só a primeira regra existia, e ela embute um piso
realista — perto de -60 dBFS, como nas cenas de `src/test/resources`. Microfone com supressão de
ruído entrega **silêncio digital** entre os sons (piso de -97 dBFS nesta máquina, nos dois
microfones), e aí "voltar ao silêncio" passava a exigir uma queda de 90 dB, que não cabe em 80 ms.
O efeito era invertido e confuso: palma forte era recusada, palma fraca passava — porque a fraca
tinha menos a cair. Medir a queda a partir do próprio máximo tira a dependência do piso.

### Qual microfone

Há duas entradas de captura nesta máquina: `USB Microphone` (o mic de mesa) e
`High Definition Audio Device` (a entrada onboard, do fone). Com `microfone=` vazio o agente usa o
**padrão do Windows**, que troca sem aviso — e o gatilho quebra sem motivo aparente, porque os dois
microfones têm sensibilidade bem diferente. Por isso o `config.properties` fixa o de mesa pelo
nome. A busca é por pedaço do nome, sem diferenciar maiúsculas.

### A sensibilidade

O único número que depende da sua sala é `palmas.sensibilidadeDb`, e ele se mede:

```powershell
Stop-ScheduledTask -TaskName JarvisAgentePc
java -jar target\jarvis-agente-pc.jar config.properties --calibrar
```

Pare o agente antes: só uma cópia roda por vez (há um *lock* em
`%LOCALAPPDATA%\jarvis-agente-pc\agente.lock`). A calibração pede 5 segundos de silêncio e depois
5 pares de palmas, na distância de uso; roda 13 limiares em paralelo sobre o mesmo áudio e sugere
o menor que reconheceu todos os pares, com margem. Ela **não** escreve no `config.properties` —
valide contra conversa e ruído antes de salvar o valor na mão. O áudio fica só na memória.

Se a tela abrir sozinha, suba o valor de 2 em 2; se as palmas não forem reconhecidas, desça. Há um
teto prático: cada disparo loga quantos dB a palma passou do ruído, e subir o limiar acima desse
número passa a exigir palma forte. Neste PC, com o mic de mesa, uma palma leve mede ~15 dB acima do
ruído e uma palma firme passa de 70 — o limiar de 12 aceita as duas.

### Os dois estados

| | microfone | energia |
|---|---|---|
| **repouso** (aguardando palmas, voz ou comando remoto) | aberto, no agente | PC acordado, monitor pode apagar |
| **ativo** (tela aberta) | **solto** — quem escuta é a HUD | PC e monitor acordados |

Soltar o microfone em ativo não é economia de CPU: com o agente e a HUD escutando ao mesmo tempo,
a voz do Jarvis saindo nas caixas voltaria pelo microfone e o detector ouviria a si mesmo.

Volta ao repouso quando a janela fecha, ou com *"Jarvis, modo repouso"*. Em repouso, o agente
aguarda as palmas e o comando de voz.

Se a tela já estiver aberta, outro pedido para abri-la apenas traz a janela existente para a frente.

### Primeira vez: configurar o perfil da tela

A tela abre num **perfil do Chrome só dela** (`%LOCALAPPDATA%\JarvisHud`). Isso mantém o Jarvis
longe das suas abas e — o detalhe que faz tudo funcionar — faz o processo do Chrome durar enquanto
a janela existir, que é como o agente sabe que a tela fechou. Com o perfil normal, o Chrome já
aberto receberia a URL e o processo novo morreria no mesmo instante: o agente acharia que a tela
fechou um segundo depois de abri-la.

Em troca, esse perfil nasce vazio, e a HUD guarda a URL da API e o token no `localStorage`, que é
por perfil. **Uma vez, na instalação:**

1. Use o comando de voz ou mande *"abre a HUD"* pelo Telegram para o perfil nascer.
2. A HUD vai cair na tela de configuração. Preencha a URL da API e o `HUD_TOKEN`.
3. Pronto. O perfil fica em disco e os despertares seguintes caem direto na tela.

### "Jarvis": baixar o modelo

```powershell
.\baixar-modelo.ps1
```

O script baixa whisper.cpp e o modelo base multilingue (cerca de 145 MB) para `ferramentas\` e
`modelos\`, ambos no `.gitignore`. Os caminhos são resolvidos a partir do `config.properties`,
mesmo quando a tarefa agendada inicia o agente com outro diretório atual.

**Sem os arquivos nada quebra**: o agente avisa que a voz offline está indisponível; os comandos
remotos para abrir a HUD continuam valendo. A voz só aceita a palavra isolada `"Jarvis"`;
a confiança configurada é verificada nos tokens dela, enquanto uma única grafia
alternativa observada para o nome ("Jardes") é normalizada para lidar com a limitação do modelo.

### Energia

Com a tela aberta o agente pede ao Windows que não apague o monitor nem suspenda a máquina — um
HUD que apaga no meio de uma frase não serve para nada. Em repouso, com
`energia.manterPcAcordado=true` (o padrão), só impede a suspensão: o monitor apaga normalmente,
mas o PC continua aguardando a palavra de ativação.

Para conferir:

```powershell
powercfg /requests
```

Com a tela aberta devem aparecer pedidos em **DISPLAY** e **SYSTEM**; em repouso, só em
**SYSTEM**. Ao encerrar o agente, os dois somem — é para isso que existe o *shutdown hook*, já que
o pedido de energia vale por thread e some junto com ela.

## Quando algo não funciona

| sintoma | causa provável |
|---|---|
| log com `HTTP 401` | `pcToken` diferente do `PC_TOKEN` da VPS, ou `PC_TOKEN` vazio no `.env` |
| log com `HTTP 403` | a chamada saiu em http; o nginx só aceita `/api/pc/` por https |
| *"não consigo ver a tela"* | o agente não está na sessão do usuário (foi instalado como serviço?); confirme com `--testar` |
| `app '...' ignorado: caminho inválido` | no `.properties` a contrabarra é escape: use `C:\\Pasta\\x.exe` ou `C:/Pasta/x.exe` |
| *"não está na lista de apps deste PC"* | falta a linha `app.<nome>` ou o `.exe` mudou de lugar |
| o Jarvis diz que o PC está offline | o agente não está rodando: veja o log e `Get-ScheduledTask` |
| *"Jarvis"* não funciona | falta o modelo: rode `.\baixar-modelo.ps1` e veja o log na partida |
| a tela abre na configuração | é a primeira vez nesse perfil do Chrome; preencha URL e `HUD_TOKEN` uma vez |
| a tela abre muda | o Chrome só libera autoplay com a flag que o agente passa; abrindo a HUD à mão, clique uma vez |
| `não consegui abrir o microfone` | outro programa o tomou, ou falta permissão em Privacidade > Microfone |

## Rotinas manuais no Windows

Copie `rotinas.properties.example` para `rotinas.properties` e ajuste pasta e programa para
caminhos absolutos reais do seu PC. O exemplo Maven aponta para um projeto de demonstração; o
exemplo Claude é apenas de análise. Nenhum deles roda automaticamente. O agente lê o cadastro na
partida, anuncia somente nome, descrição e capacidades e recusa nomes desconhecidos localmente.
Reinicie o agente depois de alterar o cadastro.

Cada rotina define `tipo`, `pasta`, `programa`, `argumentos.count`, cada `argumentos.N`,
`timeoutSegundos`, `capacidades` e `confirmacao`. Para rotinas com código, publicação, remoção ou
agentes, use `confirmacao=sempre`. Caminhos `.cmd` não são programas genéricos: somente o
adaptador `maven` aceita `mvn.cmd`, com os três argumentos fixos `-q`, `-B`, `test` e caminho sem
metacaracteres do `cmd.exe`. Para os demais tipos, aponte para `.exe` nativo.
O Jarvis pede confirmação para toda rotina; o agente aplica novamente sua política local.

`claude_leitura` requer Claude Code 2.1.248 ou superior, com `claude.exe` compatível com
`--restricted`; roda com ferramentas de leitura,
`dontAsk`, sem MCP, sem comandos slash e recebe o pedido por stdin. Confira também as permissões
efetivas da conta Windows e do projeto, pois stdin não restringe o agente por si. O diário E2 fica
em `%LOCALAPPDATA%/jarvis-agente-pc/operacoes-e2`; mantenha essa pasta entre reinícios. Um início
registrado sem resultado conhecido vira `resultado_incerto` e nunca é repetido automaticamente.
O cancelamento em execução é observado por consulta à VPS e pode demorar alguns segundos; somente
processos descendentes da execução identificada são encerrados.

O E2 transporta arquivos gerados por upload autenticado (até 8 MiB), capturas PNG/JPEG (até 8 MiB
decodificados) e respostas estruturadas das consultas de arquivos (até 32 KiB). O diário local
guarda esses resultados até a VPS confirmar o recebimento; depois remove os payloads da cópia local.
Conserve o diário entre reinícios para deduplicar comandos, inclusive quando a resposta HTTP se perde.
