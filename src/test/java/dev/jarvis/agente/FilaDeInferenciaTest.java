package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Uma inferência por vez: a GPU é uma só. */
class FilaDeInferenciaTest {

    private static final Instant LONGE = Instant.now().plusSeconds(600);

    private static ComandoRecebido comando(int n) throws Exception {
        return new ComandoRecebido(String.format("00000000-0000-0000-0000-%012d", n), "inferir", "",
                OllamaFalso.JSON.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"oi " + n + "\"}]}"));
    }

    @Test
    @DisplayName("três pedidos ao mesmo tempo chegam ao Ollama um de cada vez")
    void umaPorVez() throws Exception {
        try (OllamaFalso ollama = new OllamaFalso()) {
            OllamaFalso.Resposta padrao = ollama.chat;
            ollama.chat = (troca, corpo) -> {
                Thread.sleep(200);
                padrao.responder(troca, corpo);
            };
            var config = new Configuracao.ConfigIa(true, ollama.url(), "qwen3.5:9b", 16384, "20m", 90, 256);
            FilaDeInferencia fila = new FilaDeInferencia(new InferenciaLocal(config)::inferir, Clock.systemUTC());
            CountDownLatch fim = new CountDownLatch(3);
            Map<String, String> estados = new ConcurrentHashMap<>();
            for (int n = 1; n <= 3; n++) {
                ComandoRecebido c = comando(n);
                assertTrue(fila.enfileirar(c, LONGE, () -> false, (estado, r) -> {
                    estados.put(c.id(), estado);
                    fim.countDown();
                }));
                assertTrue(fila.emVoo(c.id()));
            }
            assertTrue(fim.await(10, TimeUnit.SECONDS));
            assertEquals(1, ollama.pico.get(), "nunca duas na GPU");
            assertEquals(3, ollama.pedidos.size());
            assertEquals(List.of("concluído", "concluído", "concluído"), List.copyOf(estados.values()));
        }
    }

    @Test
    @DisplayName("fila cheia recusa na hora, e o ID recusado não fica em voo")
    void filaCheia() throws Exception {
        CountDownLatch solta = new CountDownLatch(1);
        CountDownLatch comecou = new CountDownLatch(1);
        FilaDeInferencia fila = new FilaDeInferencia(c -> {
            comecou.countDown();
            solta.await();
            return Resultado.feito(c, "ok");
        }, Clock.systemUTC());
        CountDownLatch fim = new CountDownLatch(1 + FilaDeInferencia.CAPACIDADE);
        for (int n = 0; n <= FilaDeInferencia.CAPACIDADE; n++) {
            assertTrue(fila.enfileirar(comando(n), LONGE, () -> false, (e, r) -> fim.countDown()));
            if (n == 0) assertTrue(comecou.await(5, TimeUnit.SECONDS));
        }
        assertTrue(fila.inferindo());
        ComandoRecebido sobra = comando(99);
        assertFalse(fila.enfileirar(sobra, LONGE, () -> false, (e, r) -> { }));
        assertFalse(fila.emVoo(sobra.id()));
        solta.countDown();
        assertTrue(fim.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("na fila além do prazo de início vira timeout; cancelado na fila não roda")
    void prazoECancelamento() throws Exception {
        FilaDeInferencia fila = new FilaDeInferencia(c -> {
            throw new AssertionError("não devia rodar");
        }, Clock.systemUTC());
        CountDownLatch fim = new CountDownLatch(2);
        Map<String, Resultado> resultados = new ConcurrentHashMap<>();
        Map<String, String> estados = new ConcurrentHashMap<>();
        ComandoRecebido vencido = comando(1);
        ComandoRecebido cancelado = comando(2);
        fila.enfileirar(vencido, Instant.now().minusSeconds(1), () -> false, (e, r) -> {
            estados.put(vencido.id(), e);
            resultados.put(vencido.id(), r);
            fim.countDown();
        });
        fila.enfileirar(cancelado, LONGE, () -> true, (e, r) -> {
            estados.put(cancelado.id(), e);
            fim.countDown();
        });
        assertTrue(fim.await(5, TimeUnit.SECONDS));
        assertEquals("falhou", estados.get(vencido.id()));
        assertEquals(InferenciaLocal.TIMEOUT,
                resultados.get(vencido.id()).dados().path("codigoErro").asString(""));
        assertEquals("cancelado", estados.get(cancelado.id()));
    }
}
