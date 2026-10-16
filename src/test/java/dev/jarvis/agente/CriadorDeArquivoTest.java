package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CriadorDeArquivoTest {
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();

    private ComandoRecebido pedido(String dados) throws Exception {
        return new ComandoRecebido("123", "criar_arquivo", "", json.readTree(dados));
    }

    @Test void nomesNaoEscapamEArquivosExistentesNaoSaoSobrescritos() throws Exception {
        CriadorDeArquivo criador = new CriadorDeArquivo(pasta.resolve("Jarvis"));
        var entrada = pedido("{\"nome\":\"CON\",\"formato\":\"txt\",\"conteudo\":\"Olá\"}");
        Path primeiro = criador.criar(entrada).arquivo();
        Path segundo = criador.criar(entrada).arquivo();
        assertNotEquals(primeiro, segundo);
        assertEquals("CON_.txt", primeiro.getFileName().toString());
        assertEquals("Olá", Files.readString(primeiro).strip());
        assertThrows(Exception.class, () -> criador.criar(pedido("{\"nome\":\"../fora\",\"formato\":\"txt\"}")));
        assertThrows(Exception.class, () -> criador.criar(pedido("{\"nome\":\"C:\\\\fora\",\"formato\":\"txt\"}")));
    }

    @Test void documentoPreservaTabelaEPortugues() throws Exception {
        CriadorDeArquivo criador = new CriadorDeArquivo(pasta);
        String markdown = "# Relatório de São Paulo\\n\\n| Item | Valor |\\n| --- | --- |\\n| Café | R$ 12,50 |";
        var docx = criador.criar(pedido("{\"nome\":\"relatório\",\"formato\":\"docx\",\"conteudo\":\"" + markdown + "\"}"));
        try (var doc = new XWPFDocument(Files.newInputStream(docx.arquivo()))) {
            assertEquals("Relatório de São Paulo", doc.getParagraphs().getFirst().getText());
            assertEquals("Café", doc.getTables().getFirst().getRow(1).getCell(0).getText());
        }
        var pdf = criador.criar(pedido("{\"nome\":\"relatório\",\"formato\":\"pdf\",\"conteudo\":\"" + markdown + "\"}"));
        Path exemplos = Path.of("target", "exemplos-arquivos");
        Files.createDirectories(exemplos);
        Files.copy(docx.arquivo(), exemplos.resolve("relatorio.docx"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pdf.arquivo(), exemplos.resolve("relatorio.pdf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var doc = Loader.loadPDF(pdf.arquivo().toFile())) {
            String texto = new PDFTextStripper().getText(doc);
            assertTrue(texto.contains("São Paulo"));
            assertTrue(texto.contains("Café"));
        }
    }

    @Test void planilhaPreservaTiposFiltrosEFormulaExplicita() throws Exception {
        String dados = """
                {"nome":"vendas","formato":"xlsx","abas":[{"nome":"Ação",
                "colunas":[{"nome":"Código"},{"nome":"Preço"},{"nome":"Total"}],
                "linhas":[[{"tipo":"texto","valor":"0012"},{"tipo":"moeda","valor":"12,50"},{"tipo":"formula","valor":"SUM(B2:B2)"}],
                [{"tipo":"texto","valor":"=1+1"},{"tipo":"data","valor":"2026-10-16"}]]}]}
                """;
        Path arquivo = new CriadorDeArquivo(pasta).criar(pedido(dados)).arquivo();
        Path exemplos = Path.of("target", "exemplos-arquivos");
        Files.createDirectories(exemplos);
        Files.copy(arquivo, exemplos.resolve("vendas.xlsx"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var livro = new XSSFWorkbook(Files.newInputStream(arquivo))) {
            var folha = livro.getSheet("Ação");
            assertEquals("0012", folha.getRow(1).getCell(0).getStringCellValue());
            assertEquals(12.5, folha.getRow(1).getCell(1).getNumericCellValue());
            assertEquals("SUM(B2:B2)", folha.getRow(1).getCell(2).getCellFormula());
            assertEquals(12.5, folha.getRow(1).getCell(2).getNumericCellValue());
            assertEquals("=1+1", folha.getRow(2).getCell(0).getStringCellValue());
            assertTrue(folha.getCTWorksheet().isSetAutoFilter());
        }
        assertFalse(CriadorDeArquivo.formulaValida("HYPERLINK(\"https://x\")"));
        assertFalse(CriadorDeArquivo.formulaValida("[externo]A1"));
    }

    @Test void linkComoPastaEConteudoGrandeSaoRecusados() throws Exception {
        Path fora = pasta.resolve("fora"); Files.createDirectory(fora);
        Path link = pasta.resolve("link");
        try { Files.createSymbolicLink(link, fora); }
        catch (Exception e) { return; }
        CriadorDeArquivo criador = new CriadorDeArquivo(link);
        assertThrows(Exception.class, () -> criador.criar(pedido("{\"nome\":\"teste\",\"formato\":\"txt\",\"conteudo\":\"x\"}")));
        assertThrows(Exception.class, () -> new CriadorDeArquivo(pasta).criar(pedido("{\"nome\":\"a\",\"formato\":\"txt\",\"conteudo\":\""
                + "x".repeat(256 * 1024 + 1) + "\"}")));
    }

    @Test void csvProtegeTextoMasMantemNumeroNegativoExplicito() throws Exception {
        String dados = """
                {"nome":"dados","formato":"csv","abas":[{"nome":"Única",
                "colunas":[{"nome":"Valor"},{"nome":"Texto"}],
                "linhas":[[{"tipo":"numero","valor":"-12,50"},{"tipo":"texto","valor":"=1+1"}]]}]}
                """;
        String csv = Files.readString(new CriadorDeArquivo(pasta).criar(pedido(dados)).arquivo());
        assertTrue(csv.contains("\"-12,50\""));
        assertTrue(csv.contains("\"'=1+1\""));
    }
}
