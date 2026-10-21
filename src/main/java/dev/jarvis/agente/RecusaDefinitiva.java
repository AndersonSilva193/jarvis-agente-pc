package dev.jarvis.agente;

import java.io.IOException;

/**
 * A VPS recusou o resultado de um jeito que não melhora com tentativa.
 *
 * <p>O agente repete os resultados pendentes <b>antes</b> de buscar comando, então um resultado
 * que o servidor nunca vai aceitar é um veneno na cabeça da fila: ele volta a cada 60 s e o
 * agente para de atender tudo o mais. Quando a resposta é 4xx, insistir não tem como dar certo —
 * o pedido é que está errado, não a rede. O laço registra e desiste daquele resultado.
 *
 * <p>Ficam de fora {@code 401}, {@code 403}, {@code 408} e {@code 429}: token errado, bloqueio
 * temporário e aperto de ritmo mudam de resposta sem o resultado mudar, e descartar entrega por
 * causa deles perderia print e diagnóstico por um segredo mal copiado.
 */
final class RecusaDefinitiva extends IOException {

    private static final long serialVersionUID = 1L;

    private final int status;

    RecusaDefinitiva(String mensagem, int status) {
        super(mensagem);
        this.status = status;
    }

    int status() {
        return status;
    }

    /** Se um status de resposta é recusa definitiva, e não soluço para tentar de novo. */
    static boolean definitivo(int status) {
        return status >= 400 && status < 500
                && status != 401 && status != 403 && status != 408 && status != 429;
    }
}
