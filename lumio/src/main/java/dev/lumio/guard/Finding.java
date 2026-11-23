package dev.lumio.guard;

/**
 * Something found in the game: what kind (agent, attach, code, module, thread, library, preload,
 * process, debugger), whether it is strong (clearly injected) or only unusual, a short description
 * without folders, and a client name when one is known (e.g. from a program name).
 */
public record Finding(String kind, boolean strong, String what, String name) {
    public String key() {
        return kind + ":" + what;
    }
}
