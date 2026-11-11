package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A leitura do config.properties, com foco no que o despertar acrescentou.
 *
 * <p>A regra aqui é sempre a mesma: valor torto avisa e cai no padrão, nunca derruba o agente. Um
 * erro de digitação numa linha de sensibilidade não pode deixar o PC sem braço até alguém
 * perceber.
 */
class ConfiguracaoTest {

    @TempDir
    Path pasta;

    private Configuracao ler(String conteudo) throws IOException {
        Path arquivo = pasta.resolve("config.properties");
        Files.writeString(arquivo, "urlApi=https://jarvis.exemplo\npcToken=segredo\n" + conteudo);
        return Configuracao.ler(arquivo);
    }

    @Test void caminhosPartemDoArquivoNaoDoDiretorioAtual() throws Exception {
        Configuracao config = ler("voz.executavel=bin/whisper-cli.exe\nvoz.modelo=modelos/base.bin\n");
        assertEquals(pasta.resolve("bin/whisper-cli.exe").toAbsolutePath(), config.vozExecutavel());
        assertEquals(pasta.resolve("modelos/base.bin").toAbsolutePath(), config.vozModelo());
    }

    @Test
    @DisplayName("sem configuração, palmas ficam desativadas e a voz continua habilitada")
    void padroes() throws Exception {
        Configuracao config = ler("");

        assertFalse(config.gatilhoPalmas());
        assertTrue(config.gatilhoVoz());
        assertEquals("", config.microfone(), "vazio = o padrão do Windows");
        assertEquals(Configuracao.SENSIBILIDADE_PADRAO, config.palmasSensibilidadeDb());
        assertEquals(Configuracao.CONFIANCA_PADRAO, config.vozConfiancaMinima());
        assertEquals(Configuracao.MODO_APP, config.hudModo());
        assertFalse(config.kiosk());
        assertTrue(config.manterPcAcordado());
        assertFalse(config.computerUseAtivo());
        assertTrue(config.reportarFoco(), "sem a linha, o X-Pc-Foco vai");
    }

    @Test
    @DisplayName("reportarFoco: false/não desligam; valor torto cai no padrão true")
    void reportarFoco() throws Exception {
        assertTrue(ler("reportarFoco=true\n").reportarFoco());
        assertFalse(ler("reportarFoco=false\n").reportarFoco());
        assertFalse(ler("reportarFoco=nao\n").reportarFoco());
        assertFalse(ler("reportarFoco=0\n").reportarFoco());
        assertTrue(ler("reportarFoco=talvez\n").reportarFoco());
        assertTrue(ler("reportarFoco=\n").reportarFoco());
    }

    @Test
    @DisplayName("o config.properties.example traz reportarFoco=true")
    void exemploTrazReportarFoco() throws Exception {
        Path exemplo = Path.of("config.properties.example");
        java.util.Properties propriedades = new java.util.Properties();
        try (var entrada = Files.newInputStream(exemplo)) {
            propriedades.load(entrada);
        }
        assertEquals("true", propriedades.getProperty("reportarFoco"));
    }

    @Test
    @DisplayName("desativar palmas mantém o despertar por voz")
    void despertarPorVozSemPalmas() throws Exception {
        Configuracao config = ler("urlHud=https://hud.exemplo\n");

        assertFalse(config.gatilhoPalmas());
        assertTrue(config.gatilhoVoz());
        assertTrue(config.despertarLigado());
    }

    @Test
    @DisplayName("sem urlHud não há despertar, mesmo com os gatilhos ligados")
    void semHudNaoHaDespertar() throws Exception {
        // Não há para onde acordar: abrir nada seria pior do que não escutar.
        assertFalse(ler("").despertarLigado());
        assertTrue(ler("urlHud=https://hud.exemplo\n").despertarLigado());
        assertFalse(ler("urlHud=https://hud.exemplo\ngatilho.palmas=false\ngatilho.voz=false\n")
                .despertarLigado());
    }

    @Test
    @DisplayName("valores tortos avisam e caem no padrão, sem derrubar o agente")
    void valorTortoCaiNoPadrao() throws Exception {
        Configuracao config = ler("""
                gatilho.palmas=talvez
                palmas.sensibilidadeDb=muito alto
                voz.probabilidadeMinima=7
                hud.modo=telona
                energia.manterPcAcordado=
                """);

        assertFalse(config.gatilhoPalmas());
        assertEquals(Configuracao.SENSIBILIDADE_PADRAO, config.palmasSensibilidadeDb());
        // 7 está fora de [0, 1]: confiança não é porcentagem.
        assertEquals(Configuracao.CONFIANCA_PADRAO, config.vozConfiancaMinima());
        assertEquals(Configuracao.MODO_APP, config.hudModo());
        assertTrue(config.manterPcAcordado());
    }

