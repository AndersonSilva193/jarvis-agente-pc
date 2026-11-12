package dev.jarvis.agente;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import tools.jackson.core.JacksonException;

/** As ações rápidas da empresa: abrir o escritório e consultar o quadro. */
final class AcoesDaEmpresa {

    static final Duration ESPERA_DO_STUDIO = Duration.ofSeconds(20);
    static final String SEM_HERMES3D = "Hermes3D não configurado neste PC";
    static final String SEM_RESPOSTA = "Não consegui falar com o Hermes3D";

    /** Fixo de propósito: a VPS não escolhe o que abrir com permissão de http. */
    private static final URI ESCRITORIO = URI.create("http://localhost:3000/office");
    private static final int PORTA_DO_STUDIO = 3000;
    private static final Duration PASSO_DA_ESPERA = Duration.ofMillis(200);

    @FunctionalInterface
    interface Navegador {
        void abrir(URI endereco) throws Exception;
    }

    private final boolean ativa;
    private final ClienteEmpresa cliente;
    private final Navegador navegador;
    private final SupervisorHermes3d.PortaAberta porta;
    private final Duration esperaDoStudio;

    AcoesDaEmpresa(ClienteEmpresa cliente, Navegador navegador, SupervisorHermes3d.PortaAberta porta,
            Duration esperaDoStudio) {
        this(true, cliente, navegador, porta, esperaDoStudio);
    }

    private AcoesDaEmpresa(boolean ativa, ClienteEmpresa cliente, Navegador navegador,
            SupervisorHermes3d.PortaAberta porta, Duration esperaDoStudio) {
        this.ativa = ativa;
        this.cliente = cliente;
        this.navegador = navegador;
        this.porta = porta;
        this.esperaDoStudio = esperaDoStudio;
    }

    static AcoesDaEmpresa de(Configuracao config) {
        if (!config.hermes3d().ativo()) {
            return new AcoesDaEmpresa(false, null, null, null, Duration.ZERO);
        }
        return new AcoesDaEmpresa(new ClienteEmpresa(ClienteEmpresa.ENDERECO_PADRAO, config.hermes3d().token()),
                endereco -> Desktop.getDesktop().browse(endereco),
                SupervisorHermes3d::portaLocalAberta, ESPERA_DO_STUDIO);
    }

    Resultado abrir(ComandoRecebido comando) throws Exception {
        if (!ativa) return Resultado.falhou(comando, SEM_HERMES3D);
        long limite = System.nanoTime() + esperaDoStudio.toNanos();
        while (!porta.aberta(PORTA_DO_STUDIO)) {
            if (System.nanoTime() >= limite) {
                return Resultado.falhou(comando, "o escritório ainda está ligando");
            }
            Thread.sleep(PASSO_DA_ESPERA.toMillis());
        }
        navegador.abrir(ESCRITORIO);
        return Resultado.feito(comando, "abri o escritório no navegador");
    }

    Resultado status(ComandoRecebido comando) throws InterruptedException {
        if (!ativa) return Resultado.falhou(comando, SEM_HERMES3D);
        try {
            return Resultado.comDados(comando, cliente.status());
        } catch (IOException | JacksonException falha) {
            return Resultado.falhou(comando, SEM_RESPOSTA);
        }
    }
}
