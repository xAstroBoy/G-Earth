package gearth.app.protocol.connection.proxy.nitro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards the hotel session cookie seen on the Nitro HTTP traffic to a shared file that extensions
 * (e.g. Xabbo) read, so cookie-gated asset hosts (images.bsshotel.it) work without a manual export.
 * <p>
 * Writes to {@code %LOCALAPPDATA%/xabbo/bss_cookie.txt} as the raw {@code name=value; ...} header,
 * and only when the cookie actually changes, so it does not thrash the disk on every asset load.
 */
public final class NitroCookieForwarder {

    private static final Logger LOG = LoggerFactory.getLogger(NitroCookieForwarder.class);
    private static volatile String lastCookie = "";
    private static final Map<String, String> COOKIE_JAR = new LinkedHashMap<>();

    private NitroCookieForwarder() {
    }

    /**
     * Records a Cookie header seen on the connection. No-op if it is empty or unchanged, or if the
     * shared directory can't be determined (non-Windows).
     */
    public static synchronized void capture(String cookie) {
        if (cookie == null) {
            return;
        }
        cookie = cookie.trim();
        if (cookie.isEmpty()) {
            return;
        }

        final String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isEmpty()) {
            return;
        }

        try {
            final Path dir = Paths.get(localAppData, "xabbo");
            Files.createDirectories(dir);
            final Path file = dir.resolve("bss_cookie.txt");
            if (COOKIE_JAR.isEmpty() && Files.exists(file)) {
                merge(Files.readString(file, StandardCharsets.UTF_8));
            }
            merge(cookie);
            final String merged = COOKIE_JAR.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "; " + right).orElse("");
            if (merged.equals(lastCookie)) return;
            Files.writeString(file, merged, StandardCharsets.UTF_8);
            lastCookie = merged;
            LOG.info("Forwarded hotel session cookie to {}", file);
        } catch (IOException e) {
            LOG.error("Failed to write forwarded cookie", e);
        }
    }

    private static void merge(String header) {
        for (String part : header.split(";")) {
            final int equals = part.indexOf('=');
            if (equals <= 0) continue;
            final String name = part.substring(0, equals).trim();
            final String value = part.substring(equals + 1).trim();
            if (!name.isEmpty()) COOKIE_JAR.put(name, value);
        }
    }
}
