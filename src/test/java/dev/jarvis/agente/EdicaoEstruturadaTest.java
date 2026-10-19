package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class EdicaoEstruturadaTest {
    @TempDir Path temporaria;
    private final JsonMapper json = JsonMapper.builder().build();

    private String catalogar(Path pasta, String nome) throws Exception {
        var catalogo = new CatalogoDeArquivos(pasta);
        String id = "";
        for (var item : catalogo.listar(json.readTree("{} ")).path("arquivos"))
            if (nome.equals(item.path("nome").asText())) id = item.path("id").asText();
        assertFalse(id.isEmpty());
        new LeitorDeArquivo(pasta).ler(json.readTree("{\"id\":\"" + id + "\"}"));
        return id;
    }
    private String pedido(String id, byte[] bytes, String operacao) throws Exception {
        return "{\"id\":\"" + id + "\",\"versaoEsperada\":\"" + EditorDeArquivos.hash(bytes)
                + "\",\"destino\":\"original\",\"operacoes\":[" + operacao + "]}";
    }

    @Test void jsonRejeitaDuplicatasEAtualizaSoAlvo() throws Exception {
        Path pasta = Files.createDirectory(temporaria.resolve("arquivos"));
        Path arquivo = pasta.resolve("dados.json");
        Files.writeString(arquivo, "{\"a\":1,\"b\":{\"c\":true}}");
        String id = catalogar(pasta, "dados.json");
        byte[] antes = Files.readAllBytes(arquivo);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        String op = "{\"tipo\":\"atualizar_valor\",\"ponteiro\":\"/a\",\"tipoAnterior\":\"numero\","
                + "\"valorAnterior\":1,\"tipoNovo\":\"numero\",\"valorNovo\":2}";
        var proposta = editor.preparar(json.readTree(pedido(id, antes, op)));
        assertTrue(proposta.path("previa").asText().contains("/a"));
        String editId = proposta.path("editId").asText();
        assertTrue(editor.aplicar(new ComandoRecebido("11111111-1111-1111-1111-111111111111",
                "aplicar_edicao", "", json.readTree("{\"editId\":\"" + editId + "\"}"))).ok());
        assertEquals(2, json.readTree(Files.readString(arquivo)).path("a").asInt());
        assertTrue(json.readTree(Files.readString(arquivo)).path("b").path("c").asBoolean());
        assertTrue(editor.desfazer(new ComandoRecebido("22222222-2222-2222-2222-222222222222",
                "aplicar_desfazer", "", json.readTree("{\"editId\":\"" + editId + "\"}"))).ok());
        assertArrayEquals(antes, Files.readAllBytes(arquivo));
        assertThrows(Exception.class, () -> EdicaoJson.analisar("{\"a\":1,\"a\":2}".getBytes()));
        assertThrows(Exception.class, () -> EdicaoJson.analisar("{\"a\":1".getBytes()));
    }

    @Test void jsonRecusaCaminhoETipoDivergentes() throws Exception {
        byte[] origem = "{\"a\":1}".getBytes();
        assertThrows(Exception.class, () -> EdicaoJson.preparar(origem, json.readTree("["
                + "{\"tipo\":\"atualizar_valor\",\"ponteiro\":\"/b\",\"tipoAnterior\":\"numero\","
                + "\"valorAnterior\":1,\"tipoNovo\":\"numero\",\"valorNovo\":2}]")));
        assertThrows(Exception.class, () -> EdicaoJson.preparar(origem, json.readTree("["
                + "{\"tipo\":\"atualizar_valor\",\"ponteiro\":\"/a\",\"tipoAnterior\":\"texto\","
                + "\"valorAnterior\":\"1\",\"tipoNovo\":\"numero\",\"valorNovo\":2}]")));
    }

    @Test void xlsxPreservaEstiloFormulaEAbaNaoTocada() throws Exception {
        Path pasta = Files.createDirectory(temporaria.resolve("planilhas"));
        Path arquivo = pasta.resolve("dados.xlsx");
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Dados");
            var linha = aba.createRow(0);
            var estilo = livro.createCellStyle(); estilo.setDataFormat((short) 2);
            var alvo = linha.createCell(0); alvo.setCellValue(5); alvo.setCellStyle(estilo);
            linha.createCell(1).setCellFormula("A1*2");
            livro.createSheet("Intacta").createRow(0).createCell(0).setCellValue("original");
            try (var out = Files.newOutputStream(arquivo)) { livro.write(out); }
        }
        byte[] antes = Files.readAllBytes(arquivo);
        String id = catalogar(pasta, "dados.xlsx");
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        String op = "{\"tipo\":\"atualizar_celula\",\"aba\":\"Dados\",\"celula\":\"A1\","
                + "\"tipoAnterior\":\"numero\",\"valorAnterior\":5,\"tipoNovo\":\"numero\",\"valorNovo\":7}";
        var proposta = editor.preparar(json.readTree(pedido(id, antes, op)));
        assertTrue(proposta.path("previa").asText().contains("Dados!A1"));
        editor.aplicar(new ComandoRecebido("33333333-3333-3333-3333-333333333333",
                "aplicar_edicao", "", json.readTree("{\"editId\":\"" + proposta.path("editId").asText() + "\"}")));
        if (Boolean.getBoolean("jarvis.visual")) {
            Files.createDirectories(Path.of("target"));
            Files.copy(arquivo, Path.of("target", "visual-check.xlsx"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try (var livro = new XSSFWorkbook(Files.newInputStream(arquivo))) {
            assertEquals(7, livro.getSheet("Dados").getRow(0).getCell(0).getNumericCellValue());
            assertEquals(2, livro.getSheet("Dados").getRow(0).getCell(0).getCellStyle().getDataFormat());
            assertEquals("A1*2", livro.getSheet("Dados").getRow(0).getCell(1).getCellFormula());
            assertEquals("original", livro.getSheet("Intacta").getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test void xlsxRecusaAbaAusenteMesclaETextoContinuaTexto() throws Exception {
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("A");
            aba.createRow(0).createCell(0).setCellValue("antigo");
            aba.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
            var out = new java.io.ByteArrayOutputStream(); livro.write(out);
            byte[] bytes = out.toByteArray();
            String op = "[{\"tipo\":\"atualizar_celula\",\"aba\":\"A\",\"celula\":\"A1\","
                    + "\"tipoAnterior\":\"texto\",\"valorAnterior\":\"antigo\","
                    + "\"tipoNovo\":\"texto\",\"valorNovo\":\"=1+1\"}]";
            var proposta = EdicaoXlsx.preparar(bytes, json.readTree(op));
            try (var alterado = new XSSFWorkbook(new java.io.ByteArrayInputStream(proposta.bytes()))) {
                assertEquals(CellType.STRING, alterado.getSheet("A").getRow(0).getCell(0).getCellType());
            }
            assertThrows(Exception.class, () -> EdicaoXlsx.preparar(bytes,
                    json.readTree(op.replace("\"A\"", "\"Outra\""))));
            assertThrows(Exception.class, () -> EdicaoXlsx.preparar(bytes, json.readTree("["
                    + "{\"tipo\":\"preencher_celula_vazia\",\"aba\":\"A\",\"celula\":\"A2\","
                    + "\"tipoAnterior\":\"vazio\",\"valorAnterior\":null,"
                    + "\"tipoNovo\":\"texto\",\"valorNovo\":\"x\"}]")));
        }
    }

    @Test void buscaXlsxNaoEscolhePrimeiraOcorrencia() throws Exception {
        Path pasta = Files.createDirectory(temporaria.resolve("busca"));
        Path arquivo = pasta.resolve("itens.xlsx");
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Itens");
            aba.createRow(0).createCell(0).setCellValue("Nome");
            aba.createRow(1).createCell(0).setCellValue("Chave");
            aba.createRow(2).createCell(0).setCellValue("Chave");
            try (var out = Files.newOutputStream(arquivo)) { livro.write(out); }
        }
        String id = catalogar(pasta, "itens.xlsx");
        var resultado = new LeitorDeArquivo(pasta).ler(json.readTree("{\"id\":\"" + id
                + "\",\"aba\":\"Itens\",\"buscaValor\":\"Chave\"}"));
        assertEquals(2, resultado.path("totalCorrespondencias").asInt());
        assertEquals("A2", resultado.path("correspondencias").get(0).path("celula").asText());
        assertEquals("A3", resultado.path("correspondencias").get(1).path("celula").asText());
    }

    @Test void atualizacaoPorItemResolveColunaERecusaAmbiguidade() throws Exception {
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Itens");
            var cabecalho = aba.createRow(0);
            cabecalho.createCell(0).setCellValue("Nome");
            cabecalho.createCell(1).setCellValue("Saldo");
            var linha = aba.createRow(1);
            linha.createCell(0).setCellValue("Produto A");
            linha.createCell(1).setCellValue(2);
            var out = new java.io.ByteArrayOutputStream(); livro.write(out);
            String operacao = "[{\"tipo\":\"atualizar_por_item\",\"itemValor\":\"Produto A\","
                    + "\"cabecalhoAlvo\":\"Saldo\",\"tipoAnterior\":\"numero\","
                    + "\"valorAnterior\":2,\"tipoNovo\":\"numero\",\"valorNovo\":3}]";
            var proposta = EdicaoXlsx.preparar(out.toByteArray(), json.readTree(operacao));
            assertTrue(proposta.previa().contains("Itens!B2"));
            try (var alterado = new XSSFWorkbook(new java.io.ByteArrayInputStream(proposta.bytes()))) {
                assertEquals(3, alterado.getSheet("Itens").getRow(1).getCell(1).getNumericCellValue());
            }
            aba.createRow(2).createCell(0).setCellValue("Produto A");
            var ambiguo = new java.io.ByteArrayOutputStream(); livro.write(ambiguo);
            assertThrows(java.io.IOException.class, () -> EdicaoXlsx.preparar(ambiguo.toByteArray(),
                    json.readTree(operacao)));
        }
    }

    @Test void formulaSoPodeSerSubstituidaComAutorizacaoExplicita() throws Exception {
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Dados");
            aba.createRow(0).createCell(0).setCellFormula("1+1");
            var out = new java.io.ByteArrayOutputStream(); livro.write(out);
            String operacao = "[{\"tipo\":\"atualizar_celula\",\"aba\":\"Dados\","
                    + "\"celula\":\"A1\",\"tipoAnterior\":\"formula\","
                    + "\"valorAnterior\":\"1+1\",\"tipoNovo\":\"numero\",\"valorNovo\":2}]";
            assertThrows(java.io.IOException.class, () -> EdicaoXlsx.preparar(out.toByteArray(),
                    json.readTree(operacao)));
            var proposta = EdicaoXlsx.preparar(out.toByteArray(), json.readTree(
                    operacao.replace("\"valorNovo\":2", "\"valorNovo\":2,\"autorizarSubstituirFormula\":true")));
            try (var alterado = new XSSFWorkbook(new java.io.ByteArrayInputStream(proposta.bytes()))) {
                assertEquals(CellType.NUMERIC, alterado.getSheet("Dados").getRow(0).getCell(0).getCellType());
            }
        }
    }

    @Test void linhaNovaExpandeSerieDiretaDeGrafico() throws Exception {
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Dados");
            aba.createRow(0).createCell(0).setCellValue(1);
            aba.createRow(1).createCell(0).setCellValue(2);
            var desenho = aba.createDrawingPatriarch();
            var grafico = desenho.createChart(desenho.createAnchor(0, 0, 0, 0, 2, 0, 8, 12));
            var categorias = grafico.createCategoryAxis(AxisPosition.BOTTOM);
            var valores = grafico.createValueAxis(AxisPosition.LEFT);
            var dados = grafico.createData(ChartTypes.LINE, categorias, valores);
            var fonte = XDDFDataSourcesFactory.fromNumericCellRange(aba, new CellRangeAddress(0, 1, 0, 0));
            dados.addSeries(fonte, fonte);
            grafico.plot(dados);
            var out = new java.io.ByteArrayOutputStream(); livro.write(out);
            var proposta = EdicaoXlsx.preparar(out.toByteArray(), json.readTree("["
                    + "{\"tipo\":\"acrescentar_linha\",\"aba\":\"Dados\",\"linhaEsperada\":3},"
                    + "{\"tipo\":\"preencher_celula_vazia\",\"aba\":\"Dados\",\"celula\":\"A3\","
                    + "\"tipoAnterior\":\"vazio\",\"valorAnterior\":null,"
                    + "\"tipoNovo\":\"numero\",\"valorNovo\":3}]"));
            try (var alterado = new XSSFWorkbook(new java.io.ByteArrayInputStream(proposta.bytes()))) {
                String xml = alterado.getSheet("Dados").getDrawingPatriarch().getCharts().getFirst()
                        .getCTChart().xmlText();
                assertTrue(xml.contains("$A$1:$A$3"), xml);
                assertEquals(3, alterado.getSheet("Dados").getRow(2).getCell(0).getNumericCellValue());
            }
        }
    }

    @Test void linhaNovaExpandeTabelaEstruturada() throws Exception {
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Dados");
            aba.createRow(0).createCell(0).setCellValue("Item");
            aba.createRow(1).createCell(0).setCellValue("A");
            var tabela = aba.createTable(new AreaReference(new CellReference("A1"),
                    new CellReference("A2"), SpreadsheetVersion.EXCEL2007));
            tabela.setName("Itens"); tabela.setDisplayName("Itens");
            var out = new java.io.ByteArrayOutputStream(); livro.write(out);
            var proposta = EdicaoXlsx.preparar(out.toByteArray(), json.readTree("["
                    + "{\"tipo\":\"acrescentar_linha\",\"aba\":\"Dados\",\"linhaEsperada\":3},"
                    + "{\"tipo\":\"preencher_celula_vazia\",\"aba\":\"Dados\",\"celula\":\"A3\","
                    + "\"tipoAnterior\":\"vazio\",\"valorAnterior\":null,"
                    + "\"tipoNovo\":\"texto\",\"valorNovo\":\"B\"}]"));
            try (var alterado = new XSSFWorkbook(new java.io.ByteArrayInputStream(proposta.bytes()))) {
                assertEquals("A1:A3", alterado.getSheet("Dados").getTables().getFirst()
                        .getArea().formatAsString());
            }
        }
    }

    @Test void xlsxRecusaZipComEntradasDemais() throws Exception {
        var saida = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(saida)) {
            for (int i = 0; i < 257; i++) {
                zip.putNextEntry(new java.util.zip.ZipEntry("xl/parte" + i + ".xml"));
                zip.write('x'); zip.closeEntry();
            }
        }
        assertThrows(java.io.IOException.class, () -> EdicaoXlsx.validarZip(saida.toByteArray()));
    }

    @Test void xlsxRecusaAbaProtegidaEZipExpandido() throws Exception {
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Dados");
            aba.createRow(0).createCell(0).setCellValue("x");
            aba.protectSheet("senha");
            var out = new java.io.ByteArrayOutputStream(); livro.write(out);
            assertThrows(java.io.IOException.class, () -> EdicaoXlsx.preparar(out.toByteArray(),
                    json.readTree("[{\"tipo\":\"atualizar_celula\",\"aba\":\"Dados\","
                            + "\"celula\":\"A1\",\"tipoAnterior\":\"texto\","
                            + "\"valorAnterior\":\"x\",\"tipoNovo\":\"texto\","
                            + "\"valorNovo\":\"y\"}]")));
        }
        var saida = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(saida)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/huge.xml"));
            byte[] bloco = new byte[8192];
            for (int i = 0; i < 2049; i++) zip.write(bloco);
            zip.closeEntry();
        }
        assertThrows(java.io.IOException.class, () -> EdicaoXlsx.validarZip(saida.toByteArray()));
    }

    @Test void copiaSimplificadaOmitindoGraficoNaoMudaOriginal() throws Exception {
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Dados");
            aba.createRow(0).createCell(0).setCellValue(1);
            aba.createRow(1).createCell(0).setCellValue(2);
            var desenho = aba.createDrawingPatriarch();
            var grafico = desenho.createChart(desenho.createAnchor(0, 0, 0, 0, 2, 0, 8, 12));
            var cat = grafico.createCategoryAxis(AxisPosition.BOTTOM);
            var val = grafico.createValueAxis(AxisPosition.LEFT);
            var dados = grafico.createData(ChartTypes.LINE, cat, val);
            var fonte = XDDFDataSourcesFactory.fromNumericCellRange(aba,
                    new CellRangeAddress(0, 1, 0, 0));
            dados.addSeries(fonte, fonte); grafico.plot(dados);
            var out = new java.io.ByteArrayOutputStream(); livro.write(out);
            byte[] original = out.toByteArray();
            var proposta = EdicaoXlsx.preparar(original, json.readTree("["
                    + "{\"tipo\":\"inserir_linha\",\"aba\":\"Dados\",\"linha\":2}]"), true);
            assertTrue(proposta.previa().contains("1 gráfico"));
            try (var alterado = new XSSFWorkbook(new java.io.ByteArrayInputStream(proposta.bytes()))) {
                assertEquals(0, alterado.getSheet("Dados").getDrawingPatriarch()
                        .getCTDrawing().sizeOfTwoCellAnchorArray());
            }
            try (var intacto = new XSSFWorkbook(new java.io.ByteArrayInputStream(original))) {
                assertEquals(1, intacto.getSheet("Dados").getDrawingPatriarch().getCharts().size());
            }
        }
    }
}
