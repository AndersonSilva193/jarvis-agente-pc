package dev.jarvis.agente;

import java.util.Base64;

/**
 * O que o agente devolve sobre um comando.
 *
 * @param imagemBase64 só na captura de tela; a VPS corta acima de 8 MB decodificados
 */
record Resultado(String comandoId, boolean ok, String mensagem, String imagemBase64,
        java.nio.file.Path arquivo) {
    Resultado(String comandoId, boolean ok, String mensagem, String imagemBase64) {
        this(comandoId, ok, mensagem, imagemBase64, null);
    }

    static Resultado feito(ComandoRecebido comando, String mensagem) {
        return new Resultado(comando.id(), true, mensagem, null);
    }

    static Resultado falhou(ComandoRecebido comando, String mensagem) {
        return new Resultado(comando.id(), false, mensagem, null);
    }

    static Resultado comImagem(ComandoRecebido comando, String mensagem, byte[] png) {
        return new Resultado(comando.id(), true, mensagem, Base64.getEncoder().encodeToString(png));
    }

    static Resultado comArquivo(ComandoRecebido comando, java.nio.file.Path arquivo) {
        return new Resultado(comando.id(), true, arquivo.getFileName().toString(), null, arquivo);
    }
}
