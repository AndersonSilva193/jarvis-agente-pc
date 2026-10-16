package dev.jarvis.agente.gatilho;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import dev.jarvis.agente.Configuracao;

/** Captura apenas em repouso; a autoridade do estado é a janela, também para comandos remotos. */
public final class Despertador implements Runnable, AutoCloseable {
    @FunctionalInterface public interface PedidoDeFala { void pedir(String chave); }
    private static final Logger log = Logger.getLogger(Despertador.class.getName());
    private final Configuracao config;
    private final JanelaDaHud janela;
    private final EnergiaDoWindows energia;
    private final DiarioDeGatilhos diario = new DiarioDeGatilhos();
    private final Microfone microfone;
    private final DetectorDePalmas palmas;
    private final ReconhecedorDeVoz voz;
    private final AtomicBoolean rodando = new AtomicBoolean(true);
    private volatile long geracao;

    public Despertador(Configuracao config, JanelaDaHud janela, EnergiaDoWindows energia, PedidoDeFala ignorado) {
        this.config = config; this.janela = janela; this.energia = energia;
        microfone = new Microfone(config.microfone());
        palmas = config.urlHud() != null && config.gatilhoPalmas() ? new DetectorDePalmas(config.palmasSensibilidadeDb()) : null;
        voz = config.urlHud() != null && config.gatilhoVoz() ? ReconhecedorDeVoz.tentarCriar(config) : null;
        janela.acompanhar(this::suspender, this::reiniciar);
    }
    public boolean temGatilho() { return palmas != null || voz != null; }
    public void repousar() { janela.fechar(); }
    public int falsoPositivo() { return diario.falsoPositivo(); }
    private void suspender() { geracao++; microfone.fechar(); reiniciar(); }
    private void reiniciar() {
        if (palmas != null) palmas.reiniciar();
        if (voz != null) voz.reiniciar();
    }
    public void run() {
        short[] bloco = new short[Microfone.AMOSTRAS_POR_LEITURA];
        long proximaVerificacao = 0;
        while (rodando.get() && !Thread.currentThread().isInterrupted()) {
            try {
                long agora = System.nanoTime() / 1_000_000;
                if (agora >= proximaVerificacao) { janela.atualizar(); proximaVerificacao = agora + 250; }
                long rodada;
                synchronized (janela) {
                    boolean repouso = janela.emRepouso();
                    energia.pedir(!repouso ? EnergiaDoWindows.Estado.TELA_ABERTA : config.manterPcAcordado()
                            ? EnergiaDoWindows.Estado.ESCUTANDO : EnergiaDoWindows.Estado.LIVRE);
                    rodada = geracao;
                    if (repouso && temGatilho() && !microfone.abrir()) throw new IllegalStateException("microfone indisponível");
                }
                if (!janela.emRepouso() || !temGatilho()) { Thread.sleep(100); continue; }
                int lidas = microfone.ler(bloco);
                synchronized (janela) {
                    if (rodada != geracao || !janela.emRepouso()) continue;
                    if (lidas <= 0) { reiniciar(); continue; }
                    if (palmas != null) {
                        var disparo = palmas.alimentar(bloco, lidas);
                        if (disparo.isPresent()) {
                            diario.disparou(DiarioDeGatilhos.Origem.PALMAS, disparo.get().acimaDoRuidoDb(), false);
                            janela.abrir(); continue;
                        }
                    }
                    if (voz != null) {
                        Optional<ReconhecedorDeVoz.Reconhecimento> disparo = voz.alimentar(bloco, lidas);
                        if (disparo.isPresent()) {
                            diario.disparou(DiarioDeGatilhos.Origem.VOZ, disparo.get().confianca(), false);
                            janela.abrir();
                        }
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (RuntimeException e) {
                log.warning("falha na escuta: " + e.getClass().getSimpleName());
                microfone.fechar();
                try { Thread.sleep(5_000); } catch (InterruptedException interrompido) { Thread.currentThread().interrupt(); }
            }
        }
        microfone.fechar();
    }
    public void close() {
        rodando.set(false); microfone.fechar();
        if (voz != null) voz.close();
    }
}
