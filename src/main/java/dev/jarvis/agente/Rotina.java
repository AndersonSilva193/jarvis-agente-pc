package dev.jarvis.agente;

import java.nio.file.Path;
import java.util.List;

/** Cadastro manual de uma rotina; nenhum caminho desta classe é anunciado ao modelo. */
record Rotina(String nome, String descricao, Path pasta, Path programa, List<String> argumentos,
        int timeoutSegundos, List<String> capacidades, boolean confirmar, String tipo) { }
