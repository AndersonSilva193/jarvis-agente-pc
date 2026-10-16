package dev.jarvis.agente;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A trava pertence ao processo; uma queda a libera sem precisar apagar o arquivo. */
final class InstanciaUnica implements AutoCloseable {
    private final FileChannel canal;
    private final FileLock trava;
    private InstanciaUnica(FileChannel canal, FileLock trava) { this.canal = canal; this.trava = trava; }
    static InstanciaUnica tentar(Path pasta) throws IOException {
        Files.createDirectories(pasta);
        FileChannel canal = FileChannel.open(pasta.resolve("agente.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock trava = canal.tryLock();
            if (trava != null) return new InstanciaUnica(canal, trava);
        } catch (OverlappingFileLockException e) {
            // Já adquirido por outra instância nesta JVM (também exercitado nos testes).
        } catch (IOException e) { canal.close(); throw e; }
        canal.close();
        return null;
    }
    public void close() throws IOException { try { trava.release(); } finally { canal.close(); } }
}
