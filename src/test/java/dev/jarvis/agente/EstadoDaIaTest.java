package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O cabeçalho X-Pc-Ia: Ollama, modelo carregado, GPU e o "ocupada". */
class EstadoDaIaTest {

    /** Relógio que só anda quando o teste manda. */
    private static final class Relogio extends Clock {
        Instant agora = Instant.parse("2026-10-27T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return agora; }
    }

    private static final class Atividade implements EstadoDaIa.Atividade {
        boolean inferindo;
        Instant fim = Instant.EPOCH;

        @Override public boolean inferindo() { return inferindo; }
        @Override public Instant fimDaUltima() { return fim; }
    }

    private static final EstadoDaIa.LeitorDeGpu SEM_NVIDIA_SMI = () -> {
        throw new IOException("Cannot run program \"nvidia-smi\"");
    };

    private OllamaFalso ollama;
    private final Relogio relogio = new Relogio();
    private final Atividade atividade = new Atividade();

    @BeforeEach void subir() throws Exception {
        ollama = new OllamaFalso();
    }

    @AfterEach void descer() {
        ollama.close();
    }

    private EstadoDaIa estado(URI url, String modelo, EstadoDaIa.LeitorDeGpu gpu) {
        return new EstadoDaIa(new Configuracao.ConfigIa(true, url, modelo, 16384, "20m", 90, 256),
                gpu, atividade, relogio);
    }

    @Test
    @DisplayName("com nvidia-smi: VRAM livre, uso e GPU ocupada por outro programa")
    void comNvidiaSmi() {
        String cabecalho = estado(ollama.url(), "qwen3.5:9b", () -> "3072, 12288, 85\n").cabecalho();
        assertEquals("ativo=1;ollama=1;modelo=qwen3.5:9b;carregado=1;gpu=1;vramLivreMb=9216;"
                + "vramTotalMb=12288;gpuUso=85;ocupada=1;inferindo=0", cabecalho);
    }

    @Test
    @DisplayName("uso alto durante ou logo depois da nossa inferência não é 'ocupada'")
    void usoNossoNaoEOcupada() {
        atividade.inferindo = true;
        String durante = estado(ollama.url(), "qwen3.5:9b", () -> "9000, 12288, 99").cabecalho();
        assertTrue(durante.contains(";ocupada=0;inferindo=1"), durante);

        atividade.inferindo = false;
        atividade.fim = relogio.agora.minusSeconds(2);
        String logoDepois = estado(ollama.url(), "qwen3.5:9b", () -> "9000, 12288, 99").cabecalho();
        assertTrue(logoDepois.contains(";ocupada=0;inferindo=0"), logoDepois);
    }

    @Test
    @DisplayName("sem nvidia-smi: gpu=0, sem VRAM nem uso, e o resto continua")
    void semNvidiaSmi() {
        String cabecalho = estado(ollama.url(), "qwen3.5:9b", SEM_NVIDIA_SMI).cabecalho();
        assertEquals("ativo=1;ollama=1;modelo=qwen3.5:9b;carregado=1;gpu=0;ocupada=0;inferindo=0",
                cabecalho);
    }

    @Test void saidaTortaDoNvidiaSmiContaComoSemGpu() {
        assertTrue(estado(ollama.url(), "qwen3.5:9b", () -> "[N/A], 12288, [N/A]")
                .cabecalho().contains(";gpu=0;"));
    }

    @Test void variasGpusUsaAPrimeira() {
        String cabecalho = estado(ollama.url(), "qwen3.5:9b",
                () -> "1000, 8000, 10\r\n2000, 16000, 90").cabecalho();
        assertTrue(cabecalho.contains("vramLivreMb=7000;vramTotalMb=8000;gpuUso=10;ocupada=0"), cabecalho);
    }

    @Test void ollamaForaDoAr() throws Exception {
        String cabecalho = estado(OllamaFalso.portaMorta(), "qwen3.5:9b", SEM_NVIDIA_SMI).cabecalho();
        assertTrue(cabecalho.startsWith("ativo=1;ollama=0;modelo=qwen3.5:9b;carregado=0;"), cabecalho);
    }

    @Test
    @DisplayName("carregado vem do /api/ps, com o :latest implícito")
    void carregado() {
        ollama.ps = (troca, corpo) -> OllamaFalso.json(troca, 200,
                "{\"models\":[{\"name\":\"llama3:latest\",\"model\":\"llama3:latest\"}]}");
        assertTrue(estado(ollama.url(), "llama3", SEM_NVIDIA_SMI).cabecalho().contains(";carregado=1;"));
        assertTrue(estado(ollama.url(), "qwen3.5:9b", SEM_NVIDIA_SMI).cabecalho().contains(";carregado=0;"));
    }

    @Test
    @DisplayName("a medida vale 15 s; o 'inferindo' é sempre o de agora")
    void cache() {
        EstadoDaIa estado = estado(ollama.url(), "qwen3.5:9b", SEM_NVIDIA_SMI);
        estado.cabecalho();
        relogio.agora = relogio.agora.plusSeconds(14);
        atividade.inferindo = true;
        assertTrue(estado.cabecalho().endsWith(";inferindo=1"));
        assertEquals(1, ollama.consultasPs.get());

        relogio.agora = relogio.agora.plus(Duration.ofSeconds(1));
        estado.cabecalho();
        assertEquals(2, ollama.consultasPs.get());
    }

    @Test
    @DisplayName("higienização: nada que quebre o cabeçalho ou o formato chave=valor")
    void higienizacao() {
        String limpo = EstadoDaIa.limpo("qwen3.5:9b;\r\nX-Evil: 1=2 \"x\"");
        assertEquals("qwen3.5:9bX-Evil:12x", limpo);
        assertEquals(64, EstadoDaIa.limpo("a".repeat(500)).length());
        assertFalse(EstadoDaIa.limpo(null).contains("null"));
    }

    @Test void desligadaNaoConsultaNada() {
        var desligada = new EstadoDaIa(new Configuracao.ConfigIa(false, ollama.url(), "qwen3.5:9b",
                16384, "20m", 90, 256), () -> {
                    throw new AssertionError("não devia rodar o nvidia-smi");
                }, atividade, relogio);
        assertEquals("ativo=0", desligada.cabecalho());
        assertEquals(0, ollama.consultasPs.get());
    }
}
