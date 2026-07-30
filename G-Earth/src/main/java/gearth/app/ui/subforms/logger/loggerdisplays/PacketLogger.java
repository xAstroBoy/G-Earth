package gearth.app.ui.subforms.logger.loggerdisplays;

import gearth.app.protocol.HConnection;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

/**
 * Created by Jonas on 04/04/18.
 */
public interface PacketLogger {

    enum MESSAGE_TYPE {
        BLOCKED(1),
        INCOMING(2),
        OUTGOING(4),
        REPLACED(8),
        INJECTED(16),
        SKIPPED(32), // don't display the whole packet
        SHOW_ADDITIONAL_DATA(64);

        private int val;
        MESSAGE_TYPE(int val)
        {
            this.val = val;
        }
        public int getValue()
        {
            return val;
        }
    }

    void start(HConnection hConnection);
    void stop();

    void appendSplitLine();
    void appendMessage(HPacket packet, int types);
    void appendStructure(HPacket packet, HMessage.Direction direction);

    /** Log a packet that an extension injected, tagging it with the extension's name. Default: ignore the
     *  name (terminal loggers), so only displays that support it need to override. */
    default void appendMessage(HPacket packet, int types, String injectedBy) {
        appendMessage(packet, types);
    }

}
