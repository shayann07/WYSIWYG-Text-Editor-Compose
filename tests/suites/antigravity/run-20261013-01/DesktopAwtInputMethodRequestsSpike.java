package validation;

import java.awt.Rectangle;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;

/**
 * Desktop AWT InputMethodRequests integration spike (R12, §6.3, Gate G0).
 * Tests committed-text indexing when composed text is active in the middle of a document.
 */
public class DesktopAwtInputMethodRequestsSpike implements InputMethodRequests {

    private String fullText;
    private int markedStart;
    private int markedEnd; // [markedStart, markedEnd) is active composition

    public DesktopAwtInputMethodRequestsSpike(String text, int markedStart, int markedEnd) {
        this.fullText = text;
        this.markedStart = markedStart;
        this.markedEnd = markedEnd;
    }

    public int getCommittedLength() {
        int markedLen = (markedEnd > markedStart) ? (markedEnd - markedStart) : 0;
        return fullText.length() - markedLen;
    }

    public enum Association { BEFORE, AFTER }

    // Translates a committed-text coordinate q into a live-text offset with explicit boundary affinity
    public int mapCommittedToLive(int q, Association assoc) {
        int committedLen = getCommittedLength();
        if (q < 0 || q > committedLen) {
            throw new IndexOutOfBoundsException("Committed offset out of range: " + q);
        }
        if (q < markedStart) {
            return q;
        } else if (q == markedStart) {
            return (assoc == Association.BEFORE) ? markedStart : markedEnd;
        } else {
            return q + (markedEnd - markedStart);
        }
    }

    public int mapCommittedToLive(int q) {
        return mapCommittedToLive(q, Association.AFTER);
    }

    // Translates a live-text offset into a committed-text coordinate, or -1 if inside marked text
    public int mapLiveToCommitted(int liveOffset) {
        if (liveOffset < markedStart) {
            return liveOffset;
        } else if (liveOffset >= markedEnd) {
            return liveOffset - (markedEnd - markedStart);
        } else {
            return -1; // Inside active composed segment: no committed coordinate!
        }
    }

    @Override
    public Rectangle getTextLocation(TextHitInfo offset) {
        int live = mapCommittedToLive(offset.getCharIndex());
        // Return dummy screen rectangle for the character cell
        return new Rectangle(live * 10, 50, 10, 20);
    }

    @Override
    public TextHitInfo getLocationOffset(int x, int y) {
        int live = x / 10;
        int committed = mapLiveToCommitted(live);
        if (committed == -1) {
            // Inside composed segment: clamp to marked boundary by affinity
            committed = markedStart;
        }
        return TextHitInfo.afterOffset(committed);
    }

    @Override
    public int getInsertPositionOffset() {
        return markedStart;
    }

    @Override
    public AttributedCharacterIterator getCommittedText(int beginIndex, int endIndex, AttributedCharacterIterator.Attribute[] attributes) {
        String resultText;
        if (endIndex <= markedStart) {
            int liveBegin = mapCommittedToLive(beginIndex, Association.AFTER);
            int liveEnd = mapCommittedToLive(endIndex, Association.BEFORE);
            resultText = fullText.substring(liveBegin, liveEnd);
        } else if (beginIndex >= markedStart) {
            int liveBegin = mapCommittedToLive(beginIndex, Association.AFTER);
            int liveEnd = mapCommittedToLive(endIndex, Association.AFTER);
            resultText = fullText.substring(liveBegin, liveEnd);
        } else {
            // Span crosses marked text: stitch prefix and suffix excluding marked text
            int liveBegin = mapCommittedToLive(beginIndex, Association.AFTER);
            int liveEnd = mapCommittedToLive(endIndex, Association.AFTER);
            String prefix = fullText.substring(liveBegin, markedStart);
            String suffix = fullText.substring(markedEnd, liveEnd);
            resultText = prefix + suffix;
        }
        
        if (resultText.isEmpty()) {
            return new AttributedString(" ").getIterator(attributes, 0, 0);
        }
        return new AttributedString(resultText).getIterator();
    }