    @Test
    @DisplayName("aceita os valores bons, inclusive vírgula decimal e 'sim'/'não'")
    void valoresBons() throws Exception {
        Configuracao config = ler("""
                gatilho.voz=nao
                palmas.sensibilidadeDb=24
                voz.probabilidadeMinima=0,85
                hud.modo=KIOSK
                energia.manterPcAcordado=false
                microfone=Blue Yeti
                computer.use.ativo=true
                """);

        assertFalse(config.gatilhoVoz());
        assertEquals(24, config.palmasSensibilidadeDb());
        // Vírgula porque é o que o teclado brasileiro produz sem pensar.
        assertEquals(0.85, config.vozConfiancaMinima());
        assertTrue(config.kiosk());
        assertFalse(config.manterPcAcordado());
        assertEquals("Blue Yeti", config.microfone());
        assertTrue(config.computerUseAtivo());
    }

    @Test
    @DisplayName("IA local: desligada por padrão, com os valores combinados")
    void iaPadroes() throws Exception {
        Configuracao.ConfigIa ia = ler("").ia();
        assertFalse(ia.ativo());
        assertEquals("http://127.0.0.1:11434", ia.url().toString());
        assertEquals("qwen3.5:9b", ia.modelo());
        assertEquals(16384, ia.contexto());
        assertEquals("20m", ia.keepAlive());
        assertEquals(90, ia.timeoutSegundos());
        assertEquals(256 * 1024, ia.maxBytes());
    }

    @Test
    @DisplayName("ollama.url só local: o resto desliga a IA sem derrubar o agente")
    void iaUrlSoLocal() throws Exception {
        for (String url : new String[] {"http://192.168.0.10:11434", "http://localhost.evil.com:11434",
                "http://127.0.0.1.nip.io:11434", "http://u@127.0.0.1:11434", "https://evil.com",
                "http://[::1]:11434", "http://127.0.0.1:11434/api?x=1", "file:///C:/ollama", "ftp://127.0.0.1"}) {
            Configuracao config = ler("ollama.ativo=true\nollama.url=" + url + "\n");
            assertFalse(config.ia().ativo(), url);
            assertEquals("http://127.0.0.1:11434", config.ia().url().toString(), url);
        }
        Configuracao.ConfigIa local = ler("ollama.ativo=true\nollama.url=http://LOCALHOST:11500/\n").ia();
        assertTrue(local.ativo());
        assertEquals("http://127.0.0.1:11500", local.url().toString(), "localhost vira 127.0.0.1");
    }

    @Test void iaValoresERecusas() throws Exception {
        Configuracao.ConfigIa ia = ler("""
                ollama.ativo=true
                ollama.modelo=llama3.1:8b
                ollama.contexto=8192
                ollama.keepAlive=-1
                ollama.timeoutSegundos=30
                inferencia.maxKib=512
                """).ia();
        assertTrue(ia.ativo());
        assertEquals("llama3.1:8b", ia.modelo());
        assertEquals(8192, ia.contexto());
        assertEquals("-1", ia.keepAlive());
        assertEquals(30, ia.timeoutSegundos());
        assertEquals(512 * 1024, ia.maxBytes());

        assertEquals("qwen3.5:9b", ler("ollama.modelo=x;rm -rf\n").ia().modelo());
        assertEquals("20m", ler("ollama.keepAlive=para sempre\n").ia().keepAlive());
        assertThrows(IOException.class, () -> ler("inferencia.maxKib=0\n"));
        assertThrows(IOException.class, () -> ler("ollama.contexto=999999999\n"));
    }

    @Test
    @DisplayName("o que é obrigatório continua obrigatório, e a URL continua só https")
    void obrigatoriosEHttps() throws Exception {
        Path arquivo = pasta.resolve("config.properties");

        Files.writeString(arquivo, "urlApi=https://jarvis.exemplo\n");
        assertTrue(assertThrows(IOException.class, () -> Configuracao.ler(arquivo))
                .getMessage().contains("pcToken"));

        // http simples vazaria o pcToken na rede.
        Files.writeString(arquivo, "urlApi=http://jarvis.exemplo\npcToken=segredo\n");
        assertTrue(assertThrows(IOException.class, () -> Configuracao.ler(arquivo))
                .getMessage().contains("https"));
    }
}
