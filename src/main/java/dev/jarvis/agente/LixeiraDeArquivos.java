package dev.jarvis.agente;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lixeira reversível dentro da própria pasta de arquivos.
 *
 * <p>Vale aqui a mesma regra do resto do agente: <b>nenhum caminho vem da VPS</b>. As três ações
 * recebem só identificadores, e tudo é revalidado nesta máquina — inclusive o que este processo
 * gravou antes, porque um registro no disco é dado, nunca um caminho a resolver de olhos fechados.
 *
 * <p>Cada item fica em {@code <pastaArquivos>/.lixeira/AAAA-MM-DD/} como dois arquivos:
 *
 * <pre>
 *   3f2a...c91.json                 o registro
 *   3f2a...c91-relatorio.txt.dados  os bytes originais
 * </pre>
 *
 * <p>O registro leva o nome do UUID, e não o do arquivo, por um motivo concreto: {@code json} é
 * formato catalogável, então {@code relatorio.txt} e {@code relatorio.txt.json} na mesma pasta
 * fariam o registro de um ser o payload do outro. Com o nome em forma de UUID, arquivo de usuário
 * não pode ser confundido com registro — por forma, não por convenção. E a extensão
 * {@code .dados} fica fora dos formatos suportados, então o conteúdo retido nunca volta ao
 * catálogo por acidente.
 *
 * <p>O movimento grava o registro <b>antes</b> de mexer no arquivo. Na ordem contrária, uma queda
 * no meio deixaria um arquivo órfão invisível nas duas listagens — perda silenciosa, exatamente o
 * que uma lixeira existe para evitar. Assim toda janela de queda é recuperável, e
 * {@link #reconciliar()} fecha cada uma delas na partida.
 */
final class LixeiraDeArquivos {

    private static final Logger log = Logger.getLogger(LixeiraDeArquivos.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String PASTA = ".lixeira";
    private static final String EXTENSAO = ".dados";

    /**
     * O UUID estrito, e não o {@code [0-9a-fA-F-]{36}} do catálogo.
     *
     * <p>Aquele casa trinta e seis hifens. No catálogo tanto faz, porque o id nunca vira nome de
     * arquivo; aqui ele vira, então a forma precisa ser exata.
     */
    private static final Pattern ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern NOME_DE_REGISTRO = Pattern.compile(ID.pattern() + "\\.json");

    // Tetos: listar varre o disco a cada chamada, e a pasta é alcançável pelo usuário.
    private static final int MAX_PASTAS = 400;
    private static final int MAX_REGISTROS = 2000;
    private static final int MAX_REGISTRO_BYTES = 8192;
    private static final int MAX_APELIDO = 60;

    private final CatalogoDeArquivos catalogo;
    private final int diasRetencao;
    /** Nulo quando não há editor para consultar: aí o movimento não checa edição em voo. */
    private final EditorDeArquivos editor;
    private final Clock relogio;

    LixeiraDeArquivos(Path pastaArquivos, int diasRetencao) {
        this(pastaArquivos, diasRetencao, null, Clock.systemDefaultZone());
    }
    LixeiraDeArquivos(Path pastaArquivos, int diasRetencao, EditorDeArquivos editor) {
        this(pastaArquivos, diasRetencao, editor, Clock.systemDefaultZone());
    }
    LixeiraDeArquivos(Path pastaArquivos, int diasRetencao, Clock relogio) {
        this(pastaArquivos, diasRetencao, null, relogio);
    }
    LixeiraDeArquivos(Path pastaArquivos, int diasRetencao, EditorDeArquivos editor, Clock relogio) {
        this.catalogo = new CatalogoDeArquivos(pastaArquivos);
        this.diasRetencao = diasRetencao;
        this.editor = editor;
        this.relogio = relogio;
    }

    // ----------------------------------------------------------------- mover

    synchronized ObjectNode mover(JsonNode pedido) throws IOException {
        if (pedido == null || !pedido.isObject()) throw new FalhaLixeira("pedido inválido");
        CatalogoDeArquivos.Entrada entrada;
        try {
            entrada = catalogo.porId(CatalogoDeArquivos.texto(pedido, "id"));
        } catch (IOException e) {
            throw new FalhaLixeira("NAO_ENCONTRADO", e.getMessage(), e);
        }
        if (editor != null && editor.temEdicaoEmVoo(entrada.id()))
            throw new FalhaLixeira("há uma edição em andamento neste arquivo; tente de novo em instantes");

        // Mover arquivo "alterado" é permitido de propósito: o pedido é jogar o arquivo fora, não
        // essa versão exata dele. O aviso na resposta é o que conta ao usuário o que ele guardou.
        boolean alterado = !catalogo.estado(entrada).equals("disponivel");
        Path origem;
        try {
            origem = catalogo.caminhoDe(entrada);
        } catch (IOException e) {
            throw new FalhaLixeira("arquivo fora da pasta permitida, já removido ou link", e);
        }

        // Antes de tudo, para nunca escrever numa pasta que vai sumir no mesmo tique.
        expurgar();

        LocalDate hoje = LocalDate.now(relogio);
        Path dia = pastaDoDia(hoje);
        String idLixeira = null;
        Path registro = null;
        for (int n = 0; n < 100 && registro == null; n++) {
            String candidato = UUID.randomUUID().toString();
            Path alvo = dia.resolve(candidato + ".json");
            try {
                // createFile é a reserva atômica do nome: ninguém mais fica com este id.
                Files.createFile(alvo);
                idLixeira = candidato;
                registro = alvo;
            } catch (FileAlreadyExistsException ignorada) { /* sorteia outro */ }
        }
        if (registro == null) throw new FalhaLixeira("não consegui reservar o registro na lixeira");

        Path dados = dia.resolve(idLixeira + "-" + apelido(entrada.nome()) + EXTENSAO);
        ObjectNode r = JSON.createObjectNode();
        r.put("idLixeira", idLixeira);
        r.put("estado", "movendo");
        r.put("id", entrada.id());
        r.put("nome", entrada.nome());
        r.put("formato", entrada.formato());
        r.put("data", hoje.toString());
        r.put("movidoEm", Instant.now(relogio).toString());
        r.put("hashCatalogo", entrada.hash());
        r.put("alteradoDesdeOCadastro", alterado);
        gravarRegistro(registro, r);

        try {
            Files.move(origem, dados, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Nunca copiar e apagar: um copiar-e-apagar pela metade é a perda que a lixeira
            // deveria impedir. Melhor recusar com o arquivo intacto no lugar.
            Files.deleteIfExists(registro);
            throw new FalhaLixeira("este disco não faz movimento atômico; o arquivo ficou no lugar", e);
        } catch (AccessDeniedException e) {
            Files.deleteIfExists(registro);
            throw new FalhaLixeira("ARQUIVO_BLOQUEADO",
                    "o arquivo está aberto em outro programa; feche-o e tente de novo", e);
        } catch (IOException e) {
            Files.deleteIfExists(registro);
            throw new FalhaLixeira("não consegui mover o arquivo; ele continua no lugar", e);
        }

        // Tamanho e hash dos bytes já guardados, que é o que a restauração vai conferir.
        byte[] bytes = CatalogoDeArquivos.bytesSemLink(dados);
        r.put("estado", "na-lixeira");
        r.put("tamanho", bytes.length);
        r.put("hash", EditorDeArquivos.hash(bytes));
        gravarRegistro(registro, r);
        catalogo.esquecer(entrada.id());
        log.info("lixeira: " + entrada.formato() + " de " + bytes.length + " B guardado em "
                + hoje + " (" + idLixeira + ")");

        ObjectNode saida = JSON.createObjectNode();
        saida.put("idLixeira", idLixeira);
        saida.put("id", entrada.id());
        saida.put("nome", entrada.nome());
        saida.put("formato", entrada.formato());
        saida.put("tamanho", bytes.length);
        saida.put("hash", EditorDeArquivos.hash(bytes));
        saida.put("data", hoje.toString());
        saida.put("movidoEm", CatalogoDeArquivos.texto(r, "movidoEm"));
        saida.put("expiraEm", hoje.plusDays(diasRetencao).toString());
        saida.put("alteradoDesdeOCadastro", alterado);
        if (alterado)
            saida.put("aviso", "o arquivo havia mudado desde o cadastro; guardei como ele estava");
        return saida;
    }

    // ------------------------------------------------------------- restaurar

    synchronized ObjectNode restaurar(JsonNode pedido) throws IOException {
        if (pedido == null || !pedido.isObject()) throw new FalhaLixeira("pedido inválido");
        String idLixeira = CatalogoDeArquivos.texto(pedido, "idLixeira");
        if (!ID.matcher(idLixeira).matches())
            throw new FalhaLixeira("identificador da lixeira inválido");
        Item item = varrer().itens().stream().filter(i -> i.idLixeira().equals(idLixeira))
                .findFirst().orElseThrow(() ->
                        new FalhaLixeira("NAO_ENCONTRADO", "não achei esse item na lixeira"));
        if (!item.estado().equals("na-lixeira"))
            throw new FalhaLixeira(item.estado().equals("ausente")
                    ? "o arquivo deste item não está mais na lixeira"
                    : "este item está em movimento; tente de novo em instantes");

        // O nome foi gravado por nós, e ainda assim passa pelas mesmas regras de
        // CatalogoDeArquivos.caminho: o registro é um arquivo no disco do usuário, e confiar nele
        // seria abrir justamente a porta que as ações fecham ao aceitar só identificadores.
        String nomeOriginal = CatalogoDeArquivos.texto(item.conteudo(), "nome");
        String nome = nomeValido(nomeOriginal);
        byte[] bytes = CatalogoDeArquivos.bytesSemLink(item.dados());
        String gravado = CatalogoDeArquivos.texto(item.conteudo(), "hash");
        if (!gravado.isEmpty() && !EditorDeArquivos.hash(bytes).equals(gravado))
            throw new FalhaLixeira("os bytes guardados não conferem com o registro; não restaurei");

        Path raiz = catalogo.raizAutorizada();
        Path destino = nomeLivre(raiz, nome);
        boolean renomeado = !destino.getFileName().toString().equals(nome);

        ObjectNode r = item.conteudo();
        r.put("estado", "restaurando");
        r.put("nomeRestaurado", destino.getFileName().toString());
        gravarRegistro(item.registro(), r);
        // REPLACE_EXISTING é seguro só aqui: o destino é o arquivo vazio que acabamos de reservar.
        Files.move(item.dados(), destino, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        CatalogoDeArquivos.Entrada nova = catalogo.cadastrar(destino);
        Files.deleteIfExists(item.registro());
        log.info("lixeira: restaurei " + destino.getFileName() + " (" + idLixeira + ")");

        ObjectNode saida = JSON.createObjectNode();
        saida.put("idLixeira", idLixeira);
        saida.put("id", nova.id());
        saida.put("nome", nova.nome());
        saida.put("nomeOriginal", nomeOriginal);
        saida.put("renomeado", renomeado);
        saida.put("formato", nova.formato());
        saida.put("tamanho", nova.tamanho());
        saida.put("versaoEsperada", nova.hash());
        saida.put("restauradoEm", Instant.now(relogio).toString());
        if (renomeado)
            saida.put("aviso", "já existia " + nomeOriginal + " na pasta; restaurei como " + nova.nome());
        return saida;
    }

    // ---------------------------------------------------------------- listar

    synchronized ObjectNode listar(JsonNode pedido) throws IOException {
        String nome = CatalogoDeArquivos.texto(pedido, "nome").toLowerCase(Locale.ROOT);
        String formato = CatalogoDeArquivos.texto(pedido, "formato").toLowerCase(Locale.ROOT);
        if (!formato.isEmpty() && !CatalogoDeArquivos.FORMATOS.contains(formato))
            throw new FalhaLixeira("formato inválido");
        int pagina = CatalogoDeArquivos.numero(pedido, "pagina", 1);
        int tamanho = CatalogoDeArquivos.numero(pedido, "tamanhoPagina", 20);
        if (pagina < 1 || pagina > 10000 || tamanho < 1 || tamanho > 50 || nome.length() > 120)
            throw new FalhaLixeira("paginação ou filtro inválido");

        Varredura varredura = varrer();
        String falado = CatalogoDeArquivos.nomeFalado(nome);
        List<Item> filtrados = varredura.itens().stream()
                .filter(i -> i.nome().toLowerCase(Locale.ROOT).contains(nome)
                        || CatalogoDeArquivos.nomeFalado(i.nome()).contains(falado))
                .filter(i -> formato.isEmpty() || i.formato().equals(formato))
                .sorted(Comparator.comparing(Item::data).reversed()
                        .thenComparing(Item::nome).thenComparing(Item::idLixeira))
                .toList();

        ObjectNode saida = JSON.createObjectNode();
        saida.put("total", filtrados.size());
        saida.put("pagina", pagina);
        saida.put("tamanhoPagina", tamanho);
        saida.put("diasRetencao", diasRetencao);
        ArrayNode itens = saida.putArray("itens");
        long inicio = ((long) pagina - 1) * tamanho;
        for (long i = inicio; i < Math.min(filtrados.size(), inicio + tamanho); i++) {
            Item item = filtrados.get((int) i);
            ObjectNode no = itens.addObject();
            no.put("idLixeira", item.idLixeira());
            no.put("nome", item.nome());
            no.put("formato", item.formato());
            no.put("tamanho", item.conteudo().path("tamanho").asLong(0));
            no.put("data", item.data().toString());
            no.put("expiraEm", item.data().plusDays(diasRetencao).toString());
            no.put("estado", item.estado());
        }
        saida.put("temMais", inicio + tamanho < filtrados.size());
        saida.put("itensSemRegistro", varredura.semRegistro());
        saida.put("itensInvalidos", varredura.invalidos());
        return saida;
    }

    // --------------------------------------------------------------- expurgo

    /**
     * Apaga de vez as pastas vencidas e diz quantas foram.
     *
     * <p>A decisão é pelo nome da pasta datada: é barato, não depende de um JSON que pode estar
     * corrompido, e a pasta é a unidade de exclusão. Nome que não é data fica para sempre, com
     * aviso no log — nesta pasta o usuário também mexe, e apagar o desconhecido seria o jeito de
     * um bug daqui virar um apagão lá.
     */
    synchronized int expurgar() throws IOException {
        Path raiz = raizLixeira();
        LocalDate hoje = LocalDate.now(relogio);
        List<Path> dias;
        try (var filhos = Files.list(raiz)) { dias = filhos.toList(); }
        int apagadas = 0;
        for (Path dia : dias) {
            if (Files.isSymbolicLink(dia) || !Files.isDirectory(dia, LinkOption.NOFOLLOW_LINKS))
                continue;
            LocalDate data = dataDaPasta(dia);
            if (data == null) {
                log.info("lixeira: '" + dia.getFileName() + "' não é uma data; deixei onde está");
                continue;
            }
            if (data.equals(hoje)) continue;
            long idade = ChronoUnit.DAYS.between(data, hoje);
            if (idade < 0) {
                log.warning("lixeira: pasta com data no futuro (" + dia.getFileName() + "); preservada");
                continue;
            }
            if (idade <= diasRetencao) continue;
            if (apagarPastaDoDia(dia)) apagadas++;
        }
        return apagadas;
    }

    private boolean apagarPastaDoDia(Path dia) throws IOException {
        List<Path> filhos;
        try (var lista = Files.list(dia)) { filhos = lista.toList(); }
        for (Path filho : filhos)
            if (Files.isSymbolicLink(filho) || !Files.isRegularFile(filho, LinkOption.NOFOLLOW_LINKS)) {
                log.warning("lixeira: " + dia.getFileName()
                        + " tem conteúdo inesperado dentro; não apaguei nada dela");
                return false;
            }
        for (Path filho : filhos) Files.delete(filho);
        Files.delete(dia);
        log.info("lixeira: expurguei " + dia.getFileName() + " (" + filhos.size() + " arquivo(s))");
        return true;
    }

    // ------------------------------------------------------------ reconciliar

    /**
     * Fecha as janelas de queda do movimento e da restauração. Roda na partida.
     *
     * <p>Falha de um item não pode parar os outros: cada um vai num try próprio, igual ao
     * {@code recuperarPendentes} do editor.
     */
    synchronized void reconciliar() throws IOException {
        for (Item item : varrer().itens()) {
            try {
                ObjectNode r = item.conteudo();
                switch (CatalogoDeArquivos.texto(r, "estado")) {
                    case "movendo" -> {
                        if (item.dados() == null) {
                            // O movimento nunca aconteceu: o original continua na pasta.
                            Files.deleteIfExists(item.registro());
                            log.info("lixeira: descartei registro de movimento que não ocorreu "
                                    + item.idLixeira());
                        } else {
                            byte[] bytes = CatalogoDeArquivos.bytesSemLink(item.dados());
                            r.put("estado", "na-lixeira");
                            r.put("tamanho", bytes.length);
                            r.put("hash", EditorDeArquivos.hash(bytes));
                            gravarRegistro(item.registro(), r);
                            String id = CatalogoDeArquivos.texto(r, "id");
                            if (!id.isEmpty()) catalogo.esquecer(id);
                            log.info("lixeira: concluí movimento pendente " + item.idLixeira());
                        }
                    }
                    case "restaurando" -> {
                        if (item.dados() == null) {
                            // O arquivo já voltou; sem o registro ele é reimportado na listagem.
                            Files.deleteIfExists(item.registro());
                            log.info("lixeira: restauração pendente já estava feita "
                                    + item.idLixeira());
                        } else {
                            limparReservaVazia(r);
                            r.put("estado", "na-lixeira");
                            gravarRegistro(item.registro(), r);
                            log.info("lixeira: desfiz restauração interrompida " + item.idLixeira());
                        }
                    }
                    default -> { }
                }
            } catch (IOException e) {
                log.warning("lixeira: não consegui reconciliar " + item.idLixeira() + ": "
                        + e.getMessage());
            }
        }
    }

    /** Tira o arquivo vazio que uma restauração interrompida deixou reservado na pasta. */
    private void limparReservaVazia(ObjectNode r) throws IOException {
        String reservado = CatalogoDeArquivos.texto(r, "nomeRestaurado");
        if (reservado.isEmpty()) return;
        try {
            Path alvo = catalogo.raizAutorizada().resolve(nomeValido(reservado));
            if (Files.isRegularFile(alvo, LinkOption.NOFOLLOW_LINKS) && Files.size(alvo) == 0)
                Files.delete(alvo);
        } catch (IOException ignorada) { /* nome torto no registro: não se apaga nada */ }
    }

    // -------------------------------------------------------------- varredura

    private record Item(String idLixeira, LocalDate data, Path registro, Path dados,
            ObjectNode conteudo, String estado) {
        String nome() { return CatalogoDeArquivos.texto(conteudo, "nome"); }
        String formato() { return CatalogoDeArquivos.texto(conteudo, "formato"); }
    }

    private record Varredura(List<Item> itens, int semRegistro, int invalidos) { }

    private Varredura varrer() throws IOException {
        Path raiz = raizLixeira();
        List<Path> dias;
        try (var filhos = Files.list(raiz)) { dias = filhos.sorted().toList(); }
        List<Item> itens = new ArrayList<>();
        int pastas = 0, semRegistro = 0, invalidos = 0;
        for (Path dia : dias) {
            if (Files.isSymbolicLink(dia) || !Files.isDirectory(dia, LinkOption.NOFOLLOW_LINKS))
                continue;
            LocalDate data = dataDaPasta(dia);
            if (data == null || ++pastas > MAX_PASTAS) continue;
            List<Path> filhos;
            try (var lista = Files.list(dia)) { filhos = lista.toList(); }
            Set<String> comRegistro = new HashSet<>();
            for (Path arquivo : filhos) {
                String nome = arquivo.getFileName().toString();
                if (!NOME_DE_REGISTRO.matcher(nome).matches()
                        || Files.isSymbolicLink(arquivo)
                        || !Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)) continue;
                String idLixeira = nome.substring(0, nome.length() - ".json".length());
                ObjectNode conteudo = lerRegistro(arquivo, idLixeira);
                if (conteudo == null) { invalidos++; continue; }
                comRegistro.add(idLixeira);
                if (itens.size() >= MAX_REGISTROS) break;
                Path dados = dadosDe(filhos, idLixeira);
                String estado = CatalogoDeArquivos.texto(conteudo, "estado");
                if (estado.equals("na-lixeira") && dados == null) estado = "ausente";
                itens.add(new Item(idLixeira, data, arquivo, dados, conteudo, estado));
            }
            for (Path arquivo : filhos) {
                String nome = arquivo.getFileName().toString();
                if (NOME_DE_REGISTRO.matcher(nome).matches()) continue;
                String id = nome.length() > 37 ? nome.substring(0, 36) : "";
                if (!(nome.endsWith(EXTENSAO) && comRegistro.contains(id))) semRegistro++;
            }
        }
        return new Varredura(itens, semRegistro, invalidos);
    }

    /** Os bytes de um item são achados pelo prefixo, nunca por um caminho lido do registro. */
    private static Path dadosDe(List<Path> filhos, String idLixeira) throws IOException {
        for (Path arquivo : filhos) {
            String nome = arquivo.getFileName().toString();
            if (nome.startsWith(idLixeira + "-") && nome.endsWith(EXTENSAO)
                    && !Files.isSymbolicLink(arquivo)
                    && Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS))
                return arquivo;
        }
        return null;
    }

    private ObjectNode lerRegistro(Path arquivo, String idLixeira) {
        try {
            long tamanho = Files.size(arquivo);
            if (tamanho == 0 || tamanho > MAX_REGISTRO_BYTES) return null;
            JsonNode lido = JSON.readTree(Files.readString(arquivo));
            if (!(lido instanceof ObjectNode o)) return null;
            // O id dentro do registro tem de ser o do nome do arquivo, senão não é registro dele.
            return idLixeira.equals(CatalogoDeArquivos.texto(o, "idLixeira")) ? o : null;
        } catch (IOException | RuntimeException e) {
            log.info("lixeira: registro ilegível em " + arquivo.getFileName());
            return null;
        }
    }

    private void gravarRegistro(Path destino, ObjectNode r) throws IOException {
        Path temp = Files.createTempFile(destino.getParent(), ".registro-", ".tmp");
        try {
            Files.writeString(temp, JSON.writeValueAsString(r));
            Files.move(temp, destino, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static LocalDate dataDaPasta(Path dia) {
        String nome = dia.getFileName().toString();
        if (nome.length() != 10) return null;
        try {
            return LocalDate.parse(nome);
        } catch (DateTimeParseException e) { return null; }
    }

    // --------------------------------------------------------------- caminhos

    /** A mesma cerca de {@code CatalogoDeArquivos.raiz()}, um nível abaixo. */
    private Path raizLixeira() throws IOException {
        Path raiz = catalogo.raizAutorizada();
        Path alvo = raiz.resolve(PASTA);
        if (Files.isSymbolicLink(alvo)) throw new FalhaLixeira("a pasta da lixeira não pode ser link");
        if (Files.exists(alvo, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(alvo, LinkOption.NOFOLLOW_LINKS))
            throw new FalhaLixeira("há um arquivo chamado " + PASTA
                    + " na pasta de arquivos; renomeie-o para eu poder usar a lixeira");
        Files.createDirectories(alvo);
        Path semLinks = alvo.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!alvo.toRealPath().equals(semLinks))
            throw new FalhaLixeira("pasta da lixeira redirecionada por link ou junction");
        if (!semLinks.getParent().equals(raiz))
            throw new FalhaLixeira("pasta da lixeira fora da pasta de arquivos");
        return semLinks;
    }

    private Path pastaDoDia(LocalDate dia) throws IOException {
        Path raiz = raizLixeira();
        Path alvo = raiz.resolve(dia.toString());
        if (Files.isSymbolicLink(alvo)) throw new FalhaLixeira("a pasta do dia não pode ser link");
        if (Files.exists(alvo, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(alvo, LinkOption.NOFOLLOW_LINKS))
            throw new FalhaLixeira("há um arquivo onde deveria estar a pasta do dia na lixeira");
        Files.createDirectories(alvo);
        Path semLinks = alvo.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!alvo.toRealPath().equals(semLinks) || !semLinks.getParent().equals(raiz))
            throw new FalhaLixeira("pasta do dia fora da lixeira");
        return semLinks;
    }

    /**
     * As mesmas regras de {@code CatalogoDeArquivos.caminho} sobre um nome que nós gravamos.
     *
     * <p>Não passa por {@code CriadorDeArquivo.nomeSeguro} porque ele corta em 100 caracteres, e
     * o corte comeria a extensão de um nome longo — recusar um arquivo legítimo na hora de
     * devolvê-lo seria o pior jeito de ser rigoroso. A extensão é re-derivada do nome e exigida
     * entre os formatos suportados: o campo {@code formato} do registro não decide nada.
     */
    private static String nomeValido(String nome) throws IOException {
        if (nome == null || nome.isBlank() || nome.length() > 120
                || nome.contains("/") || nome.contains("\\") || nome.contains(":")
                || nome.equals(".") || nome.contains("..")
                || nome.matches(".*[<>\"|?*\\p{Cntrl}].*")
                || nome.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$"))
            throw new FalhaLixeira("o nome guardado no registro é inválido");
        if (!CatalogoDeArquivos.FORMATOS.contains(CatalogoDeArquivos.formato(nome)))
            throw new FalhaLixeira("o formato guardado no registro não é suportado");
        return nome;
    }

    /** Reserva o primeiro nome livre na pasta e devolve o caminho já criado, vazio. */
    private static Path nomeLivre(Path raiz, String nome) throws IOException {
        int ponto = nome.lastIndexOf('.');
        String base = nome.substring(0, ponto), extensao = nome.substring(ponto);
        for (int n = 0; n < 1000; n++) {
            Path destino = raiz.resolve(base + (n == 0 ? "" : "-" + n) + extensao);
            if (!destino.normalize().getParent().equals(raiz))
                throw new FalhaLixeira("destino da restauração fora da pasta");
            try {
                Files.createFile(destino);
                return destino;
            } catch (FileAlreadyExistsException ignorada) { /* tenta o próximo sufixo */ }
        }
        throw new FalhaLixeira("há arquivos demais com esse nome na pasta");
    }

    /** Só para a pasta da lixeira ser legível por um humano no Explorer. */
    private static String apelido(String nome) throws IOException {
        String limpo = CriadorDeArquivo.nomeSeguro(nome);
        if (limpo.length() <= MAX_APELIDO) return limpo;
        String curto = limpo.substring(0, MAX_APELIDO);
        return Character.isHighSurrogate(curto.charAt(curto.length() - 1))
                ? curto.substring(0, curto.length() - 1) : curto;
    }
}
