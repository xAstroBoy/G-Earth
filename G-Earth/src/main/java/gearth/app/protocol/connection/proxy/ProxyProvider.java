package gearth.app.protocol.connection.proxy;

import java.io.IOException;

public interface ProxyProvider {

    void start() throws IOException;
    void abort();

    /**
     * Abort the proxy and wait until resources that can affect other applications
     * have been released. Providers with asynchronous shutdown should override
     * this method.
     */
    default void abortAndWait() {
        abort();
    }

}
