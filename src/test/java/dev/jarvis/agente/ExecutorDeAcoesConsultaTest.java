package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ExecutorDeAcoesConsultaTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path pasta;

    private Resultado ler(String dados) throws Exception {
        var config = new Configuracao(URI.create("https://api.test"), "t", URI.create("https://hud.test"),
                Map.of(), false, false, "", 12, .6, "app", false, pasta.resolve("w.exe"),
                pasta.resolve("m.bin"), pasta);
        return new ExecutorDeAcoes(config, null, null)
                .executar(new ComandoRecebido("c1", "ler_arquivo", "", JSON.readTree(dados)));
    }

    @Test void nomeNoLugarDoIdDizOQueCorrigir() throws Exception {
        Resultado r = ler("{\"id\":\"notas.md\"}");
        assertFalse(r.ok());
        assertEquals("identificador inválido; use o id de listar_arquivos, não o nome do arquivo",
                r.mensagem());
    }

    @Test void inicioAlemDoFimDizOQueCorrigir() throws Exception {
        Files.writeString(pasta.resolve("notas.md"), "# oi");
        String id = new CatalogoDeArquivos(pasta).listar(JSON.createObjectNode())
                .path("arquivos").get(0).path("id").asText();
        assertTrue(ler("{\"id\":\"" + id + "\"}").ok());
        Resultado r = ler("{\"id\":\"" + id + "\",\"inicio\":999}");
        assertFalse(r.ok());
        assertTrue(r.mensagem().startsWith("intervalo de texto inválido"), r.mensagem());
    }
}
