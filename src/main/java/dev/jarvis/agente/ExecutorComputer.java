package dev.jarvis.agente;

import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import tools.jackson.databind.JsonNode;

/** Executor unico de acoes de mouse/teclado, limitado ao monitor principal. */
final class ExecutorComputer {
    private static final int MAX_LARGURA = 1600;
    private final Object bloqueio = new Object();
    private String sessao;
    private Rectangle geometria;
    private double escalaX, escalaY;
    private Point cursorEsperado;
    private Integer ultimoInput;

    Resultado executar(ComandoRecebido comando) {
        synchronized (bloqueio) {
            try {
                if (!ParadaLocalComputer.permite())
                    return Resultado.falhou(comando, "parada local ativa ou atalho indisponivel");
                JsonNode dados = comando.dados();
                String idSessao = dados.path("sessao").asText("");
                String nome = dados.path("nome").asText("");
                JsonNode entrada = dados.path("entrada");
                if (!idSessao.matches("[0-9a-fA-F-]{36}"))
                    return Resultado.falhou(comando, "sessao invalida");
                GraphicsDevice principal = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice();
                Rectangle atual = principal.getDefaultConfiguration().getBounds();
                double escalaAtualX = principal.getDefaultConfiguration().getDefaultTransform().getScaleX();
                double escalaAtualY = principal.getDefaultConfiguration().getDefaultTransform().getScaleY();
                if (atual.isEmpty()) return Resultado.falhou(comando, "monitor principal indisponivel");
                if (!idSessao.equals(sessao)) {
                    if (!"screenshot".equals(nome))
                        return Resultado.falhou(comando, "nova sessao deve comecar com captura");
                    sessao = idSessao; geometria = new Rectangle(atual);
                    escalaX = escalaAtualX; escalaY = escalaAtualY;
                    cursorEsperado = null; ultimoInput = null;
                } else if (!atual.equals(geometria) || escalaAtualX != escalaX
                        || escalaAtualY != escalaY)
                    return Resultado.falhou(comando, "geometria ou escala do monitor mudou; tarefa pausada");
                if (cursorEsperado != null && !"screenshot".equals(nome)) {
                    Point real = MouseInfo.getPointerInfo().getLocation();
                    if (real.distance(cursorEsperado) > 8)
                        return Resultado.falhou(comando, "interferencia do usuario no mouse; tarefa pausada");
                }
                if (ultimoInput != null && !"screenshot".equals(nome)
                        && ultimoInput.intValue() != ultimoInputWindows())
                    return Resultado.falhou(comando, "interferencia do usuario no teclado ou mouse");
                Robot robo = new Robot(principal);
                robo.setAutoDelay(35);
                acao(robo, nome, entrada, atual);
                if (!ParadaLocalComputer.permite())
                    return Resultado.falhou(comando, "parada local acionada");
                robo.waitForIdle();
                if (!principal.getDefaultConfiguration().getBounds().equals(geometria)
                        || principal.getDefaultConfiguration().getDefaultTransform().getScaleX() != escalaX
                        || principal.getDefaultConfiguration().getDefaultTransform().getScaleY() != escalaY)
                    return Resultado.falhou(comando, "geometria ou escala do monitor mudou; tarefa pausada");
                cursorEsperado = MouseInfo.getPointerInfo().getLocation();
                ultimoInput = ultimoInputWindows();
                byte[] png = captura(robo, atual);
                return new Resultado(comando.id(), true, "acao executada",
                        Base64.getEncoder().encodeToString(png));
            } catch (Exception e) {
                return Resultado.falhou(comando, "acao de tela recusada: " + e.getClass().getSimpleName());
            }
        }
    }

    private static int ultimoInputWindows() {
        WinUser.LASTINPUTINFO info = new WinUser.LASTINPUTINFO();
        if (!User32.INSTANCE.GetLastInputInfo(info))
            throw new IllegalStateException("Windows nao informou a ultima interacao");
        return info.dwTime;
    }

