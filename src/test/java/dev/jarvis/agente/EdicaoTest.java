package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class EdicaoTest {
    @TempDir Path temporaria;
    private final JsonMapper json = JsonMapper.builder().build();

    private String catalogar(Path pasta, String nome, byte[] bytes) throws Exception {
        Files.createDirectories(pasta);
        Files.write(pasta.resolve(nome), bytes);
        var itens = new CatalogoDeArquivos(pasta).listar(json.readTree("{}"));
        String id = "";
        for (var item : itens.path("arquivos"))
            if (nome.equals(item.path("nome").asText())) id = item.path("id").asText();
        if (id.isBlank()) throw new AssertionError("arquivo não listado: " + nome);
        if (nome.endsWith(".txt") || nome.endsWith(".md"))
            new LeitorDeArquivo(pasta).ler(json.readTree("{\"id\":\"" + id + "\"}"));
        return id;
    }
    private String pedido(String id, String hash, String destino, String operacao) {
        return "{\"id\":\"" + id + "\",\"versaoEsperada\":\"" + hash
                + "\",\"destino\":\"" + destino + "\",\"operacoes\":[" + operacao + "]}";
    }

    @Test void substituiTodasAsOcorrenciasLiteraisPreservandoBomECrlf() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] anterior = "\uFEFFChat GPT\r\nchat gpt\r\nCHAT GPT\r\n".getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "relatorio.md", anterior);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        var proposta = editor.preparar(json.readTree(pedido(id, EditorDeArquivos.hash(anterior),
                "original", "{\"tipo\":\"substituir_todas_ocorrencias\",\"textoAtual\":\"Chat GPT\","
                        + "\"textoNovo\":\"você\",\"ignorarMaiusculas\":true}")));
        assertEquals(3, proposta.path("ocorrenciasSubstituidas").asInt());
        String editId = proposta.path("editId").asText();
        var comando = new ComandoRecebido("99999999-9999-9999-9999-999999999999",
                "aplicar_edicao", "", json.readTree("{\"editId\":\"" + editId + "\"}"));
        assertTrue(editor.aplicar(comando).ok());
        assertEquals("\uFEFFvocê\r\nvocê\r\nvocê\r\n", Files.readString(pasta.resolve("relatorio.md")));
        assertTrue(editor.aplicar(comando).ok());
        assertTrue(editor.desfazer(new ComandoRecebido("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aplicar_desfazer", "", json.readTree("{\"editId\":\"" + editId + "\"}"))).ok());
        assertArrayEquals(anterior, Files.readAllBytes(pasta.resolve("relatorio.md")));
    }

    @Test void substituicaoGlobalSemOcorrenciaNaoAlteraArquivo() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] anterior = "Texto simples".getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "nota.txt", anterior);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        assertTrue(assertThrows(java.io.IOException.class, () -> editor.preparar(json.readTree(
                pedido(id, EditorDeArquivos.hash(anterior), "original",
                        "{\"tipo\":\"substituir_todas_ocorrencias\",\"textoAtual\":\"Chat GPT\","
                                + "\"textoNovo\":\"você\"}"))))
                .getMessage().contains("nenhuma ocorrência"));
        assertArrayEquals(anterior, Files.readAllBytes(pasta.resolve("nota.txt")));
    }

    @Test void substituicaoGlobalQueExpandiriaDemaisERecusadaAntesDeGravar() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] anterior = "a".repeat(1000).getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "nota.txt", anterior);
        String novo = "b".repeat(2000);
        var operacao = json.createObjectNode().put("tipo", "substituir_todas_ocorrencias")
                .put("textoAtual", "a").put("textoNovo", novo);
        var pedido = json.createObjectNode().put("id", id)
                .put("versaoEsperada", EditorDeArquivos.hash(anterior)).put("destino", "original");
        pedido.putArray("operacoes").add(operacao);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        assertTrue(assertThrows(java.io.IOException.class, () -> editor.preparar(pedido))
                .getMessage().contains("limite de bytes"));
        assertArrayEquals(anterior, Files.readAllBytes(pasta.resolve("nota.txt")));
    }

    @Test void leituraPaginadaComprovaConteudoIntegralDaMesmaVersao() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] anterior = "a".repeat(13_000).getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "longo.md", anterior);
        String hash = EditorDeArquivos.hash(anterior);
        var leitor = new LeitorDeArquivo(pasta);
        var primeira = leitor.ler(json.readTree("{\"id\":\"" + id
                + "\",\"maximoCaracteres\":12000}"));
        assertTrue(primeira.path("truncado").asBoolean());
        assertFalse(LeitorDeArquivo.foiLidoIntegralmente(id, hash));
        leitor.ler(json.readTree("{\"id\":\"" + id + "\",\"inicio\":12000}"));
        assertTrue(LeitorDeArquivo.foiLidoIntegralmente(id, hash));
        var pedido = (tools.jackson.databind.node.ObjectNode) json.readTree(pedido(id, hash, "original",
                "{\"tipo\":\"substituir_conteudo\",\"conteudo\":\"novo\"}"));
        pedido.put("leituraIntegral", true);
        assertTrue(new EditorDeArquivos(pasta, temporaria.resolve("dados"))
                .preparar(pedido).path("editId").isTextual());
    }

    @Test void preservaBomCrLfEImpedeRepeticao() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] texto = "\uFEFFOlá\r\núnico\r\n".getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "nota.md", texto);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        String hash = EditorDeArquivos.hash(texto);
        var proposta = editor.preparar(json.readTree(pedido(id, hash, "original",
                "{\"tipo\":\"substituir_trecho\",\"textoAtual\":\"único\",\"textoNovo\":\"novo\\ntexto\"}")));
        String editId = proposta.path("editId").asText();
        var comando = new ComandoRecebido("11111111-1111-1111-1111-111111111111", "aplicar_edicao", "",
                json.readTree("{\"editId\":\"" + editId + "\"}"));
        assertTrue(editor.aplicar(comando).ok());
        assertEquals("\uFEFFOlá\r\nnovo\r\ntexto\r\n", Files.readString(pasta.resolve("nota.md")));
        assertTrue(editor.aplicar(comando).ok());
        assertEquals("\uFEFFOlá\r\nnovo\r\ntexto\r\n", Files.readString(pasta.resolve("nota.md")));
        assertTrue(editor.prepararDesfazer(json.readTree("{\"editId\":\"" + editId + "\"}"))
                .path("hashRestaurado").asText().equals(hash));
        assertTrue(editor.prepararDesfazer(json.readTree("{\"editId\":\"" + editId + "\"}"))
                .path("previa").asText().contains("novo"));
        assertTrue(editor.desfazer(new ComandoRecebido("22222222-2222-2222-2222-222222222222",
                "aplicar_desfazer", "", json.readTree("{\"editId\":\"" + editId + "\"}"))).ok());
        assertArrayEquals(texto, Files.readAllBytes(pasta.resolve("nota.md")));
    }

    @Test void trechoRepetidoEConflitoNaoAlteramArquivo() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] texto = "um um".getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "nota.txt", texto);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        String hash = EditorDeArquivos.hash(texto);
        assertTrue(assertThrows(java.io.IOException.class, () -> editor.preparar(json.readTree(
                pedido(id, hash, "original", "{\"tipo\":\"substituir_trecho\",\"textoAtual\":\"um\",\"textoNovo\":\"dois\"}"))))
                .getMessage().contains("CONFLITO_DE_TRECHO"));
        var proposta = editor.preparar(json.readTree(pedido(id, hash, "original",
                "{\"tipo\":\"acrescentar_ao_final\",\"texto\":\" três\"}")));
        Files.writeString(pasta.resolve("nota.txt"), "alterado por outro programa");
        assertTrue(assertThrows(java.io.IOException.class, () -> editor.aplicar(new ComandoRecebido(
                "33333333-3333-3333-3333-333333333333", "aplicar_edicao", "",
                json.readTree("{\"editId\":\"" + proposta.path("editId").asText() + "\"}"))))
                .getMessage().contains("CONFLITO_DE_VERSAO"));
        assertEquals("alterado por outro programa", Files.readString(pasta.resolve("nota.txt")));
    }

    @Test void copiaNaoSobrescreveOriginal() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] texto = "abc".getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "nota.txt", texto);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        var proposta = editor.preparar(json.readTree(pedido(id, EditorDeArquivos.hash(texto), "copia",
                "{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"def\"}")));
        var resultado = editor.aplicar(new ComandoRecebido("44444444-4444-4444-4444-444444444444",
                "aplicar_edicao", "", json.readTree("{\"editId\":\"" + proposta.path("editId").asText() + "\"}")));
        assertEquals("abc", Files.readString(pasta.resolve("nota.txt")));
        assertEquals("abcdef", Files.readString(resultado.arquivo()));
    }

    @Test void copiaNaoSobrescreveDestinoExistenteNoWindows() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] original = "antes".getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "nota.md", original);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        var proposta = editor.preparar(json.readTree(pedido(id, EditorDeArquivos.hash(original),
                "copia", "{\"tipo\":\"acrescentar_ao_final\",\"texto\":\" depois\"}")));
        String editId = proposta.path("editId").asText();
        Path destino = pasta.resolve("nota-copia-" + editId.substring(0, 8) + ".md");
        Files.writeString(destino, "não sobrescrever");
        assertThrows(java.io.IOException.class, () -> editor.aplicar(new ComandoRecebido(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "aplicar_edicao", "",
                json.readTree("{\"editId\":\"" + editId + "\"}"))));
        assertEquals("não sobrescrever", Files.readString(destino));
        assertArrayEquals(original, Files.readAllBytes(pasta.resolve("nota.md")));
    }

    @Test void reescritaExigeLeituraIntegralEBackupFalhoPreservaOriginal() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] texto = "a".repeat(5000).getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "grande.md", texto);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        String hash = EditorDeArquivos.hash(texto);
        var pedido = json.readTree(pedido(id, hash, "original",
                "{\"tipo\":\"substituir_conteudo\",\"conteudo\":\"novo\"}"));
        ((tools.jackson.databind.node.ObjectNode) pedido).put("leituraIntegral", true);
        assertTrue(assertThrows(java.io.IOException.class, () -> editor.preparar(pedido))
                .getMessage().contains("leitura integral"));
        new LeitorDeArquivo(pasta).ler(json.readTree("{\"id\":\"" + id
                + "\",\"maximoCaracteres\":12000}"));
        var proposta = editor.preparar(pedido);
        String editId = proposta.path("editId").asText();
        Path bloqueio = Files.createDirectory(temporaria.resolve("dados/edicoes/" + editId + ".backup"));
        Files.writeString(bloqueio.resolve("impedir-move"), "x");
        assertThrows(java.io.IOException.class, () -> editor.aplicar(new ComandoRecebido(
                "55555555-5555-5555-5555-555555555555", "aplicar_edicao", "",
                json.readTree("{\"editId\":\"" + editId + "\"}"))));
        assertArrayEquals(texto, Files.readAllBytes(pasta.resolve("grande.md")));
    }

    @Test void recusaFormatoEPastaProtegidaEConflitoNoDesfazer() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        String csv = catalogar(pasta, "dados.csv", "a,b".getBytes(StandardCharsets.UTF_8));
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        assertTrue(assertThrows(java.io.IOException.class, () -> editor.preparar(json.readTree(
                pedido(csv, EditorDeArquivos.hash("a,b".getBytes(StandardCharsets.UTF_8)), "original",
                        "{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"x\"}"))))
                .getMessage().contains("extensão incompatível"));
        Path memoria = temporaria.resolve("memoria");
        String id = catalogar(memoria, "nota.txt", "abc".getBytes(StandardCharsets.UTF_8));
        var protegido = new EditorDeArquivos(memoria, temporaria.resolve("dados"));
        assertTrue(assertThrows(java.io.IOException.class, () -> protegido.preparar(json.readTree(
                pedido(id, EditorDeArquivos.hash("abc".getBytes(StandardCharsets.UTF_8)), "original",
                        "{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"d\"}"))))
                .getMessage().contains("protegido"));

        String original = catalogar(pasta, "nota.txt", "abc".getBytes(StandardCharsets.UTF_8));
        var proposta = editor.preparar(json.readTree(pedido(original,
                EditorDeArquivos.hash("abc".getBytes(StandardCharsets.UTF_8)), "original",
                "{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"d\"}")));
        String editId = proposta.path("editId").asText();
        editor.aplicar(new ComandoRecebido("66666666-6666-6666-6666-666666666666",
                "aplicar_edicao", "", json.readTree("{\"editId\":\"" + editId + "\"}")));
        Files.writeString(pasta.resolve("nota.txt"), "mudou");
        assertTrue(assertThrows(java.io.IOException.class, () -> editor.prepararDesfazer(
                json.readTree("{\"editId\":\"" + editId + "\"}")))
                .getMessage().contains("CONFLITO_DE_VERSAO"));
    }

    @Test void arquivoBloqueadoNaoEAlterado() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] texto = "antes".getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "bloqueado.txt", texto);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        var proposta = editor.preparar(json.readTree(pedido(id, EditorDeArquivos.hash(texto), "original",
                "{\"tipo\":\"acrescentar_ao_final\",\"texto\":\" depois\"}")));
        try (var canal = java.nio.channels.FileChannel.open(pasta.resolve("bloqueado.txt"),
                java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
                var bloqueio = canal.lock()) {
            var erro = assertThrows(java.io.IOException.class, () -> editor.aplicar(new ComandoRecebido(
                    "77777777-7777-7777-7777-777777777777", "aplicar_edicao", "",
                    json.readTree("{\"editId\":\"" + proposta.path("editId").asText() + "\"}"))));
            assertTrue(erro.getMessage().contains("bloqueado"), erro.getMessage());
        }
        assertArrayEquals(texto, Files.readAllBytes(pasta.resolve("bloqueado.txt")));
    }

    @Test void diarioRecuperaResultadoComHashAposQueda() throws Exception {
        Path pasta = temporaria.resolve("documentos");
        byte[] texto = "a".getBytes(StandardCharsets.UTF_8);
        String id = catalogar(pasta, "nota.txt", texto);
        var editor = new EditorDeArquivos(pasta, temporaria.resolve("dados"));
        var proposta = editor.preparar(json.readTree(pedido(id, EditorDeArquivos.hash(texto), "original",
                "{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"b\"}")));
        String editId = proposta.path("editId").asText();
        String comandoId = "88888888-8888-8888-8888-888888888888";
        var diario = new DiarioE2(temporaria.resolve("diario"));
        assertTrue(diario.recebido(comandoId)); assertTrue(diario.iniciar(comandoId));
        editor.aplicar(new ComandoRecebido(comandoId, "aplicar_edicao", "",
                json.readTree("{\"editId\":\"" + editId + "\"}")));
        Path registro = temporaria.resolve("dados/edicoes/" + editId + ".json");
        var dados = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(registro));
        dados.put("estado", "gravando"); Files.writeString(registro, json.writeValueAsString(dados));
        editor.recuperarPendentes(diario);
        assertEquals("concluído", diario.obter(comandoId).estado());
        assertEquals("ab", Files.readString(pasta.resolve("nota.txt")));
    }
}
