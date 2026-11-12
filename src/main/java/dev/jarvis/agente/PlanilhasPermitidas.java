package dev.jarvis.agente;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * As planilhas que os agentes do escritório usam, na raiz da pasta do Jarvis: a agenda, o livro
 * financeiro e os leads.
 *
 * <p>A VPS manda só o <b>nome</b> do arquivo, e ele precisa estar na lista {@code planilhas.permitidas}
 * do {@code config.properties}; caminho nunca. Cada linha tem uma coluna {@code Id}: acrescentar é
 * idempotente por ela (a fila da VPS pode reenviar o mesmo pedido), e alterar e remover acham a
 * linha por ela. Linha escrita à mão sem {@code Id} ganha um na próxima escrita.
 *
 * <p>O arquivo é lido como o Excel em português o deixar (UTF-8 com ou sem BOM, ou ANSI) e sempre
 * regravado em UTF-8 com BOM, {@code ;} e {@code \r\n}, que o Excel abre com acento certo. Arquivo
 * aberto em outro programa responde {@link #TRAVADO}, para a VPS tentar de novo mais tarde.
 */
final class PlanilhasPermitidas {

    static final String NAO_PERMITIDO = "nao_permitido";
    static final String TRAVADO = "arquivo_travado";
    static final String ID_INEXISTENTE = "id_inexistente";

    /** Quantas linhas, no máximo, chegam num pedido. */
    static final int MAX_LINHAS = 500;
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_BACKUPS = 10;
    private static final Pattern NOME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,79}");
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    record Saida(int gravadas, int jaExistiam) { }

    private final Path pasta;
    private final Set<String> permitidas;
    private final Path pastaBackup;

    PlanilhasPermitidas(Path pasta, Set<String> permitidas, Path pastaBackup) {
        this.pasta = pasta;
        Set<String> nomes = new HashSet<>();
        for (String nome : permitidas) if (nomeValido(nome)) nomes.add(nome.toLowerCase(Locale.ROOT));
        this.permitidas = Set.copyOf(nomes);
        this.pastaBackup = pastaBackup;
    }

    /** Nome simples de CSV: sem pasta, sem {@code ..}, sem unidade. */
    static boolean nomeValido(String nome) {
        return nome != null && NOME.matcher(nome).matches() && !nome.contains("..")
                && nome.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    boolean permitida(String nome) {
        return nomeValido(nome) && permitidas.contains(nome.toLowerCase(Locale.ROOT));
    }

    Saida acrescentar(String nome, List<String> cabecalho, List<List<String>> linhas) throws IOException {
        Path arquivo = arquivo(nome);
        if (cabecalho == null || cabecalho.isEmpty() || linhas == null || linhas.size() > MAX_LINHAS)
            throw new IOException(NAO_PERMITIDO + ": pedido sem cabeçalho ou com linhas demais");
        int idNoPedido = coluna(cabecalho, "id");
        if (idNoPedido < 0) throw new IOException(NAO_PERMITIDO + ": o pedido não tem coluna Id");
        conferirTrava(arquivo);
        Tabela tabela = Files.exists(arquivo, LinkOption.NOFOLLOW_LINKS) ? ler(arquivo)
                : new Tabela(new ArrayList<>(cabecalho), new ArrayList<>());
        int[] destino = new int[cabecalho.size()];
        for (int i = 0; i < cabecalho.size(); i++) {
            destino[i] = coluna(tabela.cabecalho, cabecalho.get(i));
            if (destino[i] < 0) {
                // Planilha feita à mão sem esta coluna: ela entra no fim, vazia nas linhas antigas.
                tabela.cabecalho.add(cabecalho.get(i));
                for (List<String> linha : tabela.linhas) linha.add("");
                destino[i] = tabela.cabecalho.size() - 1;
            }
        }
        Set<String> ids = new HashSet<>();
        for (List<String> linha : tabela.linhas) ids.add(linha.get(tabela.id).toLowerCase(Locale.ROOT));
        int gravadas = 0;
        int jaExistiam = 0;
        for (List<String> pedida : linhas) {
            if (pedida == null || pedida.size() != cabecalho.size())
                throw new IOException(NAO_PERMITIDO + ": linha com número de colunas diferente do cabeçalho");
            String id = pedida.get(idNoPedido).trim();
            if (id.isEmpty()) id = novoId();
            if (!ids.add(id.toLowerCase(Locale.ROOT))) {
                jaExistiam++;
                continue;
            }
            List<String> nova = vazia(tabela.cabecalho.size());
            for (int i = 0; i < cabecalho.size(); i++) nova.set(destino[i], protegida(pedida.get(i)));
            nova.set(tabela.id, id);
            tabela.linhas.add(nova);
            gravadas++;
        }
        gravar(arquivo, tabela);
        return new Saida(gravadas, jaExistiam);
    }

    Saida alterar(String nome, String id, Map<String, String> valores) throws IOException {
        Path arquivo = arquivo(nome);
        if (valores == null || valores.isEmpty()) throw new IOException(NAO_PERMITIDO + ": nada para alterar");
        conferirTrava(arquivo);
        Tabela tabela = lerExistente(arquivo, id);
        List<String> linha = linhaDoId(tabela, id);
        for (Map.Entry<String, String> valor : valores.entrySet()) {
            int coluna = coluna(tabela.cabecalho, valor.getKey());
            if (coluna < 0 || coluna == tabela.id)
                throw new IOException(NAO_PERMITIDO + ": a planilha não tem a coluna " + valor.getKey());
            linha.set(coluna, protegida(valor.getValue() == null ? "" : valor.getValue()));
        }
        guardarBackup(arquivo, nome);
        gravar(arquivo, tabela);
        return new Saida(1, 0);
    }

    Saida remover(String nome, String id) throws IOException {
        Path arquivo = arquivo(nome);
        conferirTrava(arquivo);
        Tabela tabela = lerExistente(arquivo, id);
        tabela.linhas.remove(linhaDoId(tabela, id));
        guardarBackup(arquivo, nome);
        gravar(arquivo, tabela);
        return new Saida(1, 0);
    }

    // ---------------------------------------------------------------- arquivo

    private Path arquivo(String nome) throws IOException {
        if (!permitida(nome)) throw new IOException(NAO_PERMITIDO + ": " + "arquivo fora da lista de planilhas");
        return pasta.resolve(nome);
    }

    private Tabela lerExistente(Path arquivo, String id) throws IOException {
        if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS))
            throw new IOException(ID_INEXISTENTE + ": a planilha ainda não existe");
        return ler(arquivo);
    }

    private static List<String> linhaDoId(Tabela tabela, String id) throws IOException {
        String procurado = id == null ? "" : id.trim();
        if (!procurado.isEmpty())
            for (List<String> linha : tabela.linhas)
                if (linha.get(tabela.id).equalsIgnoreCase(procurado)) return linha;
        throw new IOException(ID_INEXISTENTE + ": nenhuma linha com o id " + procurado);
    }

    /**
     * O Excel abre o arquivo sem deixar outro programa escrever: a abertura para escrita falha. Na
     * mesma JVM, a trava de outro canal aparece como {@link OverlappingFileLockException}.
     */
    private static void conferirTrava(Path arquivo) throws IOException {
        if (!Files.exists(arquivo, LinkOption.NOFOLLOW_LINKS)) return;
        try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock trava = canal.tryLock();
            if (trava == null) throw new IOException(TRAVADO + ": o arquivo está aberto em outro programa");
            trava.release();
        } catch (OverlappingFileLockException | FileSystemException e) {
            throw new IOException(TRAVADO + ": o arquivo está aberto em outro programa", e);
        }
    }

    private static Tabela ler(Path arquivo) throws IOException {
        if (Files.isSymbolicLink(arquivo)) throw new IOException(NAO_PERMITIDO + ": link não é planilha");
        if (Files.size(arquivo) > MAX_BYTES) throw new IOException(NAO_PERMITIDO + ": planilha grande demais");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(arquivo);
        } catch (FileSystemException e) {
            throw new IOException(TRAVADO + ": o arquivo está aberto em outro programa", e);
        }
        List<List<String>> linhas = parse(decodificar(bytes));
        if (linhas.isEmpty()) throw new IOException(NAO_PERMITIDO + ": planilha sem cabeçalho");
        List<String> cabecalho = new ArrayList<>(linhas.remove(0));
        // Planilha feita à mão sem coluna Id: ela entra na frente, e cada linha ganha o seu.
        boolean semId = coluna(cabecalho, "id") < 0;
        if (semId) cabecalho.add(0, "Id");
        int id = coluna(cabecalho, "id");
        List<List<String>> dados = new ArrayList<>();
        for (List<String> lida : linhas) {
            List<String> linha = new ArrayList<>(lida);
            if (semId) linha.add(0, "");
            List<String> cheia = vazia(cabecalho.size());
            for (int i = 0; i < Math.min(linha.size(), cabecalho.size()); i++) cheia.set(i, linha.get(i));
            if (cheia.stream().allMatch(String::isBlank)) continue;
            if (cheia.get(id).isBlank()) cheia.set(id, novoId());
            dados.add(cheia);
        }
        return new Tabela(cabecalho, dados);
    }

    /** UTF-8 com BOM; senão UTF-8 estrito; senão o ANSI que o Excel em português grava. */
    static String decodificar(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF)
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** CSV com {@code ;} ou {@code ,} (o que o cabeçalho usar), aspas e quebra de linha dentro de aspas. */
    static List<List<String>> parse(String texto) {
        int fimDaPrimeira = texto.indexOf('\n');
        String primeira = fimDaPrimeira < 0 ? texto : texto.substring(0, fimDaPrimeira);
        char separador = primeira.indexOf(';') >= 0 || primeira.indexOf(',') < 0 ? ';' : ',';
        List<List<String>> linhas = new ArrayList<>();
        List<String> atual = new ArrayList<>();
        StringBuilder celula = new StringBuilder();
        boolean aspas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (aspas) {
                if (c == '"' && i + 1 < texto.length() && texto.charAt(i + 1) == '"') { celula.append('"'); i++; }
                else if (c == '"') aspas = false;
                else celula.append(c);
            } else if (c == '"') {
                aspas = true;
            } else if (c == separador) {
                atual.add(celula.toString()); celula.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') i++;
                atual.add(celula.toString()); celula.setLength(0);
                linhas.add(atual); atual = new ArrayList<>();
            } else {
                celula.append(c);
            }
        }
        if (celula.length() > 0 || !atual.isEmpty()) { atual.add(celula.toString()); linhas.add(atual); }
        linhas.removeIf(l -> l.size() == 1 && l.get(0).isEmpty());
        return linhas;
    }

    private static void gravar(Path arquivo, Tabela tabela) throws IOException {
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(linhaCsv(tabela.cabecalho)).append("\r\n");
        for (List<String> linha : tabela.linhas) csv.append(linhaCsv(linha)).append("\r\n");
        Path temporario = Files.createTempFile(arquivo.getParent(), ".planilha-", ".tmp");
        try {
            Files.writeString(temporario, csv, StandardCharsets.UTF_8);
            Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileSystemException e) {
            throw new IOException(TRAVADO + ": o arquivo está aberto em outro programa", e);
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    private void guardarBackup(Path arquivo, String nome) throws IOException {
        Files.createDirectories(pastaBackup);
        String base = nome.toLowerCase(Locale.ROOT).replaceAll("\\.csv$", "");
        Files.copy(arquivo, pastaBackup.resolve(base + "-" + LocalDateTime.now().format(CARIMBO) + ".csv"),
                StandardCopyOption.REPLACE_EXISTING);
        try (Stream<Path> copias = Files.list(pastaBackup)) {
            List<Path> doArquivo = copias.filter(p -> p.getFileName().toString().startsWith(base + "-"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
            for (Path antiga : doArquivo.subList(Math.min(MAX_BACKUPS, doArquivo.size()), doArquivo.size()))
                Files.deleteIfExists(antiga);
        }
    }

    // ---------------------------------------------------------------- células

    private static String linhaCsv(List<String> celulas) {
        List<String> saida = new ArrayList<>(celulas.size());
        for (String celula : celulas) saida.add(celulaCsv(celula));
        return String.join(";", saida);
    }

    private static String celulaCsv(String valor) {
        if (valor.indexOf(';') < 0 && valor.indexOf('"') < 0 && valor.indexOf('\n') < 0 && valor.indexOf('\r') < 0)
            return valor;
        return "\"" + valor.replace("\"", "\"\"") + "\"";
    }

    /** Só no que entra agora: célula antiga, escrita à mão, fica como está. */
    private static String protegida(String valor) {
        return CriadorDeArquivo.protegerFormula(valor == null ? "" : valor);
    }

    private static int coluna(List<String> cabecalho, String nome) {
        String procurado = semAcento(nome);
        for (int i = 0; i < cabecalho.size(); i++) if (semAcento(cabecalho.get(i)).equals(procurado)) return i;
        return -1;
    }

    private static String semAcento(String texto) {
        return java.text.Normalizer.normalize(texto == null ? "" : texto.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> vazia(int tamanho) {
        List<String> linha = new ArrayList<>(tamanho);
        for (int i = 0; i < tamanho; i++) linha.add("");
        return linha;
    }

    /**
     * Oito caracteres hexadecimais começando por letra: só com dígitos (ou como {@code 123e4567}) o
     * Excel leria número, cortaria o zero da frente ou viraria notação científica ao salvar.
     */
    static String novoId() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return "abcdef".charAt(Character.digit(hex.charAt(0), 16) % 6) + hex.substring(1, 8);
    }

    private static final class Tabela {
        final List<String> cabecalho;
        final List<List<String>> linhas;
        final int id;

        Tabela(List<String> cabecalho, List<List<String>> linhas) {
            this.cabecalho = cabecalho;
            this.linhas = linhas;
            this.id = coluna(cabecalho, "id");
        }
    }
}
