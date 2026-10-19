package dev.jarvis.agente;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.XmlCursor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Edição limitada de células de um XLSX existente, sem calcular fórmulas. */
final class EdicaoXlsx {
    private static final int MAX_ENTRADAS = 256;
    private static final long MAX_EXPANDIDO = 32L * 1024 * 1024;
    private static final long MAX_ENTRADA = 16L * 1024 * 1024;
    private static final long MAX_CELULAS = 100_000;
    private static final Pattern INTERVALO_GRAFICO = Pattern.compile(
            "(?:'((?:[^']|'')+)'|([A-Za-z0-9_]+))!([$]?[A-Z]{1,3}[$]?)([1-9][0-9]*):([$]?[A-Z]{1,3}[$]?)([1-9][0-9]*)");
    record Proposta(byte[] bytes, String previa) { }
    private EdicaoXlsx() { }

    static void validarZip(byte[] bytes) throws IOException {
        int entradas = 0;
        long total = 0;
        Set<String> nomes = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entrada;
            while ((entrada = zip.getNextEntry()) != null) {
                String nome = entrada.getName();
                if (++entradas > MAX_ENTRADAS || !nomes.add(nome)
                        || nome.startsWith("/") || nome.contains("..") || nome.contains("\\"))
                    throw new IOException("ZIP XLSX inválido ou acima do limite de entradas");
                String minusculo = nome.toLowerCase(Locale.ROOT);
                if (minusculo.contains("vbaproject") || minusculo.contains("_xmlsignatures")
                        || minusculo.contains("externallinks") || minusculo.contains("embeddings")
                        || minusculo.contains("connections") || minusculo.contains("querytables")
                        || minusculo.contains("pivot") || minusculo.contains("activex"))
                    throw new IOException("recurso XLSX externo ou não preservável: " + nome);
                byte[] bloco = new byte[8192];
                long tamanho = 0;
                ByteArrayOutputStream relacoes = minusculo.endsWith(".rels")
                        ? new ByteArrayOutputStream() : null;
                for (int n; (n = zip.read(bloco)) != -1;) {
                    tamanho += n; total += n;
                    if (tamanho > MAX_ENTRADA || total > MAX_EXPANDIDO)
                        throw new IOException("ZIP XLSX expandido acima do limite");
                    if (relacoes != null) relacoes.write(bloco, 0, n);
                }
                if (relacoes != null && relacoes.toString(java.nio.charset.StandardCharsets.UTF_8)
                        .matches("(?is).*TargetMode\\s*=\\s*['\"]External['\"].*"))
                    throw new IOException("relacionamento externo não permitido no XLSX");
                zip.closeEntry();
            }
        }
        if (entradas == 0) throw new IOException("XLSX inválido ou criptografado");
    }

    static Proposta preparar(byte[] anterior, JsonNode operacoes) throws IOException {
        return preparar(anterior, operacoes, false);
    }

    static Proposta preparar(byte[] anterior, JsonNode operacoes, boolean simplificar) throws IOException {
        validarZip(anterior);
        List<String> previa = new ArrayList<>();
        try (XSSFWorkbook livro = new XSSFWorkbook(new ByteArrayInputStream(anterior))) {
            if (livro.isStructureLocked() || !livro.getExternalLinksTables().isEmpty())
                throw new IOException("XLSX protegido ou com vínculos externos");
            if (simplificar) {
                int removidos = removerGraficos(livro);
                if (removidos == 0) throw new IOException("cópia simplificada sem recursos a remover");
                previa.add("Cópia simplificada: " + removidos + " gráfico(s) serão omitidos.");
            }
            long celulas = 0;
            for (int i = 0; i < livro.getNumberOfSheets(); i++) {
                XSSFSheet aba = livro.getSheetAt(i);
                if (aba.getProtect() || aba.getCTWorksheet().isSetSheetProtection())
                    throw new IOException("aba protegida: " + aba.getSheetName());
                for (Row linha : aba) {
                    celulas += linha.getPhysicalNumberOfCells();
                    if (celulas > MAX_CELULAS) throw new IOException("XLSX acima do limite de células");
                }
            }
            for (JsonNode op : operacoes) {
                String tipo = texto(op, "tipo"), nome = texto(op, "aba");
                String enderecoPorItem = "";
                if (tipo.equals("atualizar_por_item")) {
                    AlvoItem alvo = localizarPorItem(livro, texto(op, "itemValor"),
                            texto(op, "cabecalhoAlvo"));
                    nome = alvo.aba(); enderecoPorItem = alvo.endereco();
                }
                if (tipo.equals("criar_aba")) {
                    if (nome.isBlank() || livro.getSheet(nome) != null || nome.length() > 31)
                        throw new IOException("nome de aba inválido ou existente");
                    livro.createSheet(nome);
                    previa.add("Nova aba: " + nome);
                    continue;
                }
                XSSFSheet aba = livro.getSheet(nome);
                if (aba == null) throw new IOException("aba não encontrada: " + nome);
                if (!aba.getTables().isEmpty() && tipo.equals("inserir_coluna"))
                    throw new IOException("alteração estrutural em tabela requer preservação de referências");
                if (!simplificar && (tipo.equals("inserir_linha") || tipo.equals("inserir_coluna"))
                        && temGraficos(livro))
                    throw new IOException("alteração estrutural com gráfico requer preservação de séries");
                switch (tipo) {
                    case "atualizar_celula", "preencher_celula_vazia", "atualizar_por_item" -> {
                        String endereco = tipo.equals("atualizar_por_item")
                                ? enderecoPorItem : texto(op, "celula");
                        if (!endereco.matches("[$]?[A-Z]{1,3}[$]?[1-9][0-9]{0,6}"))
                            throw new IOException("endereço de célula inválido");
                        CellReference ref = new CellReference(endereco);
                        if (ref.getRow() >= 1_048_576 || ref.getCol() >= 16_384)
                            throw new IOException("célula fora dos limites XLSX");
                        for (var mesclada : aba.getMergedRegions()) if (mesclada.isInRange(ref.getRow(), ref.getCol()))
                            throw new IOException("célula mesclada não pode ser editada");
                        Row linha = aba.getRow(ref.getRow());
                        Cell celula = linha == null ? null : linha.getCell(ref.getCol());
                        if (tipo.equals("atualizar_celula") && celula == null)
                            throw new IOException("célula inexistente");
                        if (tipo.equals("preencher_celula_vazia") && celula != null
                                && celula.getCellType() != CellType.BLANK)
                            throw new IOException("célula não está vazia");
                        String anteriorTipo = celula == null ? "vazio" : tipo(celula);
                        String esperadoTipo = texto(op, "tipoAnterior");
                        if (!anteriorTipo.equals(esperadoTipo) || !esperado(celula, op.path("valorAnterior")))
                            throw new IOException("CONFLITO_DE_CELULA: " + nome + "!" + endereco);
                        String novoTipo = texto(op, "tipoNovo");
                        if (anteriorTipo.equals("formula") && !novoTipo.equals("formula")
                                && !op.path("autorizarSubstituirFormula").asBoolean(false))
                            throw new IOException("substituir fórmula exige autorização explícita");
                        if (linha == null) linha = aba.createRow(ref.getRow());
                        if (celula == null) celula = linha.createCell(ref.getCol());
                        atribuir(celula, novoTipo, op.path("valorNovo"));
                        previa.add(nome + "!" + ref.formatAsString() + " (" + anteriorTipo + " → "
                                + novoTipo + "): " + curto(op.path("valorAnterior")) + " → "
                                + curto(op.path("valorNovo")));
                    }
                    case "acrescentar_linha" -> {
                        int indice = aba.getLastRowNum() + 1;
                        if (indice >= 1_048_576 || indice + 1 != op.path("linhaEsperada").asInt(-1))
                            throw new IOException("posição da nova linha divergente");
                        if (aba.getRow(indice) != null) throw new IOException("linha não está vazia");
                        aba.createRow(indice);
                        for (var tabela : aba.getTables()) {
                            var area = tabela.getArea();
                            if (area == null || area.getLastCell().getRow() != indice - 1)
                                throw new IOException("tabela não termina na linha anterior");
                            var fim = new CellReference(indice, area.getLastCell().getCol());
                            tabela.setArea(new AreaReference(area.getFirstCell(), fim, SpreadsheetVersion.EXCEL2007));
                        }
                        if (!simplificar) ajustarGraficos(livro, nome, indice, indice + 1);
                        previa.add(nome + ": nova linha " + (indice + 1));
                    }
                    case "inserir_linha" -> {
                        if (!aba.getTables().isEmpty()) throw new IOException("inserção dentro de tabela não suportada");
                        int indice = op.path("linha").asInt(-1) - 1;
                        if (indice < 0 || indice > aba.getLastRowNum() || aba.getLastRowNum() >= 1_048_575)
                            throw new IOException("linha de inserção inválida");
                        aba.shiftRows(indice, aba.getLastRowNum(), 1);
                        aba.createRow(indice);
                        previa.add(nome + ": linha inserida em " + (indice + 1));
                    }
                    case "inserir_coluna" -> {
                        int indice = op.path("coluna").asInt(-1) - 1;
                        if (indice < 0 || indice >= 16_383)
                            throw new IOException("coluna de inserção inválida");
                        int ultima = -1;
                        for (Row linha : aba) if (linha.getLastCellNum() > 0)
                            ultima = Math.max(ultima, linha.getLastCellNum() - 1);
                        if (ultima >= 16_383) throw new IOException("última coluna sem espaço para inserção");
                        if (indice <= ultima) aba.shiftColumns(indice, ultima, 1);
                        previa.add(nome + ": coluna inserida em " + new CellReference(0, indice)
                                .formatAsString().replace("1", ""));
                    }
                    default -> throw new IOException("operação XLSX desconhecida");
                }
            }
            long celulasFinais = 0;
            for (int i = 0; i < livro.getNumberOfSheets(); i++)
                for (Row linha : livro.getSheetAt(i)) {
                    celulasFinais += linha.getPhysicalNumberOfCells();
                    if (celulasFinais > MAX_CELULAS)
                        throw new IOException("XLSX acima do limite de células");
                }
            livro.setForceFormulaRecalculation(true);
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            livro.write(saida);
            byte[] bytes = saida.toByteArray();
            validarZip(bytes);
            return new Proposta(bytes, String.join("\n", previa)
                    + "\nFórmulas somente marcadas para recálculo ao abrir; resultados em cache podem estar desatualizados.");
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("XLSX inválido ou recurso incompatível", e); }
    }

    private static boolean temGraficos(XSSFWorkbook livro) {
        for (int i = 0; i < livro.getNumberOfSheets(); i++) {
            var desenho = livro.getSheetAt(i).getDrawingPatriarch();
            if (desenho != null && !desenho.getCharts().isEmpty()) return true;
        }
        return false;
    }

    private record AlvoItem(String aba, String endereco) { }
    private static AlvoItem localizarPorItem(XSSFWorkbook livro, String item, String cabecalho)
            throws IOException {
        if (item.isBlank() || item.length() > 200 || cabecalho.isBlank() || cabecalho.length() > 100)
            throw new IOException("item ou cabeçalho alvo inválido");
        AlvoItem encontrado = null;
        int examinadas = 0;
        for (int i = 0; i < livro.getNumberOfSheets(); i++) {
            XSSFSheet folha = livro.getSheetAt(i);
            for (Row linha : folha) for (Cell celula : linha) {
                if (++examinadas > MAX_CELULAS) throw new IOException("busca por item acima do limite");
                if (celula.getCellType() != CellType.STRING
                        || !celula.getStringCellValue().strip().equalsIgnoreCase(item)) continue;
                int linhaCabecalho = 0;
                for (var tabela : folha.getTables()) {
                    var area = tabela.getArea();
                    if (area != null && celula.getRowIndex() >= area.getFirstCell().getRow()
                            && celula.getRowIndex() <= area.getLastCell().getRow()
                            && celula.getColumnIndex() >= area.getFirstCell().getCol()
                            && celula.getColumnIndex() <= area.getLastCell().getCol()) {
                        linhaCabecalho = area.getFirstCell().getRow(); break;
                    }
                }
                Row cabecalhos = folha.getRow(linhaCabecalho);
                int coluna = -1;
                if (cabecalhos != null) for (Cell titulo : cabecalhos) {
                    if (titulo.getCellType() == CellType.STRING
                            && titulo.getStringCellValue().strip().equalsIgnoreCase(cabecalho)) {
                        if (coluna >= 0) throw new IOException("cabeçalho alvo ambíguo");
                        coluna = titulo.getColumnIndex();
                    }
                }
                if (coluna < 0 || celula.getRowIndex() <= linhaCabecalho) continue;
                if (encontrado != null) throw new IOException("ALVO_AMBIGUO: item aparece mais de uma vez");
                encontrado = new AlvoItem(folha.getSheetName(),
                        new CellReference(celula.getRowIndex(), coluna).formatAsString());
            }
        }
        if (encontrado == null) throw new IOException("item ou cabeçalho alvo não encontrado");
        return encontrado;
    }

    private static int removerGraficos(XSSFWorkbook livro) throws IOException {
        int removidos = 0;
        for (int i = 0; i < livro.getNumberOfSheets(); i++) {
            var desenho = livro.getSheetAt(i).getDrawingPatriarch();
            if (desenho == null) continue;
            var xml = desenho.getCTDrawing();
            int frames = 0;
            for (var a : xml.getTwoCellAnchorList()) if (a.isSetGraphicFrame()) frames++;
            for (var a : xml.getOneCellAnchorList()) if (a.isSetGraphicFrame()) frames++;
            for (var a : xml.getAbsoluteAnchorList()) if (a.isSetGraphicFrame()) frames++;
            if (frames != desenho.getCharts().size())
                throw new IOException("desenho contém recursos não identificados para simplificação");
            removidos += frames;
            xml.getTwoCellAnchorList().removeIf(a -> a.isSetGraphicFrame());
            xml.getOneCellAnchorList().removeIf(a -> a.isSetGraphicFrame());
            xml.getAbsoluteAnchorList().removeIf(a -> a.isSetGraphicFrame());
        }
        return removidos;
    }

    /** Atualiza somente referências diretas cujo fim era a última linha existente. */
    private static void ajustarGraficos(XSSFWorkbook livro, String aba, int linhaAntigaZero,
            int linhaNovaUm) throws IOException {
        for (int i = 0; i < livro.getNumberOfSheets(); i++) {
            var desenho = livro.getSheetAt(i).getDrawingPatriarch();
            if (desenho == null) continue;
            for (var grafico : desenho.getCharts()) {
                try (XmlCursor cursor = grafico.getCTChart().newCursor()) {
                    cursor.selectPath("declare namespace c='http://schemas.openxmlformats.org/drawingml/2006/chart' .//c:f");
                    while (cursor.toNextSelection()) {
                        String formula = cursor.getTextValue();
                        Matcher m = INTERVALO_GRAFICO.matcher(formula);
                        String abaFormula = m.matches() ? (m.group(1) == null ? m.group(2)
                                : m.group(1).replace("''", "'")) : "";
                        if (!aba.equals(abaFormula)) {
                            if (formula.contains(aba + "!") || formula.contains("'" + aba + "'!"))
                                throw new IOException("referência de gráfico não suportada: " + formula);
                            continue;
                        }
                        if (Integer.parseInt(m.group(6)) == linhaAntigaZero)
                            cursor.setTextValue(formula.substring(0, m.start(6)) + linhaNovaUm);
                    }
                }
            }
        }
    }

    private static String curto(JsonNode valor) {
        String s = valor.toString(); return s.length() < 120 ? s : s.substring(0, 119) + "…";
    }
    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo); return valor.isTextual() ? valor.stringValue() : "";
    }
    static String tipo(Cell celula) {
        return switch (celula.getCellType()) {
            case STRING -> "texto";
            case NUMERIC -> DateUtil.isCellDateFormatted(celula) ? "data" : "numero";
            case BOOLEAN -> "booleano";
            case FORMULA -> "formula";
            case BLANK -> "vazio";
            default -> "incompatível";
        };
    }
    /** O par devolvido pela leitura é exatamente o que preparar aceita como pré-condição. */
    static void descreverValorAnterior(Cell celula, ObjectNode item) {
        item.put("tipo", tipo(celula));
        switch (tipo(celula)) {
            case "texto" -> item.put("valorAnterior", celula.getStringCellValue());
            case "numero" -> item.put("valorAnterior", celula.getNumericCellValue());
            case "data" -> item.put("valorAnterior", DateUtil.getLocalDateTime(
                    celula.getNumericCellValue()).toLocalDate().toString());
            case "booleano" -> item.put("valorAnterior", celula.getBooleanCellValue());
            case "formula" -> item.put("valorAnterior", celula.getCellFormula());
            default -> item.putNull("valorAnterior");
        }
    }
    private static boolean esperado(Cell celula, JsonNode valor) {
        if (celula == null || celula.getCellType() == CellType.BLANK) return valor.isNull();
        try {
            return switch (tipo(celula)) {
                case "texto" -> valor.isTextual() && celula.getStringCellValue().equals(valor.stringValue());
                case "numero" -> valor.isNumber() && BigDecimal.valueOf(celula.getNumericCellValue())
                        .compareTo(new BigDecimal(valor.toString())) == 0;
                case "data" -> valor.isTextual() && DateUtil.getLocalDateTime(celula.getNumericCellValue())
                        .toLocalDate().equals(LocalDate.parse(valor.stringValue()));
                case "booleano" -> valor.isBoolean() && celula.getBooleanCellValue() == valor.booleanValue();
                case "formula" -> valor.isTextual() && celula.getCellFormula().equals(
                        valor.stringValue().replaceFirst("^=", ""));
                default -> false;
            };
        } catch (RuntimeException e) { return false; }
    }
    private static void atribuir(Cell celula, String tipo, JsonNode valor) throws IOException {
        try {
            if (celula.getCellType() == CellType.FORMULA && !tipo.equals("formula"))
                celula.setCellFormula(null);
            switch (tipo) {
                case "texto" -> { if (!valor.isTextual()) throw new IOException("texto inválido");
                    celula.setCellValue(valor.stringValue()); }
                case "numero" -> { if (!valor.isNumber() || new BigDecimal(valor.toString()).abs()
                        .compareTo(new BigDecimal("1000000000")) > 0)
                    throw new IOException("número inválido ou acima do limite");
                    celula.setCellValue(valor.doubleValue()); }
                case "booleano" -> { if (!valor.isBoolean()) throw new IOException("booleano inválido");
                    celula.setCellValue(valor.booleanValue()); }
                case "data" -> { if (!valor.isTextual()) throw new IOException("data inválida");
                    celula.setCellValue(LocalDate.parse(valor.stringValue()));
                    if (!DateUtil.isCellDateFormatted(celula)) {
                        var estilo = celula.getSheet().getWorkbook().createCellStyle();
                        estilo.cloneStyleFrom(celula.getCellStyle());
                        estilo.setDataFormat(celula.getSheet().getWorkbook().createDataFormat()
                                .getFormat("yyyy-mm-dd"));
                        celula.setCellStyle(estilo);
                    }
                }
                case "formula" -> { if (!valor.isTextual() || !CriadorDeArquivo.formulaValida(valor.stringValue()))
                    throw new IOException("fórmula não permitida");
                    celula.setCellFormula(valor.stringValue().replaceFirst("^=", "")); }
                case "vazio" -> { if (!valor.isNull()) throw new IOException("vazio inválido"); celula.setBlank(); }
                default -> throw new IOException("tipo XLSX inválido");
            }
        } catch (IllegalArgumentException e) { throw new IOException("valor XLSX inválido", e); }
    }
}
