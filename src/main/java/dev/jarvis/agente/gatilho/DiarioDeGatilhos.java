package dev.jarvis.agente.gatilho;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * O registro de cada despertar, e a conta dos que foram engano.
 *
 * <p>Os dois números existem para a mesma pergunta: a sensibilidade está certa? Cada disparo loga
 * o quanto o som passou do ruído de fundo; cada "Jarvis, foi engano" incrementa o contador. Muito
 * engano com nível baixo quer dizer limiar frouxo — e aí o {@code --calibrar} resolve.
 *
 * <p>O contador é em memória: reiniciou o agente, zerou. É um número para ajustar a configuração
 * numa tarde, não uma estatística para guardar o ano inteiro.
 */
final class DiarioDeGatilhos {

    private static final Logger log = Logger.getLogger(DiarioDeGatilhos.class.getName());
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AtomicInteger disparos = new AtomicInteger();
    private final AtomicInteger falsosPositivos = new AtomicInteger();

    /** De onde veio o despertar. */
    enum Origem {
        PALMAS, VOZ
    }

    /**
     * Anota um despertar.
     *
     * @param medida o nível acima do ruído, em dB, nas palmas; a confiança do modelo, na voz
     * @param telaJaAberta verdadeiro quando a HUD já estava na frente do usuário
     */
    void disparou(Origem origem, double medida, boolean telaJaAberta) {
        int total = disparos.incrementAndGet();
        String detalhe = origem == Origem.PALMAS
                ? String.format("%.1f dB acima do ruído", medida)
                : String.format("confiança %.2f", medida);
        log.info("despertar #" + total + " por " + origem.name().toLowerCase()
                + " às " + LocalDateTime.now().format(HORA) + " (" + detalhe + ")"
                + (telaJaAberta ? "; a tela já estava aberta" : "; abrindo a tela"));
    }

    /**
     * Conta mais um alarme falso e devolve o total, que é o que o Jarvis fala de volta.
     */
    int falsoPositivo() {
        int total = falsosPositivos.incrementAndGet();
        log.warning("alarme falso #" + total + " de " + disparos.get() + " despertares"
                + "; se virar rotina, rode o agente com --calibrar e suba palmas.sensibilidadeDb");
        return total;
    }

    int falsosPositivos() {
        return falsosPositivos.get();
    }
}
