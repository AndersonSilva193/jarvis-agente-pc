package dev.jarvis.agente.gatilho;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReconhecedorDeVozTest {
    private String json(String texto, double... probabilidades) {
        String[] palavras = texto.isBlank() ? new String[0] : texto.trim().split("\\s+");
        StringBuilder tokens = new StringBuilder("[");
        for (int i = 0; i < palavras.length; i++) {
            if (i > 0) tokens.append(',');
            double p = probabilidades[Math.min(i, probabilidades.length - 1)];
            tokens.append("{\"id\":123,\"text\":\" ").append(palavras[i]).append("\",\"p\":").append(p).append('}');
        }
        tokens.append(']');
        return "{\"transcription\":[{\"text\":\"" + texto + "\",\"tokens\":" + tokens + "}]}";
    }
    @Test void aceitaSomenteFraseInteira() {
        assertTrue(ReconhecedorDeVoz.interpretar(json("Jarvis, iniciar!", .9), .6).isPresent());
        for (String texto : new String[]{"iniciar", "jarvis inicia", "jarvis iniciar agora", "nao jarvis iniciar", ""})
            assertTrue(ReconhecedorDeVoz.interpretar(json(texto, .9), .6).isEmpty(), texto);
        assertTrue(ReconhecedorDeVoz.interpretar(json("jarvis iniciar", .3), .6).isEmpty());
        var variacaoReconhecida = ReconhecedorDeVoz.interpretar(json("Jardes, iniciar.", .2, .9), .6);
        assertTrue(variacaoReconhecida.isPresent());
        assertEquals(.9, variacaoReconhecida.orElseThrow().confianca(), .001);
        assertTrue(ReconhecedorDeVoz.interpretar(json("Jarvis iniciar", .9, .59), .6).isEmpty());
        assertTrue(ReconhecedorDeVoz.interpretar("{}", .6).isEmpty());
        assertTrue(ReconhecedorDeVoz.interpretar("quebrado", .6).isEmpty());
    }
    @Test void silencioNaoIniciaInferencia() {
        AtomicInteger chamadas = new AtomicInteger();
        try (var voz = new ReconhecedorDeVoz(pcm -> { chamadas.incrementAndGet(); return Optional.empty(); })) {
            for (int i = 0; i < 1000; i++) voz.alimentar(new short[320], 320);
            assertEquals(0, chamadas.get());
        }
    }
    @Test void cancelaResultadoAntigoENaoEmpilhaInferencias() throws Exception {
        CountDownLatch iniciou = new CountDownLatch(1), liberar = new CountDownLatch(1);
        AtomicInteger chamadas = new AtomicInteger();
        try (var voz = new ReconhecedorDeVoz(pcm -> {
            assertTrue(pcm.length <= 80_000);
            chamadas.incrementAndGet(); iniciou.countDown(); liberar.await(3, TimeUnit.SECONDS);
            return Optional.of(new ReconhecedorDeVoz.Reconhecimento(.9));
        })) {
            falar(voz);
            assertTrue(iniciou.await(2, TimeUnit.SECONDS));
            falar(voz);
            assertEquals(1, chamadas.get());
            voz.reiniciar(); liberar.countDown();
            Thread.sleep(50);
            assertTrue(voz.alimentar(new short[320], 320).isEmpty());
        } finally { liberar.countDown(); }
    }
    private void falar(ReconhecedorDeVoz voz) {
        short[] quieto = new short[320], som = new short[320]; Arrays.fill(som, (short) 3000);
        for (int i = 0; i < 60; i++) voz.alimentar(quieto, 320);
        for (int i = 0; i < 30; i++) voz.alimentar(som, 320);
        for (int i = 0; i < 35; i++) voz.alimentar(quieto, 320);
    }
}
