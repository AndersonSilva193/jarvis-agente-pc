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
 * @param lixeiraDiasRetencao dias que um arquivo fica em {@code <pastaArquivos>/.lixeira} antes de
 *                        ser apagado de vez. O expurgo roda na partida e uma vez por dia
 * @param ia              o Ollama deste PC, que a ação {@code inferir} usa
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
        Path vozModelo,
        Path pastaArquivos,
        boolean computerUseAtivo,
        LimitesEdicao limitesEdicao,
        int lixeiraDiasRetencao,
        ConfigIa ia) {

    /**
     * O Ollama local. Modelo, contexto e keep_alive são decididos aqui, nunca pela VPS.
     *
     * @param url só {@code http://127.0.0.1}; {@code localhost} já chega reescrito
     */
    public record ConfigIa(boolean ativo, URI url, String modelo, int contexto, String keepAlive,
            int timeoutSegundos, int maxKib) {
        public static final URI URL_PADRAO = URI.create("http://127.0.0.1:11434");
        public static final String MODELO_PADRAO = "qwen3.5:9b";

        static ConfigIa padrao() {
            return new ConfigIa(false, URL_PADRAO, MODELO_PADRAO, 16384, "20m", 90, 256);
        }

        /** O teto do pedido e do resultado da inferência, em bytes. */
        public int maxBytes() {
            return maxKib * 1024;
        }
    }

    /** Construtor mantido para quem monta a configuração sem falar da IA local. */
    public Configuracao(URI urlApi, String pcToken, URI urlHud, Map<String, Path> apps,
            boolean gatilhoPalmas, boolean gatilhoVoz, String microfone,
            double palmasSensibilidadeDb, double vozConfiancaMinima, String hudModo,
            boolean manterPcAcordado, Path vozExecutavel, Path vozModelo, Path pastaArquivos,
            boolean computerUseAtivo, LimitesEdicao limitesEdicao, int lixeiraDiasRetencao) {
        this(urlApi, pcToken, urlHud, apps, gatilhoPalmas, gatilhoVoz, microfone,
                palmasSensibilidadeDb, vozConfiancaMinima, hudModo, manterPcAcordado,
                vozExecutavel, vozModelo, pastaArquivos, computerUseAtivo, limitesEdicao,
                lixeiraDiasRetencao, ConfigIa.padrao());
    }

    public record LimitesEdicao(int maxBytesArquivo, int maxOperacoes, int maxBytesResultado,
            int maxBytesPrevia, int maxVersoesBackup, long maxBytesBackup) {
        static LimitesEdicao padrao() {
            return new LimitesEdicao(1024 * 1024, 10, 1024 * 1024, 12_000, 10, 100L * 1024 * 1024);
        }
    }

    /** Construtor mantido para quem monta a configuração sem falar da lixeira. */
    public Configuracao(URI urlApi, String pcToken, URI urlHud, Map<String, Path> apps,
            boolean gatilhoPalmas, boolean gatilhoVoz, String microfone,
            double palmasSensibilidadeDb, double vozConfiancaMinima, String hudModo,
            boolean manterPcAcordado, Path vozExecutavel, Path vozModelo, Path pastaArquivos,
            boolean computerUseAtivo, LimitesEdicao limitesEdicao) {
        this(urlApi, pcToken, urlHud, apps, gatilhoPalmas, gatilhoVoz, microfone,
                palmasSensibilidadeDb, vozConfiancaMinima, hudModo, manterPcAcordado,
                vozExecutavel, vozModelo, pastaArquivos, computerUseAtivo, limitesEdicao,
                DIAS_RETENCAO_PADRAO);
    }

    public Configuracao(URI urlApi, String pcToken, URI urlHud, Map<String, Path> apps,
            boolean gatilhoPalmas, boolean gatilhoVoz, String microfone,
            double palmasSensibilidadeDb, double vozConfiancaMinima, String hudModo,
            boolean manterPcAcordado, Path vozExecutavel, Path vozModelo, Path pastaArquivos,
            boolean computerUseAtivo) {
        this(urlApi, pcToken, urlHud, apps, gatilhoPalmas, gatilhoVoz, microfone,
                palmasSensibilidadeDb, vozConfiancaMinima, hudModo, manterPcAcordado,
                vozExecutavel, vozModelo, pastaArquivos, computerUseAtivo,
                LimitesEdicao.padrao());
    }

    public Configuracao(URI urlApi, String pcToken, URI urlHud, Map<String, Path> apps,
            boolean gatilhoPalmas, boolean gatilhoVoz, String microfone,
            double palmasSensibilidadeDb, double vozConfiancaMinima, String hudModo,
            boolean manterPcAcordado, Path vozExecutavel, Path vozModelo, Path pastaArquivos) {
        this(urlApi, pcToken, urlHud, apps, gatilhoPalmas, gatilhoVoz, microfone,
                palmasSensibilidadeDb, vozConfiancaMinima, hudModo, manterPcAcordado,
                vozExecutavel, vozModelo, pastaArquivos, false);
    }

    private static final Logger log = Logger.getLogger(Configuracao.class.getName());
    private static final String PREFIXO_APP = "app.";
    /** O mesmo formato que o PcService aceita do outro lado. */
    private static final String NOME_DE_APP = "[a-z0-9][a-z0-9_.-]{0,31}";


    /** Um mês de arrependimento é o bastante, e não enche o disco do usuário. */
    public static final int DIAS_RETENCAO_PADRAO = 30;
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
                caminho(arquivo, propriedades, "voz.modelo", "modelos/ggml-base.bin"),
                pastaArquivos(arquivo, propriedades),
                booleano(propriedades, "computer.use.ativo", false),
                new LimitesEdicao(
                        inteiro(propriedades, "edicao.maxBytesArquivo", 1024 * 1024, 1, 8 * 1024 * 1024),
                        inteiro(propriedades, "edicao.maxOperacoes", 10, 1, 50),
                        inteiro(propriedades, "edicao.maxBytesResultado", 1024 * 1024, 1, 8 * 1024 * 1024),
                        inteiro(propriedades, "edicao.maxBytesPrevia", 12_000, 100, 20_000),
                        inteiro(propriedades, "edicao.maxVersoesBackup", 10, 1, 100),
                        inteiro(propriedades, "edicao.maxBytesBackup", 100 * 1024 * 1024,
                                1024 * 1024, 1024 * 1024 * 1024)),
                inteiro(propriedades, "lixeira.diasRetencao", DIAS_RETENCAO_PADRAO, 1, 3650),
                ia(propriedades));
    }

    private static ConfigIa ia(Properties propriedades) throws IOException {
        boolean ativo = booleano(propriedades, "ollama.ativo", false);
        String valorUrl = texto(propriedades, "ollama.url");
        URI url = valorUrl.isEmpty() ? ConfigIa.URL_PADRAO : urlLocal(valorUrl);
        if (url == null) {
            // Desliga a IA em vez de derrubar o agente; e a URL recusada nunca é usada.
            if (ativo) log.warning("ollama.url '" + valorUrl + "' recusada: só http://127.0.0.1 "
                    + "ou http://localhost; IA local desligada");
            ativo = false;
            url = ConfigIa.URL_PADRAO;
        }
        String modelo = texto(propriedades, "ollama.modelo");
        if (modelo.isEmpty()) modelo = ConfigIa.MODELO_PADRAO;
        else if (!modelo.matches("[A-Za-z0-9._:/-]{1,128}")) {
            log.warning("ollama.modelo inválido; usando " + ConfigIa.MODELO_PADRAO);
            modelo = ConfigIa.MODELO_PADRAO;
        }
        String keepAlive = texto(propriedades, "ollama.keepAlive");
        if (keepAlive.isEmpty()) keepAlive = "20m";
        else if (!keepAlive.matches("-1|0|\\d{1,5}[smh]?")) {
            log.warning("ollama.keepAlive '" + keepAlive + "' inválido; usando 20m");
            keepAlive = "20m";
        }
        return new ConfigIa(ativo, url, modelo,
                inteiro(propriedades, "ollama.contexto", 16384, 2048, 262144),
                keepAlive,
                inteiro(propriedades, "ollama.timeoutSegundos", 90, 1, 600),
                inteiro(propriedades, "inferencia.maxKib", 256, 1, 8192));
    }

    /**
     * O Ollama tem de estar nesta máquina: o agente manda para lá o que a VPS escreveu e devolve o
     * que vier, então outra URL viraria um proxy da VPS para a rede local. Nulo = recusar.
     *
     * <p>O {@code localhost} vira {@code 127.0.0.1}: sem passar pelo arquivo hosts e sem cair no
     * {@code ::1}, onde o Ollama não escuta por padrão.
     */
    static URI urlLocal(String valor) {
        try {
            URI uri = URI.create(valor.endsWith("/") ? valor.substring(0, valor.length() - 1) : valor);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean boa = "http".equalsIgnoreCase(uri.getScheme())
                    && (host.equals("127.0.0.1") || host.equals("localhost"))
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
            if (!boa) return null;
            return URI.create("http://127.0.0.1" + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static Path pastaArquivos(Path arquivo, Properties propriedades) {
        String valor = texto(propriedades, "pastaArquivos");
        if (valor.isEmpty()) return javax.swing.filechooser.FileSystemView.getFileSystemView()
                .getDefaultDirectory().toPath().resolve("Jarvis").toAbsolutePath();
        Path pasta = Path.of(valor);
        return (pasta.isAbsolute() ? pasta : arquivo.toAbsolutePath().getParent().resolve(pasta))
                .toAbsolutePath().normalize();
    }

    public Configuracao(URI api, String token, URI hud, Map<String, Path> apps,
            boolean palmas, boolean voz, String microfone, double sensibilidade,
            double probabilidade, String modo, boolean acordado, Path executavel, Path modelo) {
        this(api, token, hud, apps, palmas, voz, microfone, sensibilidade, probabilidade,
                modo, acordado, executavel, modelo, javax.swing.filechooser.FileSystemView
                        .getFileSystemView().getDefaultDirectory().toPath().resolve("Jarvis"));
    }

    /** Construtor mantido para configurações programáticas existentes. */
    public Configuracao(URI api, String token, URI hud, Map<String, Path> apps,
            boolean palmas, boolean voz, String microfone, double sensibilidade,
            double probabilidade, String modo, boolean acordado) {
        this(api, token, hud, apps, palmas, voz, microfone, sensibilidade, probabilidade,
                modo, acordado, Path.of("ferramentas/whisper/whisper-cli.exe").toAbsolutePath(),
                Path.of("modelos/ggml-base.bin").toAbsolutePath(),
                javax.swing.filechooser.FileSystemView.getFileSystemView()
                        .getDefaultDirectory().toPath().resolve("Jarvis"));
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

    private static int inteiro(Properties propriedades, String chave, int padrao, int minimo, int maximo)
            throws IOException {
        String texto = texto(propriedades, chave);
        if (texto.isBlank()) return padrao;
        try {
            int valor = Integer.parseInt(texto);
            if (valor >= minimo && valor <= maximo) return valor;
        } catch (NumberFormatException ignorada) { }
        throw new IOException(chave + " fora do intervalo permitido");
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
