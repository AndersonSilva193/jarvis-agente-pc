package dev.jarvis.agente;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * O {@code config.properties} do agente — a única fonte de verdade sobre o que este PC aceita.
 *
 * @param apps nome -> caminho do executável. O Jarvis conhece apenas os <b>nomes</b>, que vão no
 *             cabeçalho {@code X-Pc-Apps} de cada consulta; o caminho nunca sai daqui. É isso que
 *             impede o modelo de escolher o que executar: ele escolhe um nome desta lista, ou
 *             nada.
 * @param gatilhoPalmas   se o despertar por palmas está habilitado
 * @param gatilhoVoz      se a palavra "Jarvis" acorda o Jarvis; sem o modelo em {@code modelos/}
 *                        isto se desliga sozinho, com aviso no log
 * @param microfone       parte do nome do microfone; vazio usa o padrão do Windows
 * @param palmasSensibilidadeDb quanto a palma precisa passar do ruído de fundo. Mede-se com
 *                        {@code --calibrar}, porque depende da sala e da distância
 * @param vozConfiancaMinima  a probabilidade média mínima dos tokens de "Jarvis" no Whisper
 * @param hudModo         {@code app} (janela sem barra) ou {@code kiosk} (tela cheia travada)
 * @param manterPcAcordado  em repouso, impedir a suspensão para as palmas continuarem valendo
 */