    @Override
    public int getCommittedTextLength() {
        return getCommittedLength();
    }

    @Override
    public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] attributes) {
        // Return null if not supported or no text to cancel
        return null;
    }

    @Override
    public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] attributes) {
        return new AttributedString("").getIterator();
    }

    public static void main(String[] args) {
        // Test Document: "Hello[COMPOSED]World"
        // fullText = "HelloCOMPOSEDWorld" (len 18)
        // Prefix "Hello" (indices 0..5, len 5)
        // Composed "COMPOSED" (indices 5..13, len 8)
        // Suffix "World" (indices 13..18, len 5)
        String doc = "HelloCOMPOSEDWorld";
        int markedStart = 5;
        int markedEnd = 13;

        DesktopAwtInputMethodRequestsSpike spike = new DesktopAwtInputMethodRequestsSpike(doc, markedStart, markedEnd);

        // 1. Committed Length
        int committedLen = spike.getCommittedTextLength();
        if (committedLen != 10) {
            throw new AssertionError("Expected committed length 10, got: " + committedLen);
        }

        // 2. Coordinate Mapping
        // q = 0 -> live 0 ('H')
        if (spike.mapCommittedToLive(0) != 0) throw new AssertionError();
        // q = 4 -> live 4 ('o')
        if (spike.mapCommittedToLive(4) != 4) throw new AssertionError();
        // q = 5 -> live 13 ('W') (skips COMPOSED)
        if (spike.mapCommittedToLive(5) != 13) throw new AssertionError();
        // q = 9 -> live 17 ('d')
        if (spike.mapCommittedToLive(9) != 17) throw new AssertionError();

        // 3. Inverse Mapping
        // live 2 ('l') -> q = 2
        if (spike.mapLiveToCommitted(2) != 2) throw new AssertionError();
        // live 7 ('M' inside COMPOSED) -> q = -1 (no committed coordinate!)
        if (spike.mapLiveToCommitted(7) != -1) throw new AssertionError();
        // live 14 ('o') -> q = 6
        if (spike.mapLiveToCommitted(14) != 6) throw new AssertionError();

        // 4. Committed Text Queries
        // Slice [0, 5) -> "Hello"
        AttributedCharacterIterator itPrefix = spike.getCommittedText(0, 5, null);
        StringBuilder sbPrefix = new StringBuilder();
        for (char c = itPrefix.first(); c != AttributedCharacterIterator.DONE; c = itPrefix.next()) {
            sbPrefix.append(c);
        }
        if (!sbPrefix.toString().equals("Hello")) {
            throw new AssertionError("Expected 'Hello', got: " + sbPrefix);
        }

        // Slice [5, 10) -> "World" (skips composed segment!)
        AttributedCharacterIterator itSuffix = spike.getCommittedText(5, 10, null);
        StringBuilder sbSuffix = new StringBuilder();
        for (char c = itSuffix.first(); c != AttributedCharacterIterator.DONE; c = itSuffix.next()) {
            sbSuffix.append(c);
        }
        if (!sbSuffix.toString().equals("World")) {
            throw new AssertionError("Expected 'World', got: " + sbSuffix);
        }

        // Slice [0, 10) -> "HelloWorld" (stitches prefix and suffix, never exposing COMPOSED)
        AttributedCharacterIterator itFull = spike.getCommittedText(0, 10, null);
        StringBuilder sbFull = new StringBuilder();
        for (char c = itFull.first(); c != AttributedCharacterIterator.DONE; c = itFull.next()) {
            sbFull.append(c);
        }
        if (!sbFull.toString().equals("HelloWorld")) {
            throw new AssertionError("Expected 'HelloWorld', got: " + sbFull);
        }

        System.out.println("{\"test\":\"R12\",\"status\":\"PASS\",\"description\":\"AWT InputMethodRequests committed-text coordinate system excludes composed text, maps coordinates safely and stitches slices.\"}");
    }
}
