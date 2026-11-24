package dev.lumio.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lumio.LumioClient;
import dev.lumio.net.JsonPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Checks the running game for code put in from outside (injection clients such as Vape or Prestige),
 * while connected to a server that asks for it, and tells that server what it found: at the start,
 * when something new turns up, and every 30 seconds so the server knows the check still runs.
 *
 * <p>Only this game's own process is looked at, plus the names of running programs compared with the
 * server's list of injectors (only a match is told). Folders and other private things are never sent.
 */
public final class Integrity {
    private static final long CODE_EVERY = 2_000;
    private static final long FULL_EVERY = 10_000;
    private static final long HEARTBEAT = 30_000;
    private static final long SAMPLE_FIRST = 2_000;

    private static volatile String nonce;
    private static volatile List<String> watch = List.of();
    private static volatile boolean packs;
    private static final Map<String, Finding> FOUND = new LinkedHashMap<>();
    private static final List<String> CHECKS = new ArrayList<>();
    private static Thread thread;
    private static int seq;

    private Integrity() {}

    /** The server's greeting: whether to check, and which program names count as injectors. */
    public static synchronized void onHello(String word, boolean check, List<String> names, boolean withPacks) {
        if (!check || word == null || word.isEmpty()) return;
        watch = List.copyOf(names);
        packs = withPacks;
        synchronized (FOUND) {
            FOUND.clear();
        }
        seq = 0;
        nonce = word;
        // the game's thread is watched from the greeting on, so the first answer already covers it
        Checks.startSampler();
        if (thread == null) {
            thread = new Thread(Integrity::loop, "Lumio integrity");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
    }

    /** Left the server: nothing more is checked or sent until the next greeting. */
    public static void reset() {
        nonce = null;
        synchronized (FOUND) {
            FOUND.clear();
        }
    }

    /** What has been found in this session (for the menu). */
    public static List<Finding> found() {
        synchronized (FOUND) {
            return new ArrayList<>(FOUND.values());
        }
    }

    private static void loop() {
        long lastFull = 0, lastCode = 0, lastSent = 0;
        String sentFor = null;
        while (true) {
            try {
                String word = nonce;
                if (word == null) {
                    Thread.sleep(1000);
                    continue;
                }
                long now = System.currentTimeMillis();
                boolean fresh = !word.equals(sentFor);
                boolean changed = false;
                List<String> checks = new ArrayList<>();
                if (fresh || now - lastFull >= FULL_EVERY) {
                    lastFull = now;
                    changed |= add(checks, "agents", Checks::agents);
                    changed |= add(checks, "attach", Checks::attach);
                    switch (Checks.os()) {
                        case "windows" -> changed |= add(checks, "native", Checks::windows);
                        case "mac" -> changed |= add(checks, "native", Checks::mac);
                        default -> changed |= add(checks, "native", Checks::linux);
                    }
                    List<String> names = watch;
                    changed |= add(checks, "processes", () -> Checks.processes(names));
                    // resource packs switched on later (an x-ray pack after joining) count too
                    if (packs) changed |= add(checks, "packs", Packs::findings);
                    synchronized (CHECKS) {
                        CHECKS.clear();
                        CHECKS.addAll(checks);
                        CHECKS.add("code");
                        if (Checks.sampledFor() >= SAMPLE_FIRST) CHECKS.add("sampled");
                    }
                }
                if (fresh || now - lastCode >= CODE_EVERY) {
                    lastCode = now;
                    changed |= add(checks, "code", Checks::code);
                    Checks.startSampler();
                    changed |= add(checks, "sampled", Checks::sampled);
                    // the game's thread counts as looked at once it was watched for two seconds: that
                    // answer is told at once (the server waits for it before letting the player play)
                    if (Checks.sampledFor() >= SAMPLE_FIRST) {
                        synchronized (CHECKS) {
                            if (!CHECKS.contains("sampled")) {
                                CHECKS.add("sampled");
                                changed = true;
                            }
                        }
                    }
                }
                if (fresh || changed || now - lastSent >= HEARTBEAT) {
                    lastSent = now;
                    sentFor = word;
                    send(word);
                }
                // until the game's thread was watched long enough, look again sooner
                Thread.sleep(Checks.sampledFor() < SAMPLE_FIRST ? 500 : CODE_EVERY);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                LumioClient.LOG.debug("Lumio integrity: {}", e.toString());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** Runs one check; what it finds is kept for the session. Returns whether something new turned up. */
    private static boolean add(List<String> checks, String name, Supplier<List<Finding>> check) {
        List<Finding> got;
        try {
            got = check.get();
        } catch (Throwable e) {
            // this check cannot run here (e.g. no native access): it is simply not listed as done
            LumioClient.LOG.debug("Lumio integrity check {}: {}", name, e.toString());
            return false;
        }
        checks.add(name);
        boolean changed = false;
        synchronized (FOUND) {
            for (Finding f : got) {
                if (FOUND.size() >= 32) break;
                if (FOUND.putIfAbsent(f.key(), f) == null) changed = true;
            }
        }
        return changed;
    }

    private static void send(String word) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("nonce", word);
        o.addProperty("seq", ++seq);
        JsonArray done = new JsonArray();
        synchronized (CHECKS) {
            for (String c : CHECKS) done.add(c);
        }
        o.add("checks", done);
        JsonArray list = new JsonArray();
        for (Finding f : found()) {
            JsonObject x = new JsonObject();
            x.addProperty("kind", f.kind());
            x.addProperty("strong", f.strong());
            x.addProperty("what", f.what());
            if (!f.name().isEmpty()) x.addProperty("name", f.name());
            list.add(x);
        }
        o.add("findings", list);
        String json = o.toString();
        Minecraft.getInstance().execute(() -> {
            if (word.equals(nonce) && ClientPlayNetworking.canSend(JsonPayload.INTEGRITY)) {
                ClientPlayNetworking.send(new JsonPayload(JsonPayload.INTEGRITY, json));
            }
        });
    }
}
