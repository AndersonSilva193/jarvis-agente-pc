package dev.jarvis.agente.gatilho;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

import dev.jarvis.agente.Configuracao;
import dev.jarvis.agente.Processos;

/**
 * A janela do Chrome com a tela do Jarvis.
 *
 * <p>Abre com {@code --app=}, sem barra de endereço, em tela cheia, com o autoplay liberado (é o
 * que deixa a HUD falar sem alguém clicar antes) e — o detalhe que faz tudo funcionar — com
 * <b>perfil próprio</b>.
 *
 * <p>O perfil próprio resolve dois problemas de uma vez. Ele não mistura abas, histórico nem
 * sessões com o Chrome do dia a dia; e, principalmente, faz o processo durar enquanto a janela
 * estiver aberta. Com o perfil normal, o Chrome já rodando receberia a URL e o processo novo
 * morreria no mesmo instante — o agente acharia que a tela fechou um segundo depois de abri-la, e
 * voltaria a escutar com a HUD na cara do usuário.
 *
 * <p>Em troca, esse perfil nasce vazio: a HUD guarda a URL da API e o token no {@code
 * localStorage}, que é por perfil. Na instalação isso é preenchido uma vez, à mão, na tela de
 * configuração — e fica. Ver o README.
 */
public final class JanelaDaHud {

    private static final Logger log = Logger.getLogger(JanelaDaHud.class.getName());

    /** A marca que faz a HUD tocar a sequência de abertura em vez de só aparecer. */
    static final String PARAMETRO_DE_INICIO = "inicio=1";

    private final Configuracao config;

    /**
     * O Chrome da tela, visto por duas threads.
     *
     * <p>Quem abre e espera é a thread do despertar; quem fecha pode ser a do laço principal, no
     * {@code modo_repouso}. Daí o {@code volatile} e o cuidado de cada método copiar o campo para
     * uma variável local antes de usá-lo: ler o campo duas vezes — {@code if (chrome != null)} e
     * depois {@code chrome.waitFor()} — deixaria um {@code NullPointerException} esperando a hora
     * exata em que o outro lado anulasse no meio.
     */
    private volatile Process chrome;

    public JanelaDaHud(Configuracao config) {
        this.config = config;
    }

    /** Verdadeiro enquanto a janela estiver na tela. */
    public boolean aberta() {
        Process atual = chrome;
        return atual != null && atual.isAlive();
    }

    /**
     * Abre a tela, ou traz para a frente a que já está aberta.
     *
     * <p>{@code synchronized} porque duas threads chegam aqui: o despertar por palmas e o
     * {@code abrir_hud} vindo do Telegram. Sem isso, palmas no mesmo instante de um comando
     * abririam duas janelas e o agente só vigiaria uma.
     *
     * @return verdadeiro se <b>abriu agora</b>; falso se já estava aberta (e foi só focada) ou se
     *         não deu para abrir
     */
    public synchronized boolean abrir() {
        if (aberta()) {
            trazerParaFrente();
            return false;
        }
        Path executavel = chromeOuNulo();
        if (executavel == null) {
            return false;
        }
        try {
            chrome = Processos.iniciar(comando(executavel));
            log.info("tela aberta em " + (config.kiosk() ? "kiosk" : "app"));
            return true;
        } catch (IOException e) {
            log.warning("não consegui abrir a tela: " + e.getMessage());
            chrome = null;
            return false;
        }
    }

    /**
     * Traz a janela existente para a frente.
     *
     * <p>Chamar o Chrome de novo com o mesmo {@code --user-data-dir} e a mesma URL faz ele focar
     * a janela que já existe, em vez de abrir outra. Esse segundo processo morre sozinho logo
     * depois — e é por isso que ele não substitui o {@link #chrome} que estamos vigiando.
     */
    public void trazerParaFrente() {
        Path executavel = chromeOuNulo();
        if (executavel == null) {
            return;
        }
        try {
            Processos.disparar(comando(executavel));
        } catch (IOException e) {
            log.warning("não consegui trazer a tela para a frente: " + e.getMessage());
        }
    }

    /**
     * Espera a janela fechar. Volta na hora se ela já não estiver aberta.
     *
     * <p><b>Não</b> é {@code synchronized}: isto fica parado o tempo que a tela estiver aberta —
     * horas, se for o caso —, e segurar o monitor aqui travaria o {@link #fechar()} do
     * {@code modo_repouso} para sempre.
     */
    public void aguardarFechar() throws InterruptedException {
        Process atual = chrome;
        if (atual == null) {
            return;
        }
        atual.waitFor();
        // Só limpa se ninguém trocou a janela nesse meio-tempo; comparar evita apagar um Chrome
        // novo que o abrir() tenha posto aqui enquanto esperávamos o antigo morrer.
        synchronized (this) {
            if (chrome == atual) {
                chrome = null;
            }
        }
    }

    /** Fecha a janela, se estiver aberta — é o "Jarvis, modo repouso". */
    public synchronized void fechar() {
        Process atual = chrome;
        if (atual != null && atual.isAlive()) {
            atual.destroy();
        }
        chrome = null;
    }

    private Path chromeOuNulo() {
        if (config.urlHud() == null) {
            log.warning("falta urlHud no config.properties deste PC");
            return null;
        }
        Path executavel = config.app("chrome");
        if (executavel == null) {
            log.warning("falta app.chrome no config.properties deste PC");
        }
        return executavel;
    }

    private String[] comando(Path chrome) {
        // Argumentos fixos, como manda o Processos: o único pedaço variável é a URL da HUD, que
        // vem do config.properties desta máquina e foi validada como https na leitura.
        return new String[] {
                chrome.toString(),
                "--app=" + urlDeInicio(),
                config.kiosk() ? "--kiosk" : "--start-fullscreen",
                "--autoplay-policy=no-user-gesture-required",
                "--user-data-dir=" + perfil()
        };
    }

    /** A URL com o {@code ?inicio=1}, respeitando uma query que a URL já tenha. */
    String urlDeInicio() {
        String url = config.urlHud().toString();
        return url + (url.contains("?") ? "&" : "?") + PARAMETRO_DE_INICIO;
    }

    /** O perfil em {@code %LOCALAPPDATA%\JarvisHud}, separado do Chrome do dia a dia. */
    static String perfil() {
        String local = System.getenv("LOCALAPPDATA");
        return local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), ".jarvis-hud").toString()
                : Path.of(local, "JarvisHud").toString();
    }
}
