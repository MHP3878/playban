package dev.bastion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Injection clients (ghost clients put into the running game from outside, not installed as a mod)
 * and what gives them away to Lumio: the names of their loader programs and the beginnings of their
 * class names. A finding of Lumio is matched against these, so the kick can name the client.
 * Program names can be changed by whoever runs them; the code check of Lumio does not depend on them.
 */
public final class KnownInjects {
    public record Client(String id, String name, List<String> processes, List<String> packages) {}

    public static final List<Client> ALL = List.of(
            new Client("vape", "Vape V4", List.of("vape", "vapev4", "vape-v4", "vapeloader", "vape_loader"), List.of("gg.vape", "manthe.")),
            new Client("vapelite", "Vape Lite", List.of("vapelite", "vape-lite", "vape_lite"), List.of("gg.vape.lite")),
            new Client("prestige", "Prestige", List.of("prestige", "prestigeclient", "prestige-client", "prestigeloader"), List.of("prestige", "dev.prestige")),
            new Client("drip", "Drip", List.of("driplite", "drip-lite", "driploader", "dripclient"), List.of("drip", "net.drip")),
            new Client("entropy", "Entropy", List.of("entropyclient", "entropyloader"), List.of("entropy")),
            new Client("doomsday", "Doomsday", List.of("doomsday", "doomsdayclient", "doomsdayloader"), List.of("doomsday")),
            new Client("slinky", "Slinky", List.of("slinky", "slinkyclient"), List.of("slinky")),
            new Client("karma", "Karma", List.of("karmaclient", "karmaloader"), List.of("karma")),
            new Client("sunset", "Sunset", List.of("sunsetclient", "sunsetloader"), List.of("sunset")),
            new Client("raven", "Raven", List.of("ravenb", "ravenbplus", "ravenclient"), List.of("keystrokesmod", "raven")),
            new Client("koid", "Koid", List.of("koidclient", "koidloader"), List.of("koid")),
            new Client("akrien", "Akrien", List.of("akrien", "akrienclient"), List.of("akrien")),
            new Client("itami", "Itami", List.of("itamiclient", "itamiloader"), List.of("itami")));

    private KnownInjects() {}

    public static Client byId(String id) {
        for (Client c : ALL) if (c.id().equals(id)) return c;
        return null;
    }

    /** A typed name ("Vape", "vape lite", "vape-lite.exe") to a known client, or null. */
    public static Client find(String typed) {
        String n = KnownMods.normalize(typed == null ? "" : typed.replaceAll("(?i)\\.exe$", ""));
        if (n.isEmpty()) return null;
        for (Client c : ALL) if (c.id().equals(n) || KnownMods.normalize(c.name()).equals(n)) return c;
        for (Client c : ALL) for (String p : c.processes()) if (KnownMods.normalize(p).equals(n)) return c;
        return null;
    }

    /**
     * Which client a finding of Lumio belongs to: a running program by its name, or code by the
     * beginning of its class name. Null when it fits none.
     */
    public static Client match(String kind, String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT).trim();
        if (w.isEmpty()) return null;
        if (kind.equals("process") || kind.equals("module") || kind.equals("library")) {
            // a program, or a DLL/library in an unusual folder: "vape.exe", "vape.dll (Temp)"
            String file = w.split(" \\(", 2)[0].replaceAll(".*[/\\\\]", "").replaceAll("\\.(exe|dll|so|dylib)$", "");
            for (Client c : ALL) if (c.processes().contains(file)) return c;
            return null;
        }
        if (kind.equals("code") || kind.equals("codefile") || kind.equals("codeloader") || kind.equals("hook")) {
            String cls = w.split(" ", 2)[0];
            Client best = null;
            int bestLen = 0;
            for (Client c : ALL) {
                for (String pkg : c.packages()) {
                    boolean hit = pkg.contains(".") ? cls.startsWith(pkg) : cls.startsWith(pkg + ".") || cls.contains("." + pkg + ".");
                    if (hit && pkg.length() > bestLen) {
                        best = c;
                        bestLen = pkg.length();
                    }
                }
            }
            return best;
        }
        return null;
    }

    /** Every program name of every known client: the list Lumio looks for next to the game. */
    public static List<String> allProcesses() {
        List<String> out = new ArrayList<>();
        for (Client c : ALL) for (String p : c.processes()) if (!out.contains(p)) out.add(p);
        return out;
    }
}
