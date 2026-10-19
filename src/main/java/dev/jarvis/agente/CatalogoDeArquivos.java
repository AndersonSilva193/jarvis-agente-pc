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

/** Catálogo persistente somente dos arquivos diretos da pasta configurada. */
final class CatalogoDeArquivos {
    static final long LIMITE_ARQUIVO = 8L * 1024 * 1024;
    private static final Set<String> FORMATOS = Set.of("txt", "md", "json", "docx", "pdf", "xlsx", "csv");
    private static final String ARQUIVO_CATALOGO = ".jarvis-catalogo.properties";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path pasta;

    record Entrada(String id, String nome, String formato, long tamanho, long modificado, String hash) { }

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

    private Path caminho(String nome) throws IOException {
        if (nome == null || nome.isBlank() || nome.contains("/") || nome.contains("\\")
                || nome.contains(":") || nome.equals(".") || nome.contains(".."))
            throw new IOException("nome de arquivo inválido");
        Path destino = raiz().resolve(nome).normalize();
        if (!destino.getParent().equals(raiz()) || Files.isSymbolicLink(destino))
            throw new IOException("arquivo fora da pasta permitida ou link");
        return destino;
    }

    private static String formato(String nome) {
        int ponto = nome.lastIndexOf('.');
        return ponto < 0 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
    }

    private Entrada validar(Path arquivo, String id) throws IOException {
        String nome = arquivo.getFileName().toString();
        if (!FORMATOS.contains(formato(nome)) || !Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)
                || !arquivo.toRealPath().getParent().equals(raiz()))
            throw new IOException("arquivo não suportado ou link");
        long tamanho = Files.size(arquivo);
        if (tamanho == 0 || tamanho > LIMITE_ARQUIVO) throw new IOException("arquivo vazio ou acima de 8 MiB");
        if (id != null && !id.isEmpty()) validarFormato(arquivo, formato(nome));
        return new Entrada(id, nome, formato(nome), tamanho,
                Files.getLastModifiedTime(arquivo, LinkOption.NOFOLLOW_LINKS).toMillis(), hash(arquivo));
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
        for (Entrada e : entradas) if (e.nome().equals(atual.nome())) {
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
        List<Entrada> entradas = carregar();
        boolean mudou = false;
        try (var arquivos = Files.list(raiz())) {
            for (Path arquivo : arquivos.toList()) {
                String n = arquivo.getFileName().toString();
                if (!FORMATOS.contains(formato(n)) || entradas.stream().anyMatch(e -> e.nome().equals(n))) continue;
                try { entradas.add(validar(arquivo, UUID.randomUUID().toString())); mudou = true; }
                catch (IOException ignorada) { /* links, diretórios e arquivos inválidos ficam fora */ }
            }
        }
        if (mudou) salvar(entradas);
        List<Entrada> filtradas = entradas.stream().filter(e -> e.nome().toLowerCase(Locale.ROOT).contains(nome))
                .filter(e -> formato.isEmpty() || e.formato().equals(formato))
                .sorted(Comparator.comparing(Entrada::nome).thenComparing(Entrada::id)).toList();
        ObjectNode saida = JSON.createObjectNode();
        saida.put("total", filtradas.size()); saida.put("pagina", pagina); saida.put("tamanhoPagina", tamanho);
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

    synchronized void atualizar(String id, String hashAnterior) throws IOException {
        List<Entrada> entradas = carregar();
        for (int i = 0; i < entradas.size(); i++) {
            Entrada e = entradas.get(i);
            if (!e.id().equals(id)) continue;
            if (!e.hash().equals(hashAnterior) && !estado(e).equals("disponivel"))
                throw new IOException("CONFLITO_DE_VERSAO no catálogo");
            entradas.set(i, validar(caminho(e.nome()), id));
            salvar(entradas);
            return;
        }
        throw new IOException("arquivo não encontrado no catálogo");
    }

    synchronized String estado(Entrada e) {
        try {
            Path arquivo = caminho(e.nome());
            if (!Files.exists(arquivo, LinkOption.NOFOLLOW_LINKS)) return "removido";
            Entrada atual = validar(arquivo, e.id());
            return atual.tamanho() == e.tamanho() && atual.modificado() == e.modificado()
                    && atual.hash().equals(e.hash()) ? "disponivel" : "alterado";
        } catch (IOException ex) { return "alterado"; }
    }

    Path arquivo(Entrada e) throws IOException {
        String estado = estado(e);
        if (!estado.equals("disponivel")) throw new IOException("arquivo " + estado + " desde o cadastro");
        return caminho(e.nome());
    }

    Path caminhoDe(Entrada e) throws IOException {
        Path arquivo = caminho(e.nome());
        if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)
                || !arquivo.toRealPath().getParent().equals(raiz()))
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
            lista.add(new Entrada(id, nome, partes[1], Long.parseLong(partes[2]),
                    Long.parseLong(partes[3]), partes[4]));
        } catch (RuntimeException ex) { throw new IOException("catálogo corrompido", ex); }
        return lista;
    }

    private void salvar(List<Entrada> entradas) throws IOException {
        Properties props = new Properties();
        for (Entrada e : entradas) props.setProperty(e.id(), Base64.getUrlEncoder().encodeToString(
                e.nome().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "|" + e.formato() + "|"
                + e.tamanho() + "|" + e.modificado() + "|" + e.hash());
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
