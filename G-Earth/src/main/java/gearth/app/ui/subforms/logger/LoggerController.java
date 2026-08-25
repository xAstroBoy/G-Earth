package gearth.app.ui.subforms.logger;

import gearth.app.protocol.connection.HState;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.app.ui.SubForm;
import gearth.app.ui.subforms.logger.loggerdisplays.PacketLogger;
import gearth.app.ui.subforms.logger.loggerdisplays.PacketLoggerFactory;

import java.util.Calendar;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class LoggerController extends SubForm {


    public TextField txtPacketLimit;
    public CheckBox cbx_blockIn;
    public CheckBox cbx_blockOut;
    public CheckBox cbx_showAdditional;
    public CheckBox cbx_splitPackets;
    public CheckBox cbx_useLog;
    public TextFlow txt_logField;
    public Button btnUpdate;
    public CheckBox cbx_showstruct;

    private int packetLimit = 0;

    /*
     * Traffic listeners run in the packet forwarding path. Never format packets or enqueue one
     * JavaFX callback per packet there: a single large packet or a traffic burst would otherwise
     * monopolize the FX event queue. The queue is intentionally lossless; the FX thread consumes
     * it in short time slices and UiLogger performs the expensive formatting on its own worker.
     */
    private static final int MAX_MESSAGES_PER_FX_SLICE = 32;
    private static final long FX_SLICE_BUDGET_NANOS = 4_000_000L;
    private final ConcurrentLinkedQueue<PendingLogMessage> pendingLogMessages = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean logDrainScheduled = new AtomicBoolean(false);

    private static final class PendingLogMessage {
        final HPacket packet;
        final HMessage.Direction destination;
        final boolean blocked;
        final boolean replaced;
        final boolean injected;
        final String injectedBy;

        PendingLogMessage(HMessage message) {
            packet = message.getPacket();
            destination = message.getDestination();
            blocked = message.isBlocked();
            replaced = packet.isReplaced();
            injected = message.isInjected();
            injectedBy = message.getInjectedBy();
        }
    }

    private PacketLoggerFactory packetLoggerFactory;
    private PacketLogger packetLogger;

    public void onParentSet(){
        packetLoggerFactory = new PacketLoggerFactory(parentController.extensionsController.getExtensionHandler());
        packetLogger = packetLoggerFactory.get();

        getHConnection().getStateObservable().addListener((oldState, newState) -> Platform.runLater(() -> {
            if (newState == HState.PREPARING) {
                miniLogText(Color.ORANGE, "Connecting to "+getHConnection().getDomain() + ":" + getHConnection().getServerPort());
            }
            if (newState == HState.CONNECTED) {
                miniLogText(Color.GREEN, "Connected to "+getHConnection().getDomain() + ":" + getHConnection().getServerPort());
                packetLogger.start(getHConnection());
            }
            if (newState == HState.NOT_CONNECTED) {
                miniLogText(Color.RED, "End of connection");
                packetLogger.stop();
            }
        }));

        getHConnection().addTrafficListener(2, this::enqueueLogMessage);
    }

    private void enqueueLogMessage(HMessage message) {
        pendingLogMessages.add(new PendingLogMessage(message));
        scheduleLogDrain();
    }

    private void scheduleLogDrain() {
        if (logDrainScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::drainLogMessages);
        }
    }

    private void drainLogMessages() {
        final long deadline = System.nanoTime() + FX_SLICE_BUDGET_NANOS;
        int processed = 0;
        PendingLogMessage message;

        while (processed < MAX_MESSAGES_PER_FX_SLICE
                && System.nanoTime() < deadline
                && (message = pendingLogMessages.poll()) != null) {
            appendLogMessage(message);
            processed++;
        }

        if (!pendingLogMessages.isEmpty()) {
            Platform.runLater(this::drainLogMessages);
            return;
        }

        logDrainScheduled.set(false);
        // Close the race where traffic arrived between isEmpty() and resetting the flag.
        if (!pendingLogMessages.isEmpty()) {
            scheduleLogDrain();
        }
    }

    private void appendLogMessage(PendingLogMessage message) {
        if (message.destination == HMessage.Direction.TOCLIENT && cbx_blockIn.isSelected() ||
                message.destination == HMessage.Direction.TOSERVER && cbx_blockOut.isSelected()) return;

        if (cbx_splitPackets.isSelected()) {
            packetLogger.appendSplitLine();
        }

        int types = 0;
        if (message.destination == HMessage.Direction.TOCLIENT) types |= PacketLogger.MESSAGE_TYPE.INCOMING.getValue();
        else if (message.destination == HMessage.Direction.TOSERVER) types |= PacketLogger.MESSAGE_TYPE.OUTGOING.getValue();
        if (packetLimit > 0 && message.packet.length() >= packetLimit) types |= PacketLogger.MESSAGE_TYPE.SKIPPED.getValue();
        if (message.blocked) types |= PacketLogger.MESSAGE_TYPE.BLOCKED.getValue();
        if (message.replaced) types |= PacketLogger.MESSAGE_TYPE.REPLACED.getValue();
        if (message.injected) types |= PacketLogger.MESSAGE_TYPE.INJECTED.getValue();
        if (cbx_showAdditional.isSelected()) types |= PacketLogger.MESSAGE_TYPE.SHOW_ADDITIONAL_DATA.getValue();

        packetLogger.appendMessage(message.packet, types, message.injectedBy);

        if (cbx_showstruct.isSelected() && (packetLimit <= 0 || message.packet.length() < packetLimit)) {
            packetLogger.appendStructure(message.packet, message.destination);
        }
    }

    public void updatePacketLimit(ActionEvent actionEvent) {
        packetLimit = Integer.parseInt(txtPacketLimit.getText());
    }

    @SuppressWarnings("Duplicates")
    public void initialize() {
        txtPacketLimit.textProperty().addListener(observable -> {
            boolean isInt = true;

            try {
                Integer.parseInt(txtPacketLimit.getText());
            } catch (NumberFormatException e) {
                isInt = false;
            }

            btnUpdate.setDisable(!isInt);
        });

        txtPacketLimit.setOnKeyPressed(event -> {
            if(event.getCode().equals(KeyCode.ENTER) && !btnUpdate.isDisable()) {
                updatePacketLimit(null);
            }
        });
    }

    public void miniLogText(Color color, String text) {
        if (cbx_useLog.isSelected()) {
            String color2 = "#" + color.toString().substring(2, 8);

            Calendar rightNow = Calendar.getInstance();
            String hour = addToNumber(""+rightNow.get(Calendar.HOUR_OF_DAY));
            String minutes = addToNumber(""+rightNow.get(Calendar.MINUTE));
            String seconds = addToNumber(""+rightNow.get(Calendar.SECOND));
            String timestamp = "["+hour+":"+minutes+":"+seconds+"] ";

            timestamp = timestamp.replace(" ", "\u00A0"); // disable automatic linebreaks
            Text time = new Text(timestamp);
            time.setStyle("-fx-opacity: "+0.5+";");

            text = text.replace(" ", "\u00A0");
            Text otherText = new Text(text + "\n");
            otherText.setStyle("-fx-fill: "+color2+";");

            txt_logField.getChildren().addAll(time, otherText);
        }
    }

    private String addToNumber(String text)	{
        if (text.length() == 1) text = "0" + text;
        return text;
    }

}
