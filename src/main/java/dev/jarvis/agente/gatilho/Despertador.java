package dev.jarvis.agente.gatilho;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import dev.jarvis.agente.Configuracao;

/**
 * O despertar: duas palmas ou "Jarvis, iniciar" abrem a tela, e a tela se apresenta falando.
 *
 * <h2>Os dois estados</h2>
 *
 * <b>REPOUSO</b> — o microfone está aberto e o mesmo áudio alimenta o {@link DetectorDePalmas} e o
 * {@link ReconhecedorDeVoz}. Nada sai desta máquina: o reconhecimento é todo local.
 *
 * <p><b>ATIVO</b> — a tela está aberta e o microfone é <b>solto</b>. Quem escuta o "Jarvis" agora
 * é a própria HUD, pelo navegador. Soltar não é economia: com os dois escutando, a voz do Jarvis
 * saindo nas caixas voltaria pelo microfone e o detector ouviria a si mesmo.
 *
 * <p>Volta a REPOUSO quando a janela fecha (o {@code Process} do Chrome morre) ou quando a VPS
 * manda {@code modo_repouso}.
 *
 * <h2>Tela já aberta</h2>
 *
 * Bater palmas com a HUD na frente não abre outra janela: traz esta para a frente e pede ao Jarvis
 * que diga "Já estou aqui, senhor.". Esse pedido é a <b>única</b> vez em que o agente fala com a
 * VPS sem ter recebido um comando antes.
 */
public final class Despertador implements Runnable, AutoCloseable {

    private static final Logger log = Logger.getLogger(Despertador.class.getName());

    /** Microfone sumiu? Tenta de novo daqui a pouco, sem derrubar o agente. */
    private static final long ESPERA_PARA_REABRIR_MS = 5_000;

    /** Quem leva o pedido de fala à VPS. Mantém o {@code ClienteJarvis} fora deste pacote. */
    @FunctionalInterface
    public interface PedidoDeFala {
        /** @param chave a chave de uma frase do catálogo da VPS, nunca texto livre */
        void pedir(String chave);
    }

    private enum Estado {
        REPOUSO, ATIVO
    }

    private final Configuracao config;
    private final JanelaDaHud janela;
    private final EnergiaDoWindows energia;
    private final DiarioDeGatilhos diario = new DiarioDeGatilhos();
    private final PedidoDeFala pedidoDeFala;

    private final Microfone microfone;
    private final DetectorDePalmas palmas;
    private final ReconhecedorDeVoz voz;

    private final AtomicBoolean rodando = new AtomicBoolean(true);
    private volatile Estado estado = Estado.REPOUSO;
    private final short[] bloco = new short[Microfone.AMOSTRAS_POR_LEITURA];

    public Despertador(Configuracao config, JanelaDaHud janela, EnergiaDoWindows energia,
            PedidoDeFala pedidoDeFala) {
        this.config = config;
        this.janela = janela;
        this.energia = energia;
        this.pedidoDeFala = pedidoDeFala;
        this.microfone = new Microfone(config.microfone());
        this.palmas = config.gatilhoPalmas()
                ? new DetectorDePalmas(config.palmasSensibilidadeDb())
                : null;
        this.voz = config.gatilhoVoz()
                ? ReconhecedorDeVoz.tentarCriar(Configuracao.PASTA_DOS_MODELOS,
                        config.vozConfiancaMinima())
                : null;
    }

    /** Verdadeiro quando há pelo menos um gatilho de pé para escutar. */
    public boolean temGatilho() {
        return palmas != null || voz != null;
    }

    /** Faz o agente voltar a escutar o gatilho, fechando a tela. Vem do comando modo_repouso. */
    public void repousar() {
        if (estado == Estado.REPOUSO) {
            return;
        }
        log.info("voltando ao repouso a pedido");
        janela.fechar();
    }

    /** Conta um alarme falso e devolve o total, que o Jarvis fala de volta. */
    public int falsoPositivo() {
        return diario.falsoPositivo();
    }

    @Override
    public void run() {
        log.info("escutando o gatilho: "
                + (palmas != null ? "duas palmas" : "")
                + (palmas != null && voz != null ? " ou " : "")
                + (voz != null ? "\"Jarvis, iniciar\"" : ""));
        while (rodando.get()) {
            try {
                if (estado == Estado.ATIVO) {
                    esperarATelaFechar();
                } else {
                    escutar();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.warning("o despertar tropeçou (" + e + "); continuo escutando");
                dormir(ESPERA_PARA_REABRIR_MS);
            }
        }
        microfone.fechar();
        log.info("despertar encerrado");
    }

    /** REPOUSO: abre o microfone se preciso, lê e deixa os dois gatilhos julgarem. */
    private void escutar() throws InterruptedException {
        energia.pedir(config.manterPcAcordado()
                ? EnergiaDoWindows.Estado.ESCUTANDO
                : EnergiaDoWindows.Estado.LIVRE);

        if (!microfone.abrir()) {
            dormir(ESPERA_PARA_REABRIR_MS);
            return;
        }
        int lidas = microfone.ler(bloco);
        if (lidas < 0) {
            // A linha caiu — USB desconectado, driver reiniciando. Tenta de novo daqui a pouco.
            dormir(ESPERA_PARA_REABRIR_MS);
            return;
        }
        if (lidas == 0) {
            return;
        }

        if (palmas != null) {
            Optional<DetectorDePalmas.Disparo> disparo = palmas.alimentar(bloco, lidas);
            if (disparo.isPresent()) {
                acordar(DiarioDeGatilhos.Origem.PALMAS, disparo.get().acimaDoRuidoDb());
                return;
            }
        }
        if (voz != null) {
            Optional<ReconhecedorDeVoz.Reconhecimento> ouvido = voz.alimentar(bloco, lidas);
            if (ouvido.isPresent()) {
                acordar(DiarioDeGatilhos.Origem.VOZ, ouvido.get().confianca());
            }
        }
    }

    /**
     * O gatilho aconteceu.
     *
     * <p>Com a tela fechada, abre. Com a tela aberta, só traz para a frente e pede a frase curta —
     * abrir outra janela por cima da que o usuário já está olhando seria o pior resultado.
     */
    private void acordar(DiarioDeGatilhos.Origem origem, double medida) {
        boolean jaEstavaAberta = janela.aberta();
        diario.disparou(origem, medida, jaEstavaAberta);

        if (jaEstavaAberta) {
            janela.trazerParaFrente();
            pedidoDeFala.pedir("ja_estou_aqui");
            return;
        }
        if (!janela.abrir()) {
            // Não abriu (falta chrome ou urlHud): continua escutando, já avisado no log.
            return;
        }
        entrarEmAtivo();
    }

    private void entrarEmAtivo() {
        estado = Estado.ATIVO;
        // Solta o microfone: daqui em diante quem escuta é a tela.
        microfone.fechar();
        if (voz != null) {
            voz.reiniciar();
        }
        energia.pedir(EnergiaDoWindows.Estado.TELA_ABERTA);
    }

    /** ATIVO: não há nada a fazer além de esperar a janela fechar. */
    private void esperarATelaFechar() throws InterruptedException {
        janela.aguardarFechar();
        log.info("a tela fechou; voltando a escutar o gatilho");
        estado = Estado.REPOUSO;
    }

    private static void dormir(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        rodando.set(false);
        microfone.fechar();
        if (voz != null) {
            voz.close();
        }
    }
}
