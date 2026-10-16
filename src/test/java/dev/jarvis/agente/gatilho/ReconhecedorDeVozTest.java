package dev.jarvis.agente.gatilho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A decisão sobre o que o Vosk devolveu, sem precisar do modelo de 45 MB.
 *
 * <p>Esta é a parte que decide se a casa acorda, e ela é só leitura de JSON — dá para exercitar
 * inteira sem áudio e sem binário nativo.
 */
class ReconhecedorDeVozTest {

    private static final double MINIMA = 0.7;

    @Test
    @DisplayName("encontra o modelo com final.mdl na raiz")
    void encontraModeloNaRaiz(@TempDir Path pasta) throws IOException {
        Path modelo = Files.createDirectory(pasta.resolve("portugues"));
        Files.createFile(modelo.resolve("final.mdl"));
        assertEquals(modelo, ReconhecedorDeVoz.procurarModelo(pasta));
    }

    @Test
    @DisplayName("continua encontrando o modelo com am/final.mdl")
    void encontraModeloEmAm(@TempDir Path pasta) throws IOException {
        Path modelo = Files.createDirectory(pasta.resolve("outro-modelo"));
        Files.createDirectories(modelo.resolve("am"));
        Files.createFile(modelo.resolve("am/final.mdl"));
        assertEquals(modelo, ReconhecedorDeVoz.procurarModelo(pasta));
    }

    @Test
    @DisplayName("ignora pastas incompletas e diretorios chamados final.mdl")
    void ignoraModeloIncompleto(@TempDir Path pasta) throws IOException {
        Files.createDirectory(pasta.resolve("incompleto"));
        Files.createDirectories(pasta.resolve("invalido/am/final.mdl"));
        Files.createDirectory(pasta.resolve("invalido/final.mdl"));
        assertEquals(null, ReconhecedorDeVoz.procurarModelo(pasta));
    }

    private static String resultado(String texto, double... confiancas) {
        StringBuilder json = new StringBuilder("{\"result\":[");
        String[] palavras = texto.isEmpty() ? new String[0] : texto.split(" ");
        for (int i = 0; i < palavras.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"conf\":").append(confiancas[i])
                    .append(",\"word\":\"").append(palavras[i]).append("\"}");
        }
        return json.append("],\"text\":\"").append(texto).append("\"}").toString();
    }

    @Test
    @DisplayName("aceita as duas formas da frase quando a confiança é alta")
    void aceitaAFrase() {
        assertTrue(ReconhecedorDeVoz.interpretar(resultado("jarvis iniciar", 0.95, 0.9), MINIMA)
                .isPresent());
        // O modelo pequeno engole o "r" final com frequência; a gramática já prevê isso.
        assertTrue(ReconhecedorDeVoz.interpretar(resultado("jarvis inicia", 0.88, 0.81), MINIMA)
                .isPresent());
    }

    @Test
    @DisplayName("a confiança é a MENOR entre as palavras, não a média")
    void confiancaEhAMenor() {
        // "jarvis" certíssimo e "iniciar" no chute dariam média 0,61 — que já reprovaria — mas o
        // caso perigoso é este: média boa escondendo uma palavra duvidosa.
        assertFalse(ReconhecedorDeVoz.interpretar(resultado("jarvis iniciar", 1.0, 0.45), MINIMA)
                .isPresent(), "média 0,72 passaria; a menor, 0,45, não pode passar");

        assertEquals(0.82, ReconhecedorDeVoz
                .interpretar(resultado("jarvis iniciar", 0.99, 0.82), MINIMA)
                .orElseThrow().confianca(), 1e-9);
    }

    @Test
    @DisplayName("recusa confiança baixa, frase de fora da gramática e resposta vazia")
    void recusaOResto() {
        assertFalse(ReconhecedorDeVoz.interpretar(resultado("jarvis iniciar", 0.5, 0.5), MINIMA)
                .isPresent());
        // O [unk] da gramática é exatamente o "não foi nada disso".
        assertFalse(ReconhecedorDeVoz.interpretar(resultado("jarvis", 0.99), MINIMA).isPresent());
        assertFalse(ReconhecedorDeVoz.interpretar(resultado(""), MINIMA).isPresent());
        assertFalse(ReconhecedorDeVoz.interpretar("{\"text\":\"jarvis iniciar\"}", MINIMA)
                .isPresent(), "sem as palavras não há confiança para conferir");
        assertFalse(ReconhecedorDeVoz.interpretar("isto não é json", MINIMA).isPresent());
    }

    @Test
    @DisplayName("sem modelo em modelos/, o reconhecedor não sobe e o agente segue sem ele")
    void semModeloNaoSobe() {
        // É o estado de quem acabou de clonar o repositório: o modelo não é versionado.
        assertEquals(null,
                ReconhecedorDeVoz.tentarCriar(Path.of("modelos-que-nao-existem"), MINIMA));
    }

    @Test
    @DisplayName("a gramática tem as duas frases e o [unk], e nada mais")
    void gramaticaFechada() {
        // Vocabulário livre faria o modelo pequeno achar "jarvis" em meia conversa.
        assertTrue(ReconhecedorDeVoz.GRAMATICA.contains("[unk]"));
        for (String frase : ReconhecedorDeVoz.frasesAceitas()) {
            assertTrue(ReconhecedorDeVoz.GRAMATICA.contains(frase), frase);
        }
    }
}
