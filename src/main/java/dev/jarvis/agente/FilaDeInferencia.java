package dev.jarvis.agente;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Uma inferência por vez, numa thread própria: a GPU é uma só.
 *
 * <p>Thread própria porque uma inferência leva até {@code ollama.timeoutSegundos}, e no laço
 * principal isso seria o agente surdo — sem long poll e sem abrir app — durante a geração inteira.
 * A fila é curta de propósito: pedido que esperaria minutos atrás de outros já perdeu a vez, e a
 * VPS faz melhor indo para o fallback na hora.
 */
final class FilaDeInferencia implements EstadoDaIa.Atividade {

    /** Quem faz a inferência de fato; nos testes, um falso. */
    interface Inferencia {
        Resultado inferir(ComandoRecebido comando) throws InterruptedException;
    }

    /** Chamado na thread da fila, com o estado do diário E2 e o resultado. */
    interface AoTerminar {
        void aceitar(String estado, Resultado resultado);
    }

    static final int CAPACIDADE = 4;

    private static final Logger log = Logger.getLogger(FilaDeInferencia.class.getName());

    private final Inferencia inferencia;
    private final Clock relogio;
    private final ThreadPoolExecutor executor;
    private final Set<String> emVoo = ConcurrentHashMap.newKeySet();
    private final AtomicInteger rodando = new AtomicInteger();
    private volatile Instant fimDaUltima = Instant.EPOCH;

    FilaDeInferencia(Inferencia inferencia, Clock relogio) {
        this.inferencia = inferencia;
        this.relogio = relogio;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CAPACIDADE), tarefa -> {
                    Thread thread = new Thread(tarefa, "inferencia");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Põe o pedido na fila. Falso = fila cheia, e nada foi agendado.
     *
     * <p>O ID entra em {@link #emVoo} antes de agendar e só sai depois do {@code aoTerminar} —
     * que é quem entrega o resultado. Assim o replay do laço principal nunca vê uma inferência
     * viva como "executando sem resultado" e a marca como incerta.
     */
    boolean enfileirar(ComandoRecebido comando, Instant prazoInicio, BooleanSupplier cancelado,
            AoTerminar aoTerminar) {
        emVoo.add(comando.id());
        try {
            executor.execute(() -> rodar(comando, prazoInicio, cancelado, aoTerminar));
            return true;
        } catch (RejectedExecutionException e) {
            emVoo.remove(comando.id());
            return false;
        }
    }

    private void rodar(ComandoRecebido comando, Instant prazoInicio, BooleanSupplier cancelado,
            AoTerminar aoTerminar) {
        try {
            if (relogio.instant().isAfter(prazoInicio)) {
                aoTerminar.aceitar("falhou", InferenciaLocal.falha(comando, InferenciaLocal.TIMEOUT,
                        "a fila local passou do prazo de início"));
                return;
            }
            if (cancelado.getAsBoolean()) {
                aoTerminar.aceitar("cancelado", Resultado.falhou(comando, "cancelado antes do início"));
                return;
            }
            Resultado resultado;
            rodando.incrementAndGet();
            try {
                resultado = inferencia.inferir(comando);
            } catch (RuntimeException e) {
                log.warning("inferir " + comando.id() + " falhou: " + e.getClass().getSimpleName());
                resultado = InferenciaLocal.falha(comando, InferenciaLocal.FALHA_OLLAMA,
                        "falha inesperada na inferência");
            } finally {
                rodando.decrementAndGet();
                fimDaUltima = relogio.instant();
            }
            aoTerminar.aceitar(resultado.ok() ? "concluído" : "falhou", resultado);
        } catch (InterruptedException e) {
            // Agente encerrando: o diário fica em "executando" e vira incerto na próxima partida.
            Thread.currentThread().interrupt();
        } finally {
            emVoo.remove(comando.id());
        }
    }

    boolean emVoo(String id) {
        return emVoo.contains(id);
    }

    @Override
    public boolean inferindo() {
        return rodando.get() > 0;
    }

    @Override
    public Instant fimDaUltima() {
        return fimDaUltima;
    }
}
