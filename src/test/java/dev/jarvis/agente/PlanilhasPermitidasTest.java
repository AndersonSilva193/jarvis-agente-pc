package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanilhasPermitidasTest {

    @TempDir Path pasta;
    @TempDir Path backup;

    private PlanilhasPermitidas planilhas;

    private static final List<String> CAB_FIN = List.of("Id", "Data", "Tipo");
    private static final List<String> CAB_AGENDA = List.of("Id", "Data", "Início", "Fim", "Compromisso", "Local");

    @BeforeEach
    void preparar() {
        planilhas = new PlanilhasPermitidas(pasta, Set.of("agenda.csv", "financas.csv", "leads.csv"), backup);
    }

    private String texto(String nome) throws IOException {
        byte[] bytes = Files.readAllBytes(pasta.resolve(nome));
        return new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
    }

    @Test
    void acrescentarCriaOArquivoComCabecalhoEBom() throws IOException {
        planilhas.acrescentar("financas.csv", CAB_FIN, List.of(List.of("l1", "11/11/2026", "gasto")));
        byte[] bytes = Files.readAllBytes(pasta.resolve("financas.csv"));
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertEquals("Id;Data;Tipo\r\nl1;11/11/2026;gasto\r\n", texto("financas.csv"));
    }

    @Test
    void acrescentarEIdempotentePorId() throws IOException {
        PlanilhasPermitidas.Saida primeira = planilhas.acrescentar("financas.csv", CAB_FIN,
                List.of(List.of("l1", "11/11/2026", "gasto")));
        PlanilhasPermitidas.Saida segunda = planilhas.acrescentar("financas.csv", CAB_FIN,
                List.of(List.of("l1", "11/11/2026", "gasto")));
        assertEquals(new PlanilhasPermitidas.Saida(1, 0), primeira);
        assertEquals(new PlanilhasPermitidas.Saida(0, 1), segunda);
        assertEquals("Id;Data;Tipo\r\nl1;11/11/2026;gasto\r\n", texto("financas.csv"));
    }

    @Test
    void acrescentarPreservaLinhasAntigasEPoeNoFim() throws IOException {
        planilhas.acrescentar("financas.csv", CAB_FIN, List.of(List.of("l1", "10/11/2026", "gasto")));
        planilhas.acrescentar("financas.csv", CAB_FIN, List.of(List.of("l2", "11/11/2026", "ganho")));
        assertEquals("Id;Data;Tipo\r\nl1;10/11/2026;gasto\r\nl2;11/11/2026;ganho\r\n", texto("financas.csv"));
    }

    @Test
    void recusaNomeComCaminhoOuForaDaLista() {
        for (String nome : List.of("../x.csv", "sub\\agenda.csv", "sub/agenda.csv", "outro.csv", "C:agenda.csv")) {
            IOException erro = assertThrows(IOException.class,
                    () -> planilhas.acrescentar(nome, CAB_FIN, List.of(List.of("l1", "x", "y"))), nome);
            assertTrue(erro.getMessage().startsWith(PlanilhasPermitidas.NAO_PERMITIDO + ": "), erro.getMessage());
        }
        assertFalse(planilhas.permitida("../agenda.csv"));
        assertTrue(planilhas.permitida("Agenda.CSV"));
    }

    @Test
    void arquivoTravadoViraTravado() throws IOException {
        planilhas.acrescentar("agenda.csv", CAB_AGENDA,
                List.of(List.of("a1", "12/11/2026", "10:00", "", "Dentista", "")));
        try (FileChannel canal = FileChannel.open(pasta.resolve("agenda.csv"), StandardOpenOption.WRITE);
                FileLock trava = canal.lock()) {
            IOException erro = assertThrows(IOException.class, () -> planilhas.acrescentar("agenda.csv",
                    CAB_AGENDA, List.of(List.of("a2", "13/11/2026", "", "", "Reunião", ""))));
            assertTrue(erro.getMessage().startsWith(PlanilhasPermitidas.TRAVADO + ": "), erro.getMessage());
        }
        assertFalse(texto("agenda.csv").contains("a2"));
    }

    @Test
    void leAnsiDoExcelEGravaUtf8() throws IOException {
        Files.write(pasta.resolve("agenda.csv"),
                "Id;Data;Início;Fim;Compromisso;Local\r\na1;12/11/2026;;;Reunião;Batel\r\n"
                        .getBytes(Charset.forName("windows-1252")));
        planilhas.acrescentar("agenda.csv", CAB_AGENDA,
                List.of(List.of("a2", "13/11/2026", "", "", "Ação", "")));
        byte[] bytes = Files.readAllBytes(pasta.resolve("agenda.csv"));
        assertEquals((byte) 0xEF, bytes[0]);
        String conteudo = texto("agenda.csv");
        assertTrue(conteudo.contains("Reunião"), conteudo);
        assertTrue(conteudo.contains("Início"), conteudo);
        assertTrue(conteudo.contains("Ação"), conteudo);
    }

    @Test
    void alterarTrocaSoAsColunasPedidas() throws IOException {
        planilhas.acrescentar("agenda.csv", CAB_AGENDA,
                List.of(List.of("a1", "12/11/2026", "10:00", "", "Dentista", "Centro")));
        PlanilhasPermitidas.Saida saida = planilhas.alterar("agenda.csv", "a1",
                Map.of("Início", "11:30", "Compromisso", "Dentista (retorno)"));
        assertEquals(new PlanilhasPermitidas.Saida(1, 0), saida);
        assertTrue(texto("agenda.csv").contains("a1;12/11/2026;11:30;;Dentista (retorno);Centro\r\n"),
                texto("agenda.csv"));
    }

    @Test
    void alterarIdInexistente() throws IOException {
        planilhas.acrescentar("agenda.csv", CAB_AGENDA,
                List.of(List.of("a1", "12/11/2026", "10:00", "", "Dentista", "")));
        IOException erro = assertThrows(IOException.class,
                () -> planilhas.alterar("agenda.csv", "zz", Map.of("Local", "x")));
        assertTrue(erro.getMessage().startsWith(PlanilhasPermitidas.ID_INEXISTENTE + ": "), erro.getMessage());
    }

    @Test
    void removerTiraALinhaEGuardaBackup() throws IOException {
        planilhas.acrescentar("agenda.csv", CAB_AGENDA, List.of(
                List.of("a1", "12/11/2026", "10:00", "", "Dentista", ""),
                List.of("a2", "13/11/2026", "", "", "Reunião", "")));
        planilhas.remover("agenda.csv", "a1");
        assertFalse(texto("agenda.csv").contains("a1;"));
        assertTrue(texto("agenda.csv").contains("a2;"));
        try (Stream<Path> copias = Files.list(backup)) {
            assertEquals(1, copias.count());
        }
    }

    @Test
    void linhaSemIdGanhaIdNaProximaEscrita() throws IOException {
        Files.writeString(pasta.resolve("agenda.csv"),
                "Id;Data;Início;Fim;Compromisso;Local\r\n;12/11/2026;10:00;;Dentista;\r\n");
        planilhas.acrescentar("agenda.csv", CAB_AGENDA,
                List.of(List.of("a2", "13/11/2026", "", "", "Reunião", "")));
        String linha = texto("agenda.csv").lines().filter(l -> l.contains("Dentista")).findFirst().orElseThrow();
        assertTrue(linha.matches("[0-9a-f]{8};12/11/2026;10:00;;Dentista;"), linha);
    }

    @Test
    void celulaComFormulaGanhaApostrofo() throws IOException {
        planilhas.acrescentar("financas.csv", CAB_FIN, List.of(List.of("l1", "=1+1", "gasto")));
        assertTrue(texto("financas.csv").contains("l1;'=1+1;gasto"), texto("financas.csv"));
    }

    @Test
    void celulaComSeparadorVaiEntreAspas() throws IOException {
        planilhas.acrescentar("financas.csv", CAB_FIN, List.of(List.of("l1", "a;b \"c\"", "gasto")));
        assertTrue(texto("financas.csv").contains("l1;\"a;b \"\"c\"\"\";gasto"), texto("financas.csv"));
        planilhas.acrescentar("financas.csv", CAB_FIN, List.of(List.of("l2", "x", "y")));
        assertTrue(texto("financas.csv").contains("l1;\"a;b \"\"c\"\"\";gasto"), texto("financas.csv"));
    }
}
