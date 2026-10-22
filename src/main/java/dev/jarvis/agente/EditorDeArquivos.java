package dev.jarvis.agente;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Prepara edições imutáveis e grava somente arquivos catalogados na pasta autorizada. */
final class EditorDeArquivos {
    private static final Logger log = Logger.getLogger(EditorDeArquivos.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CatalogoDeArquivos catalogo;
    private final Path dados;
    private final Path configuracao;
    private final Configuracao.LimitesEdicao limites;

    EditorDeArquivos(Path pasta, Path dados) {
        this(pasta, dados, null, Configuracao.LimitesEdicao.padrao());
    }
    EditorDeArquivos(Path pasta, Path dados, Path configuracao) {
        this(pasta, dados, configuracao, Configuracao.LimitesEdicao.padrao());
    }
    EditorDeArquivos(Path pasta, Path dados, Path configuracao,
            Configuracao.LimitesEdicao limites) {
        this.catalogo = new CatalogoDeArquivos(pasta);
        this.dados = dados.toAbsolutePath().normalize().resolve("edicoes");
        this.configuracao = configuracao == null ? null : configuracao.toAbsolutePath().normalize();
        this.limites = limites;
    }

    synchronized ObjectNode preparar(JsonNode pedido) throws IOException {
        limparPropostasExpiradas();
        if (pedido == null || !pedido.isObject()
                || pedido.toString().getBytes(StandardCharsets.UTF_8).length
                        > limites.maxBytesResultado() + 20_000)
            throw new FalhaEdicao("pedido de edição acima do limite");
        String id = campo(pedido, "id");
        String versao = campo(pedido, "versaoEsperada");
        String destino = campo(pedido, "destino");
        if (!versao.matches("[0-9a-f]{64}") || !destino.matches("original|copia"))
            throw new FalhaEdicao("versão ou destino inválido");
        CatalogoDeArquivos.Entrada entrada = catalogo.porId(id);
        Path arquivo = seguro(entrada);
        byte[] anterior = ler(arquivo);
        if (!hash(anterior).equals(versao)) throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO");
        if (!LeitorDeArquivo.foiLido(id, versao))
            throw new FalhaEdicao("leia o arquivo antes de propor edição");
        JsonNode operacoes = pedido.path("operacoes");
        if (!operacoes.isArray() || operacoes.isEmpty() || operacoes.size() > limites.maxOperacoes())
            throw new FalhaEdicao("quantidade de operações inválida");
        byte[] propostos;
        String previa;
        int ocorrenciasSubstituidas = 0;
        if (entrada.formato().equals("json")) {
            EdicaoJson.Proposta propostaJson = EdicaoJson.preparar(anterior, operacoes);
            propostos = propostaJson.bytes();
            previa = propostaJson.previa();
        } else if (entrada.formato().equals("xlsx")) {
            boolean simplificar = pedido.path("simplificar").asBoolean(false);
            if (simplificar && !destino.equals("copia"))
                throw new FalhaEdicao("simplificação exige destino cópia");
            EdicaoXlsx.Proposta propostaXlsx = EdicaoXlsx.preparar(anterior, operacoes, simplificar);
            propostos = propostaXlsx.bytes();
            previa = propostaXlsx.previa();
        } else {
        Texto texto = decodificar(anterior);
        String proposto = texto.conteudo();
        for (JsonNode operacao : operacoes) {
            String tipo = campo(operacao, "tipo");
            switch (tipo) {
                case "substituir_trecho" -> {
                    String atual = campoBruto(operacao, "textoAtual");
                    String novo = campoBruto(operacao, "textoNovo");
                    if (atual.isEmpty() || !proposto.contains(atual)
                            || proposto.indexOf(atual) != proposto.lastIndexOf(atual))
                        throw new FalhaEdicao("CONFLITO_DE_TRECHO", "CONFLITO_DE_TRECHO: esperado exatamente uma ocorrência");
                    proposto = proposto.replace(atual, normalizarQuebras(novo, texto.crlf()));
                    ocorrenciasSubstituidas++;
                }
                case "substituir_todas_ocorrencias" -> {
                    String atual = normalizarQuebras(campoBruto(operacao, "textoAtual"), texto.crlf());
                    String novo = normalizarQuebras(campoBruto(operacao, "textoNovo"), texto.crlf());
                    if (atual.isEmpty()) throw new FalhaEdicao("textoAtual vazio");
                    JsonNode ignorar = operacao.path("ignorarMaiusculas");
                    if (!ignorar.isMissingNode() && !ignorar.isBoolean())
                        throw new FalhaEdicao("ignorarMaiusculas deve ser booleano");
                    int opcoes = ignorar.asBoolean(false)
                            ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                    Matcher busca = Pattern.compile(Pattern.quote(atual), opcoes).matcher(proposto);
                    long tamanhoProjetado = proposto.length();
                    int encontrados = 0;
                    while (busca.find()) {
                        tamanhoProjetado += (long) novo.length() - (busca.end() - busca.start());
                        encontrados++;
                    }
                    if (encontrados == 0) throw new FalhaEdicao("CONFLITO_DE_TRECHO", "CONFLITO_DE_TRECHO: nenhuma ocorrência");
                    if (tamanhoProjetado > limites.maxBytesResultado())
                        throw new FalhaEdicao("resultado acima do limite de bytes");
                    busca.reset();
                    StringBuffer alterado = new StringBuffer();
                    while (busca.find()) {
                        busca.appendReplacement(alterado, Matcher.quoteReplacement(novo));
                    }
                    busca.appendTail(alterado);
                    proposto = alterado.toString();
                    ocorrenciasSubstituidas += encontrados;
                }
                case "acrescentar_ao_final" -> proposto += normalizarQuebras(
                        campoBruto(operacao, "texto"), texto.crlf());
                case "substituir_conteudo" -> {
                    if (!pedido.path("leituraIntegral").asBoolean(false)
                            || !LeitorDeArquivo.foiLidoIntegralmente(id, versao))
                        throw new FalhaEdicao("reescrita exige leitura integral da mesma versão");
                    proposto = normalizarQuebras(campoBruto(operacao, "conteudo"), texto.crlf());
                }
                default -> throw new FalhaEdicao("operação desconhecida");
            }
            if (proposto.length() > limites.maxBytesResultado())
                throw new FalhaEdicao("resultado acima do limite de bytes");
        }
        if (proposto.equals(texto.conteudo())) throw new FalhaEdicao("nenhuma alteração proposta");
        validarControles(proposto);
        propostos = codificar(proposto, texto.bom());
        previa = previa(texto.conteudo(), proposto);
        }
        if (propostos.length == 0 || propostos.length > limites.maxBytesResultado())
            throw new FalhaEdicao("resultado vazio ou acima do limite de bytes");
        String editId = UUID.randomUUID().toString();
        Instant expira = Instant.now().plusSeconds(120);
        String resumo = operacoes.size() + " operação(ões); " + ocorrenciasSubstituidas
                + " ocorrência(s) substituída(s); " + anterior.length + " → "
                + propostos.length + " bytes";
        ObjectNode registro = JSON.createObjectNode().put("editId", editId).put("id", id)
                .put("nome", entrada.nome()).put("hashAnterior", versao)
                .put("hashProposto", hash(propostos)).put("destino", destino)
                .put("estado", "preparada").put("expiraEm", expira.toString())
                .put("resumo", resumo);
        registro.put("chaveArquivo", chaveArquivo(arquivo));
        registro.set("operacoes", operacoes.deepCopy());
        guardarBytes(editId, "proposta", propostos);
        guardar(editId, registro);
        String previaLimitada = limitarUtf8(previa, limites.maxBytesPrevia());
        ObjectNode resposta = JSON.createObjectNode().put("editId", editId)
                .put("id", id).put("nome", entrada.nome()).put("versaoAnterior", versao)
                .put("versaoProposta", hash(propostos)).put("destino", destino)
                .put("operacoes", operacoes.size()).put("expiraEm", expira.toString())
                .put("ocorrenciasSubstituidas", ocorrenciasSubstituidas)
                .put("resumo", resumo + "; original preservado.")
                .put("previa", previaLimitada)
                .put("previaTruncada", previaLimitada.length() < previa.length());
        resposta.put("apelidoUnico", catalogo.apelidoUnico(entrada));
        log.info("edicao preparada id=" + editId + " arquivo=" + id + " hash=" + versao
                + " proposto=" + hash(propostos) + " destino=" + destino);
        return resposta;
    }

    synchronized Resultado aplicar(ComandoRecebido comando) throws IOException {
        String editId = campo(comando.dados(), "editId");
        ObjectNode r = registro(editId);
        String estado = campo(r, "estado");
        if (estado.equals("concluida")) return resultadoConcluido(comando, r);
        if (estado.equals("gravando")) return recuperar(comando, r);
        if (!estado.equals("preparada") || Instant.now().isAfter(Instant.parse(campo(r, "expiraEm"))))
            throw new FalhaEdicao("proposta ausente, consumida ou expirada");
        CatalogoDeArquivos.Entrada entrada = catalogo.porId(campo(r, "id"));
        Path arquivo = seguro(entrada);
        byte[] anterior = ler(arquivo);
        if (!hash(anterior).equals(campo(r, "hashAnterior"))) throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO");
        if (!chaveArquivo(arquivo).equals(campo(r, "chaveArquivo")))
            throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO: arquivo substituído desde a preparação");
        byte[] proposta = Files.readAllBytes(caminho(editId, "proposta"));
        if (!hash(proposta).equals(campo(r, "hashProposto"))) throw new FalhaEdicao("proposta corrompida");
        boolean copia = campo(r, "destino").equals("copia");
        Path alvo = copia ? nomeCopia(arquivo, editId) : arquivo;
        if (copia && Files.exists(alvo, LinkOption.NOFOLLOW_LINKS))
            throw new FalhaEdicao("cópia já existe");
        if (!copia) { checarBloqueio(arquivo); backup(editId, r, anterior); }
        Path temporario = Files.createTempFile(arquivo.getParent(), ".jarvis-edicao-", ".tmp");
        try {
            Files.write(temporario, proposta, StandardOpenOption.TRUNCATE_EXISTING);
            sincronizar(temporario);
            if (!Arrays.equals(Files.readAllBytes(temporario), proposta))
                throw new FalhaEdicao("temporário não validado");
            if (!copia) {
                preservarAtributos(arquivo, temporario);
                checarBloqueio(arquivo);
                if (!chaveArquivo(arquivo).equals(campo(r, "chaveArquivo")))
                    throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO: arquivo substituído");
                if (!hash(ler(arquivo)).equals(campo(r, "hashAnterior")))
                    throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO");
            }
            r.put("estado", "gravando").put("comandoId", comando.id()); guardar(editId, r);
            try {
                if (copia) Files.move(temporario, alvo, StandardCopyOption.ATOMIC_MOVE);
                else Files.move(temporario, alvo, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new FalhaEdicao("substituição atômica não suportada; original preservado", e);
            }
            r.put("estado", "concluida").put("nomeResultado", alvo.getFileName().toString());
            guardar(editId, r);
            Files.deleteIfExists(caminho(editId, "proposta"));
            if (copia) catalogo.cadastrar(alvo);
            else catalogo.atualizar(entrada.id(), campo(r, "hashAnterior"));
            log.info("edicao concluida id=" + editId + " arquivo=" + entrada.id()
                    + " anterior=" + campo(r, "hashAnterior") + " atual=" + campo(r, "hashProposto"));
            return resultadoConcluido(comando, r);
        } catch (IOException e) {
            if (java.util.Set.of("gravando", "concluida").contains(r.path("estado").asText()))
                throw new FalhaEdicao("RESULTADO_INCERTO", "resultado_incerto: gravação iniciada; verifique o hash pelo editId", e);
            throw e;
        } finally { Files.deleteIfExists(temporario); }
    }

    synchronized ObjectNode prepararDesfazer(JsonNode pedido) throws IOException {
        String editId = campo(pedido, "editId");
        ObjectNode r = registro(editId);
        if (!campo(r, "estado").equals("concluida") || !campo(r, "destino").equals("original"))
            throw new FalhaEdicao("edição original concluída não encontrada");
        CatalogoDeArquivos.Entrada entrada = catalogo.porId(campo(r, "id"));
        Path arquivo = seguro(entrada);
        if (!hash(ler(arquivo)).equals(campo(r, "hashProposto")))
            throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO");
        byte[] backup = Files.readAllBytes(caminho(editId, "backup"));
        if (!hash(backup).equals(campo(r, "hashAnterior"))) throw new FalhaEdicao("backup corrompido");
        String diff = switch (entrada.formato()) {
            case "json", "xlsx" -> "Restaurar integralmente a versão anterior de " + entrada.nome();
            default -> previa(decodificar(ler(arquivo)).conteudo(), decodificar(backup).conteudo());
        };
        String limitado = limitarUtf8(diff, limites.maxBytesPrevia());
        return JSON.createObjectNode().put("editId", editId).put("nome", entrada.nome())
                .put("hashAtual", campo(r, "hashProposto"))
                .put("hashRestaurado", campo(r, "hashAnterior"))
                .put("previa", limitado).put("previaTruncada", limitado.length() < diff.length())
                .put("resumo", "Restaurar backup da edição; requer confirmação em nova mensagem.");
    }

    synchronized Resultado desfazer(ComandoRecebido comando) throws IOException {
        String editId = campo(comando.dados(), "editId");
        ObjectNode r = registro(editId);
        if (campo(r, "estado").equals("desfeita"))
            return Resultado.feito(comando, "edição já desfeita: " + editId);
        if (!campo(r, "estado").equals("concluida") || !campo(r, "destino").equals("original"))
            throw new FalhaEdicao("edição não pode ser desfeita");
        CatalogoDeArquivos.Entrada entrada = catalogo.porId(campo(r, "id"));
        Path arquivo = seguro(entrada);
        byte[] atual = ler(arquivo);
        if (!hash(atual).equals(campo(r, "hashProposto"))) throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO");
        byte[] backup = Files.readAllBytes(caminho(editId, "backup"));
        if (!hash(backup).equals(campo(r, "hashAnterior"))) throw new FalhaEdicao("backup corrompido");
        guardarBytes(editId, "backup-revertido", atual);
        if (!hash(Files.readAllBytes(caminho(editId, "backup-revertido")))
                .equals(campo(r, "hashProposto")))
            throw new FalhaEdicao("backup do estado revertido não validado");
        try { expurgarBackups(editId + ".backup-revertido"); }
        catch (IOException e) { Files.deleteIfExists(caminho(editId, "backup-revertido")); throw e; }
        checarBloqueio(arquivo);
        Path temporario = Files.createTempFile(arquivo.getParent(), ".jarvis-desfazer-", ".tmp");
        try {
            Files.write(temporario, backup);
            sincronizar(temporario);
            preservarAtributos(arquivo, temporario);
            if (!hash(Files.readAllBytes(temporario)).equals(campo(r, "hashAnterior")))
                throw new FalhaEdicao("temporário de desfazer inválido");
            if (!hash(ler(arquivo)).equals(campo(r, "hashProposto"))) throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO");
            checarBloqueio(arquivo);
            r.put("estado", "desfazendo").put("comandoId", comando.id()); guardar(editId, r);
            Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            r.put("estado", "desfeita"); guardar(editId, r);
            catalogo.atualizar(entrada.id(), campo(r, "hashProposto"));
            log.info("edicao desfeita id=" + editId + " arquivo=" + entrada.id());
            return new Resultado(comando.id(), true, entrada.nome(), null, arquivo,
                    JSON.createObjectNode().put("editId", editId).put("versao", campo(r, "hashAnterior")));
        } catch (IOException e) {
            if (java.util.Set.of("desfazendo", "desfeita").contains(r.path("estado").asText()))
                throw new FalhaEdicao("RESULTADO_INCERTO", "resultado_incerto: restauração iniciada; verifique o hash pelo editId", e);
            throw e;
        } finally { Files.deleteIfExists(temporario); }
    }

    /**
     * Diz se há escrita em voo neste arquivo — proposta já em {@code gravando} ou {@code desfazendo}.
     *
     * <p>Serve à lixeira. São exatamente os estados que {@link #recuperarPendentes(DiarioE2)} vai
     * tentar concluir na próxima partida: mover o arquivo debaixo deles transformaria uma escrita
     * ainda recuperável num "resultado incerto" permanente. Proposta só preparada não aparece
     * aqui de propósito — ela falha sozinha e expira em dois minutos.
     */
    synchronized boolean temEdicaoEmVoo(String idArquivo) throws IOException {
        if (!Files.isDirectory(dados)) return false;
        try (var registros = Files.list(dados)) {
            for (Path caminho : registros
                    .filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    ObjectNode r = registro(caminho.getFileName().toString().replace(".json", ""));
                    if (java.util.Set.of("gravando", "desfazendo").contains(campo(r, "estado"))
                            && campo(r, "id").equals(idArquivo)) return true;
                } catch (IOException ignorada) { /* registro torto não bloqueia a lixeira */ }
            }
        }
        return false;
    }

    synchronized void recuperarPendentes(DiarioE2 diario) throws IOException {
        if (!Files.isDirectory(dados)) return;
        try (var registros = Files.list(dados)) {
            for (Path caminho : registros.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    String editId = caminho.getFileName().toString().replace(".json", "");
                    ObjectNode r = registro(editId);
                    String estado = campo(r, "estado");
                    if (!java.util.Set.of("gravando", "desfazendo", "concluida", "desfeita")
                            .contains(estado) || !r.path("comandoId").isTextual()) continue;
                    String comandoId = campo(r, "comandoId");
                    DiarioE2.Registro pendente = diario.obter(comandoId);
                    if (pendente == null || !java.util.Set.of("recebido", "executando")
                            .contains(pendente.estado())) continue;
                    CatalogoDeArquivos.Entrada e = catalogo.porId(campo(r, "id"));
                    Path alvo = campo(r, "destino").equals("copia")
                            ? nomeCopia(seguro(e), editId) : seguro(e);
                    String esperado = estado.equals("gravando") || estado.equals("concluida")
                            ? campo(r, "hashProposto") : campo(r, "hashAnterior");
                    if (Files.isRegularFile(alvo, LinkOption.NOFOLLOW_LINKS)
                            && hash(ler(alvo)).equals(esperado)) {
                        r.put("estado", estado.equals("gravando") || estado.equals("concluida")
                                ? "concluida" : "desfeita")
                                .put("nomeResultado", alvo.getFileName().toString());
                        guardar(editId, r);
                        if ((estado.equals("gravando") || estado.equals("concluida"))
                                && campo(r, "destino").equals("copia"))
                            catalogo.cadastrar(alvo);
                        else catalogo.atualizar(e.id(), estado.equals("gravando")
                                ? campo(r, "hashAnterior") : campo(r, "hashProposto"));
                        Resultado resultado = new Resultado(comandoId, true, alvo.getFileName().toString(),
                                null, alvo, JSON.createObjectNode().put("editId", editId)
                                        .put("versao", esperado));
                        diario.concluir(comandoId, "concluído", resultado, null);
                    } else {
                        diario.incerto(comandoId);
                        log.warning("resultado incerto na recuperação da edição " + editId);
                    }
                } catch (Exception e) {
                    log.warning("não foi possível recuperar edição: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    private Resultado recuperar(ComandoRecebido comando, ObjectNode r) throws IOException {
        CatalogoDeArquivos.Entrada entrada = catalogo.porId(campo(r, "id"));
        Path alvo = campo(r, "destino").equals("copia")
                ? nomeCopia(seguro(entrada), campo(r, "editId")) : seguro(entrada);
        if (Files.isRegularFile(alvo, LinkOption.NOFOLLOW_LINKS)
                && hash(ler(alvo)).equals(campo(r, "hashProposto"))) {
            r.put("estado", "concluida").put("nomeResultado", alvo.getFileName().toString());
            guardar(campo(r, "editId"), r);
            if (campo(r, "destino").equals("copia")) catalogo.cadastrar(alvo);
            else catalogo.atualizar(entrada.id(), campo(r, "hashAnterior"));
            return resultadoConcluido(comando, r);
        }
        throw new FalhaEdicao("RESULTADO_INCERTO", "resultado_incerto: não foi possível provar o estado após gravação");
    }

    private Resultado resultadoConcluido(ComandoRecebido comando, ObjectNode r) throws IOException {
        CatalogoDeArquivos.Entrada e = catalogo.porId(campo(r, "id"));
        Path alvo = campo(r, "destino").equals("copia")
                ? nomeCopia(seguro(e), campo(r, "editId")) : seguro(e);
        if (!hash(ler(alvo)).equals(campo(r, "hashProposto")))
            throw new FalhaEdicao("CONFLITO_DE_VERSAO", "CONFLITO_DE_VERSAO: resultado mudou depois da edição");
        return new Resultado(comando.id(), true, alvo.getFileName().toString(), null, alvo,
                JSON.createObjectNode().put("editId", campo(r, "editId"))
                        .put("versao", campo(r, "hashProposto")));
    }

    private Path seguro(CatalogoDeArquivos.Entrada e) throws IOException {
        if (!e.formato().matches("txt|md|json|xlsx")) throw new FalhaEdicao("extensão incompatível com edição");
        Path arquivo = catalogo.caminhoDe(e);
        Path real = arquivo.toRealPath();
        if (!real.getParent().equals(arquivo.getParent().toRealPath())
                || Files.isSymbolicLink(arquivo) || Files.isDirectory(arquivo, LinkOption.NOFOLLOW_LINKS)
                || arquivo.getFileName().toString().matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$"))
            throw new FalhaEdicao("arquivo fora da pasta autorizada ou reservado");
        if (arquivo.startsWith(dados) || dados.startsWith(arquivo.getParent()))
            throw new FalhaEdicao("pasta protegida não pode ser editada");
        if (configuracao != null && (arquivo.equals(configuracao)
                || arquivo.getParent().equals(configuracao.getParent())))
            throw new FalhaEdicao("pasta de configuração não pode ser editada");
        for (Path parte = arquivo.getParent(); parte != null; parte = parte.getParent()) {
            String nome = parte.getFileName() == null ? "" : parte.getFileName().toString().toLowerCase(Locale.ROOT);
            if (java.util.Set.of("memoria", "memória", "backups", "backup", "rotinas",
                    "credenciais", "credentials", "config", "lixeira", ".lixeira").contains(nome))
                throw new FalhaEdicao("diretório protegido não pode ser editado");
        }
        return arquivo;
    }

    private byte[] ler(Path arquivo) throws IOException {
        byte[] bytes;
        try { bytes = CatalogoDeArquivos.bytesSemLink(arquivo); }
        catch (java.nio.file.AccessDeniedException | java.nio.file.NoSuchFileException e) { throw e; }
        catch (IOException e) {
            throw new FalhaEdicao("FALHA_DE_IO", "falha de leitura; arquivo pode estar bloqueado ou indisponível", e);
        }
        if (bytes.length == 0 || bytes.length > limites.maxBytesArquivo())
            throw new FalhaEdicao("arquivo fora do limite de edição");
        if (!arquivo.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            for (byte b : bytes) if (b == 0) throw new FalhaEdicao("arquivo binário recusado");
        return bytes;
    }

    private record Texto(String conteudo, boolean bom, boolean crlf) { }
    private static Texto decodificar(byte[] bytes) throws IOException {
        boolean bom = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187
                && (bytes[2] & 255) == 191;
        try {
            String texto = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0))).toString();
            validarControles(texto);
            boolean crlf = texto.contains("\r\n");
            if (texto.replace("\r\n", "").contains("\r")
                    || crlf && texto.replace("\r\n", "").contains("\n"))
                throw new FalhaEdicao("quebras de linha misturadas ou desconhecidas");
            return new Texto(texto, bom, crlf);
        } catch (CharacterCodingException e) { throw new FalhaEdicao("codificação desconhecida; apenas UTF-8", e); }
    }
    private static byte[] codificar(String texto, boolean bom) {
        byte[] conteudo = texto.getBytes(StandardCharsets.UTF_8);
        if (!bom) return conteudo;
        byte[] saida = new byte[conteudo.length + 3];
        saida[0] = (byte) 0xef; saida[1] = (byte) 0xbb; saida[2] = (byte) 0xbf;
        System.arraycopy(conteudo, 0, saida, 3, conteudo.length);
        return saida;
    }
    private static void validarControles(String texto) throws IOException {
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\r' && c != '\n')
                throw new FalhaEdicao("arquivo binário ou com controles recusado");
        }
    }
    private static String chaveArquivo(Path arquivo) throws IOException {
        Object chave = Files.readAttributes(arquivo, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        return chave == null ? "sem-chave" : chave.toString();
    }
    private static void checarBloqueio(Path arquivo) throws IOException {
        try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            try (var bloqueio = canal.tryLock()) {
                if (bloqueio == null) throw new FalhaEdicao("ARQUIVO_BLOQUEADO", "arquivo bloqueado por outro programa");
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new FalhaEdicao("ARQUIVO_BLOQUEADO", "arquivo bloqueado por outro programa", e);
        }
    }
    private static void sincronizar(Path arquivo) throws IOException {
        try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.WRITE)) {
            canal.force(true);
        }
    }
    private static void preservarAtributos(Path origem, Path destino) throws IOException {
        AclFileAttributeView aclOrigem = Files.getFileAttributeView(origem, AclFileAttributeView.class);
        AclFileAttributeView aclDestino = Files.getFileAttributeView(destino, AclFileAttributeView.class);
        if (aclOrigem != null && aclDestino != null) {
            aclDestino.setOwner(aclOrigem.getOwner());
            aclDestino.setAcl(aclOrigem.getAcl());
        }
        DosFileAttributeView dosOrigem = Files.getFileAttributeView(origem, DosFileAttributeView.class);
        DosFileAttributeView dosDestino = Files.getFileAttributeView(destino, DosFileAttributeView.class);
        if (dosOrigem != null && dosDestino != null) {
            var a = dosOrigem.readAttributes();
            dosDestino.setHidden(a.isHidden()); dosDestino.setSystem(a.isSystem());
            dosDestino.setArchive(a.isArchive()); dosDestino.setReadOnly(a.isReadOnly());
        }
    }
    private static String normalizarQuebras(String texto, boolean crlf) throws IOException {
        if (texto.replace("\r\n", "").contains("\r"))
            throw new FalhaEdicao("quebra de linha não suportada");
        String lf = texto.replace("\r\n", "\n");
        return crlf ? lf.replace("\n", "\r\n") : lf;
    }
    private static String previa(String anterior, String proposto) {
        int inicio = 0, limite = Math.min(anterior.length(), proposto.length());
        while (inicio < limite && anterior.charAt(inicio) == proposto.charAt(inicio)) inicio++;
        int fimA = anterior.length(), fimP = proposto.length();
        while (fimA > inicio && fimP > inicio && anterior.charAt(fimA - 1) == proposto.charAt(fimP - 1)) {
            fimA--; fimP--;
        }
        int a = Math.max(0, inicio - 80), b = Math.min(anterior.length(), fimA + 80);
        int c = Math.min(proposto.length(), fimP + 80);
        return "- " + anterior.substring(a, b) + "\n+ " + proposto.substring(a, c);
    }
    private static String limitarUtf8(String texto, int maxBytes) {
        int fim = 0, bytes = 0;
        while (fim < texto.length()) {
            int ponto = texto.codePointAt(fim);
            int custo = new String(Character.toChars(ponto)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + custo > maxBytes) break;
            bytes += custo; fim += Character.charCount(ponto);
        }
        return texto.substring(0, fim);
    }
    private Path nomeCopia(Path original, String id) {
        String nome = original.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        return original.resolveSibling(nome.substring(0, ponto) + "-copia-" + id.substring(0, 8)
                + nome.substring(ponto));
    }
    private void backup(String id, ObjectNode r, byte[] anterior) throws IOException {
        if (anterior.length > limites.maxBytesBackup())
            throw new FalhaEdicao("backup excede o limite de retenção");
        guardarBytes(id, "backup", anterior);
        if (!hash(Files.readAllBytes(caminho(id, "backup"))).equals(campo(r, "hashAnterior")))
            throw new FalhaEdicao("backup não validado");
        try { expurgarBackups(id + ".backup"); }
        catch (IOException e) { Files.deleteIfExists(caminho(id, "backup")); throw e; }
    }

    private void limparPropostasExpiradas() throws IOException {
        if (!Files.isDirectory(dados)) return;
        try (var arquivos = Files.list(dados)) {
            for (Path arquivo : arquivos.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    String id = arquivo.getFileName().toString().replace(".json", "");
                    ObjectNode r = registro(id);
                    if (campo(r, "estado").equals("preparada")
                            && Instant.now().isAfter(Instant.parse(campo(r, "expiraEm")))) {
                        Files.deleteIfExists(caminho(id, "proposta"));
                        Files.deleteIfExists(arquivo);
                    }
                } catch (IOException ignorada) { /* registro inválido não dá acesso a arquivo comum */ }
            }
        }
    }

