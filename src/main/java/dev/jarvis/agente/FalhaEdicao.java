package dev.jarvis.agente;

import java.io.IOException;

/** Recusa conhecida, com código estável e mensagem produzida pelo editor. */
final class FalhaEdicao extends IOException {
    private final String codigo;
    private int indiceOperacao = -1;

    FalhaEdicao(String mensagem) { this("VALIDACAO_RECUSADA", mensagem, null); }
    FalhaEdicao(String mensagem, Throwable causa) { this("VALIDACAO_RECUSADA", mensagem, causa); }
    FalhaEdicao(String codigo, String mensagem) { this(codigo, mensagem, null); }
    FalhaEdicao(String codigo, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.codigo = codigo;
    }
    String codigo() { return codigo; }
    int indiceOperacao() { return indiceOperacao; }
    FalhaEdicao naOperacao(int indice) { indiceOperacao = indice; return this; }
}
