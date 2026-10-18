package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogoRotinasTest {
    @TempDir Path pasta;
    @Test void nomeDesconhecidoEArgumentosFixos() throws Exception {
        Path exe = Files.createFile(pasta.resolve("teste.exe"));
        Path config = pasta.resolve("rotinas.properties");
        Files.writeString(config, """
                rotina.demo.descricao=Testa projeto de demonstração
                rotina.demo.pasta=%s
                rotina.demo.programa=%s
                rotina.demo.tipo=executavel
                rotina.demo.argumentos.count=2
                rotina.demo.argumentos.0=--primeiro
                rotina.demo.argumentos.1=valor fixo
                rotina.demo.timeoutSegundos=10
                rotina.demo.capacidades=leitura
                rotina.demo.confirmacao=dispensada
                """.formatted(pasta.toString().replace('\\', '/'), exe.toString().replace('\\', '/')));
        CatalogoRotinas catalogo = CatalogoRotinas.ler(config);
        assertNull(catalogo.obter("desconhecido"));
        assertEquals(java.util.List.of("--primeiro", "valor fixo"), catalogo.obter("demo").argumentos());
    }

    @Test void cmdGenericoEAgenteSemConfirmacaoSaoRecusados() throws Exception {
        Path cmd = Files.createFile(pasta.resolve("teste.cmd"));
        Path config = pasta.resolve("rotinas.properties");
        String base = """
                rotina.demo.descricao=Análise
                rotina.demo.pasta=%s
                rotina.demo.programa=%s
                rotina.demo.tipo=executavel
                rotina.demo.argumentos.count=0
                rotina.demo.timeoutSegundos=10
                rotina.demo.capacidades=agentes
                rotina.demo.confirmacao=dispensada
                """.formatted(pasta.toString().replace('\\', '/'), cmd.toString().replace('\\', '/'));
        Files.writeString(config, base);
        assertThrows(java.io.IOException.class, () -> CatalogoRotinas.ler(config));
        Path exe = Files.createFile(pasta.resolve("teste.exe"));
        Files.writeString(config, base.replace(cmd.toString().replace('\\', '/'),
                exe.toString().replace('\\', '/')));
        assertThrows(java.io.IOException.class, () -> CatalogoRotinas.ler(config));
    }
}
