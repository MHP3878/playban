package dev.lumio.update;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Where updates may come from: only the project's GitHub over HTTPS (or this computer, for tests). Plain Java only. */
final class Hosts {
    static final Set<String> ALLOWED = Set.of("raw.githubusercontent.com", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com", "127.0.0.1", "localhost");

    private Hosts() {}

    static boolean allowed(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            boolean local = host.equals("127.0.0.1") || host.equals("localhost");
            return ALLOWED.contains(host) && ("https".equals(u.getScheme()) || local && "http".equals(u.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
