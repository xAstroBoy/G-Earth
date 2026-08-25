package gearth.app.services.internal_extensions.uilogger;

import gearth.app.misc.Cacher;
import gearth.app.services.internal_extensions.uilogger.hexdumper.Hexdump;
import gearth.services.packet_info.PacketInfo;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.app.ui.subforms.logger.loggerdisplays.PacketLogger;
import gearth.app.ui.translations.LanguageBundle;
import gearth.app.ui.translations.TranslatableString;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.bouncycastle.util.encoders.Hex;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class UiLoggerController implements Initializable {

    private static final String LOGGER_SETTINGS_CACHE = "LOGGER_SETTINGS";

    public FlowPane flowPane;
    public BorderPane borderPane;
    public Label lblViewIncoming;
    public Label lblViewOutgoing;

    public CheckMenuItem chkViewIncoming;
    public CheckMenuItem chkViewOutgoing;
    public CheckMenuItem chkDisplayStructure;
    public Label lblAutoScroll;
    public CheckMenuItem chkAutoscroll;
    public CheckMenuItem chkSkipBigPackets;
    public CheckMenuItem chkMessageName;
    public CheckMenuItem chkMessageHash;
    public CheckMenuItem chkMessageId;
    public CheckMenuItem chkInjectedBy;
    public Label lblPacketInfo;
    public CheckMenuItem chkAlwaysOnTop;

    public CheckMenuItem chkOpenOnConnect;
    public CheckMenuItem chkResetOnConnect;
    public CheckMenuItem chkHideOnDisconnect;
    public CheckMenuItem chkResetOnDisconnect;

    private final static int FILTER_AMOUNT_THRESHOLD_L = 15;
    private final static int FILTER_AMOUNT_THRESHOLD_M = 9;
    private final static int FILTER_AMOUNT_THRESHOLD_H = 4;
    private final static int FILTER_AMOUNT_THRESHOLD_U = 2;
    private final static int FILTER_TIME_THRESHOLD = 5000;

    public RadioMenuItem chkAntiSpam_none;
    public RadioMenuItem chkAntiSpam_low;
    public RadioMenuItem chkAntiSpam_medium;
    public RadioMenuItem chkAntiSpam_high;
    public RadioMenuItem chkAntiSpam_ultra;
    public Label lblFiltered;
    public CheckMenuItem chkTimestamp;

    public CheckMenuItem chkReprLegacy;
    public CheckMenuItem chkReprHex;
    public CheckMenuItem chkReprRawHex;
    public MenuItem menuItem_clear, menuItem_exportAll, menuItem_exportNames;

    private Map<Integer, LinkedList<Long>> filterTimestamps = new HashMap<>();

    // Buffer of the packets actually shown in the window, kept so they can be exported later together
    // with their resolved message names (independent of the current display toggles). Packet copies are
    // made on the formatter worker, never on the JavaFX or packet-forwarding threads.
    private final List<LoggedPacket> loggedPackets = new ArrayList<>();

    private static final class LoggedPacket {
        final HPacket packet;
        final boolean incoming;
        final long timestamp;

        LoggedPacket(HPacket packet, boolean incoming, long timestamp) {
            this.packet = packet;
            this.incoming = incoming;
            this.timestamp = timestamp;
        }
    }

    private StyleClassedTextArea area;

    private Stage stage;

    private int filteredAmount = 0;

    private volatile boolean initialized = false;
    private final List<Element> appendLater = new ArrayList<>();

    // Full packet formatting is serialized away from JavaFX. This queue is deliberately unbounded:
    // logger output is lossless and packets are never dropped or truncated just to protect the UI.
    private final ThreadPoolExecutor formatterExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "G-Earth packet logger formatter");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "G-Earth packet logger export");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong logGeneration = new AtomicLong();
    private final AtomicBoolean loggerInfoUpdateScheduled = new AtomicBoolean(false);

    // Formatted output is streamed into RichTextFX in small slices. PendingElement keeps an offset
    // into the original string, avoiding repeated giant substring copies while a huge packet drains.
    private static final class PendingElement {
        final Element element;
        final long generation;
        int offset;

        PendingElement(Element element, long generation) {
            this.element = element;
            this.generation = generation;
        }
    }

    private final ConcurrentLinkedDeque<PendingElement> pendingElements = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private static final int MAX_CHARS_PER_FX_SLICE = 16_384;
    private static final int MAX_ELEMENTS_PER_FX_SLICE = 128;
    private static final long FX_RENDER_BUDGET_NANOS = 5_000_000L;
    private static final int MAX_DISPLAY_PARAGRAPH_CHARS = 4_096;
    private static final int EXPORT_UI_CHUNK_CHARS = 65_536;

    private static final class RenderOptions {
        final boolean timestamp;
        final boolean messageName;
        final boolean messageHash;
        final boolean messageId;
        final boolean injectedBy;
        final boolean reprLegacy;
        final boolean reprHex;
        final boolean reprRawHex;
        final boolean displayStructure;
        final boolean skipBigPackets;

        RenderOptions(UiLoggerController controller) {
            timestamp = controller.chkTimestamp.isSelected();
            messageName = controller.chkMessageName.isSelected();
            messageHash = controller.chkMessageHash.isSelected();
            messageId = controller.chkMessageId.isSelected();
            injectedBy = controller.chkInjectedBy.isSelected();
            reprLegacy = controller.chkReprLegacy.isSelected();
            reprHex = controller.chkReprHex.isSelected();
            reprRawHex = controller.chkReprRawHex.isSelected();
            displayStructure = controller.chkDisplayStructure.isSelected();
            skipBigPackets = controller.chkSkipBigPackets.isSelected();
        }
    }

    private List<MenuItem> allMenuItems = new ArrayList<>();
    private UiLogger uiLogger;

    public Menu menu_window, menu_window_onConnect, menu_window_onDisconnect, menu_view, menu_packets,
            menu_packets_details, menu_packets_details_byteRep, menu_packets_details_message, menu_packets_antiSpam;

    private TranslatableString viewIncoming, viewOutgoing, autoScroll, packetInfo, filtered;

    private boolean isSelected(MenuItem item) {
        if (item instanceof CheckMenuItem) {
            return ((CheckMenuItem)item).isSelected();
        }
        if (item instanceof RadioMenuItem) {
            return ((RadioMenuItem)item).isSelected();
        }
        return false;
    }

    private void setSelected(MenuItem item, boolean selected) {
        if (item instanceof CheckMenuItem) {
            ((CheckMenuItem)item).setSelected(selected);
        }
        if (item instanceof RadioMenuItem) {
            ((RadioMenuItem)item).setSelected(selected);
        }
    }


    private void saveAllMenuItems() {
        List<Boolean> selection = new ArrayList<>();
        for (MenuItem menuItem : allMenuItems) {
            selection.add(isSelected(menuItem));
        }

        Cacher.put(LOGGER_SETTINGS_CACHE, selection);
    }

    private void loadAllMenuItems() {
        List<Object> selectedMenuItems = Cacher.getList(LOGGER_SETTINGS_CACHE);

        if (selectedMenuItems == null) {
            return;
        }

        if (selectedMenuItems.size() != allMenuItems.size()) {
            saveAllMenuItems();
            return;
        }

        for (int i = 0; i < selectedMenuItems.size(); i++) {
            boolean isSelected = (boolean) selectedMenuItems.get(i);
            setSelected(allMenuItems.get(i), isSelected);
        }
    }

    @Override
    public void initialize(URL arg0, ResourceBundle arg1) {
        allMenuItems.addAll(Arrays.asList(
                chkViewIncoming, chkViewOutgoing, chkDisplayStructure, chkAutoscroll,
                chkSkipBigPackets, chkMessageName, chkMessageHash, chkMessageId,
                chkInjectedBy,
                chkOpenOnConnect, chkResetOnConnect, chkHideOnDisconnect, chkResetOnDisconnect,
                chkAntiSpam_none, chkAntiSpam_low, chkAntiSpam_medium, chkAntiSpam_high, chkAntiSpam_ultra,
                chkTimestamp, chkReprHex, chkReprLegacy, chkReprRawHex
        ));
        loadAllMenuItems();

        for (MenuItem item : allMenuItems) {
            item.setOnAction(event -> {
                saveAllMenuItems();
                updateLoggerInfo();
            });
        }

        area = new StyleClassedTextArea();
        area.getStyleClass().add("dark");
        area.setWrapText(true);
        area.setUndoManager(null);

        VirtualizedScrollPane<StyleClassedTextArea> vsPane = new VirtualizedScrollPane<>(area);
        borderPane.setCenter(vsPane);

        synchronized (appendLater) {
            initialized = true;
            if (!appendLater.isEmpty()) {
                appendLog(appendLater);
                appendLater.clear();
            }
        }

        initLanguageBinding();
    }

    private static String cleanTextContent(String text) {
//        // strips off all non-ASCII characters
//        text = text.replaceAll("[^\\x00-\\x7F]", "");
//
//        // erases all the ASCII control characters
        text = text.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");

        // removes non-printable characters from Unicode
//        text = text.replaceAll("\\p{C}", "");

        return text.trim();
    }

    /**
     * Re-decodes a string that was built from raw packet bytes (each byte one ISO-8859-1 char) as
     * UTF-8, so multi-byte characters the client actually sent (accents, emoji, …) read correctly.
     * ASCII is untouched; a byte run that is not valid UTF-8 falls back to the original text.
     */
    private static String reinterpretAsUtf8(String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            // Not valid UTF-8 (e.g. binary stuff data) — keep the byte-accurate view.
            return text;
        }
    }

    /**
     * The packet's message name for its header + direction, or null if none is known. Used to show
     * the name inline in the packet line (e.g. Incoming[RoomModel:2434]) instead of just the header.
     * Backed by a HashMap lookup, so it's cheap enough to call per packet.
     */
    private String headerName(boolean isIncoming, int headerId) {
        if (uiLogger.getPacketInfoManager().getPacketInfoList().isEmpty()) return null;
        return uiLogger.getPacketInfoManager().getAllPacketInfoFromHeaderId(
                        isIncoming ? HMessage.Direction.TOCLIENT : HMessage.Direction.TOSERVER, headerId)
                .stream().map(PacketInfo::getName).filter(Objects::nonNull).findFirst().orElse(null);
    }


    private boolean checkFilter(HPacket packet) {
        int headerId = packet.headerId();

        int threshold = chkAntiSpam_none.isSelected() ? 100000000 : (
                chkAntiSpam_low.isSelected() ? FILTER_AMOUNT_THRESHOLD_L : (
                        chkAntiSpam_medium.isSelected() ? FILTER_AMOUNT_THRESHOLD_M : (
                                chkAntiSpam_high.isSelected() ? FILTER_AMOUNT_THRESHOLD_H : FILTER_AMOUNT_THRESHOLD_U
                        )
                )
        );

        if (!filterTimestamps.containsKey(headerId)) {
            filterTimestamps.put(headerId, new LinkedList<>());
        }

        long queueRemoveThreshold = System.currentTimeMillis() - FILTER_TIME_THRESHOLD;
        LinkedList<Long> list = filterTimestamps.get(headerId);
        while (!list.isEmpty() && list.get(0) < queueRemoveThreshold) list.removeFirst();

        if (list.size() == threshold) list.removeFirst();
        list.add(System.currentTimeMillis());

        return list.size() >= threshold;
    }

    public void appendMessage(HPacket packet, int types, String injectedBy) {
        boolean isBlocked = (types & PacketLogger.MESSAGE_TYPE.BLOCKED.getValue()) != 0;
        boolean isReplaced = (types & PacketLogger.MESSAGE_TYPE.REPLACED.getValue()) != 0;
        boolean isIncoming = (types & PacketLogger.MESSAGE_TYPE.INCOMING.getValue()) != 0;

        if (isIncoming && !isBlocked && !isReplaced) {
            boolean filter = checkFilter(packet);
            if (filter) {
                filteredAmount++;
                updateLoggerInfo();
                return;
            }
        }

        if (isIncoming && !chkViewIncoming.isSelected()) return;
        if (!isIncoming && !chkViewOutgoing.isSelected()) return;

        // Snapshot JavaFX-owned settings here, then leave the UI thread immediately. The packet is
        // cloned and fully rendered by a single ordered worker so even multi-megabyte packets cannot
        // block the game, the proxy, or this window.
        RenderOptions options = new RenderOptions(this);
        long timestamp = System.currentTimeMillis();
        long generation = logGeneration.get();
        formatterExecutor.execute(() -> formatPacket(packet, types, injectedBy, isIncoming,
                isBlocked, isReplaced, timestamp, options, generation));
    }

    /**
     * Breaks very long single-line representations into virtualizable paragraphs without removing
     * any payload characters. This prevents RichTextFX from repeatedly laying out one enormous
     * wrapped paragraph while a packet is streamed into the document.
     */
    static void addChunkedDisplay(List<Element> elements, String text, String className) {
        if (text.isEmpty()) return;
        for (int start = 0; start < text.length(); start += MAX_DISPLAY_PARAGRAPH_CHARS) {
            int end = Math.min(text.length(), start + MAX_DISPLAY_PARAGRAPH_CHARS);
            elements.add(new Element(text.substring(start, end), className));
            if (end < text.length()) {
                elements.add(new Element("\n", ""));
            }
        }
    }

    private void formatPacket(HPacket sourcePacket, int types, String injectedBy, boolean isIncoming,
                              boolean isBlocked, boolean isReplaced, long timestamp,
                              RenderOptions options, long generation) {
        HPacket packet = new HPacket(sourcePacket);
        if (generation != logGeneration.get()) return;

        // Keep every accepted packet in full for the existing named/expression export.
        synchronized (loggedPackets) {
            if (generation != logGeneration.get()) return;
            loggedPackets.add(new LoggedPacket(packet, isIncoming, timestamp));
        }

        ArrayList<Element> elements = new ArrayList<>();

        if (options.timestamp) {
            elements.add(new Element(String.format("(%s: %d)\n", LanguageBundle.get("ext.logger.element.timestamp"), timestamp), "timestamp"));
        }

        boolean packetInfoAvailable = uiLogger.getPacketInfoManager().getPacketInfoList().size() > 0;
        boolean addedSomeMessageInfo = false;

        if ((options.messageName || options.messageHash) && packetInfoAvailable) {
            List<PacketInfo> messages = uiLogger.getPacketInfoManager().getAllPacketInfoFromHeaderId(
                    (isIncoming ? HMessage.Direction.TOCLIENT : HMessage.Direction.TOSERVER),
                    packet.headerId()
            );
            List<String> names = messages.stream().map(PacketInfo::getName)
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList());
            List<String> hashes = messages.stream().map(PacketInfo::getHash)
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList());

            if (options.messageName && names.size() > 0) {
                for (String name : names) {elements.add(new Element("["+name+"]", "messageinfo")); }
                addedSomeMessageInfo = true;
            }
            if (options.messageHash && hashes.size() > 0) {
                for (String hash : hashes) {elements.add(new Element("["+hash+"]", "messageinfo")); }
                addedSomeMessageInfo = true;
            }
        }

        if (options.messageId) {
            elements.add(new Element(String.format("[%d]", packet.headerId()), "messageinfo"));
            addedSomeMessageInfo = true;
        }

        if (addedSomeMessageInfo) {
            elements.add(new Element("\n", ""));
        }

        // Injected/spoofed by an extension — show WHICH one, so extension traffic is distinguishable from
        // the client's own. Toggled by the "Injected by (extension)" menu item; messageinfo colour.
        if (injectedBy != null && !injectedBy.isEmpty() && options.injectedBy)
            elements.add(new Element(String.format("[injected by %s]\n", injectedBy), "messageinfo"));

        if (isBlocked) elements.add(new Element(String.format("[%s]\n", LanguageBundle.get("ext.logger.element.blocked")), "blocked"));
        else if (isReplaced) elements.add(new Element(String.format("[%s]\n", LanguageBundle.get("ext.logger.element.replaced")), "replaced"));

        if (options.reprLegacy || options.reprHex || options.reprRawHex) {
            boolean isSkipped = (types & PacketLogger.MESSAGE_TYPE.SKIPPED.getValue()) != 0
                    || options.skipBigPackets && (packet.length() > 4000
                    || (packet.length() > 1000 && options.reprHex));
            if (isSkipped) {
                elements.add(new Element(String.format("<%s>", LanguageBundle.get("ext.logger.element.skipped")), "skipped"));
            } else {
                String packetType = isIncoming ? "incoming" : "outgoing";
                String type = isIncoming ?
                        LanguageBundle.get("ext.logger.element.direction.incoming") :
                        LanguageBundle.get("ext.logger.element.direction.outgoing");

                elements.add(new Element(String.format("%s[", type), packetType));
                String hdrName = headerName(isIncoming, packet.headerId());
                if (hdrName != null) {
                    elements.add(new Element(hdrName, packetType));
                    elements.add(new Element(":" + packet.headerId(), ""));
                } else {
                    elements.add(new Element(String.valueOf(packet.headerId()), ""));
                }
                elements.add(new Element("]", packetType));

                elements.add(new Element(" -> ", ""));

                if (options.reprLegacy) {
                    addChunkedDisplay(elements, packet.toString(), packetType);
                    elements.add(new Element("\n", ""));
                }

                if (options.reprHex) {
                    elements.add(new Element(Hexdump.hexdump(packet.toBytes()), String.format("%sHex", packetType)));
                    elements.add(new Element("\n", ""));
                }

                if (options.reprRawHex) {
                    addChunkedDisplay(elements, Hex.toHexString(packet.toBytes()), String.format("%sHex", packetType));
                    elements.add(new Element("\n", ""));
                }
            }
        }

        boolean packetWasSkipped = (types & PacketLogger.MESSAGE_TYPE.SKIPPED.getValue()) != 0;
        if (options.displayStructure && !packetWasSkipped) {
            try {
                String expr = packet.toExpression(isIncoming ? HMessage.Direction.TOCLIENT : HMessage.Direction.TOSERVER, uiLogger.getPacketInfoManager(), true);
                String cleaned = cleanTextContent(expr);
                if (cleaned.equals(expr) && !expr.isEmpty()) {
                    // Nitro sends strings as UTF-8, but packets are handled byte-for-byte as
                    // ISO-8859-1 (so injection stays lossless). Re-decode only the readable view.
                    addChunkedDisplay(elements, reinterpretAsUtf8(cleaned), "structure");
                    elements.add(new Element("\n", ""));
                }
            }
            catch (Exception e) {
                System.err.printf("Packet logger structure failed for header %d (%d bytes): %s%n",
                        packet.headerId(), packet.getBytesLength(), e.getMessage());
            }
        }

        elements.add(new Element("--------------------\n", ""));

        if (generation != logGeneration.get()) return;
        synchronized (appendLater) {
            if (initialized) {
                appendLog(elements, generation);
            }
            else {
                appendLater.addAll(elements);
            }
        }
    }

    private void appendLog(List<Element> elements) {
        appendLog(elements, logGeneration.get());
    }

    private void appendLog(List<Element> elements, long generation) {
        // Queue the work and schedule at most one lossless, time-sliced FX drain.
        for (Element element : elements) {
            pendingElements.addLast(new PendingElement(element, generation));
        }
        if (flushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushPendingElements);
        }
    }

    private void flushPendingElements() {
        StringBuilder sb = new StringBuilder();
        StyleSpansBuilder<Collection<String>> styleSpansBuilder = new StyleSpansBuilder<>(0);
        final long deadline = System.nanoTime() + FX_RENDER_BUDGET_NANOS;
        int count = 0;

        while (count < MAX_ELEMENTS_PER_FX_SLICE
                && sb.length() < MAX_CHARS_PER_FX_SLICE
                && System.nanoTime() < deadline) {
            PendingElement pending = pendingElements.peekFirst();
            if (pending == null) break;
            if (pending.generation != logGeneration.get()) {
                pendingElements.pollFirst();
                continue;
            }

            int remaining = pending.element.text.length() - pending.offset;
            if (remaining <= 0) {
                pendingElements.pollFirst();
                continue;
            }

            int amount = Math.min(remaining, MAX_CHARS_PER_FX_SLICE - sb.length());
            String chunk = pending.element.text.substring(pending.offset, pending.offset + amount);
            sb.append(chunk);
            styleSpansBuilder.add(Collections.singleton(pending.element.className), amount);
            pending.offset += amount;
            if (pending.offset == pending.element.text.length()) {
                pendingElements.pollFirst();
            }
            count++;
        }

        if (count > 0) {
            int oldLen = area.getLength();
            area.appendText(sb.toString());
            area.setStyleSpans(oldLen, styleSpansBuilder.create());

            if (chkAutoscroll.isSelected()) {
                // appendText doesn't move the caret, so move it before following the viewport.
                area.moveTo(area.getLength());
                area.requestFollowCaret();
            }
        }

        if (!pendingElements.isEmpty()) {
            Platform.runLater(this::flushPendingElements);
            return;
        }

        flushScheduled.set(false);
        // Close the race where the formatter appended after isEmpty() but before the flag reset.
        if (!pendingElements.isEmpty() && flushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushPendingElements);
        }
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public void updateLoggerInfo() {
        if (loggerInfoUpdateScheduled.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                loggerInfoUpdateScheduled.set(false);
                viewIncoming.setKey(1, "ext.logger.state." + (chkViewIncoming.isSelected() ? "true" : "false"));
                viewOutgoing.setKey(1, "ext.logger.state." + (chkViewOutgoing.isSelected() ? "true" : "false"));
                autoScroll.setKey(1, "ext.logger.state." + (chkAutoscroll.isSelected() ? "true" : "false"));
                filtered.setFormat("%s: " + filteredAmount);

                boolean packetInfoAvailable = uiLogger.getPacketInfoManager().getPacketInfoList().size() > 0;
                packetInfo.setKey(1, "ext.logger.state." + (packetInfoAvailable ? "true" : "false"));
            });
        }
    }

    public void toggleAlwaysOnTop(ActionEvent actionEvent) {
        stage.setAlwaysOnTop(chkAlwaysOnTop.isSelected());
    }

    public void clearText(ActionEvent actionEvent) {
        logGeneration.incrementAndGet();
        formatterExecutor.getQueue().clear();
        pendingElements.clear();
        synchronized (appendLater) {
            appendLater.clear();
        }
        area.clear();
        synchronized (loggedPackets) {
            loggedPackets.clear();
        }
    }

    public void onDisconnect() {
        Platform.runLater(() -> {
            if (chkHideOnDisconnect.isSelected()) {
                stage.hide();
            }
            if (chkResetOnDisconnect.isSelected()) {
                clearText(null);
            }
        });
    }

    public void onConnect() {
        Platform.runLater(() -> {
            if (chkResetOnConnect.isSelected()) {
                clearText(null);
            }
            if (chkOpenOnConnect.isSelected()) {
                stage.show();
            }
        });
        Platform.runLater(this::updateLoggerInfo);
    }

    public void exportAll(ActionEvent actionEvent) {
        FileChooser fileChooser = new FileChooser();

        //Set extension filter
        FileChooser.ExtensionFilter extFilter =
                new FileChooser.ExtensionFilter(String.format("%s (*.txt)", LanguageBundle.get("ext.logger.menu.packets.exportall.filetype")), "*.txt");
        fileChooser.getExtensionFilters().add(extFilter);
        fileChooser.setTitle(LanguageBundle.get("ext.logger.menu.packets.exportall.windowtitle"));

        //Show save file dialog
        File file = fileChooser.showSaveDialog(stage);

        if (file != null) {
            // Read the RichTextFX document in bounded FX slices and write those slices on a worker.
            // This preserves the complete visible log without copying a potentially huge document
            // in one UI-thread operation.
            new AreaExportTask(file, area.getLength()).start();
        }
    }

    private final class AreaExportTask {
        private final File file;
        private final int snapshotLength;
        private int position;
        private BufferedWriter writer;

        AreaExportTask(File file, int snapshotLength) {
            this.file = file;
            this.snapshotLength = snapshotLength;
        }

        void start() {
            exportExecutor.execute(() -> {
                try {
                    writer = new BufferedWriter(new FileWriter(file));
                    Platform.runLater(this::readNextChunk);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }

        private void readNextChunk() {
            int availableEnd = Math.min(snapshotLength, area.getLength());
            if (position >= availableEnd) {
                closeWriter();
                return;
            }

            int end = Math.min(availableEnd, position + EXPORT_UI_CHUNK_CHARS);
            String chunk = area.getText(position, end);
            position = end;
            exportExecutor.execute(() -> {
                try {
                    writer.write(chunk);
                    Platform.runLater(this::readNextChunk);
                } catch (IOException e) {
                    e.printStackTrace();
                    closeWriter();
                }
            });
        }

        private void closeWriter() {
            exportExecutor.execute(() -> {
                try {
                    if (writer != null) writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public void exportWithNames(ActionEvent actionEvent) {
        FileChooser fileChooser = new FileChooser();

        FileChooser.ExtensionFilter extFilter =
                new FileChooser.ExtensionFilter(String.format("%s (*.txt)", LanguageBundle.get("ext.logger.menu.packets.exportall.filetype")), "*.txt");
        fileChooser.getExtensionFilters().add(extFilter);
        fileChooser.setTitle(LanguageBundle.get("ext.logger.menu.packets.exportall.windowtitle"));

        File file = fileChooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }

        exportExecutor.execute(() -> exportWithNames(file));
    }

    private void exportWithNames(File file) {
        List<LoggedPacket> snapshot;
        synchronized (loggedPackets) {
            snapshot = new ArrayList<>(loggedPackets);
        }

        java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm:ss.SSS");

        try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
            out.write(String.format("# G-Earth packet export - %d packet(s)%n%n", snapshot.size()));

            for (LoggedPacket logged : snapshot) {
                HMessage.Direction direction = logged.incoming ? HMessage.Direction.TOCLIENT : HMessage.Direction.TOSERVER;
                HPacket packet = logged.packet;

                // Resolve the real message name(s) for this header id (same source the window uses).
                List<PacketInfo> infos = uiLogger.getPacketInfoManager()
                        .getAllPacketInfoFromHeaderId(direction, packet.headerId());
                String name = infos.stream().map(PacketInfo::getName)
                        .filter(Objects::nonNull).distinct().collect(Collectors.joining(", "));
                if (name.isEmpty()) {
                    name = "?";
                }

                String expression;
                try {
                    expression = packet.toExpression(direction, uiLogger.getPacketInfoManager(), true);
                } catch (Exception e) {
                    expression = "";
                }

                out.write(String.format("[%s] %s[%d] %s %s%n",
                        timeFormat.format(new java.util.Date(logged.timestamp)),
                        logged.incoming ? "TOCLIENT" : "TOSERVER",
                        packet.headerId(),
                        name,
                        expression));
            }

            out.flush();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void init(UiLogger uiLogger) {
        this.uiLogger = uiLogger;
    }

    private void initLanguageBinding() {
        menu_window.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.window"));
        chkAlwaysOnTop.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.window.alwaysontop"));

        menu_window_onConnect.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.window.onconnect"));
        chkOpenOnConnect.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.window.onconnect.openwindow"));
        chkResetOnConnect.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.window.onconnect.reset"));

        menu_window_onDisconnect.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.window.ondisconnect"));
        chkHideOnDisconnect.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.window.ondisconnect.hidewindow"));
        chkResetOnDisconnect.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.window.ondisconnect.reset"));

        menu_view.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.view"));
        chkViewIncoming.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.view.incoming"));
        chkViewOutgoing.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.view.outgoing"));
        chkAutoscroll.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.view.autoscroll"));
        menuItem_clear.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.view.cleartext"));

        menu_packets.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets"));
        menu_packets_details.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails"));
        chkDisplayStructure.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.structure"));
        chkTimestamp.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.timestamp"));

        menu_packets_details_byteRep.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.byterep"));
        chkReprLegacy.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.byterep.legacy"));
        chkReprHex.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.byterep.hexdump"));
        chkReprRawHex.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.byterep.rawhex"));

        menu_packets_details_message.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.message"));
        chkMessageHash.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.message.hash"));
        chkMessageName.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.message.name"));
        chkMessageId.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.displaydetails.message.id"));

        menu_packets_antiSpam.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.antispam"));
        chkAntiSpam_none.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.antispam.none"));
        chkAntiSpam_low.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.antispam.low"));
        chkAntiSpam_medium.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.antispam.med"));
        chkAntiSpam_high.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.antispam.high"));
        chkAntiSpam_ultra.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.antispam.ultra"));

        chkSkipBigPackets.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.skipbig"));

        menuItem_exportAll.textProperty().bind(new TranslatableString("%s", "ext.logger.menu.packets.exportall"));

        viewIncoming = new TranslatableString("%s: %s", "ext.logger.menu.view.incoming", "ext.logger.state.true");
        viewOutgoing = new TranslatableString("%s: %s", "ext.logger.menu.view.outgoing", "ext.logger.state.true");
        autoScroll = new TranslatableString("%s: %s", "ext.logger.menu.view.autoscroll", "ext.logger.state.true");
        packetInfo = new TranslatableString("%s: %s", "ext.logger.state.packetinfo", "ext.logger.state.false");
        filtered = new TranslatableString("%s: 0", "ext.logger.state.filtered");

        lblViewIncoming.textProperty().bind(viewIncoming);
        lblViewOutgoing.textProperty().bind(viewOutgoing);
        lblAutoScroll.textProperty().bind(autoScroll);
        lblPacketInfo.textProperty().bind(packetInfo);
        lblFiltered.textProperty().bind(filtered);
    }
}
