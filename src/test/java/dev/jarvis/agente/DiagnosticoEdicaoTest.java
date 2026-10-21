package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DiagnosticoEdicaoTest {
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void distingueAcessoIoErroInternoEEstadoIncertoSemExporConteudo() throws Exception {
        String segredo = "CONTEUDO_PRIVADO_NAO_LOGAR";
        var dados = json.createObjectNode(); dados.putArray("operacoes").addObject()
                .put("tipo", "preencher_celula_vazia").put("valorNovo", segredo);
        var comando = new ComandoRecebido(UUID.randomUUID().toString(), "preparar_edicao", "", dados);
        var log = Logger.getLogger(DiagnosticoEdicao.class.getName());
        StringBuilder mensagens = new StringBuilder();
        Handler captura = new Handler() {
            public void publish(LogRecord r) { mensagens.append(r.getMessage()); }
            public void flush() { }
            public void close() { }
        };
        log.addHandler(captura);
        try {
            Exception[] erros = {new AccessDeniedException(segredo), new NoSuchFileException(segredo),
                    new IOException(segredo), new NullPointerException(segredo)};
            String[] codigos = {"ACESSO_NEGADO", "ARQUIVO_INDISPONIVEL", "FALHA_DE_IO", "FALHA_INTERNA"};
            for (int i = 0; i < erros.length; i++) {
                var r = DiagnosticoEdicao.resultado(comando, erros[i]);
                assertEquals(codigos[i], r.dados().path("codigoErro").asText());
                assertFalse(r.mensagem().contains(segredo));
            }
            var incerto = DiagnosticoEdicao.resultado(comando, new FalhaEdicao("RESULTADO_INCERTO",
                    "resultado_incerto: verifique o estado", new IOException(segredo)));
            assertTrue(incerto.mensagem().startsWith("resultado_incerto"));
            assertEquals("RESULTADO_INCERTO", incerto.dados().path("codigoErro").asText());
            assertFalse(mensagens.toString().contains(segredo));
            assertTrue(mensagens.toString().contains(comando.id()));
        } finally { log.removeHandler(captura); }
    }

    @Test void diagnosticoSobreviveAoEnvioEReinicioDoDiario() throws Exception {
        String id = UUID.randomUUID().toString();
        var comando = new ComandoRecebido(id, "preparar_edicao", "", json.createObjectNode());
        var r = DiagnosticoEdicao.resultado(comando, new FalhaEdicao("POSICAO_DIVERGENTE", "posição divergente"));
        var diario = new DiarioE2(pasta); diario.recebido(id); diario.iniciar(id);
        diario.concluir(id, "falhou", r, null); diario.confirmarEnvio(id);
        var reaberto = new DiarioE2(pasta).obter(id);
        assertTrue(reaberto.enviado());
        assertEquals("POSICAO_DIVERGENTE", reaberto.dados().path("codigoErro").asText());
        assertEquals("preparar_edicao", reaberto.dados().path("etapa").asText());
    }
}
