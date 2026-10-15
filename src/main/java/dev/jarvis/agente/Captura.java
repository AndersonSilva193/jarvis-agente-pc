package dev.jarvis.agente;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** O print da tela, reduzido para caber com folga no limite da VPS e do Telegram. */
final class Captura {

    /** Largura máxima do PNG. Deixa o print em algumas centenas de KB, longe dos 8 MB da VPS. */
    private static final int MAX_LARGURA = 1600;

    private Captura() {
    }

    /**
     * Tira o print de <b>todos</b> os monitores e devolve o PNG.
     *
     * @throws AWTException quando o agente não está na sessão interativa do usuário — que é
     *                      exatamente o sintoma de ter sido instalado como serviço em vez de no
     *                      logon, e por isso vira mensagem explicando isso
     */
    static byte[] tirar() throws AWTException, IOException {
        Rectangle uniao = null;
        for (GraphicsDevice monitor : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getScreenDevices()) {
            Rectangle limites = monitor.getDefaultConfiguration().getBounds();
            uniao = uniao == null ? limites : uniao.union(limites);
        }
        if (uniao == null || uniao.isEmpty()) {
            throw new AWTException("nenhum monitor visível para este processo");
        }
        return paraPng(new Robot().createScreenCapture(uniao));
    }

    /**
     * Reescala e tira o canal alpha.
     *
     * <p>O redesenho acontece mesmo quando a imagem já é pequena: {@code TYPE_INT_RGB} dá um PNG
     * de 24 bits, bem menor que o de 32 bits que vem do {@code Robot}, e a tela não tem
     * transparência para preservar.
     */
    private static byte[] paraPng(BufferedImage bruta) throws IOException {
        int largura = Math.min(bruta.getWidth(), MAX_LARGURA);
        int altura = Math.max(1, Math.round(bruta.getHeight() * (largura / (float) bruta.getWidth())));

        BufferedImage menor = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D desenho = menor.createGraphics();
        try {
            desenho.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            desenho.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            desenho.drawImage(bruta, 0, 0, largura, altura, null);
        } finally {
            desenho.dispose();
        }

        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        if (!ImageIO.write(menor, "png", saida)) {
            throw new IOException("esta JVM não sabe escrever PNG");
        }
        return saida.toByteArray();
    }

    /** Verdadeiro quando nem vale tentar: sem tela, o Robot só lançaria exceção. */
    static boolean semTela() {
        return GraphicsEnvironment.isHeadless() || temHeadlessException();
    }

    private static boolean temHeadlessException() {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            return false;
        } catch (HeadlessException e) {
            return true;
        }
    }
}
