package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Base64;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiarioE2Test {
    @TempDir Path pasta;
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test void inicioRegistradoNaoExecutaDeNovoAposReinicio() throws Exception {
        DiarioE2 primeiro = new DiarioE2(pasta);
        assertTrue(primeiro.recebido(ID));
        assertTrue(primeiro.iniciar(ID));
        DiarioE2 reiniciado = new DiarioE2(pasta);
        assertFalse(reiniciado.recebido(ID));
        assertFalse(reiniciado.iniciar(ID));
        reiniciado.incerto(ID);
        assertEquals("resultado_incerto", reiniciado.obter(ID).estado());
    }

    @Test void resultadoSobreviveAQuedaDurantePost() throws Exception {
        DiarioE2 primeiro = new DiarioE2(pasta);
        primeiro.recebido(ID); primeiro.iniciar(ID);
        primeiro.concluir(ID, "concluído", new Resultado(ID, true, "feito", null), 0);
        DiarioE2 reiniciado = new DiarioE2(pasta);
        assertEquals(1, reiniciado.pendentes().size());
        assertEquals("feito", reiniciado.obter(ID).mensagem());
        reiniciado.confirmarEnvio(ID);
        assertTrue(new DiarioE2(pasta).pendentes().isEmpty());
        assertFalse(new DiarioE2(pasta).recebido(ID), "ID aceito não pode executar novamente");
    }

    @Test void acaoSobreviveAoReinicioERegistroAntigoNaoTemAcao() throws Exception {
        var diario = new DiarioE2(pasta);
        diario.recebido(ID); diario.iniciar(ID);
        diario.concluir(ID, "inferir", "concluído", new Resultado(ID, true, "feito", null), null);
        assertEquals("inferir", new DiarioE2(pasta).obter(ID).acao());
        new DiarioE2(pasta).confirmarEnvio(ID);
        assertEquals("inferir", new DiarioE2(pasta).obter(ID).acao());

        String antigo = "223e4567-e89b-12d3-a456-426614174000";
        java.nio.file.Files.writeString(pasta.resolve(antigo + ".json"),
                "{\"estado\":\"concluído\",\"mensagem\":\"x\",\"atualizado\":\"2026-10-20T00:00:00Z\","
                        + "\"imagemBase64\":\"\",\"arquivo\":\"\",\"enviado\":false}");
        assertEquals("", new DiarioE2(pasta).obter(antigo).acao());
    }

    @Test void limiteDeDadosMaiorSoParaInferir() throws Exception {
        Path arquivo = pasta.resolve("config.properties");
        java.nio.file.Files.writeString(arquivo,
                "urlApi=https://jarvis.exemplo\npcToken=segredo\ninferencia.maxKib=512\n");
        Configuracao config = Configuracao.ler(arquivo);
        var diario = new DiarioE2(pasta.resolve("diario"));
        diario.recebido(ID); diario.iniciar(ID);
        diario.concluir(ID, "inferir", "concluído", new Resultado(ID, true, "feito", null), null);
        assertEquals(512 * 1024, ClienteJarvis.limiteDados(diario.obter(ID), config));
        diario.concluir(ID, "concluído", new Resultado(ID, true, "feito", null), null);
        assertEquals(32_000, ClienteJarvis.limiteDados(diario.obter(ID), config));
    }

    @Test void payloadFicaAteAckEDepoisSaiDoDiarioLocal() throws Exception {
        var diario = new DiarioE2(pasta);
        diario.recebido(ID); diario.iniciar(ID);
        String imagem = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        var dados = JsonMapper.builder().build().createObjectNode().put("nome", "demo.txt");
        diario.concluir(ID, "concluído", new Resultado(ID, true, "feito", imagem,
                pasta.resolve("demo.txt"), dados), 0);
        var reiniciado = new DiarioE2(pasta);
        assertEquals(imagem, reiniciado.obter(ID).imagemBase64());
        assertEquals("demo.txt", reiniciado.obter(ID).dados().path("nome").asText());
        reiniciado.confirmarEnvio(ID);
        assertTrue(new DiarioE2(pasta).obter(ID).imagemBase64().isEmpty());
        assertTrue(new DiarioE2(pasta).obter(ID).arquivo().isEmpty());
        assertTrue(new DiarioE2(pasta).pendentes().isEmpty());
    }
}
