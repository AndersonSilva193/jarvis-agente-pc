package dev.jarvis.agente;

import java.io.IOException;

/** Recusa conhecida da lixeira, com código estável e mensagem escrita por nós. */
final class FalhaLixeira extends IOException {
    private final String codigo;

    FalhaLixeira(String mensagem) { this("VALIDACAO_RECUSADA", mensagem, null); }
    FalhaLixeira(String mensagem, Throwable causa) { this("VALIDACAO_RECUSADA", mensagem, causa); }
    FalhaLixeira(String codigo, String mensagem) { this(codigo, mensagem, null); }
    FalhaLixeira(String codigo, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.codigo = codigo;
    }
    String codigo() { return codigo; }
}
