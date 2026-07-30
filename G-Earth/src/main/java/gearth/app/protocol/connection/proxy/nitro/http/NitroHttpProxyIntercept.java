package gearth.app.protocol.connection.proxy.nitro.http;

import com.github.monkeywie.proxyee.intercept.HttpProxyIntercept;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline;
import com.github.monkeywie.proxyee.intercept.common.FullResponseIntercept;
import gearth.protocol.HPacket;
import gearth.protocol.HPacketFormat;
import gearth.app.protocol.connection.proxy.nitro.NitroConstants;
import gearth.app.protocol.connection.proxy.nitro.NitroCookieForwarder;
import gearth.app.protocol.connection.proxy.nitro.websocket.NitroWebsocketCallback;
import gearth.app.protocol.connection.proxy.nitro.websocket.NitroWebsocketProxy;
import gearth.app.services.nitro.NitroHotel;
import gearth.app.services.nitro.NitroHotelManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NitroHttpProxyIntercept extends HttpProxyInterceptInitializer {

    private static final Logger log = LoggerFactory.getLogger(NitroHttpProxyIntercept.class);

    /**
     * Default max content length size is 100MB
     */
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024 * 100;
    private static final int CLIENT_HELLO_PACKET_ID = 4000;

    /**
     * The first field of the nitro ClientHello (RELEASE_VERSION) message is the client release
     * version, which always starts with this prefix (e.g. "NITRO-1-5-4"). We use this together
     * with the {@link NitroConstants#WEBSOCKET_CLIENT_IDENTIFIER} field to detect the ClientHello
     * by its payload signature, because some hotels remap header ids and therefore do not use the
     * default {@link #CLIENT_HELLO_PACKET_ID}.
     */
    private static final String NITRO_RELEASE_VERSION_PREFIX = "NITRO";

    private final NitroHotelManager nitroHotelManager;
    private final NitroWebsocketCallback callback;

    public NitroHttpProxyIntercept(NitroHotelManager nitroHotelManager, NitroWebsocketCallback callback) {
        this.nitroHotelManager = nitroHotelManager;
        this.callback = callback;
    }

    private byte[] getBinaryData(final BinaryWebSocketFrame binaryFrame) {
        // Read binary data.
        final ByteBuf content = binaryFrame.content();
        final byte[] binaryData = new byte[binaryFrame.content().readableBytes()];

        content.markReaderIndex();

        try {
            content.readBytes(binaryData);
        } finally {
            content.resetReaderIndex();
        }

        return binaryData;
    }

    private boolean checkPacket(final String websocketUrl, final BinaryWebSocketFrame binaryFrame) {
        // Read binary data.
        final byte[] binaryData = getBinaryData(binaryFrame);

        // Log the packet.
        log.debug("Received binary frame");
        log.debug(ByteBufUtil.hexDump(binaryFrame.content()));

        // Detect nitro connection.
        final HPacket packet = HPacketFormat.EVA_WIRE.createPacket(binaryData);

        packet.setReadIndex(0);

        // Check packet length.
        final int packetLen = packet.readInteger();
        if (packetLen + 4 != binaryData.length) {
            log.debug("websocket[{}] packet length mismatch: {} != {}", websocketUrl, packetLen + 4, binaryData.length);
            return false;
        }

        // Fast path: the standard nitro ClientHello header id.
        final short packetId = packet.readShort();
        if (packetId == CLIENT_HELLO_PACKET_ID) {
            return true;
        }

        // Fallback: hotels commonly remap their header ids, so the ClientHello is not guaranteed to
        // use CLIENT_HELLO_PACKET_ID. Detect it by its payload signature instead, otherwise the
        // connection is treated as non-nitro, the proxy is never paused and the client stalls
        // mid-load (e.g. stuck at 60% right after authenticating).
        if (isReleaseVersionSignature(packet)) {
            log.info("websocket[{}] detected nitro ClientHello via payload signature (header id {})", websocketUrl, packetId);
            return true;
        }

        log.debug("websocket[{}] packet id mismatch: {} != {} and no ClientHello signature", websocketUrl, packetId, CLIENT_HELLO_PACKET_ID);
        return false;
    }

    /**
     * Detect a nitro ClientHello (RELEASE_VERSION) message by its payload rather than its header id.
     * The message layout is {@code [int length][short header][string releaseVersion][string clientIdentifier]...},
     * where {@code releaseVersion} starts with {@link #NITRO_RELEASE_VERSION_PREFIX} and
     * {@code clientIdentifier} equals {@link NitroConstants#WEBSOCKET_CLIENT_IDENTIFIER}.
     *
     * @param packet a packet positioned right after the header id has been read.
     * @return true when the payload matches the ClientHello signature.
     */
    private boolean isReleaseVersionSignature(final HPacket packet) {
        try {
            final String releaseVersion = packet.readString();
            if (!releaseVersion.startsWith(NITRO_RELEASE_VERSION_PREFIX)) {
                return false;
            }

            final String clientIdentifier = packet.readString();
            return NitroConstants.WEBSOCKET_CLIENT_IDENTIFIER.equals(clientIdentifier);
        } catch (Exception e) {
            // Reading past the buffer or malformed strings simply means this is not a ClientHello.
            return false;
        }
    }

    @Override
    public void init(HttpProxyInterceptPipeline pipeline) {
        pipeline.addLast(new FullResponseIntercept(DEFAULT_MAX_CONTENT_LENGTH) {
            @Override
            public boolean match(HttpRequest httpRequest, HttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) {
                return httpResponse.status().code() == 200;
            }

            @Override
            public void handleResponse(HttpRequest httpRequest, FullHttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) {
                // Forward the site session cookie so cookie-gated asset hosts (images.bsshotel.it)
                // work in extensions without a manual export.
                NitroCookieForwarder.capture(httpRequest.headers().get(io.netty.handler.codec.http.HttpHeaderNames.COOKIE));

                final byte[] data = ByteBufUtil.getBytes(httpResponse.content());

                String uriPath = httpRequest.uri();

                if (uriPath.contains("?")) {
                    uriPath = uriPath.substring(0, uriPath.indexOf("?"));
                }

                nitroHotelManager.checkAsset(pipeline.getRequestProto().getHost(),
                        uriPath,
                        data);
            }
        });

        pipeline.addLast(new HttpProxyIntercept() {
            @Override
            public void onWebsocketHandshakeCompleted(HttpProxyInterceptPipeline pipeline) {
                callback.onHandshakeComplete();
            }

            @Override
            public void onWebsocketRequest(Channel clientChannel, Channel proxyChannel, WebSocketFrame webSocketFrame, HttpProxyInterceptPipeline pipeline) throws Exception {
                boolean foundMatch = false;

                try {
                    if (!(webSocketFrame instanceof BinaryWebSocketFrame)) {
                        return;
                    }

                    // Obtain url.
                    final String websocketUrl = pipeline.getRequestProto().getWebsocketUrl();
                    final String normalizedWebsocketUrl = NitroHotelManager.normalizeWebsocketUrl(websocketUrl);

                    log.debug("Checking websocket url: {}", websocketUrl);

                    final NitroHotel hotel = nitroHotelManager.getByWebsocketOrNull(normalizedWebsocketUrl);
                    final boolean accept;

                    if (hotel != null) {
                        log.debug("websocket[{}] matched hotel {}", websocketUrl, hotel.getName());

                        if (hotel.skipWebsocket(normalizedWebsocketUrl)) {
                            log.debug("websocket[{}] skipped by hotel configuration", websocketUrl);
                            return;
                        }

                        // Trusted exact-url hotel match.
                        accept = hotel.isInitialFrame(normalizedWebsocketUrl, getBinaryData((BinaryWebSocketFrame) webSocketFrame));
                    } else {
                        // No url match: fall back to generic nitro CLIENT_HELLO detection.
                        accept = checkPacket(websocketUrl, (BinaryWebSocketFrame) webSocketFrame);
                    }

                    if (!accept) {
                        log.debug("websocket[{}] not a nitro hotel", websocketUrl);
                        return;
                    }

                    log.debug("websocket[{}] found nitro hotel", websocketUrl);

                    foundMatch = true;

                    pipeline.remove(this);
                    pipeline.addLast(new NitroWebsocketProxy(callback));

                    callback.onConnected(websocketUrl, clientChannel, proxyChannel);
                    callback.onClientMessage(getBinaryData((BinaryWebSocketFrame) webSocketFrame));
                } catch (Exception e) {
                    log.error("Failed to read initial binary websocket frame", e);
                } finally {
                    pipeline.remove(this);
                    pipeline.resetWebsocketRequest();

                    if (foundMatch) {
                        webSocketFrame.release();
                    } else {
                        super.onWebsocketRequest(clientChannel, proxyChannel, webSocketFrame, pipeline);
                    }
                }
            }
        });
    }
}