    private void expurgarBackups(String preservarArquivo) throws IOException {
        try (var arquivos = Files.list(dados)) {
            var backups = arquivos.filter(p -> p.getFileName().toString().endsWith(".backup")
                            || p.getFileName().toString().endsWith(".backup-revertido"))
                    .sorted(java.util.Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return Long.MAX_VALUE; }
                    })).toList();
            long total = 0;
            java.util.Map<String, Integer> porArquivo = new java.util.HashMap<>();
            for (Path p : backups) {
                total += Files.size(p);
                String id = idDoBackup(p);
                try { porArquivo.merge(campo(registro(id), "id"), 1, Integer::sum); }
                catch (IOException e) { porArquivo.merge("desconhecido", 1, Integer::sum); }
            }
            for (Path p : backups) {
                String id = idDoBackup(p);
                String arquivoId;
                try { arquivoId = campo(registro(id), "id"); }
                catch (IOException e) { arquivoId = "desconhecido"; }
                int quantidade = porArquivo.getOrDefault(arquivoId, 0);
                if (!p.getFileName().toString().equals(preservarArquivo)
                        && (quantidade > limites.maxVersoesBackup()
                        || total > limites.maxBytesBackup())) {
                    long tamanho = Files.size(p);
                    Files.deleteIfExists(p);
                    total -= tamanho;
                    porArquivo.merge(arquivoId, -1, Integer::sum);
                    log.info("backup expurgado editId=" + id);
                }
            }
            if (total > limites.maxBytesBackup())
                throw new FalhaEdicao("limite de backups atingido; original preservado");
        }
    }
    private static String idDoBackup(Path caminho) {
        String nome = caminho.getFileName().toString();
        return nome.substring(0, nome.indexOf('.'));
    }
    private ObjectNode registro(String id) throws IOException {
        prepararDados();
        if (!id.matches("[0-9a-f-]{36}")) throw new FalhaEdicao("editId inválido");
        Path arquivo = caminho(id, "json");
        if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)) throw new FalhaEdicao("edição desconhecida");
        JsonNode lido = JSON.readTree(Files.readString(arquivo));
        if (!(lido instanceof ObjectNode o)) throw new FalhaEdicao("registro de edição corrompido");
        return o;
    }
    private void guardar(String id, ObjectNode r) throws IOException {
        prepararDados();
        Path temp = Files.createTempFile(dados, ".registro-", ".tmp");
        try {
            Files.writeString(temp, JSON.writeValueAsString(r));
            Files.move(temp, caminho(id, "json"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private void guardarBytes(String id, String tipo, byte[] bytes) throws IOException {
        prepararDados();
        Path temp = Files.createTempFile(dados, ".bytes-", ".tmp");
        try {
            Files.write(temp, bytes);
            sincronizar(temp);
            Files.move(temp, caminho(id, tipo), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private Path caminho(String id, String tipo) { return dados.resolve(id + "." + tipo); }
    private void prepararDados() throws IOException {
        Files.createDirectories(dados);
        for (Path parte = dados; parte != null; parte = parte.getParent())
            if (Files.isSymbolicLink(parte)) throw new FalhaEdicao("backup em link recusado");
        Path real = dados.toRealPath();
        if (!real.equals(dados.toRealPath(LinkOption.NOFOLLOW_LINKS))
                || real.startsWith(catalogo.raizAutorizada()))
            throw new FalhaEdicao("backup fora da pasta protegida");
    }
    private static String campo(JsonNode n, String nome) throws IOException {
        String valor = campoBruto(n, nome);
        if (valor.isBlank()) throw new FalhaEdicao("campo " + nome + " ausente");
        return valor;
    }
    private static String campoBruto(JsonNode n, String nome) throws IOException {
        if (n == null || !n.path(nome).isTextual()) throw new FalhaEdicao("campo " + nome + " inválido");
        return n.path(nome).stringValue();
    }
    static String hash(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