    private static void acao(Robot robo, String nome, JsonNode entrada, Rectangle tela)
            throws Exception {
        switch (nome) {
            case "screenshot" -> { }
            case "left_click", "click", "right_click", "double_click" -> {
                int[] xy = coordenada(entrada, tela);
                robo.mouseMove(xy[0], xy[1]);
                int botao = "right_click".equals(nome) || "right".equals(entrada.path("button").asText())
                        ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
                int vezes = "double_click".equals(nome) ? 2 : 1;
                for (int i = 0; i < vezes; i++) { robo.mousePress(botao); robo.mouseRelease(botao); }
            }
            case "mouse_move", "move" -> {
                int[] xy = coordenada(entrada, tela); robo.mouseMove(xy[0], xy[1]);
            }
            case "left_click_drag", "drag" -> {
                JsonNode inicio = entrada.path("start_coordinate");
                JsonNode fim = entrada.path("coordinate");
                if ("drag".equals(nome) && entrada.path("path").isArray()
                        && entrada.path("path").size() >= 2) {
                    JsonNode caminho = entrada.path("path");
                    JsonNode primeiro = caminho.get(0), ultimo = caminho.get(caminho.size() - 1);
                    int[] a = escala(primeiro.path("x").asInt(-1), primeiro.path("y").asInt(-1), tela);
                    int[] b = escala(ultimo.path("x").asInt(-1), ultimo.path("y").asInt(-1), tela);
                    robo.mouseMove(a[0], a[1]); robo.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    try {
                        for (JsonNode ponto : caminho) {
                            if (!ParadaLocalComputer.permite()) throw new InterruptedException();
                            int[] xy = escala(ponto.path("x").asInt(-1), ponto.path("y").asInt(-1), tela);
                            robo.mouseMove(xy[0], xy[1]);
                        }
                    } finally { robo.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
                    break;
                }
                if (!inicio.isArray() || !fim.isArray()) throw new IllegalArgumentException();
                int[] a = escala(inicio.get(0).asInt(-1), inicio.get(1).asInt(-1), tela);
                int[] b = escala(fim.get(0).asInt(-1), fim.get(1).asInt(-1), tela);
                robo.mouseMove(a[0], a[1]); robo.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                try {
                    for (int i = 1; i <= 10; i++) {
                        if (!ParadaLocalComputer.permite()) throw new InterruptedException();
                        robo.mouseMove(a[0] + (b[0] - a[0]) * i / 10,
                                a[1] + (b[1] - a[1]) * i / 10);
                    }
                } finally { robo.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
            }
            case "scroll" -> {
                if (entrada.path("coordinate").isArray() || entrada.path("x").isNumber()) {
                    int[] xy = coordenada(entrada, tela);
                    robo.mouseMove(xy[0], xy[1]);
                }
                int quantidade = entrada.path("scroll_amount").isNumber()
                        ? entrada.path("scroll_amount").asInt()
                        : Math.max(1, Math.abs(entrada.path("scroll_y").asInt()) / 100);
                if (Math.abs(quantidade) > 10) throw new IllegalArgumentException();
                String direcao = entrada.path("scroll_direction").asText(
                        entrada.path("scroll_y").asInt() < 0 ? "up" : "down");
                robo.mouseWheel(("up".equals(direcao) ? -1 : 1) * quantidade);
            }
            case "type" -> digitar(robo, entrada.path("text").asText(""));
            case "key", "keypress" -> tecla(robo, entrada);
            case "wait" -> {
                double segundos = entrada.path("duration").asDouble(0);
                if (segundos < 0 || segundos > 10) throw new IllegalArgumentException();
                long fim = System.nanoTime() + (long) (segundos * 1_000_000_000L);
                while (System.nanoTime() < fim) {
                    if (!ParadaLocalComputer.permite()) throw new InterruptedException();
                    Thread.sleep(Math.min(100, Math.max(1, (fim - System.nanoTime()) / 1_000_000)));
                }
            }
            default -> throw new IllegalArgumentException("acao desconhecida");
        }
    }
    private static int[] coordenada(JsonNode entrada, Rectangle tela) {
        JsonNode c = entrada.path("coordinate");
        if (c.isArray() && c.size() == 2) return escala(c.get(0).asInt(-1), c.get(1).asInt(-1), tela);
        return escala(entrada.path("x").asInt(-1), entrada.path("y").asInt(-1), tela);
    }
    static int[] escala(int x, int y, Rectangle tela) {
        int[] dimensoes = dimensoes(tela);
        int largura = dimensoes[0], altura = dimensoes[1];
        if (x < 0 || y < 0 || x >= largura || y >= altura) throw new IllegalArgumentException();
        return new int[] {tela.x + (int) ((long) x * tela.width / largura),
                tela.y + (int) ((long) y * tela.height / altura)};
    }
    private static int[] dimensoes(Rectangle tela) {
        double fator = Math.min(1.0, Math.min(MAX_LARGURA / (double) tela.width,
                Math.min(2000.0 / tela.height,
                        Math.sqrt(3_000_000.0 / ((double) tela.width * tela.height)))));
        return new int[] {Math.max(1, (int) Math.round(tela.width * fator)),
                Math.max(1, (int) Math.round(tela.height * fator))};
    }
    private static void digitar(Robot robo, String texto) throws Exception {
        if (texto.isEmpty() || texto.length() > 1000 || texto.indexOf('\0') >= 0)
            throw new IllegalArgumentException();
        Clipboard area = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable anterior = area.getContents(null);
        try {
            area.setContents(new StringSelection(texto), null);
            robo.keyPress(KeyEvent.VK_CONTROL); robo.keyPress(KeyEvent.VK_V);
            robo.keyRelease(KeyEvent.VK_V); robo.keyRelease(KeyEvent.VK_CONTROL);
            robo.delay(150);
        } finally {
            if (anterior != null) area.setContents(anterior, null);
        }
    }
    private static void tecla(Robot robo, JsonNode entrada) {
        String texto = entrada.path("text").asText("");
        if (texto.isBlank() && entrada.path("keys").isArray()) {
            StringBuilder b = new StringBuilder();
            for (JsonNode chave : entrada.path("keys")) {
                if (!b.isEmpty()) b.append('+'); b.append(chave.asText());
            }
            texto = b.toString();
        }
        String[] partes = texto.toLowerCase(java.util.Locale.ROOT).split("\\+");
        if (partes.length > 3) throw new IllegalArgumentException();
        int[] codigos = new int[partes.length];
        for (int i = 0; i < partes.length; i++) codigos[i] = switch (partes[i]) {
            case "ctrl", "control" -> KeyEvent.VK_CONTROL;
            case "shift" -> KeyEvent.VK_SHIFT;
            case "alt" -> KeyEvent.VK_ALT;
            case "tab" -> KeyEvent.VK_TAB;
            case "return", "enter" -> KeyEvent.VK_ENTER;
            case "escape", "esc" -> KeyEvent.VK_ESCAPE;
            case "backspace" -> KeyEvent.VK_BACK_SPACE;
            case "delete" -> KeyEvent.VK_DELETE;
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            case "a" -> KeyEvent.VK_A;
            case "c" -> KeyEvent.VK_C;
            case "v" -> KeyEvent.VK_V;
            case "s" -> KeyEvent.VK_S;
            default -> throw new IllegalArgumentException("tecla nao permitida");
        };
        int repeticoes = entrada.path("repeat").asInt(1);
        if (repeticoes < 1 || repeticoes > 5) throw new IllegalArgumentException();
        for (int r = 0; r < repeticoes; r++) {
            for (int codigo : codigos) robo.keyPress(codigo);
            for (int i = codigos.length - 1; i >= 0; i--) robo.keyRelease(codigos[i]);
        }
    }
    private static byte[] captura(Robot robo, Rectangle tela) throws Exception {
        BufferedImage bruta = robo.createScreenCapture(tela);
        int[] tamanho = dimensoes(tela);
        int largura = tamanho[0], altura = tamanho[1];
        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagem.createGraphics();
        try { g.drawImage(bruta, 0, 0, largura, altura, null); }
        finally { g.dispose(); }
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageIO.write(imagem, "png", saida);
        if (saida.size() > 8_000_000) throw new IllegalArgumentException("captura grande demais");
        return saida.toByteArray();
    }
}
