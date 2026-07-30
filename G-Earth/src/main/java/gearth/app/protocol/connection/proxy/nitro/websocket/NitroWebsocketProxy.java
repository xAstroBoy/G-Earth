package gearth.app.protocol.connection.proxy.nitro.websocket;

import com.github.monkeywie.proxyee.intercept.HttpProxyIntercept;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

public class NitroWebsocketProxy extends HttpProxyIntercept {

    private static final Logger LOG = LoggerFactory.getLogger(NitroWebsocketProxy.class);

    private final NitroWebsocketCallback callback;

    /**
     * Reassembly buffers for fragmented websocket messages. A message can be split across a
     * (non-final) {@link BinaryWebSocketFrame} followed by one or more {@link ContinuationWebSocketFrame}s.
     * Nitro packets must be handed to the callback as complete messages, so we accumulate the
     * fragments per direction until the final fragment arrives. Previously the continuation frames
     * were dropped, which corrupted larger server bursts (e.g. the data sent right after
     * authentication) and left the client stuck mid-load.
     */
    private final ByteArrayOutputStream clientFragments = new ByteArrayOutputStream();
    private final ByteArrayOutputStream serverFragments = new ByteArrayOutputStream();

    public NitroWebsocketProxy(NitroWebsocketCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onWebsocketHandshakeCompleted(HttpProxyInterceptPipeline pipeline) {
        this.callback.onHandshakeComplete();
    }

    @Override
    public void onWebsocketRequest(Channel clientChannel, Channel proxyChannel, WebSocketFrame frame, HttpProxyInterceptPipeline pipeline) {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("client->server frame {} (fin={}, {} bytes)", frame.getClass().getSimpleName(), frame.isFinalFragment(), frame.content().readableBytes());
            }

            if (frame instanceof PingWebSocketFrame ping) {
                clientChannel.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
                return;
            }

            if (frame instanceof CloseWebSocketFrame) {
                this.callback.onClose();
                return;
            }

            final byte[] data = accumulate(clientFragments, frame);
            if (data != null) {
                this.callback.onClientMessage(data);
            }
        } finally {
            frame.release();
        }
    }

    @Override
    public void onWebsocketResponse(Channel clientChannel, Channel proxyChannel, WebSocketFrame frame, HttpProxyInterceptPipeline pipeline) {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("server->client frame {} (fin={}, {} bytes)", frame.getClass().getSimpleName(), frame.isFinalFragment(), frame.content().readableBytes());
            }

            if (frame instanceof PingWebSocketFrame ping) {
                proxyChannel.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
                return;
            }

            if (frame instanceof CloseWebSocketFrame) {
                this.callback.onClose();
                return;
            }

            final byte[] data = accumulate(serverFragments, frame);
            if (data != null) {
                this.callback.onServerMessage(data);
            }
        } finally {
            frame.release();
        }
    }

    @Override
    public void onWebsocketClose(HttpProxyInterceptPipeline pipeline) {
        this.callback.onClose();
    }

    /**
     * Append the payload of a data frame to the reassembly buffer for its direction and, once the
     * final fragment of the message has been received, return the complete message.
     *
     * @param buffer the per-direction reassembly buffer.
     * @param frame  a {@link BinaryWebSocketFrame} or {@link ContinuationWebSocketFrame}.
     * @return the complete message bytes when {@code frame} is the final fragment, otherwise null.
     */
    private byte[] accumulate(final ByteArrayOutputStream buffer, final WebSocketFrame frame) {
        if (!(frame instanceof BinaryWebSocketFrame) && !(frame instanceof ContinuationWebSocketFrame)) {
            LOG.warn("Ignoring unexpected nitro frame type: {}", frame.getClass().getSimpleName());
            return null;
        }

        final ByteBuf content = frame.content();
        final int readable = content.readableBytes();

        if (readable > 0) {
            final byte[] chunk = new byte[readable];
            content.getBytes(content.readerIndex(), chunk);
            buffer.write(chunk, 0, chunk.length);
        }

        if (!frame.isFinalFragment()) {
            // More fragments are on the way; wait for the final one before dispatching.
            return null;
        }

        final byte[] message = buffer.toByteArray();
        buffer.reset();
        return message;
    }
}
