package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

class CorrecaoEdicaoXlsxTest {
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();

    private byte[] exemplo() throws Exception {
        try (var livro = new XSSFWorkbook()) {
            var aba = livro.createSheet("Academias");
            var estilo = livro.createCellStyle();
            estilo.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var fonte = livro.createFont(); fonte.setColor(IndexedColors.WHITE.getIndex()); fonte.setBold(true);
            estilo.setFont(fonte);
            String[] titulos = {"Nome", "Endereço", "Bairro", "Telefone", "Site", "Contato", "Situação", "Observação"};
            var cabecalho = aba.createRow(0);
            for (int c = 0; c < titulos.length; c++) {
                var celula = cabecalho.createCell(c); celula.setCellValue(titulos[c]); celula.setCellStyle(estilo);
                aba.setColumnWidth(c, (c == 0 ? 26 : 18) * 256);
            }
            aba.createRow(1).createCell(0).setCellValue("Academia A");
            aba.createRow(2).createCell(0).setCellValue("Academia B");
            aba.createRow(4).createCell(0).setCellValue("Notas: preservar abaixo da tabela");
            aba.createRow(8).createCell(0).setCellValue("Última nota");
            aba.getRow(8).createCell(1, CellType.BLANK);
            aba.createFreezePane(0, 1);
            aba.setAutoFilter(CellRangeAddress.valueOf("A1:H3"));
            var fontes = livro.createSheet("Fontes");
            fontes.createRow(0).createCell(0).setCellValue("Referência intacta");
            fontes.getRow(0).getCell(0).setCellStyle(estilo);
            fontes.createRow(1).createCell(0).setCellFormula("1+1");
            var descartadas = livro.createSheet("Descartadas");
            descartadas.createRow(0).createCell(0).setCellValue("Nome");
            descartadas.createRow(1).createCell(0).setCellValue("Outra academia");
            var tabela = descartadas.createTable(new AreaReference("A1:A2", SpreadsheetVersion.EXCEL2007));
            tabela.setName("DescartadasTabela"); tabela.setDisplayName("DescartadasTabela");
            return bytes(livro);
        }
    }
    private byte[] bytes(XSSFWorkbook livro) throws Exception {
        var saida = new ByteArrayOutputStream(); livro.write(saida); return saida.toByteArray();
    }
    private ArrayNode preenchimento() {
        var ops = json.createArrayNode();
        String[] valores = {"Academia de teste", "Rua de teste, 447", "Campo de Santana", "(41) 99999-0000",
                "Não verificado", "Contato de teste", "Pendente", "Dado fictício para validação"};
        for (int c = 0; c < 8; c++) {
            var op = ops.addObject().put("tipo", "preencher_celula_vazia").put("aba", "Academias")
                    .put("celula", "" + (char) ('A' + c) + "4").put("tipoAnterior", "vazio")
                    .put("tipoNovo", "texto").put("valorNovo", valores[c]);
            op.putNull("valorAnterior");
        }
        return ops;
    }
    private void expandir(ArrayNode ops, String antes, String depois) {
        ops.addObject().put("tipo", "expandir_filtro").put("aba", "Academias")
                .put("intervaloAnterior", antes).put("intervaloNovo", depois);
    }
    private FalhaEdicao falha(byte[] bytes, JsonNode ops, String codigo) {
        var e = assertThrows(FalhaEdicao.class, () -> EdicaoXlsx.preparar(bytes, ops));
        assertEquals(codigo, e.codigo()); return e;
    }

    @Test void errosReaisNaoViraramProblemaDeDisco() throws Exception {
        var ops = json.createArrayNode();
        ops.addObject().put("tipo", "acrescentar_linha").put("aba", "Academias").put("linhaEsperada", 4);
        var e = falha(exemplo(), ops, "POSICAO_DIVERGENTE");
        assertTrue(e.getMessage().contains("10"));
        var resultado = DiagnosticoEdicao.resultado(new ComandoRecebido(UUID.randomUUID().toString(),
                "preparar_edicao", "", json.createObjectNode().set("operacoes", ops)), e);
        assertEquals("POSICAO_DIVERGENTE", resultado.dados().path("codigoErro").asText());
        assertEquals("acrescentar_linha", resultado.dados().path("operacao").asText());
        assertEquals(1, resultado.dados().path("indiceOperacao").asInt());
        assertFalse(resultado.mensagem().contains("disco"));
        var atualizar = preenchimento();
        ((tools.jackson.databind.node.ObjectNode) atualizar.get(0)).put("tipo", "atualizar_celula");
        falha(exemplo(), atualizar, "CELULA_INEXISTENTE");
        var ausente = preenchimento();
        ((tools.jackson.databind.node.ObjectNode) ausente.get(0)).remove("valorAnterior");
        falha(exemplo(), ausente, "CAMPO_AUSENTE");
    }

