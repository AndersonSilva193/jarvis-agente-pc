package dev.jarvis.agente;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Catálogo persistente dos arquivos diretos da pasta configurada e da subpasta {@link #PASTA_ENTRADA}.
 *
 * <p>A subpasta é um nome fixo, não um caminho: a VPS pede {@code "pasta": "entrada"} ou nada, e
 * qualquer outro valor é recusado. É por ela que a VPS percebe arquivo novo chegando (o gatilho
 * {@code arquivo_novo_na_entrada}). Os arquivos de lá ganham ID próprio e são lidos e apagados pelo
 * ID como os da raiz; criar, copiar e restaurar da lixeira continuam sendo na raiz.
 */
final class CatalogoDeArquivos {
    static final long LIMITE_ARQUIVO = 8L * 1024 * 1024;
    static final Set<String> FORMATOS = Set.of("txt", "md", "json", "docx", "pdf", "xlsx", "csv");
    /** A única subpasta catalogada. Vazio é a raiz. */
    static final String PASTA_ENTRADA = "entrada";
    private static final String ARQUIVO_CATALOGO = ".jarvis-catalogo.properties";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path pasta;

    /** @param pasta {@code ""} para a raiz ou {@link #PASTA_ENTRADA} */
    record Entrada(String id, String nome, String formato, long tamanho, long modificado, String hash,
            String pasta) {
        Entrada {
            pasta = pasta == null ? "" : pasta;
        }
        /** Entrada da raiz: a forma de antes da subpasta existir. */
        Entrada(String id, String nome, String formato, long tamanho, long modificado, String hash) {
            this(id, nome, formato, tamanho, modificado, hash, "");
        }
    }

    static String nomeFalado(String nome) {
        return Normalizer.normalize(nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    /**
     * Diz se o nome falado do alvo — o nome sem extensão — serve para identificá-lo sozinho.
     *
     * A contagem percorre o catálogo inteiro, e não só o formato do alvo: quem fala "edite o
     * relatório" não está escolhendo entre {@code relatorio.md} e {@code relatorio.xlsx}, então
     * os dois juntos tornam o apelido ambíguo e a edição é recusada do outro lado.
     */
    synchronized boolean apelidoUnico(Entrada alvo) throws IOException {
        String nome = alvo.nome();
        int ponto = nome.lastIndexOf('.');
        if (ponto <= 0) return false;
        String apelido = nomeFalado(nome.substring(0, ponto));
        return carregar().stream()
                .filter(e -> {
                    int separador = e.nome().lastIndexOf('.');
                    return separador > 0 && nomeFalado(e.nome().substring(0, separador)).equals(apelido);
                }).count() == 1;
    }

    CatalogoDeArquivos(Path pasta) { this.pasta = pasta.toAbsolutePath().normalize(); }

    private Path raiz() throws IOException {
        for (Path parte = pasta; parte != null; parte = parte.getParent())
            if (Files.isSymbolicLink(parte)) throw new IOException("pasta de arquivos contém link");
        Files.createDirectories(pasta);
        if (!Files.isDirectory(pasta, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("pasta de arquivos inválida");
        Path semLinks = pasta.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!pasta.toRealPath().equals(semLinks))
            throw new IOException("pasta de arquivos redirecionada por link ou junction");
        return semLinks;
    }
    Path raizAutorizada() throws IOException { return raiz(); }

    /**
     * A raiz ou a subpasta {@code entrada}, com as mesmas checagens de link e junction: a subpasta
     * tem de estar de fato dentro da raiz, e não apontar para outro lugar da máquina.
     */
    private Path pastaDe(String subpasta) throws IOException {
        if (subpasta == null || subpasta.isEmpty()) return raiz();
        if (!PASTA_ENTRADA.equals(subpasta)) throw new IOException("pasta inválida");
        Path raiz = raiz();
        Path entrada = raiz.resolve(PASTA_ENTRADA);
        if (Files.isSymbolicLink(entrada)) throw new IOException("pasta entrada não pode ser link");
        Files.createDirectories(entrada);
        if (!Files.isDirectory(entrada, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("pasta entrada inválida");
        Path real = entrada.toRealPath();
        if (!real.equals(entrada.toRealPath(LinkOption.NOFOLLOW_LINKS)) || !raiz.equals(real.getParent()))
            throw new IOException("pasta entrada redirecionada por link ou junction");
        return real;
    }

    private Path caminho(String nome) throws IOException {
        return caminho(nome, "");
    }

    private Path caminho(String nome, String subpasta) throws IOException {
        if (nome == null || nome.isBlank() || nome.contains("/") || nome.contains("\\")
                || nome.contains(":") || nome.equals(".") || nome.contains(".."))
            throw new IOException("nome de arquivo inválido");
        Path base = pastaDe(subpasta);
        Path destino = base.resolve(nome).normalize();
        if (!destino.getParent().equals(base) || Files.isSymbolicLink(destino))
            throw new IOException("arquivo fora da pasta permitida ou link");
        return destino;
    }

    static String formato(String nome) {
        int ponto = nome.lastIndexOf('.');
        return ponto < 0 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
    }

    private Entrada validar(Path arquivo, String id) throws IOException {
        return validar(arquivo, id, "");
    }

    private Entrada validar(Path arquivo, String id, String subpasta) throws IOException {
        String nome = arquivo.getFileName().toString();
        if (!FORMATOS.contains(formato(nome)) || !Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)
                || !arquivo.toRealPath().getParent().equals(pastaDe(subpasta)))
            throw new IOException("arquivo não suportado ou link");
        long tamanho = Files.size(arquivo);
        if (tamanho == 0 || tamanho > LIMITE_ARQUIVO) throw new IOException("arquivo vazio ou acima de 8 MiB");
        if (id != null && !id.isEmpty()) validarFormato(arquivo, formato(nome));
        return new Entrada(id, nome, formato(nome), tamanho,
                Files.getLastModifiedTime(arquivo, LinkOption.NOFOLLOW_LINKS).toMillis(), hash(arquivo),
                subpasta);
    }

    private static void validarFormato(Path arquivo, String formato) throws IOException {
        try {
            switch (formato) {
                case "txt", "md", "csv" -> java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytesSemLink(arquivo)));
                case "json" -> EdicaoJson.analisar(bytesSemLink(arquivo));
                case "pdf" -> { try (var doc = Loader.loadPDF(bytesSemLink(arquivo))) {
                    if (doc.isEncrypted()) throw new IOException("PDF protegido por senha");
                    doc.getNumberOfPages();
                } }
                case "docx" -> { try (var doc = new XWPFDocument(Files.newInputStream(arquivo,
                        LinkOption.NOFOLLOW_LINKS))) { doc.getParagraphs().size(); } }
                case "xlsx" -> { EdicaoXlsx.validarZip(bytesSemLink(arquivo));
                    try (var livro = new XSSFWorkbook(Files.newInputStream(arquivo,
                        LinkOption.NOFOLLOW_LINKS))) { livro.getNumberOfSheets(); } }
                default -> throw new IOException("formato inválido");
            }
        } catch (RuntimeException e) { throw new IOException("arquivo corrompido ou protegido", e); }
    }

    static byte[] bytesSemLink(Path arquivo) throws IOException {
        try (InputStream entrada = Files.newInputStream(arquivo, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = entrada.readNBytes((int) LIMITE_ARQUIVO + 1);
            if (bytes.length > LIMITE_ARQUIVO) throw new IOException("arquivo acima de 8 MiB");
            return bytes;
        }
    }

    private static String hash(Path arquivo) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream entrada = Files.newInputStream(arquivo, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bloco = new byte[8192]; int n; long total = 0;
                while ((n = entrada.read(bloco)) >= 0) {
                    total += n;
                    if (total > LIMITE_ARQUIVO) throw new IOException("arquivo acima de 8 MiB");
                    digest.update(bloco, 0, n);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    synchronized Entrada cadastrar(Path arquivo) throws IOException {
        Path seguro = caminho(arquivo.getFileName().toString());
        Entrada atual = validar(seguro, "");
        List<Entrada> entradas = carregar();
        for (Entrada e : entradas) if (e.pasta().isEmpty() && e.nome().equals(atual.nome())) {
            if (!e.hash().equals(atual.hash())) throw new IOException("arquivo já cadastrado foi alterado");
            return e;
        }
        Entrada novo = new Entrada(UUID.randomUUID().toString(), atual.nome(), atual.formato(),
                atual.tamanho(), atual.modificado(), atual.hash());
        entradas.add(novo); salvar(entradas); return novo;
    }

    synchronized ObjectNode listar(JsonNode pedido) throws IOException {
        String nome = texto(pedido, "nome").toLowerCase(Locale.ROOT);
        String formato = texto(pedido, "formato").toLowerCase(Locale.ROOT);
        if (!formato.isEmpty() && !FORMATOS.contains(formato)) throw new IOException("formato inválido");
        int pagina = numero(pedido, "pagina", 1), tamanho = numero(pedido, "tamanhoPagina", 20);
        if (pagina < 1 || pagina > 10000 || tamanho < 1 || tamanho > 50 || nome.length() > 120)
            throw new IOException("paginação ou filtro inválido");
        String subpasta = texto(pedido, "pasta");
        if (!subpasta.isEmpty() && !PASTA_ENTRADA.equals(subpasta)) throw new IOException("pasta inválida");
        List<Entrada> entradas = carregar();
        boolean mudou = false;
        try (var arquivos = Files.list(pastaDe(subpasta))) {
            for (Path arquivo : arquivos.toList()) {
                String n = arquivo.getFileName().toString();
                if (!FORMATOS.contains(formato(n)) || entradas.stream()
                        .anyMatch(e -> e.pasta().equals(subpasta) && e.nome().equals(n))) continue;
                try { entradas.add(validar(arquivo, UUID.randomUUID().toString(), subpasta)); mudou = true; }
                catch (IOException ignorada) { /* links, diretórios e arquivos inválidos ficam fora */ }
            }
        }
        if (mudou) salvar(entradas);
        String buscaFalado = nomeFalado(nome);
        List<Entrada> filtradas = entradas.stream()
                .filter(e -> e.pasta().equals(subpasta))
                .filter(e -> e.nome().toLowerCase(Locale.ROOT).contains(nome)
                        || nomeFalado(e.nome()).contains(buscaFalado))
                .filter(e -> formato.isEmpty() || e.formato().equals(formato))
                .sorted(Comparator.comparing(Entrada::nome).thenComparing(Entrada::id)).toList();
        ObjectNode saida = JSON.createObjectNode();
        saida.put("total", filtradas.size()); saida.put("pagina", pagina); saida.put("tamanhoPagina", tamanho);
        // A VPS confere o eco: agente antigo, que lista a raiz, não pode passar por subpasta.
        if (!subpasta.isEmpty()) saida.put("pasta", subpasta);
        ArrayNode itens = saida.putArray("arquivos");
        long inicio = ((long) pagina - 1) * tamanho;
        for (long i = inicio; i < Math.min(filtradas.size(), inicio + tamanho); i++) {
            Entrada e = filtradas.get((int) i);
            ObjectNode item = itens.addObject();
            item.put("id", e.id()); item.put("nome", e.nome()); item.put("formato", e.formato());
            item.put("tamanho", e.tamanho()); item.put("modificadoEm", java.time.Instant.ofEpochMilli(e.modificado()).toString());
            item.put("versaoEsperada", e.hash());
            item.put("estado", estado(e));
        }
        saida.put("temMais", inicio + tamanho < filtradas.size());
        return saida;
    }

    synchronized Entrada porId(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IOException("identificador inválido");
        return carregar().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("arquivo não encontrado no catálogo"));
    }

    /**
     * Tira a entrada do catálogo e diz se havia o que tirar.
     *
     * <p>É o que a lixeira usa depois de mover o arquivo. Sem isto a entrada sobreviveria e o
     * {@code listar_arquivos} seguiria mostrando o item, com estado "removido" — um arquivo que o
     * usuário mandou para a lixeira não deve aparecer mais na listagem comum.
     */
    synchronized boolean esquecer(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IOException("identificador inválido");
        List<Entrada> entradas = carregar();
        if (!entradas.removeIf(e -> e.id().equals(id))) return false;
        salvar(entradas);
        return true;
    }

    synchronized void atualizar(String id, String hashAnterior) throws IOException {
        List<Entrada> entradas = carregar();
        for (int i = 0; i < entradas.size(); i++) {
            Entrada e = entradas.get(i);
            if (!e.id().equals(id)) continue;
            if (!e.hash().equals(hashAnterior) && !estado(e).equals("disponivel"))
                throw new IOException("CONFLITO_DE_VERSAO no catálogo");
            entradas.set(i, validar(caminho(e.nome(), e.pasta()), id, e.pasta()));
            salvar(entradas);
            return;
        }
        throw new IOException("arquivo não encontrado no catálogo");
    }

    synchronized String estado(Entrada e) {
        try {
            Path arquivo = caminho(e.nome(), e.pasta());
            if (!Files.exists(arquivo, LinkOption.NOFOLLOW_LINKS)) return "removido";
            Entrada atual = validar(arquivo, e.id(), e.pasta());
            return atual.tamanho() == e.tamanho() && atual.modificado() == e.modificado()
                    && atual.hash().equals(e.hash()) ? "disponivel" : "alterado";
        } catch (IOException ex) { return "alterado"; }
    }

    Path arquivo(Entrada e) throws IOException {
        String estado = estado(e);
        if (!estado.equals("disponivel")) throw new IOException("arquivo " + estado + " desde o cadastro");
        return caminho(e.nome(), e.pasta());
    }

    Path caminhoDe(Entrada e) throws IOException {
        Path arquivo = caminho(e.nome(), e.pasta());
        if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)
                || !arquivo.toRealPath().getParent().equals(pastaDe(e.pasta())))
            throw new IOException("arquivo fora da pasta permitida ou link");
        return arquivo;
    }

    private List<Entrada> carregar() throws IOException {
        Path arquivo = raiz().resolve(ARQUIVO_CATALOGO);
        if (Files.isSymbolicLink(arquivo)) throw new IOException("catálogo não pode ser link");
        Properties props = new Properties();
        if (Files.exists(arquivo)) try (InputStream in = Files.newInputStream(arquivo, LinkOption.NOFOLLOW_LINKS)) {
            props.load(in);
        }
        List<Entrada> lista = new ArrayList<>();
        for (String id : props.stringPropertyNames()) try {
            String[] partes = props.getProperty(id).split("\\|", -1);
            String nome = new String(Base64.getUrlDecoder().decode(partes[0]), java.nio.charset.StandardCharsets.UTF_8);
            // O sexto campo é a subpasta; catálogo gravado antes dela tem cinco, e é tudo raiz.
            String subpasta = partes.length > 5 ? partes[5] : "";
            if (!subpasta.isEmpty() && !PASTA_ENTRADA.equals(subpasta)) throw new IOException("pasta inválida no catálogo");
            lista.add(new Entrada(id, nome, partes[1], Long.parseLong(partes[2]),
                    Long.parseLong(partes[3]), partes[4], subpasta));
        } catch (RuntimeException ex) { throw new IOException("catálogo corrompido", ex); }
        return lista;
    }

    private void salvar(List<Entrada> entradas) throws IOException {
        Properties props = new Properties();
        for (Entrada e : entradas) props.setProperty(e.id(), Base64.getUrlEncoder().encodeToString(
                e.nome().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "|" + e.formato() + "|"
                + e.tamanho() + "|" + e.modificado() + "|" + e.hash()
                + (e.pasta().isEmpty() ? "" : "|" + e.pasta()));
        Path temporario = Files.createTempFile(raiz(), ".catalogo-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporario, StandardOpenOption.TRUNCATE_EXISTING))
                { props.store(out, "Jarvis"); }
            Files.move(temporario, raiz().resolve(ARQUIVO_CATALOGO), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporario); }
    }

    static String texto(JsonNode dados, String campo) {
        if (dados == null) return "";
        JsonNode valor = dados.path(campo);
        return valor.isTextual() ? valor.stringValue().trim() : "";
    }
    static int numero(JsonNode dados, String campo, int padrao) {
        if (dados == null || dados.path(campo).isMissingNode()) return padrao;
        return dados.path(campo).isInt() ? dados.path(campo).intValue() : -1;
    }
}
