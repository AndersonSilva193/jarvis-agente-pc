package dev.jarvis.agente.gatilho;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Só opera janelas Chrome cujo processo usa o perfil exclusivo da HUD. */
final class JanelasWindows {
    interface User32 extends StdCallLibrary {
        interface Enumerador extends StdCallCallback { int invoke(Pointer janela, Pointer dados); }
        int EnumWindows(Enumerador callback, Pointer dados);
        int IsWindowVisible(Pointer janela);
        int GetClassNameW(Pointer janela, char[] nome, int tamanho);
        int GetWindowThreadProcessId(Pointer janela, IntByReference pid);
        int ShowWindow(Pointer janela, int comando);
        int IsIconic(Pointer janela);
        int SetForegroundWindow(Pointer janela);
        int PostMessageW(Pointer janela, int mensagem, Pointer wparam, Pointer lparam);
    }
    interface Kernel32 extends StdCallLibrary {
        Pointer OpenProcess(int acesso, boolean herdar, int pid);
        boolean CloseHandle(Pointer handle);
    }
    interface NtDll extends StdCallLibrary {
        int NtQueryInformationProcess(Pointer processo, int classe, Pointer dados, int tamanho,
                IntByReference necessario);
    }
    private final User32 user = Native.load("user32", User32.class);
    private final Kernel32 kernel = Native.load("kernel32", Kernel32.class);
    private final NtDll nt = Native.load("ntdll", NtDll.class);

    List<Long> localizar(String perfil) {
        List<Long> janelas = new ArrayList<>();
        user.EnumWindows((janela, ignorado) -> {
            if (user.IsWindowVisible(janela) == 0) return 1;
            char[] classe = new char[256];
            user.GetClassNameW(janela, classe, classe.length);
            if (!Native.toString(classe).startsWith("Chrome_WidgetWin")) return 1;
            IntByReference pid = new IntByReference();
            user.GetWindowThreadProcessId(janela, pid);
            if (usaPerfil(linhaDeComando(pid.getValue()), perfil)) janelas.add(Pointer.nativeValue(janela));
            return 1;
        }, null);
        return List.copyOf(janelas);
    }
    private String linhaDeComando(int pid) {
        Pointer processo = kernel.OpenProcess(0x1000, false, pid);
        if (processo == null) return "";
        try (Memory dados = new Memory(131072)) {
            if (nt.NtQueryInformationProcess(processo, 60, dados, (int) dados.size(),
                    new IntByReference()) != 0) return "";
            int bytes = Short.toUnsignedInt(dados.getShort(0));
            Pointer texto = dados.getPointer(Native.POINTER_SIZE == 8 ? 8 : 4);
            return texto == null ? "" : new String(texto.getByteArray(0, bytes),
                    java.nio.charset.StandardCharsets.UTF_16LE);
        } finally { kernel.CloseHandle(processo); }
    }
    static boolean usaPerfil(String comando, String perfil) {
        String normalizado = comando.replace("\"", "").replace('/', '\\').toLowerCase(Locale.ROOT);
        String argumento = "--user-data-dir=" + perfil.replace('/', '\\').toLowerCase(Locale.ROOT);
        int indice = normalizado.indexOf(argumento);
        if (indice < 0 || (indice > 0 && !Character.isWhitespace(normalizado.charAt(indice - 1)))) return false;
        int fim = indice + argumento.length();
        return fim == normalizado.length() || Character.isWhitespace(normalizado.charAt(fim));
    }
    void focar(long janela) {
        Pointer handle = new Pointer(janela);
        if (user.IsIconic(handle) != 0) user.ShowWindow(handle, 9);
        user.SetForegroundWindow(handle);
    }
    void fechar(long janela) { user.PostMessageW(new Pointer(janela), 0x0010, null, null); }
}
