package dev.jarvis.agente.gatilho;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * Impede o Windows de dormir na hora errada, via {@code SetThreadExecutionState}.
 *
 * <ul>
 *   <li><b>Com a tela aberta</b>: monitor e máquina acordados. Um HUD que apaga no meio de uma
 *       frase não serve para nada.</li>
 *   <li><b>Em repouso</b> (e com {@code energia.manterPcAcordado=true}): só a máquina. O monitor
 *       pode apagar — e deve —, mas o PC suspenso não ouviria palma nenhuma.</li>
 * </ul>
 *
 * <p><b>O pedido vale por thread, e só enquanto ela vive.</b> É por isso que existe o executor de
 * uma thread só aqui dentro: chamar isto do laço principal, ou de uma thread que termina logo
 * depois, não teria efeito nenhum — o Windows descarta o pedido junto com a thread que o fez, e o
 * monitor apagaria no meio do briefing sem nenhum erro no log para explicar.
 *
 * <p>Para conferir de fora: {@code powercfg /requests}.
 */
public final class EnergiaDoWindows implements AutoCloseable {

    private static final Logger log = Logger.getLogger(EnergiaDoWindows.class.getName());

    private static final int ES_SYSTEM_REQUIRED = 0x00000001;
    private static final int ES_DISPLAY_REQUIRED = 0x00000002;
    private static final int ES_CONTINUOUS = 0x80000000;

    /**
     * O pedacinho da kernel32 que usamos.
     *
     * <p>Escrito à mão em vez de puxar o {@code jna-platform} (3 MB) só por esta função.
     */
    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int SetThreadExecutionState(int flags);
    }

    /** O que se quer do Windows agora. */
    public enum Estado {
        /** Tela aberta: nem o monitor apaga, nem a máquina suspende. */
        TELA_ABERTA(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED),
        /** Em repouso: o monitor apaga, a máquina continua ouvindo. */
        ESCUTANDO(ES_CONTINUOUS | ES_SYSTEM_REQUIRED),
        /** Nada: o Windows volta a decidir sozinho. */
        LIVRE(ES_CONTINUOUS);

        private final int flags;

        Estado(int flags) {
            this.flags = flags;
        }
    }

    /** Uma thread só, de vida longa, porque o pedido morre com a thread que o fez. */
    private final ExecutorService dona = Executors.newSingleThreadExecutor(tarefa -> {
        Thread thread = new Thread(tarefa, "energia");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean funciona = true;
    private volatile Estado atual;

    /** Pede o estado ao Windows. Falha uma vez, avisa, e não tenta mais — não é essencial. */
    public void pedir(Estado estado) {
        if (!funciona || estado == atual) {
            return;
        }
        atual = estado;
        dona.execute(() -> {
            try {
                if (Kernel32.INSTANCE.SetThreadExecutionState(estado.flags) == 0) {
                    log.warning("o Windows recusou o pedido de energia " + estado);
                } else {
                    log.fine("energia: " + estado);
                }
            } catch (Throwable e) {
                // UnsatisfiedLinkError fora do Windows, por exemplo. O agente não depende disto.
                funciona = false;
                log.warning("sem controle de energia (" + e + "); o PC pode suspender sozinho");
            }
        });
    }

    /** Devolve o controle ao Windows e encerra a thread. */
    @Override
    public void close() {
        pedir(Estado.LIVRE);
        dona.shutdown();
        try {
            // O pedido de liberação precisa chegar antes de a thread morrer, senão o Windows o
            // descarta junto com ela e o PC ficaria acordado até o próximo logon.
            if (!dona.awaitTermination(2, TimeUnit.SECONDS)) {
                dona.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dona.shutdownNow();
        }
    }
}
