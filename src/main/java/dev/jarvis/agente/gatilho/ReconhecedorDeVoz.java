package dev.jarvis.agente.gatilho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reconhece "Jarvis, iniciar" com o Vosk, <b>offline</b>.
 *
 * <p>Offline é o ponto: nenhum áudio desta sala sai da máquina antes do gatilho. Nada de enviar o
 * microfone para uma API de fala para descobrir se alguém chamou.
 *
 * <p>A gramática é restrita a duas frases mais {@code [unk]}. Isso não é economia, é precisão: com
 * vocabulário livre, o modelo pequeno acha "jarvis" em meia conversa. Preso a duas frases, ele só
 * pode responder uma delas ou "não sei".
 *
 * <p><b>Anulável de propósito.</b> O modelo são ~45 MB que não cabem no repositório, e sem ele
 * este objeto não existe — {@link #tentarCriar} devolve {@code null}, o agente avisa no log e
 * segue só com as palmas. Gatilho de voz que não dá para montar não pode impedir o PC de ter
 * braço.
 */
final class ReconhecedorDeVoz implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ReconhecedorDeVoz.class.getName());

    /** As únicas coisas que o reconhecedor pode responder. */
    static final String GRAMATICA = "[\"jarvis iniciar\", \"jarvis inicia\", \"[unk]\"]";

    /** O que marca uma pasta como modelo do Vosk, qualquer que seja a versão no nome. */
    private static final List<Path> MARCAS_DO_MODELO =
            List.of(Path.of("am", "final.mdl"), Path.of("final.mdl"));

    /** -1 cala o Vosk: ele escreve direto no stderr e inundaria o agente.log. */
    private static final int VOSK_EM_SILENCIO = -1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Model modelo;
    private final Recognizer reconhecedor;
    private final double confiancaMinima;

    /** Uma frase aceita, com o quanto o modelo confiou nela. */
    record Reconhecimento(String frase, double confianca) {
    }

    private ReconhecedorDeVoz(Model modelo, Recognizer reconhecedor, double confiancaMinima) {
        this.modelo = modelo;
        this.reconhecedor = reconhecedor;
        this.confiancaMinima = confiancaMinima;
    }

    /**
     * Monta o reconhecedor, ou devolve {@code null} explicando no log por quê.
     *
     * <p>Pega {@code Throwable} e não {@code Exception} porque a falha mais provável aqui é um
     * {@code UnsatisfiedLinkError} ao carregar o binário nativo — e isso é um {@code Error}.
     */
    static ReconhecedorDeVoz tentarCriar(Path pastaDosModelos, double confiancaMinima) {
        Path modelo = procurarModelo(pastaDosModelos);
        if (modelo == null) {
            log.warning("gatilho de voz desligado: não achei o modelo do Vosk em "
                    + pastaDosModelos.toAbsolutePath() + " (rode baixar-modelo.ps1). "
                    + "As palmas continuam valendo.");
            return null;
        }
        try {
            LibVosk.vosk_set_log_level(VOSK_EM_SILENCIO);
            Model carregado = new Model(modelo.toString());
            Recognizer reconhecedor =
                    new Recognizer(carregado, DetectorDePalmas.TAXA, GRAMATICA);
            // A confiança por palavra só vem com isto ligado.
            reconhecedor.setWords(true);
            log.info("gatilho de voz de pé com o modelo " + modelo.getFileName());
            return new ReconhecedorDeVoz(carregado, reconhecedor, confiancaMinima);
        } catch (Throwable e) {
            log.warning("gatilho de voz desligado: o Vosk não subiu (" + e + "). "
                    + "As palmas continuam valendo.");
            return null;
        }
    }

    /** A primeira pasta de {@code modelos/} que parece um modelo do Vosk. */
    static Path procurarModelo(Path pasta) {
        if (!Files.isDirectory(pasta)) {
            return null;
        }
        try (Stream<Path> candidatos = Files.list(pasta)) {
            // Pelo conteúdo, e não pelo nome: assim trocar de versão do modelo não pede
            // mexer na configuração.
            return candidatos.filter(Files::isDirectory)
                    .filter(dir -> MARCAS_DO_MODELO.stream()
                            .anyMatch(marca -> Files.isRegularFile(dir.resolve(marca))))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warning("não consegui ler " + pasta + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Consome áudio e devolve a frase quando ela foi dita inteira.
     *
     * <p>Só resultado <b>final</b>: o parcial do Vosk muda a cada pedaço de áudio e dispararia no
     * meio de uma palavra. {@code acceptWaveForm} devolvendo verdadeiro é o fim de um enunciado —
     * é ali que o resultado para de mudar.
     */
    Optional<Reconhecimento> alimentar(short[] amostras, int quantidade) {
        try {
            if (!reconhecedor.acceptWaveForm(amostras, quantidade)) {
                return Optional.empty();
            }
            return interpretar(reconhecedor.getResult(), confiancaMinima);
        } catch (RuntimeException e) {
            log.warning("o Vosk reclamou: " + e);
            return Optional.empty();
        }
    }

    /** Zera o reconhecedor: ao voltar do modo ativo, nada do que se falou antes deve sobrar. */
    void reiniciar() {
        try {
            reconhecedor.reset();
        } catch (RuntimeException e) {
            log.warning("não consegui reiniciar o Vosk: " + e);
        }
    }

    /**
     * Lê o JSON do Vosk e decide.
     *
     * <p>A confiança é a <b>menor</b> entre as palavras, não a média: "jarvis" certíssimo e
     * "iniciar" no chute dariam média boa, e é justamente esse o caso que não pode acordar a casa.
     */
    static Optional<Reconhecimento> interpretar(String resultadoJson, double confiancaMinima) {
        JsonNode resposta;
        try {
            resposta = JSON.readTree(resultadoJson);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String frase = resposta.path("text").asString("").trim().toLowerCase(Locale.ROOT);
        if (!frase.startsWith("jarvis inicia")) {
            return Optional.empty();
        }
        JsonNode palavras = resposta.path("result");
        if (!palavras.isArray() || palavras.isEmpty()) {
            return Optional.empty();
        }
        double menor = 1;
        for (JsonNode palavra : palavras) {
            menor = Math.min(menor, palavra.path("conf").asDouble(0));
        }
        if (menor < confiancaMinima) {
            log.fine("ouvi '" + frase + "' com confiança " + menor + "; abaixo do mínimo");
            return Optional.empty();
        }
        return Optional.of(new Reconhecimento(frase, menor));
    }

    /** As frases que a gramática aceita, para o log e o README não saírem do combinado. */
    static List<String> frasesAceitas() {
        return List.of("jarvis iniciar", "jarvis inicia");
    }

    @Override
    public void close() {
        try {
            reconhecedor.close();
            modelo.close();
        } catch (RuntimeException e) {
            log.warning("erro ao fechar o Vosk: " + e);
        }
    }
}
