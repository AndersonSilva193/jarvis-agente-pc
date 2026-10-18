package dev.jarvis.agente;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;

/** Parada fisica: Ctrl+Alt+Shift+F12, independente da VPS e do provedor. */
final class ParadaLocalComputer {
    private static final Logger log = Logger.getLogger(ParadaLocalComputer.class.getName());
    private static final int ID = 0x4a14;
    private static final int MODIFICADORES = 0x0001 | 0x0002 | 0x0004;
    private static final AtomicBoolean parada = new AtomicBoolean();
    private static volatile boolean atalhoAtivo;
    private ParadaLocalComputer() { }

    static void iniciar() {
        Thread escuta = new Thread(() -> {
            if (!User32.INSTANCE.RegisterHotKey(null, ID, MODIFICADORES, KeyEvent.VK_F12)) {
                log.severe("atalho local de parada indisponivel; computer use bloqueado");
                return;
            }
            atalhoAtivo = true;
            try {
                WinUser.MSG mensagem = new WinUser.MSG();
                while (User32.INSTANCE.GetMessage(mensagem, null, 0, 0) > 0) {
                    if (mensagem.message == 0x0312) parar();
                }
            } finally {
                atalhoAtivo = false;
                User32.INSTANCE.UnregisterHotKey(null, ID);
            }
        }, "parada-local-computer");
        escuta.setDaemon(true);
        escuta.start();
        if (SystemTray.isSupported()) {
            try {
                BufferedImage imagem = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = imagem.createGraphics();
                g.setColor(Color.RED); g.fillOval(2, 2, 12, 12); g.dispose();
                PopupMenu menu = new PopupMenu();
                MenuItem parar = new MenuItem("Parar computer use");
                parar.addActionListener(evento -> parar());
                menu.add(parar);
                MenuItem liberar = new MenuItem("Permitir nova tarefa");
                liberar.addActionListener(evento -> parada.set(false));
                menu.add(liberar);
                TrayIcon icone = new TrayIcon(imagem, "Jarvis computer use", menu);
                icone.setImageAutoSize(true);
                SystemTray.getSystemTray().add(icone);
            } catch (Exception e) {
                log.warning("icone de parada indisponivel: " + e.getClass().getSimpleName());
            }
        }
    }
    static void parar() { parada.set(true); log.warning("computer use parado localmente"); }
    static boolean permite() { return atalhoAtivo && !parada.get(); }
}
