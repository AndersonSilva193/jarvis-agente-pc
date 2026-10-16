package dev.jarvis.agente.gatilho;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import dev.jarvis.agente.Configuracao;
import dev.jarvis.agente.Processos;

/** Autoridade única para aberturas e fechamento, inclusive comandos remotos. */
public final class JanelaDaHud {
    public enum Estado { REPOUSO, ABRINDO, ATIVO, FECHANDO }
    public enum Abertura { ABERTA, JA_EXISTENTE, FALHA }
    interface Ambiente {
        List<Long> localizar();
        void iniciar(String[] comando) throws IOException;
        void focar(long janela);
        void fechar(long janela);
    }
    private static final Logger log = Logger.getLogger(JanelaDaHud.class.getName());
    private final Configuracao config;
    private final Ambiente ambiente;
    private final LongSupplier relogio;
    private Estado estado = Estado.REPOUSO;
    private long prazo;
    private long ausenteDesde = -1;
    private boolean aberturaPendente;
    private Runnable suspender = () -> {};
    private Runnable rearmar = () -> {};

    public JanelaDaHud(Configuracao config) {
        this(config, new Ambiente() {
            private final JanelasWindows windows = new JanelasWindows();
            public List<Long> localizar() { return windows.localizar(perfil()); }
            public void iniciar(String[] comando) throws IOException { Processos.iniciar(comando); }
            public void focar(long janela) { windows.focar(janela); }
            public void fechar(long janela) { windows.fechar(janela); }
        }, () -> System.nanoTime() / 1_000_000);
    }
    JanelaDaHud(Configuracao config, Ambiente ambiente, LongSupplier relogio) {
        this.config = config;
        this.ambiente = ambiente;
        this.relogio = relogio;
    }
    public synchronized void acompanhar(Runnable suspender, Runnable rearmar) {
        this.suspender = suspender;
        this.rearmar = rearmar;
        atualizar();
    }
    public synchronized Estado estado() { return estado; }
    public synchronized boolean emRepouso() { return estado == Estado.REPOUSO; }
    public synchronized boolean aberta() { return !ambiente.localizar().isEmpty(); }
    public synchronized Abertura abrir() {
        atualizar();
        if (estado != Estado.REPOUSO) {
            if (estado == Estado.FECHANDO) return Abertura.FALHA;
            trazerParaFrente();
            return Abertura.JA_EXISTENTE;
        }
        if (config.urlHud() == null || config.app("chrome") == null) return Abertura.FALHA;
        suspender.run();
        mudar(Estado.ABRINDO);
        aberturaPendente = true;
        prazo = relogio.getAsLong() + 15_000;
        try {
            ambiente.iniciar(new String[] {config.app("chrome").toString(), "--app=" + urlDeInicio(),
                    config.kiosk() ? "--kiosk" : "--start-fullscreen",
                    "--autoplay-policy=no-user-gesture-required", "--user-data-dir=" + perfil()});
            return Abertura.ABERTA;
        } catch (IOException e) {
            log.warning("falha ao iniciar a HUD: " + e.getClass().getSimpleName());
            mudar(Estado.FECHANDO);
            aberturaPendente = false;
            ausenteDesde = relogio.getAsLong();
            return Abertura.FALHA;
        }
    }
    public synchronized void trazerParaFrente() {
        ambiente.localizar().stream().findFirst().ifPresent(ambiente::focar);
    }
    public synchronized void fechar() {
        suspender.run();
        mudar(Estado.FECHANDO);
        prazo = relogio.getAsLong() + 15_000;
        ausenteDesde = -1;
        ambiente.localizar().forEach(ambiente::fechar);
    }
    /** Janela minimizada continua presente. Não depende da duração do processo lançador. */
    public synchronized void atualizar() {
        long agora = relogio.getAsLong();
        List<Long> janelas = ambiente.localizar();
        if (!janelas.isEmpty()) {
            aberturaPendente = false;
            ausenteDesde = -1;
            if (estado == Estado.FECHANDO) {
                janelas.forEach(ambiente::fechar);
                if (agora >= prazo) {
                    log.warning("a HUD ainda não fechou; gatilhos permanecem suspensos");
                    prazo = agora + 15_000;
                }
            } else if (estado != Estado.ATIVO) {
                suspender.run();
                mudar(Estado.ATIVO);
            }
            return;
        }
        if (estado == Estado.REPOUSO) return;
        if (estado == Estado.FECHANDO && aberturaPendente && agora < prazo) return;
        if (estado == Estado.ABRINDO && agora < prazo) return;
        if (estado == Estado.ABRINDO) log.warning("a janela da HUD não apareceu em 15 segundos");
        if (estado != Estado.FECHANDO) mudar(Estado.FECHANDO);
        if (ausenteDesde < 0) ausenteDesde = agora;
        if (agora - ausenteDesde >= 1_000) {
            rearmar.run();
            mudar(Estado.REPOUSO);
            ausenteDesde = -1;
        }
    }
    private void mudar(Estado novo) {
        if (estado != novo) log.info("HUD: " + estado + " -> " + novo);
        estado = novo;
    }
    String urlDeInicio() {
        String url = config.urlHud().toString();
        int hash = url.indexOf('#');
        String fragmento = hash < 0 ? "" : url.substring(hash);
        String base = hash < 0 ? url : url.substring(0, hash);
        base = base.replaceAll("([?&])inicio=[^&#]*", "$1").replace("?&", "?")
                .replaceAll("[?&]$", "");
        return base + (base.contains("?") ? "&" : "?") + "inicio=1" + fragmento;
    }
    static String perfil() {
        String local = System.getenv("LOCALAPPDATA");
        return local == null || local.isBlank() ? Path.of(System.getProperty("user.home"), ".jarvis-hud").toString()
                : Path.of(local, "JarvisHud").toString();
    }
}
