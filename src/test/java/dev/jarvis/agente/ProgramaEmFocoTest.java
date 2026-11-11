package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** O X-Pc-Foco: só o nome do processo, higienizado, com cache de 30 s e falha = ausente. */
class ProgramaEmFocoTest {

    /** Relógio que só anda quando o teste manda. */
    private static final class Relogio extends Clock {
        Instant agora = Instant.parse("2026-11-08T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return agora; }
    }

    private final Relogio relogio = new Relogio();

    @Test
    @DisplayName("do caminho do executável ao nome do processo, sem o .exe")
    void higienizaNomesBons() {
        assertEquals("chrome", ProgramaEmFoco.higienizar(
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"));
        assertEquals("Code", ProgramaEmFoco.higienizar("Code.exe"));
        assertEquals("WINWORD", ProgramaEmFoco.higienizar(
                "C:\\Program Files\\Microsoft Office\\root\\Office16\\WINWORD.EXE"));
        assertEquals("idea64", ProgramaEmFoco.higienizar("C:/JetBrains/bin/idea64.exe"));
        assertEquals("ms-teams", ProgramaEmFoco.higienizar("ms-teams"));
        assertEquals("node_v2.1", ProgramaEmFoco.higienizar("node_v2.1.exe"));
    }

    @Test
    @DisplayName("o que não cabe no padrão é recusado inteiro, nunca filtrado até caber")
    void recusaNomesRuins() {
        String[] ruins = {null, "", ".exe", "C:\\Pasta\\", "a b.exe", "café.exe",
                "x\r\nX-Evil: 1", "chrome\n", "nome;rm.exe", "a\u0000b", "C:\\x\\nome com espaço.exe",
                "relatório.exe", "a".repeat(41), "a".repeat(41) + ".exe"};
        for (String ruim : ruins) assertNull(ProgramaEmFoco.higienizar(ruim), String.valueOf(ruim));
    }

    @Test
    @DisplayName("até 40 caracteres passa; 41 não")
    void limiteDe40() {
        String quarenta = "a".repeat(40);
        assertEquals(quarenta, ProgramaEmFoco.higienizar("C:\\" + quarenta + ".exe"));
        assertNull(ProgramaEmFoco.higienizar(quarenta + "b"));
        assertTrue(ProgramaEmFoco.valido(quarenta));
    }

    @Test
    @DisplayName("lê no máximo a cada 30 s, e o ausente também fica em cache")
    void cacheDe30Segundos() {
        AtomicInteger leituras = new AtomicInteger();
        String[] resposta = {"C:\\x\\Code.exe"};
        ProgramaEmFoco foco = new ProgramaEmFoco(true, () -> {
            leituras.incrementAndGet();
            return resposta[0];
        }, relogio);

        assertEquals("Code", foco.cabecalho());
        resposta[0] = "chrome.exe";
        relogio.agora = relogio.agora.plus(Duration.ofSeconds(29));
        assertEquals("Code", foco.cabecalho(), "dentro dos 30 s vale a medida anterior");
        assertEquals(1, leituras.get());

        relogio.agora = relogio.agora.plus(Duration.ofSeconds(1));
        assertEquals("chrome", foco.cabecalho());
        assertEquals(2, leituras.get());

        resposta[0] = null;
        relogio.agora = relogio.agora.plus(ProgramaEmFoco.CACHE);
        assertNull(foco.cabecalho());
        resposta[0] = "Code.exe";
        relogio.agora = relogio.agora.plus(Duration.ofSeconds(10));
        assertNull(foco.cabecalho(), "o ausente também vale 30 s");
        assertEquals(3, leituras.get());
    }

    @Test
    @DisplayName("falha do leitor, inclusive sem user32, é cabeçalho ausente")
    void falhaEAusente() {
        assertNull(new ProgramaEmFoco(true, () -> { throw new IllegalStateException("x"); }, relogio)
                .cabecalho());
        assertNull(new ProgramaEmFoco(true, () -> { throw new UnsatisfiedLinkError("user32"); }, relogio)
                .cabecalho());
        assertNull(new ProgramaEmFoco(true, () -> "C:\\x\\nome com espaço.exe", relogio).cabecalho());
    }

    @Test
    @DisplayName("reportarFoco=false: nem chega a ler")
    void desligadoNaoLe() {
        AtomicInteger leituras = new AtomicInteger();
        ProgramaEmFoco foco = new ProgramaEmFoco(false, () -> {
            leituras.incrementAndGet();
            return "Code.exe";
        }, relogio);
        assertNull(foco.cabecalho());
        assertNull(foco.cabecalho());
        assertEquals(0, leituras.get());
    }

    @Test
    @EnabledIfSystemProperty(named = "teste.windows", matches = "true")
    @DisplayName("no Windows real, o leitor devolve um nome que passa na higienização")
    void leitorReal() throws Exception {
        String caminho = ProgramaEmFoco.WINDOWS.caminhoDoExecutavel();
        String nome = ProgramaEmFoco.higienizar(caminho);
        assertTrue(nome == null || ProgramaEmFoco.valido(nome), String.valueOf(caminho));
        System.out.println("em foco: " + nome);
    }
}
