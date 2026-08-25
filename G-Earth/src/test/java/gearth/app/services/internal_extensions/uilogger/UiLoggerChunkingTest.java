package gearth.app.services.internal_extensions.uilogger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiLoggerChunkingTest {

    @Test
    void multiMegabyteDisplayTextIsFullyPreservedInVirtualizableParagraphs() {
        String packetText = "x".repeat(5_000_003);
        List<Element> elements = new ArrayList<>();

        UiLoggerController.addChunkedDisplay(elements, packetText, "incoming");

        String reconstructed = elements.stream()
                .filter(element -> element.className.equals("incoming"))
                .map(element -> element.text)
                .collect(Collectors.joining());

        assertEquals(packetText, reconstructed);
        assertTrue(elements.stream()
                .filter(element -> element.className.equals("incoming"))
                .allMatch(element -> element.text.length() <= 4_096));
    }
}