    @Test void leitorDistingueFimDaAbaFiltroTabelasEAusencia() throws Exception {
        Files.write(pasta.resolve("academias.xlsx"), exemplo());
        String id = new CatalogoDeArquivos(pasta).listar(json.createObjectNode()).path("arquivos").get(0).path("id").asText();
        var leitor = new LeitorDeArquivo(pasta);
        var consulta = json.createObjectNode().put("id", id).put("aba", "Academias")
                .put("linhaInicial", 9).put("linhaFinal", 9).put("colunaInicial", 2).put("colunaFinal", 3);
        var lido = leitor.ler(consulta);
        assertEquals(9, lido.path("ultimaLinhaFisica").asInt());
        assertEquals(10, lido.path("proximaLinhaFisica").asInt());
        assertEquals("A1:H3", lido.path("intervaloFiltro").asText());
        assertEquals(2, lido.path("celulas").size());
        assertTrue(lido.path("celulas").get(0).path("existe").asBoolean());
        assertEquals("BLANK", lido.path("celulas").get(0).path("tipoArquivo").asText());
        var ausente = lido.path("celulas").get(1);
        assertFalse(ausente.path("existe").asBoolean());
        assertEquals("vazio", ausente.path("tipo").asText());
        assertTrue(ausente.path("valorAnterior").isNull());
        var tabela = leitor.ler(json.createObjectNode().put("id", id).put("aba", "Descartadas"));
        assertEquals("A1:A2", tabela.path("tabelas").get(0).path("intervalo").asText());
        assertEquals("DescartadasTabela", tabela.path("tabelas").get(0).path("nome").asText());
    }

