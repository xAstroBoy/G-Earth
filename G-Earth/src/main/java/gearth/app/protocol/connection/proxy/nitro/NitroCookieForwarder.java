package gearth.app.protocol.connection.proxy.nitro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    private NitroCookieForwarder() {
    }

    /**
     * Records a Cookie header seen on the connection. No-op if it is empty or unchanged, or if the
     * shared directory can't be determined (non-Windows).
     */
    public static void capture(String cookie) {
        if (cookie == null) {
            return;
        }
        cookie = cookie.trim();
        if (cookie.isEmpty() || cookie.equals(lastCookie)) {
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
            Files.write(file, cookie.getBytes(StandardCharsets.UTF_8));
            lastCookie = cookie;
            LOG.info("Forwarded hotel session cookie to {}", file);
        } catch (IOException e) {
            LOG.error("Failed to write forwarded cookie", e);
        }
    }
}
