package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** As três ações de planilha como chegam da VPS, e o anúncio delas no cabeçalho. */
class AcoesDePlanilhaTest {

    @TempDir Path pasta;
    @TempDir Path backup;

    private final JsonMapper json = JsonMapper.builder().build();
    private PlanilhasPermitidas planilhas;

    @BeforeEach
    void preparar() {
        planilhas = new PlanilhasPermitidas(pasta, Set.of("agenda.csv", "financas.csv"), backup);
    }

    private Resultado executar(String acao, String dados) {
        JsonNode no = json.readTree(dados.replace('\'', '"'));
        return AgentePc.executarPlanilha(new ComandoRecebido("c1", acao, "", no), planilhas);
    }

    private String texto() throws Exception {
        return new String(Files.readAllBytes(pasta.resolve("agenda.csv")), StandardCharsets.UTF_8).replace("﻿", "");
    }

    private static final String CAB = "'cabecalho':['Id','Data','Compromisso']";

    @Test
    void acrescentarLinhasPeloComando() throws Exception {
        Resultado r = executar("acrescentar_linhas",
                "{'arquivo':'agenda.csv'," + CAB + ",'linhas':[['a1','12/11/2026','Dentista']]}");
        assertTrue(r.ok(), r.mensagem());
        assertEquals(1, r.dados().path("gravadas").intValue());
        assertEquals(0, r.dados().path("jaExistiam").intValue());
        assertTrue(texto().contains("a1;12/11/2026;Dentista"));
    }

    @Test
    void alterarLinhaPeloComando() throws Exception {
        executar("acrescentar_linhas", "{'arquivo':'agenda.csv'," + CAB + ",'linhas':[['a1','12/11/2026','Dentista']]}");
        Resultado r = executar("alterar_linha",
                "{'arquivo':'agenda.csv','id':'a1','valores':{'Compromisso':'Dentista às 10h'}}");
        assertTrue(r.ok(), r.mensagem());
        assertEquals(1, r.dados().path("gravadas").intValue());
        assertTrue(texto().contains("a1;12/11/2026;Dentista às 10h"));
    }

    @Test
    void removerLinhaPeloComando() throws Exception {
        executar("acrescentar_linhas", "{'arquivo':'agenda.csv'," + CAB + ",'linhas':[['a1','12/11/2026','Dentista']]}");
        Resultado r = executar("remover_linha", "{'arquivo':'agenda.csv','id':'a1'}");
        assertTrue(r.ok(), r.mensagem());
        assertFalse(texto().contains("a1;"));
    }

    @Test
    void dadosInvalidosFalhamSemTocarNoArquivo() {
        for (String dados : new String[] {
                "{" + CAB + ",'linhas':[['a1','x','y']]}",
                "{'arquivo':'agenda.csv'," + CAB + ",'linhas':'a1'}",
                "{'arquivo':'agenda.csv'," + CAB + ",'linhas':[['a1',3,'y']]}",
                "{'arquivo':'agenda.csv','linhas':[['a1','x','y']]}",
                "{'arquivo':'agenda.csv'," + CAB + ",'linhas':[" + "['a','b','c'],".repeat(501) + "['a','b','c']]}"}) {
            Resultado r = executar("acrescentar_linhas", dados);
            assertFalse(r.ok(), dados);
            assertTrue(r.mensagem().startsWith(PlanilhasPermitidas.NAO_PERMITIDO), r.mensagem());
        }
        assertFalse(executar("alterar_linha", "{'arquivo':'agenda.csv','id':'a1','valores':'x'}").ok());
        assertFalse(executar("remover_linha", "{'arquivo':'agenda.csv'}").ok());
        assertFalse(Files.exists(pasta.resolve("agenda.csv")));
    }

    @Test
    void travadoVoltaComPrefixo() throws Exception {
        executar("acrescentar_linhas", "{'arquivo':'agenda.csv'," + CAB + ",'linhas':[['a1','12/11/2026','Dentista']]}");
        try (FileChannel canal = FileChannel.open(pasta.resolve("agenda.csv"), StandardOpenOption.WRITE);
                FileLock trava = canal.lock()) {
            Resultado r = executar("acrescentar_linhas",
                    "{'arquivo':'agenda.csv'," + CAB + ",'linhas':[['a2','13/11/2026','Reunião']]}");
            assertFalse(r.ok());
            assertTrue(r.mensagem().startsWith(PlanilhasPermitidas.TRAVADO), r.mensagem());
        }
    }

    @Test
    void cabecalhoAnunciaAsAcoesDePlanilha() {
        Configuracao config = new Configuracao(URI.create("https://jarvis.exemplo"), "segredo", null,
                Map.of(), false, false, "", 18, 0.6, "app", true);
        HttpRequest pedido = new ClienteJarvis(config)
                .comAcoes(HttpRequest.newBuilder(URI.create("https://jarvis.exemplo/api/pc/comandos"))).GET().build();
        assertEquals("acrescentar_linhas,alterar_linha,remover_linha",
                pedido.headers().firstValue("X-Pc-Acoes").orElse(""));
    }
}