    @Test void filtroPreservaNotasEstilosFormulasAbasEPermiteDesfazer() throws Exception {
        byte[] original = exemplo();
        var ops = preenchimento(); expandir(ops, "A1:H3", "A1:H4");
        Path arquivos = Files.createDirectory(pasta.resolve("arquivos"));
        Path arquivo = arquivos.resolve("academias.xlsx"); Files.write(arquivo, original);
        String id = new CatalogoDeArquivos(arquivos).listar(json.createObjectNode()).path("arquivos").get(0).path("id").asText();
        new LeitorDeArquivo(arquivos).ler(json.createObjectNode().put("id", id).put("aba", "Academias"));
        var editor = new EditorDeArquivos(arquivos, pasta.resolve("dados"));
        var pedido = json.createObjectNode().put("id", id).put("destino", "original")
                .put("versaoEsperada", EditorDeArquivos.hash(original)); pedido.set("operacoes", ops);
        var proposta = editor.preparar(pedido);
        assertArrayEquals(original, Files.readAllBytes(arquivo));
        assertTrue(proposta.path("previa").asText().contains("A1:H3 → A1:H4"));
        assertTrue(proposta.path("previa").asText().contains("LibreOffice pode ampliar"));
        var comando = new ComandoRecebido(UUID.randomUUID().toString(), "aplicar_edicao", "",
                json.createObjectNode().put("editId", proposta.path("editId").asText()));
        assertTrue(editor.aplicar(comando).ok());
        try (var livro = new XSSFWorkbook(new ByteArrayInputStream(Files.readAllBytes(arquivo)));
                var antes = new XSSFWorkbook(new ByteArrayInputStream(original))) {
            var aba = livro.getSheet("Academias");
            assertEquals("A1:H4", aba.getCTWorksheet().getAutoFilter().getRef());
            assertEquals("Academia de teste", aba.getRow(3).getCell(0).getStringCellValue());
            assertEquals("Notas: preservar abaixo da tabela", aba.getRow(4).getCell(0).getStringCellValue());
            assertEquals("Última nota", aba.getRow(8).getCell(0).getStringCellValue());
            assertEquals(26 * 256, aba.getColumnWidth(0));
            assertEquals(IndexedColors.DARK_BLUE.getIndex(), aba.getRow(0).getCell(0).getCellStyle().getFillForegroundColor());
            assertTrue(aba.getPaneInformation().isFreezePane());
            assertTrue(livro.getNames("_xlnm._FilterDatabase").getFirst().getRefersToFormula().endsWith("$A$1:$H$4"));
            for (String nome : new String[]{"Fontes", "Descartadas"})
                assertEquals(antes.getSheet(nome).getCTWorksheet().xmlText(), livro.getSheet(nome).getCTWorksheet().xmlText());
            assertEquals("1+1", livro.getSheet("Fontes").getRow(1).getCell(0).getCellFormula());
            assertTrue(livro.getForceFormulaRecalculation());
        }
        Path visual = Path.of("target", "validacao-edicao", "planilha-editada.xlsx");
        Files.createDirectories(visual.getParent()); Files.copy(arquivo, visual, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        editor.prepararDesfazer(comando.dados());
        assertTrue(editor.desfazer(new ComandoRecebido(UUID.randomUUID().toString(), "aplicar_desfazer", "", comando.dados())).ok());
        assertArrayEquals(original, Files.readAllBytes(arquivo));
    }

    @Test void filtroRecusaConflitosNotasLacunasEMudancasDeCabecalho() throws Exception {
        byte[] original = exemplo();
        var ops = preenchimento(); expandir(ops, "A1:H2", "A1:H4"); falha(original, ops, "CONFLITO_DE_FILTRO");
        ops = preenchimento(); expandir(ops, "A1:H3", "A1:H5"); falha(original, ops, "SOBREPOSICAO_DE_CONTEUDO");
        for (String invalido : new String[]{"A2:H4", "A1:I4", "A1:H2"}) {
            ops = preenchimento(); expandir(ops, "A1:H3", invalido); falha(original, ops, "FILTRO_INCOMPATIVEL");
        }
        ops = json.createArrayNode(); expandir(ops, "A1:H3", "A1:H4"); falha(original, ops, "FILTRO_INCOMPATIVEL");
        ops = preenchimento(); expandir(ops, "A1:H3", "A1:H4");
        ops.addObject().put("tipo", "inserir_linha").put("aba", "Academias").put("linha", 4);
        falha(original, ops, "FILTRO_INCOMPATIVEL");
        try (var livro = new XSSFWorkbook(new ByteArrayInputStream(original))) {
            livro.getSheet("Academias").addMergedRegion(CellRangeAddress.valueOf("A4:B4"));
            var mesclada = preenchimento(); expandir(mesclada, "A1:H3", "A1:H4");
            falha(bytes(livro), mesclada, "RECURSO_INCOMPATIVEL");
        }
    }

    @Test void expansaoAntesDoPreenchimentoTambemEhAtomica() throws Exception {
        var ops = json.createArrayNode(); expandir(ops, "A1:H3", "A1:H4"); ops.addAll(preenchimento());
        assertTrue(EdicaoXlsx.preparar(exemplo(), ops).previa().contains("A1:H4"));
    }

    @Test void recusaCriteriosAtivosTabelaEProtecao() throws Exception {
        var ops = preenchimento(); expandir(ops, "A1:H3", "A1:H4");
        try (var livro = new XSSFWorkbook(new ByteArrayInputStream(exemplo()))) {
            // XMLBeans cursor evita classes ausentes no poi-ooxml-lite, sem adicionar dependência.
            try (var cursor = livro.getSheet("Academias").getCTWorksheet().getAutoFilter().newCursor()) {
                cursor.toEndToken();
                cursor.beginElement("filterColumn", "http://schemas.openxmlformats.org/spreadsheetml/2006/main");
                cursor.insertAttributeWithValue("colId", "0");
            }
            falha(bytes(livro), ops, "RECURSO_INCOMPATIVEL");
        }
        try (var livro = new XSSFWorkbook(new ByteArrayInputStream(exemplo()))) {
            var aba = livro.getSheet("Academias");
            var tabela = aba.createTable(new AreaReference("A1:H3", SpreadsheetVersion.EXCEL2007));
            tabela.setName("AcademiasTabela"); tabela.setDisplayName("AcademiasTabela");
            falha(bytes(livro), ops, "RECURSO_INCOMPATIVEL");
        }
        try (var livro = new XSSFWorkbook(new ByteArrayInputStream(exemplo()))) {
            livro.getSheet("Academias").protectSheet("teste");
            falha(bytes(livro), ops, "RECURSO_INCOMPATIVEL");
        }
    }
}
