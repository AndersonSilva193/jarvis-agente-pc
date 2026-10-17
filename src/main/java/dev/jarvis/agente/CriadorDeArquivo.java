package dev.jarvis.agente;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import tools.jackson.databind.JsonNode;

/** Geração local limitada: nenhum arquivo fornecido pelo modelo é aberto como entrada. */
final class CriadorDeArquivo {
    private static final int LIMITE = 8 * 1024 * 1024;
    private static final Set<String> FORMATOS = Set.of("md", "txt", "docx", "pdf", "xlsx", "csv");
    private static final Pattern FORMULA = Pattern.compile("(?i)^=?[A-Z0-9:(),.+\\-*/ ]{1,128}$");
    private final Path pasta;
    CriadorDeArquivo(Path pasta) { this.pasta = pasta; }

    Resultado criar(ComandoRecebido comando) throws IOException {
        JsonNode dados = comando.dados();
        if (dados == null || !dados.isObject() || dados.toString().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
            throw new IOException("dados ausentes ou acima de 1 MiB");
        String formato = texto(dados, "formato");
        if (!FORMATOS.contains(formato)) throw new IOException("formato inválido");
        String nome = nomeSeguro(texto(dados, "nome"));
        if (Set.of("md", "txt", "docx", "pdf").contains(formato)) validarDocumento(dados);
        else validarPlanilha(dados, formato);
        validarFontes(dados);
        Path absoluto = pasta.toAbsolutePath().normalize();
        for (Path parte = absoluto; parte != null; parte = parte.getParent())
            if (Files.isSymbolicLink(parte)) throw new IOException("pasta de arquivos contém link");
        Files.createDirectories(absoluto);
        if (Files.isSymbolicLink(pasta)) throw new IOException("pasta de arquivos não pode ser link");
        Path real = pasta.toRealPath();
        if (!real.equals(pasta.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("pasta de arquivos redirecionada por link");
        for (int n = 0; n < 1000; n++) {
            String sufixo = n == 0 ? "" : "-" + n;
            Path destino = real.resolve(nome + sufixo + "." + formato);
            if (!destino.normalize().getParent().equals(real)) throw new IOException("caminho fora da pasta");
            try {
                Files.createFile(destino);
            } catch (FileAlreadyExistsException e) { continue; }
            try {
                escrever(destino, formato, dados);
                if (!Files.isRegularFile(destino, LinkOption.NOFOLLOW_LINKS)
                        || !destino.toRealPath().getParent().equals(real))
                    throw new IOException("arquivo saiu da pasta permitida");
                if (Files.size(destino) == 0 || Files.size(destino) > LIMITE)
                    throw new IOException("arquivo vazio ou acima de 8 MiB");
                new CatalogoDeArquivos(pasta).cadastrar(destino);
                return Resultado.comArquivo(comando, destino);
            } catch (Exception e) {
                Files.deleteIfExists(destino);
                if (e instanceof IOException io) throw io;
                throw new IOException("não consegui gerar " + formato + ": " + e.getMessage(), e);
            }
        }
        throw new IOException("há muitos arquivos com esse nome");
    }

    static String nomeSeguro(String nome) throws IOException {
        if (nome.isBlank() || nome.length() > 120 || nome.contains("/") || nome.contains("\\")
                || nome.contains(":") || nome.contains("..")) throw new IOException("nome deve vir sem caminho");
        String limpo = Normalizer.normalize(nome, Normalizer.Form.NFC)
                .replaceAll("[<>\"|?*\\p{Cntrl}]", "_").replaceAll("[. ]+$", "").trim();
        if (limpo.isBlank()) throw new IOException("nome vazio");
        if (limpo.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")) limpo += "_";
        return limpo.length() > 100 ? limpo.substring(0, 100) : limpo;
    }

    private static void validarDocumento(JsonNode dados) throws IOException {
        String md = texto(dados, "conteudo");
        if (md.getBytes(StandardCharsets.UTF_8).length > 256 * 1024 || md.matches("(?s).*(!\\[[^]]*]\\([^)]*\\)|<[^>]+>|\\{\\{.*?}}).*"))
            throw new IOException("Markdown grande demais ou contém imagem, HTML ou template");
    }

    private static void validarPlanilha(JsonNode dados, String formato) throws IOException {
        JsonNode abas = dados.path("abas");
        if (!abas.isArray() || abas.isEmpty() || abas.size() > (formato.equals("csv") ? 1 : 5))
            throw new IOException("quantidade de abas inválida");
        int total = 0;
        Set<String> nomes = new HashSet<>();
        for (JsonNode aba : abas) {
            String nome = texto(aba, "nome");
            if (nome.isBlank() || nome.length() > 31 || nome.matches(".*[\\[\\]:*?/\\\\].*")
                    || !nomes.add(nome.toLowerCase(Locale.ROOT))) throw new IOException("nome de aba inválido");
            JsonNode colunas = aba.path("colunas"), linhas = aba.path("linhas");
            if (!colunas.isArray() || colunas.isEmpty() || colunas.size() > 30
                    || !linhas.isArray() || linhas.size() > 2000) throw new IOException("limite da planilha excedido");
            total += colunas.size() * (linhas.size() + 1);
            for (JsonNode coluna : colunas) if (texto(coluna, "nome").isBlank()) throw new IOException("cabeçalho vazio");
            for (JsonNode linha : linhas) {
                if (!linha.isArray() || linha.size() > colunas.size()) throw new IOException("linha inválida");
                for (JsonNode celula : linha) {
                    String tipo = texto(celula, "tipo"), valor = texto(celula, "valor");
                    if (!Set.of("texto", "numero", "moeda", "data", "booleano", "formula").contains(tipo)
                            || valor.length() > 4096 || (formato.equals("csv") && tipo.equals("formula")))
                        throw new IOException("célula inválida");
                    if (tipo.equals("data")) try { LocalDate.parse(valor); }
                    catch (RuntimeException e) { throw new IOException("data deve ser ISO yyyy-MM-dd"); }
                    if (tipo.equals("formula") && !formulaValida(valor)) throw new IOException("fórmula recusada");
                    if (tipo.equals("numero") || tipo.equals("moeda")) try {
                        BigDecimal numero = new BigDecimal(valor.replace(',', '.'));
                        if (numero.abs().compareTo(new BigDecimal("1000000000")) > 0
                                || (tipo.equals("moeda") && numero.scale() > 2))
                            throw new IOException("número ou moeda fora do limite");
                    } catch (NumberFormatException e) { throw new IOException("número inválido"); }
                    if (tipo.equals("booleano") && !Set.of("true", "false").contains(valor))
                        throw new IOException("booleano inválido");
                }
            }
        }
        if (total > 20_000) throw new IOException("limite de 20.000 células excedido");
    }

    private static void validarFontes(JsonNode dados) throws IOException {
        JsonNode fontes = dados.path("fontes");
        if (dados.path("pesquisaAtual").asBoolean(false) && (!fontes.isArray() || fontes.isEmpty()))
            throw new IOException("pesquisa atual exige fontes");
        if (fontes.isMissingNode() || fontes.isNull()) return;
        if (!fontes.isArray() || fontes.size() > 20) throw new IOException("fontes inválidas");
        for (JsonNode fonte : fontes) {
            try {
                var uri = java.net.URI.create(texto(fonte, "url"));
                LocalDate.parse(texto(fonte, "consultadoEm"));
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || texto(fonte, "titulo").isBlank()
                        || texto(fonte, "titulo").length() > 160
                        || texto(fonte, "titulo").matches("(?s).*[<>\\r\\n!\\[\\]{}].*"))
                    throw new IOException("fonte inválida");
            } catch (RuntimeException e) { throw new IOException("fonte inválida", e); }
        }
    }

    static boolean formulaValida(String valor) {
        if (!FORMULA.matcher(valor).matches() || valor.contains("!") || valor.contains("[")) return false;
        var encontrados = Pattern.compile("[A-Z_]+(?=\\()", Pattern.CASE_INSENSITIVE).matcher(valor);
        while (encontrados.find()) if (!Set.of("SUM", "AVERAGE", "MIN", "MAX", "COUNT")
                .contains(encontrados.group().toUpperCase(Locale.ROOT))) return false;
        return true;
    }

    private static void escrever(Path destino, String formato, JsonNode dados) throws Exception {
        switch (formato) {
            case "md" -> Files.writeString(destino, markdownComFontes(dados), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            case "txt" -> Files.writeString(destino, blocos(dados).stream().map(Bloco::texto)
                    .reduce("", (a, b) -> a + b + System.lineSeparator()), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            case "docx" -> escreverDocx(destino, blocos(dados));
            case "pdf" -> escreverPdf(destino, blocos(dados));
            case "xlsx" -> escreverXlsx(destino, dados);
            case "csv" -> escreverCsv(destino, dados);
            default -> throw new IOException("formato inválido");
        }
    }

    private static String markdownComFontes(JsonNode dados) {
        StringBuilder md = new StringBuilder(texto(dados, "conteudo"));
        JsonNode fontes = dados.path("fontes");
        if (fontes.isArray() && !fontes.isEmpty()) {
            md.append("\n\n## Fontes\n");
            for (JsonNode fonte : fontes) md.append("- ").append(texto(fonte, "titulo"))
                    .append(" — ").append(texto(fonte, "url"))
                    .append(" (consulta: ").append(texto(fonte, "consultadoEm")).append(")\n");
        }
        return md.toString();
    }

    private record Bloco(String tipo, String texto, List<List<String>> tabela) {
        Bloco(String tipo, String texto) { this(tipo, texto, List.of()); }
    }

    private static List<Bloco> blocos(JsonNode dados) throws IOException {
        Node raiz = Parser.builder().extensions(List.of(TablesExtension.create())).build()
                .parse(markdownComFontes(dados));
        List<Bloco> saida = new ArrayList<>();
        for (Node no = raiz.getFirstChild(); no != null; no = no.getNext()) {
            if (no instanceof TableBlock tabela) {
                List<List<String>> linhas = new ArrayList<>();
                for (Node grupo = tabela.getFirstChild(); grupo != null; grupo = grupo.getNext())
                    for (Node linha = grupo.getFirstChild(); linha != null; linha = linha.getNext())
                        if (linha instanceof TableRow) {
                            List<String> celulas = new ArrayList<>();
                            for (Node celula = linha.getFirstChild(); celula != null; celula = celula.getNext())
                                if (celula instanceof TableCell) celulas.add(textoNo(celula));
                            linhas.add(celulas);
                        }
                saida.add(new Bloco("tabela", "", linhas));
            } else if (no instanceof Heading) saida.add(new Bloco("titulo", textoNo(no)));
            else if (no instanceof Paragraph) saida.add(new Bloco("paragrafo", textoNo(no)));
            else if (no instanceof FencedCodeBlock) saida.add(new Bloco("codigo", textoNo(no)));
            else if (no.getFirstChild() != null) for (Node filho = no.getFirstChild(); filho != null; filho = filho.getNext())
                saida.add(new Bloco("lista", "• " + textoNo(filho)));
        }
        return saida;
    }

    private static String textoNo(Node no) throws IOException {
        StringBuilder texto = new StringBuilder();
        no.accept(new AbstractVisitor() {
            @Override public void visit(Text no) { texto.append(no.getLiteral()); }
            @Override public void visit(Image no) { throw new IllegalArgumentException("imagem recusada"); }
        });
        return texto.toString();
    }

    private static void escreverDocx(Path destino, List<Bloco> blocos) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (Bloco bloco : blocos) {
                if (bloco.tipo().equals("tabela")) {
                    if (bloco.tabela().isEmpty()) continue;
                    int colunas = bloco.tabela().stream().mapToInt(List::size).max().orElse(1);
                    XWPFTable tabela = doc.createTable(bloco.tabela().size(), colunas);
                    for (int i = 0; i < bloco.tabela().size(); i++) for (int j = 0; j < bloco.tabela().get(i).size(); j++)
                        tabela.getRow(i).getCell(j).setText(bloco.tabela().get(i).get(j));
                } else {
                    var paragrafo = doc.createParagraph();
                    if (bloco.tipo().equals("titulo")) paragrafo.setStyle("Heading1");
                    var trecho = paragrafo.createRun();
                    trecho.setText(bloco.texto());
                    trecho.setFontFamily("DejaVu Sans");
                    trecho.setFontSize(bloco.tipo().equals("titulo") ? 16 : 10);
                    trecho.setBold(bloco.tipo().equals("titulo"));
                }
            }
            try (OutputStream saida = Files.newOutputStream(destino, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                doc.write(saida);
            }
        }
    }

    private static void escreverPdf(Path destino, List<Bloco> blocos) throws IOException {
        try (PDDocument doc = new PDDocument();
                var normal = CriadorDeArquivo.class.getResourceAsStream("/fontes/DejaVuSans.ttf");
                var negrito = CriadorDeArquivo.class.getResourceAsStream("/fontes/DejaVuSans-Bold.ttf")) {
            if (normal == null || negrito == null) throw new IOException("fontes do PDF ausentes");
            PDType0Font fonte = PDType0Font.load(doc, normal);
            PDType0Font forte = PDType0Font.load(doc, negrito);
            PDPage pagina = new PDPage(PDRectangle.A4);
            doc.addPage(pagina);
            PDPageContentStream fluxo = new PDPageContentStream(doc, pagina);
            float y = 790;
            try {
                for (Bloco bloco : blocos) {
                    if (bloco.tipo().equals("tabela") && !bloco.tabela().isEmpty()) {
                        int colunas = bloco.tabela().stream().mapToInt(List::size).max().orElse(1);
                        float largura = 500f / colunas;
                        int letras = Math.max(4, (int) (largura / 5.7f));
                        for (List<String> linha : bloco.tabela()) {
                            List<List<String>> partes = new ArrayList<>();
                            int maior = 1;
                            for (int c = 0; c < colunas; c++) {
                                List<String> celula = quebrar(c < linha.size() ? linha.get(c) : "", letras);
                                partes.add(celula); maior = Math.max(maior, celula.size());
                            }
                            float altura = 8 + maior * 13;
                            if (y - altura < 55) {
                                fluxo.close(); pagina = new PDPage(PDRectangle.A4); doc.addPage(pagina);
                                fluxo = new PDPageContentStream(doc, pagina); y = 790;
                            }
                            for (int c = 0; c < colunas; c++) {
                                float x = 45 + c * largura;
                                fluxo.addRect(x, y - altura, largura, altura); fluxo.stroke();
                                for (int l = 0; l < partes.get(c).size(); l++) {
                                    fluxo.beginText(); fluxo.setFont(fonte, 9);
                                    fluxo.newLineAtOffset(x + 4, y - 13 - l * 13);
                                    fluxo.showText(partes.get(c).get(l)); fluxo.endText();
                                }
                            }
                            y -= altura;
                        }
                        y -= 10;
                        continue;
                    }
                    List<String> linhas = List.of(bloco.texto());
                    for (String linha : linhas) for (String parte : quebrar(linha, bloco.tipo().equals("titulo") ? 70 : 105)) {
                        if (y < 55) {
                            fluxo.close(); pagina = new PDPage(PDRectangle.A4); doc.addPage(pagina);
                            fluxo = new PDPageContentStream(doc, pagina); y = 790;
                        }
                        fluxo.beginText(); fluxo.setFont(bloco.tipo().equals("titulo") ? forte : fonte,
                                bloco.tipo().equals("titulo") ? 15 : 9);
                        fluxo.newLineAtOffset(45, y); fluxo.showText(parte); fluxo.endText();
                        y -= bloco.tipo().equals("titulo") ? 21 : 15;
                    }
                    y -= 7;
                }
            } finally { fluxo.close(); }
            try (OutputStream saida = Files.newOutputStream(destino, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) { doc.save(saida); }
        }
    }

    private static List<String> quebrar(String texto, int max) {
        List<String> linhas = new ArrayList<>();
        while (texto.length() > max) {
            int corte = texto.lastIndexOf(' ', max);
            if (corte < 1) corte = max;
            linhas.add(texto.substring(0, corte)); texto = texto.substring(corte).stripLeading();
        }
        linhas.add(texto); return linhas;
    }

    private static void escreverXlsx(Path destino, JsonNode dados) throws IOException {
        try (XSSFWorkbook livro = new XSSFWorkbook()) {
            DataFormat formato = livro.createDataFormat();
            CellStyle moeda = livro.createCellStyle(); moeda.setDataFormat(formato.getFormat("R$ #,##0.00"));
            CellStyle data = livro.createCellStyle(); data.setDataFormat(formato.getFormat("dd/mm/yyyy"));
            CellStyle numero = livro.createCellStyle(); numero.setDataFormat(formato.getFormat("#,##0.########"));
            CellStyle cabecalho = livro.createCellStyle();
            Font forte = livro.createFont(); forte.setBold(true); cabecalho.setFont(forte);
            int indiceAba = 0;
            for (JsonNode aba : dados.path("abas")) {
                var folha = livro.createSheet(texto(aba, "nome"));
                JsonNode colunas = aba.path("colunas"), linhas = aba.path("linhas");
                var topo = folha.createRow(0);
                for (int j = 0; j < colunas.size(); j++) {
                    var celula = topo.createCell(j); celula.setCellValue(texto(colunas.get(j), "nome"));
                    celula.setCellStyle(cabecalho); folha.setColumnWidth(j, 18 * 256);
                }
                for (int i = 0; i < linhas.size(); i++) {
                    var linha = folha.createRow(i + 1);
                    for (int j = 0; j < linhas.get(i).size(); j++) {
                        JsonNode entrada = linhas.get(i).get(j);
                        String tipo = texto(entrada, "tipo"), valor = texto(entrada, "valor");
                        var celula = linha.createCell(j);
                        switch (tipo) {
                            case "numero", "moeda" -> {
                                BigDecimal n = new BigDecimal(valor.replace(',', '.'));
                                if (n.abs().compareTo(new BigDecimal("1000000000")) > 0) throw new IOException("número acima do limite");
                                celula.setCellValue(n.doubleValue()); celula.setCellStyle(tipo.equals("moeda") ? moeda : numero);
                            }
                            case "data" -> {
                                celula.setCellValue(java.util.Date.from(LocalDate.parse(valor)
                                        .atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant()));
                                celula.setCellStyle(data);
                            }
                            case "booleano" -> celula.setCellValue(Boolean.parseBoolean(valor));
                            case "formula" -> celula.setCellFormula(valor.startsWith("=") ? valor.substring(1) : valor);
                            default -> celula.setCellValue(valor);
                        }
                    }
                }
                JsonNode fontes = dados.path("fontes");
                if (indiceAba == 0 && fontes.isArray() && !fontes.isEmpty()) {
                    int linhaFonte = linhas.size() + 2;
                    var tituloFontes = folha.createRow(linhaFonte++);
                    tituloFontes.createCell(0).setCellValue("Fontes e data de consulta");
                    for (JsonNode fonte : fontes) {
                        var linha = folha.createRow(linhaFonte++);
                        linha.createCell(0).setCellValue(texto(fonte, "titulo") + " — "
                                + texto(fonte, "url") + " (consulta: "
                                + texto(fonte, "consultadoEm") + ")");
                    }
                }
                folha.createFreezePane(0, 1);
                folha.setAutoFilter(new CellRangeAddress(0, Math.max(1, linhas.size()), 0, colunas.size() - 1));
                indiceAba++;
            }
            livro.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (OutputStream saida = Files.newOutputStream(destino, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                livro.write(saida);
            }
        }
    }

    private static void escreverCsv(Path destino, JsonNode dados) throws IOException {
        JsonNode aba = dados.path("abas").get(0);
        StringBuilder csv = new StringBuilder("\uFEFF");
        List<String> colunas = new ArrayList<>();
        for (JsonNode coluna : aba.path("colunas")) colunas.add(csv(texto(coluna, "nome")));
        csv.append(String.join(";", colunas)).append("\r\n");
        for (JsonNode linha : aba.path("linhas")) {
            List<String> valores = new ArrayList<>();
            for (JsonNode celula : linha) valores.add(csv(texto(celula, "valor"),
                    "texto".equals(texto(celula, "tipo"))));
            csv.append(String.join(";", valores)).append("\r\n");
        }
        JsonNode fontes = dados.path("fontes");
        if (fontes.isArray() && !fontes.isEmpty()) {
            csv.append("\r\n\"Fontes e data de consulta\"\r\n");
            for (JsonNode fonte : fontes) csv.append(csv(texto(fonte, "titulo"))).append(';')
                    .append(csv(texto(fonte, "url"))).append(';')
                    .append(csv(texto(fonte, "consultadoEm"))).append("\r\n");
        }
        Files.writeString(destino, csv, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static String csv(String valor) {
        return csv(valor, true);
    }

    private static String csv(String valor, boolean proteger) {
        if (proteger && !valor.isEmpty() && "=+-@".indexOf(valor.charAt(0)) >= 0) valor = "'" + valor;
        return "\"" + valor.replace("\"", "\"\"") + "\"";
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        return valor.isTextual() ? valor.stringValue() : valor.isNumber() || valor.isBoolean() ? valor.toString() : "";
    }
}