public record Configuracao(
        URI urlApi,
        String pcToken,
        URI urlHud,
        Map<String, Path> apps,
        boolean gatilhoPalmas,
        boolean gatilhoVoz,
        String microfone,
        double palmasSensibilidadeDb,
        double vozConfiancaMinima,
        String hudModo,
        boolean manterPcAcordado,
        Path vozExecutavel,
        Path vozModelo) {

    private static final Logger log = Logger.getLogger(Configuracao.class.getName());
    private static final String PREFIXO_APP = "app.";
    /** O mesmo formato que o PcService aceita do outro lado. */
    private static final String NOME_DE_APP = "[a-z0-9][a-z0-9_.-]{0,31}";


    public static final double SENSIBILIDADE_PADRAO = 18;
    public static final double CONFIANCA_PADRAO = 0.6;
    public static final String MODO_APP = "app";
    public static final String MODO_KIOSK = "kiosk";

    /** O arquivo ao lado do jar, usado quando nenhum caminho é passado na linha de comando. */
    static Path padrao() {
        return Path.of("config.properties");
    }

    static Configuracao ler(Path arquivo) throws IOException {
        if (!Files.isRegularFile(arquivo)) {
            throw new IOException("não achei o arquivo de configuração: " + arquivo.toAbsolutePath());
        }
        Properties propriedades = new Properties();
        try (InputStream entrada = Files.newInputStream(arquivo)) {
            propriedades.load(entrada);
        }

        if (propriedades.containsKey("voz.confiancaMinima")) log.warning("voz.confiancaMinima era do Vosk; use voz.probabilidadeMinima para Whisper");

        URI urlApi = urlHttps(propriedades, "urlApi", true);
        String pcToken = texto(propriedades, "pcToken");
        if (pcToken.isEmpty()) {
            throw new IOException("pcToken vazio: ponha o mesmo PC_TOKEN que está no .env da VPS");
        }
        URI urlHud = urlHttps(propriedades, "urlHud", false);

        String modo = texto(propriedades, "hud.modo").toLowerCase(Locale.ROOT);
        if (!MODO_APP.equals(modo) && !MODO_KIOSK.equals(modo)) {
            if (!modo.isEmpty()) {
                log.warning("hud.modo '" + modo + "' não existe; usando " + MODO_APP);
            }
            modo = MODO_APP;
        }

        return new Configuracao(urlApi, pcToken, urlHud, apps(propriedades),
                booleano(propriedades, "gatilho.palmas", false),
                booleano(propriedades, "gatilho.voz", true),
                texto(propriedades, "microfone"),
                decimal(propriedades, "palmas.sensibilidadeDb", SENSIBILIDADE_PADRAO, 3, 60),
                decimal(propriedades, "voz.probabilidadeMinima", CONFIANCA_PADRAO, 0, 1),
                modo,
                booleano(propriedades, "energia.manterPcAcordado", true),
                caminho(arquivo, propriedades, "voz.executavel", "ferramentas/whisper/whisper-cli.exe"),
                caminho(arquivo, propriedades, "voz.modelo", "modelos/ggml-base.bin"));
    }

    /**
     * Os apps liberados, só os que existem mesmo no disco.
     *
     * <p>Caminho que apontava para um programa desinstalado não entra no {@code X-Pc-Apps}, e
     * assim o Claude nem oferece abrir o que falharia.
     */
    private static Map<String, Path> apps(Properties propriedades) {
        Map<String, Path> apps = new LinkedHashMap<>();
        for (String chave : propriedades.stringPropertyNames()) {
            if (!chave.startsWith(PREFIXO_APP)) {
                continue;
            }
            String nome = chave.substring(PREFIXO_APP.length()).trim().toLowerCase(Locale.ROOT);
            String caminho = propriedades.getProperty(chave, "").trim();
            if (!nome.matches(NOME_DE_APP)) {
                log.warning("app '" + nome + "' ignorado: o nome só aceita letras, números, _, . e -");
                continue;
            }
            if (caminho.isEmpty()) {
                continue;
            }
            Path executavel;
            try {
                executavel = Path.of(caminho);
            } catch (InvalidPathException e) {
                // Acontece de verdade: no formato .properties a contrabarra é escape, então
                // "C:\Windows\notepad.exe" chega aqui com um \n virado em quebra de linha. O
                // caminho tem de ir com contrabarra dupla (ou com barra normal) — e um erro de
                // digitação num app não pode derrubar o agente inteiro.
                log.warning("app '" + nome + "' ignorado: caminho inválido. No config.properties a "
                        + "contrabarra é escape: escreva C:\\\\Pasta\\\\programa.exe ou C:/Pasta/programa.exe");
                continue;
            }
            if (!Files.isRegularFile(executavel)) {
                log.warning("app '" + nome + "' ignorado: não achei " + caminho);
                continue;
            }
            apps.put(nome, executavel);
        }
        return Map.copyOf(apps);
    }

    private static Path caminho(Path arquivo, Properties propriedades, String chave, String padrao) {
        return arquivo.toAbsolutePath().getParent().resolve(propriedades.getProperty(chave, padrao)).normalize();
    }

    /** Construtor mantido para configurações programáticas existentes. */
    public Configuracao(URI api, String token, URI hud, Map<String, Path> apps,
            boolean palmas, boolean voz, String microfone, double sensibilidade,
            double probabilidade, String modo, boolean acordado) {
        this(api, token, hud, apps, palmas, voz, microfone, sensibilidade, probabilidade,
                modo, acordado, Path.of("ferramentas/whisper/whisper-cli.exe").toAbsolutePath(),
                Path.of("modelos/ggml-base.bin").toAbsolutePath());
    }

    /** Os nomes para o cabeçalho X-Pc-Apps, em ordem e separados por vírgula. */
    public String cabecalhoDeApps() {
        return String.join(",", apps.keySet());
    }

    public Path app(String nome) {
        return apps.get(nome == null ? "" : nome.trim().toLowerCase(Locale.ROOT));
    }

    /** Verdadeiro quando pelo menos um gatilho está ligado e há HUD para abrir. */
    public boolean despertarLigado() {
        return (gatilhoPalmas || gatilhoVoz) && urlHud != null;
    }

    public boolean kiosk() {
        return MODO_KIOSK.equals(hudModo);
    }

    private static String texto(Properties propriedades, String chave) {
        return propriedades.getProperty(chave, "").trim();
    }

    /**
     * Um booleano do arquivo.
     *
     * <p>Valor torto avisa e cai no padrão, nunca derruba o agente — mesma política dos apps: um
     * erro de digitação numa linha não pode deixar o PC sem braço.
     */
    private static boolean booleano(Properties propriedades, String chave, boolean padrao) {
        String valor = texto(propriedades, chave).toLowerCase(Locale.ROOT);
        if (valor.isEmpty()) {
            return padrao;
        }
        if (valor.equals("true") || valor.equals("sim") || valor.equals("1")) {
            return true;
        }
        if (valor.equals("false") || valor.equals("nao") || valor.equals("não") || valor.equals("0")) {
            return false;
        }
        log.warning(chave + " = '" + valor + "' não é true nem false; usando " + padrao);
        return padrao;
    }

    /** Um número do arquivo, preso entre mínimo e máximo. */
    private static double decimal(Properties propriedades, String chave, double padrao,
            double minimo, double maximo) {
        String valor = texto(propriedades, chave).replace(',', '.');
        if (valor.isEmpty()) {
            return padrao;
        }
        try {
            double numero = Double.parseDouble(valor);
            if (!Double.isFinite(numero) || numero < minimo || numero > maximo) {
                log.warning(chave + " = " + valor + " está fora de [" + minimo + ", " + maximo
                        + "]; usando " + padrao);
                return padrao;
            }
            return numero;
        } catch (NumberFormatException e) {
            log.warning(chave + " = '" + valor + "' não é número; usando " + padrao);
            return padrao;
        }
    }

    /** Só https, sempre: o pcToken viaja no cabeçalho e em texto claro vazaria na rede. */
    private static URI urlHttps(Properties propriedades, String chave, boolean obrigatoria)
            throws IOException {
        String valor = texto(propriedades, chave);
        if (valor.isEmpty()) {
            if (obrigatoria) {
                throw new IOException(chave + " vazio no config.properties");
            }
            return null;
        }
        try {
            URI uri = URI.create(valor.endsWith("/") ? valor.substring(0, valor.length() - 1) : valor);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IOException(chave + " tem de ser um endereço https: " + valor);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException(chave + " não é um endereço válido: " + valor);
        }
    }
}
