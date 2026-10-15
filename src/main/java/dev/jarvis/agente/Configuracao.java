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
 */
record Configuracao(URI urlApi, String pcToken, URI urlHud, Map<String, Path> apps) {

    private static final Logger log = Logger.getLogger(Configuracao.class.getName());
    private static final String PREFIXO_APP = "app.";
    /** O mesmo formato que o PcService aceita do outro lado. */
    private static final String NOME_DE_APP = "[a-z0-9][a-z0-9_.-]{0,31}";

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

        URI urlApi = urlHttps(propriedades, "urlApi", true);
        String pcToken = texto(propriedades, "pcToken");
        if (pcToken.isEmpty()) {
            throw new IOException("pcToken vazio: ponha o mesmo PC_TOKEN que está no .env da VPS");
        }
        URI urlHud = urlHttps(propriedades, "urlHud", false);

        return new Configuracao(urlApi, pcToken, urlHud, apps(propriedades));
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

    /** Os nomes para o cabeçalho X-Pc-Apps, em ordem e separados por vírgula. */
    String cabecalhoDeApps() {
        return String.join(",", apps.keySet());
    }

    Path app(String nome) {
        return apps.get(nome == null ? "" : nome.trim().toLowerCase(Locale.ROOT));
    }

    private static String texto(Properties propriedades, String chave) {
        return propriedades.getProperty(chave, "").trim();
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
