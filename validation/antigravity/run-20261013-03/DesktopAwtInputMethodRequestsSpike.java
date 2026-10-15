package validation;

import java.awt.Rectangle;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Map;

/**
 * Corrected Desktop AWT InputMethodRequests Spike (R12, §6.3, Gate G0 scoping).
 * 
 * Implements and verifies the official java.awt.im.InputMethodRequests contract:
 * 1. getCommittedText:
 *    - All valid slices [beginIndex, endIndex) match an independent oracle string.
 *    - Empty slices (beginIndex == endIndex) at all boundaries, especially composition boundary, return empty iterator.
 *    - Slices crossing the composition concatenate prefix and suffix without exposing composed text.
 *    - Invalid slices throw IllegalArgumentException.
 * 2. getTextLocation:
 *    - Composed-text-relative when active composition exists (leading/trailing edge semantics).
 *    - Returns standard caret rectangle shape (width 2, height lineHeight).
 *    - Committed-text selection relative when no composition exists (handles null offset).
 * 3. getLocationOffset:
 *    - Screen hits outside composed text strictly return null.
 *    - Screen hits inside composed text return hit info relative to composed text.
 *    - Returns null when no composition is active.
 * 4. Boundary affinity correction (§6.3):
 *    - Endpoint affinity alone is insufficient for empty slices; empty slices must be explicitly checked.
 * 5. Gate G0 Scoping:
 *    - This headless interface spike verifies the coordinate translation unit only.
 *    - Real native Compose/AWT host input, selection, geometry, settlement, and accessibility remain NOT RUN.
 */
public class DesktopAwtInputMethodRequestsSpike implements InputMethodRequests {

    private final String fullText;
    private final int markedStart;
    private final int markedEnd; // [markedStart, markedEnd) is active composed text; -1 if no composition
    private final int selectionStart;
    private final int selectionEnd;
    private final int charWidth;
    private final int lineHeight;
    private final int screenOriginX;
    private final int screenOriginY;

    public DesktopAwtInputMethodRequestsSpike(String text, int markedStart, int markedEnd) {
        this(text, markedStart, markedEnd, 0, 0, 10, 20, 100, 50);
    }

    public DesktopAwtInputMethodRequestsSpike(String text, int markedStart, int markedEnd,
                                              int selStart, int selEnd,
                                              int charWidth, int lineHeight,
                                              int originX, int originY) {
        this.fullText = text != null ? text : "";
        this.markedStart = markedStart;
        this.markedEnd = markedEnd;
        this.selectionStart = selStart;
        this.selectionEnd = selEnd;
        this.charWidth = charWidth;
        this.lineHeight = lineHeight;
        this.screenOriginX = originX;
        this.screenOriginY = originY;
    }

    public boolean hasActiveComposition() {
        return markedStart >= 0 && markedEnd > markedStart && markedEnd <= fullText.length();
    }

    public int getCommittedLength() {
        if (!hasActiveComposition()) {
            return fullText.length();
        }
        return fullText.length() - (markedEnd - markedStart);
    }

    /**
     * Independent reference oracle formed by removing the marked segment.
     */
    public String getOracleCommittedText() {
        if (!hasActiveComposition()) {
            return fullText;
        }
        return fullText.substring(0, markedStart) + fullText.substring(markedEnd);
    }

    // -------------------------------------------------------------------------
    // InputMethodRequests Implementation
    // -------------------------------------------------------------------------

    @Override
    public int getCommittedTextLength() {
        return getCommittedLength();
    }

    @Override
    public AttributedCharacterIterator getCommittedText(int beginIndex, int endIndex,
                                                        AttributedCharacterIterator.Attribute[] attributes) {
        int committedLen = getCommittedLength();
        if (beginIndex < 0 || endIndex < beginIndex || endIndex > committedLen) {
            throw new IllegalArgumentException(
                "Invalid committed text slice [" + beginIndex + ", " + endIndex + ") for committed length " + committedLen
            );
        }

        // Empty slice at ANY boundary (including composition boundary) returns empty iterator
        if (beginIndex == endIndex) {
            return new AttributedString("").getIterator();
        }

        String resultText;
        if (!hasActiveComposition()) {
            resultText = fullText.substring(beginIndex, endIndex);
        } else {
            if (endIndex <= markedStart) {
                // Slices strictly in prefix
                resultText = fullText.substring(beginIndex, endIndex);
            } else if (beginIndex >= markedStart) {
                // Slices strictly in suffix (shift by marked length)
                int markedLen = markedEnd - markedStart;
                resultText = fullText.substring(beginIndex + markedLen, endIndex + markedLen);
            } else {
                // Slice crosses composition: concatenate prefix and suffix WITHOUT exposing composed text
                int markedLen = markedEnd - markedStart;
                String prefix = fullText.substring(beginIndex, markedStart);
                String suffix = fullText.substring(markedEnd, endIndex + markedLen);
                resultText = prefix + suffix;
            }
        }

        if (resultText.isEmpty()) {
            return new AttributedString("").getIterator();
        }

        AttributedString as = new AttributedString(resultText);
        if (attributes != null && attributes.length > 0) {
            return as.getIterator(attributes);
        }
        return as.getIterator();
    }

    @Override
    public Rectangle getTextLocation(TextHitInfo offset) {
        int liveIndex;
        boolean isLeading;

        if (hasActiveComposition()) {
            // Official contract: offset is relative to the composed text!
            int compLen = markedEnd - markedStart;
            if (offset == null) {
                liveIndex = markedStart;
                isLeading = true;
            } else {
                int charIndex = offset.getCharIndex();
                // Clamp within composed text bounds [0, compLen]
                charIndex = Math.max(0, Math.min(compLen, charIndex));
                liveIndex = markedStart + charIndex;
                isLeading = offset.isLeadingEdge();
            }
        } else {
            // Official contract: offset is relative to committed text (or selection if null)
            if (offset == null) {
                liveIndex = selectionEnd;
                isLeading = true;
            } else {
                liveIndex = offset.getCharIndex();
                isLeading = offset.isLeadingEdge();
            }
        }

        // Caret geometry: 2px wide caret rectangle
        int screenX = screenOriginX + (liveIndex * charWidth) + (isLeading ? 0 : charWidth);
        int screenY = screenOriginY;
        return new Rectangle(screenX, screenY, 2, lineHeight);
    }

    @Override
    public TextHitInfo getLocationOffset(int x, int y) {
        if (!hasActiveComposition()) {
            // Official contract: returns null if no composed text
            return null;
        }

        // Check vertical line bounds (exclusive lower bound)
        if (y < screenOriginY || y >= screenOriginY + lineHeight) {
            return null;
        }

        int compScreenStart = screenOriginX + (markedStart * charWidth);
        int compScreenEnd = screenOriginX + (markedEnd * charWidth);

        // Official contract: returns null if location is outside composed text!
        if (x < compScreenStart || x >= compScreenEnd) {
            return null;
        }

        // Inside composed text: return TextHitInfo relative to composed text
        int relX = x - compScreenStart;
        int compCharIndex = relX / charWidth;
        int remX = relX % charWidth;

        if (remX < charWidth / 2) {
            return TextHitInfo.leading(compCharIndex);
        } else {
            return TextHitInfo.trailing(compCharIndex);
        }
    }

    @Override
    public int getInsertPositionOffset() {
        if (hasActiveComposition()) {
            return markedStart;
        }
        return selectionEnd;
    }

    @Override
    public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] attributes) {
        int selLen = Math.abs(selectionEnd - selectionStart);
        if (selLen == 0) {
            return new AttributedString("").getIterator();
        }
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        String text = fullText.substring(start, end);
        AttributedString as = new AttributedString(text);
        if (attributes != null && attributes.length > 0) {
            return as.getIterator(attributes);
        }
        return as.getIterator();
    }

    @Override
    public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] attributes) {
        // Unsupported in this editor
        return null;
    }

    // -------------------------------------------------------------------------
    // Verification Suite
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("=== Testing Corrected Desktop AWT InputMethodRequests Spike ===");

        // Test Document: "Hello[COMPOSED]World"
        // fullText = "HelloCOMPOSEDWorld" (len 18)
        // marked: [5, 13) ("COMPOSED", len 8)
        // Oracle committed text: "HelloWorld" (len 10)
        String doc = "HelloCOMPOSEDWorld";
        int markedStart = 5;
        int markedEnd = 13;

        DesktopAwtInputMethodRequestsSpike spike = new DesktopAwtInputMethodRequestsSpike(
            doc, markedStart, markedEnd, 2, 2, 10, 20, 100, 50
        );

        String oracleCommitted = spike.getOracleCommittedText();
        if (!oracleCommitted.equals("HelloWorld")) {
            throw new AssertionError("Oracle text mismatch: " + oracleCommitted);
        }
        if (spike.getCommittedTextLength() != 10) {
            throw new AssertionError("Committed length mismatch");
        }

        // 1. Exhaustive slice test: Every valid pair [begin, end) with 0 <= begin <= end <= 10
        System.out.println("1. Exhaustive testing of all 66 valid committed-text slices against independent oracle...");
        int sliceCount = 0;
        for (int begin = 0; begin <= 10; begin++) {
            for (int end = begin; end <= 10; end++) {
                AttributedCharacterIterator it = spike.getCommittedText(begin, end, null);
                StringBuilder sb = new StringBuilder();
                for (char c = it.first(); c != AttributedCharacterIterator.DONE; c = it.next()) {
                    sb.append(c);
                }
                String expected = oracleCommitted.substring(begin, end);
                if (!sb.toString().equals(expected)) {
                    throw new AssertionError("Slice [" + begin + ", " + end + ") expected '" + expected + "', got '" + sb + "'");
                }
                sliceCount++;
            }
        }
        System.out.println("   -> PASS: All " + sliceCount + " valid slices exactly match independent oracle.");

        // 2. Specific empty slice at composition boundary [5, 5)
        System.out.println("2. Verifying empty slice at composition boundary [5, 5)...");
        AttributedCharacterIterator itBoundaryEmpty = spike.getCommittedText(5, 5, null);
        if (itBoundaryEmpty.first() != AttributedCharacterIterator.DONE) {
            throw new AssertionError("Expected empty iterator at [5, 5)");
        }
        System.out.println("   -> PASS: [5, 5) returns empty iterator without throwing.");

        // 3. Slices crossing composition concatenation
        System.out.println("3. Verifying slice crossing composition [3, 7)...");
        AttributedCharacterIterator itCross = spike.getCommittedText(3, 7, null);
        StringBuilder sbCross = new StringBuilder();
        for (char c = itCross.first(); c != AttributedCharacterIterator.DONE; c = itCross.next()) {
            sbCross.append(c);
        }
        // [3, 7) in "HelloWorld": prefix "lo" + suffix "Wo" = "loWo"
        if (!sbCross.toString().equals("loWo")) {
            throw new AssertionError("Expected 'loWo', got: " + sbCross);
        }
        System.out.println("   -> PASS: Cross-composition slice [3, 7) yielded 'loWo' with zero composed text leakage.");

        // 4. Invalid slices throw IllegalArgumentException
        System.out.println("4. Verifying invalid slices throw IllegalArgumentException...");
        int[][] invalidRanges = {
            {-1, 5},
            {5, 4},
            {0, 11},
            {12, 15}
        };
        for (int[] r : invalidRanges) {
            try {
                spike.getCommittedText(r[0], r[1], null);
                throw new AssertionError("Expected IllegalArgumentException for [" + r[0] + ", " + r[1] + ")");
            } catch (IllegalArgumentException expected) {
                // Expected!
            }
        }
        System.out.println("   -> PASS: All invalid slices threw IllegalArgumentException.");

        // 5. getTextLocation composition-relative semantics
        System.out.println("5. Verifying getTextLocation composed-text-relative geometry...");
        // Offset 0 in composed text -> character 'C' at live offset 5
        // screenX = 100 + 5 * 10 = 150
        Rectangle rComp0 = spike.getTextLocation(TextHitInfo.leading(0));
        if (rComp0.x != 150 || rComp0.y != 50 || rComp0.width != 2 || rComp0.height != 20) {
            throw new AssertionError("Unexpected rComp0: " + rComp0);
        }
        // Offset 0 trailing -> screenX = 150 + 10 = 160
        Rectangle rComp0Trailing = spike.getTextLocation(TextHitInfo.trailing(0));
        if (rComp0Trailing.x != 160) {
            throw new AssertionError("Unexpected rComp0Trailing: " + rComp0Trailing);
        }
        // Offset 7 leading (last char 'D' in COMPOSED) -> live offset 12 -> screenX = 100 + 12 * 10 = 220
        Rectangle rComp7 = spike.getTextLocation(TextHitInfo.leading(7));
        if (rComp7.x != 220) {
            throw new AssertionError("Unexpected rComp7: " + rComp7);
        }
        System.out.println("   -> PASS: getTextLocation correctly evaluates composed-text-relative geometry and caret rectangle.");

        // 6. getLocationOffset outside vs inside composition
        System.out.println("6. Verifying getLocationOffset bounds and null returns...");
        // Screen x = 120 (live offset 2 in "Hello"): OUTSIDE composition! Must return null!
        TextHitInfo hitOutsidePrefix = spike.getLocationOffset(120, 50);
        if (hitOutsidePrefix != null) {
            throw new AssertionError("Expected null for hit outside composition in prefix, got: " + hitOutsidePrefix);
        }

        // Screen x = 240 (live offset 14 in "World"): OUTSIDE composition! Must return null!
        TextHitInfo hitOutsideSuffix = spike.getLocationOffset(240, 50);
        if (hitOutsideSuffix != null) {
            throw new AssertionError("Expected null for hit outside composition in suffix, got: " + hitOutsideSuffix);
        }

        // Screen x = 152 (inside 'C' at comp offset 0): leading
        TextHitInfo hitInside0Leading = spike.getLocationOffset(152, 50);
        if (hitInside0Leading == null || hitInside0Leading.getCharIndex() != 0 || !hitInside0Leading.isLeadingEdge()) {
            throw new AssertionError("Expected leading(0), got: " + hitInside0Leading);
        }

        // Screen x = 158 (inside 'C' at comp offset 0): trailing
        TextHitInfo hitInside0Trailing = spike.getLocationOffset(158, 50);
        if (hitInside0Trailing == null || hitInside0Trailing.getCharIndex() != 0 || hitInside0Trailing.isLeadingEdge()) {
            throw new AssertionError("Expected trailing(0), got: " + hitInside0Trailing);
        }
        System.out.println("   -> PASS: getLocationOffset returns null outside composed text and exact TextHitInfo inside.");

        // 7. No-composition state behavior
        System.out.println("7. Verifying no-composition state behavior...");
        DesktopAwtInputMethodRequestsSpike spikeNoComp = new DesktopAwtInputMethodRequestsSpike(
            "Hello", -1, -1, 1, 3, 10, 20, 100, 50
        );
        if (spikeNoComp.getLocationOffset(110, 50) != null) {
            throw new AssertionError("Expected null getLocationOffset when no composition active");
        }
        // getTextLocation with null offset defaults to committed selection (index 3)
        Rectangle rNoCompSel = spikeNoComp.getTextLocation(null);
        if (rNoCompSel.x != 100 + 3 * 10) {
            throw new AssertionError("Expected selection x = 130, got: " + rNoCompSel.x);
        }
        System.out.println("   -> PASS: No-composition state correctly handles null offset and returns null hit.");

        // 8. Selected text behavior across selections & attributes
        System.out.println("8. Verifying getSelectedText across forward, collapsed, reversed selections and attributes...");
        // 8.1 Forward selection [2, 7) on spike ("HelloCOMPOSEDWorld") -> text [2, 7) is "lloCO"
        DesktopAwtInputMethodRequestsSpike spikeSelForward = new DesktopAwtInputMethodRequestsSpike(
            doc, markedStart, markedEnd, 2, 7, 10, 20, 100, 50
        );
        AttributedCharacterIterator itSelForward = spikeSelForward.getSelectedText(null);
        StringBuilder sbSelForward = new StringBuilder();
        for (char c = itSelForward.first(); c != AttributedCharacterIterator.DONE; c = itSelForward.next()) {
            sbSelForward.append(c);
        }
        if (!sbSelForward.toString().equals("lloCO")) {
            throw new AssertionError("Expected 'lloCO', got: " + sbSelForward);
        }

        // 8.2 Collapsed selection [4, 4)
        DesktopAwtInputMethodRequestsSpike spikeSelCollapsed = new DesktopAwtInputMethodRequestsSpike(
            doc, markedStart, markedEnd, 4, 4, 10, 20, 100, 50
        );
        AttributedCharacterIterator itSelCollapsed = spikeSelCollapsed.getSelectedText(null);
        if (itSelCollapsed.first() != AttributedCharacterIterator.DONE) {
            throw new AssertionError("Expected empty iterator for collapsed selection");
        }

        // 8.3 Reversed selection [7, 2) -> normalizes to [2, 7) "lloCO"
        DesktopAwtInputMethodRequestsSpike spikeSelReversed = new DesktopAwtInputMethodRequestsSpike(
            doc, markedStart, markedEnd, 7, 2, 10, 20, 100, 50
        );
        AttributedCharacterIterator itSelReversed = spikeSelReversed.getSelectedText(null);
        StringBuilder sbSelReversed = new StringBuilder();
        for (char c = itSelReversed.first(); c != AttributedCharacterIterator.DONE; c = itSelReversed.next()) {
            sbSelReversed.append(c);
        }
        if (!sbSelReversed.toString().equals("lloCO")) {
            throw new AssertionError("Expected 'lloCO' for reversed selection, got: " + sbSelReversed);
        }
        System.out.println("   -> PASS: getSelectedText handles forward, collapsed, and reversed selections accurately.");

        // 9. Requested attributes behavior & unsupported attributes identification
        System.out.println("9. Verifying requested text attributes on getCommittedText and getSelectedText...");
        AttributedCharacterIterator.Attribute[] requestedAttrs = new AttributedCharacterIterator.Attribute[] {
            java.awt.font.TextAttribute.INPUT_METHOD_SEGMENT,
            java.awt.font.TextAttribute.WEIGHT
        };
        AttributedCharacterIterator itWithAttrs = spike.getCommittedText(0, 5, requestedAttrs);
        StringBuilder sbWithAttrs = new StringBuilder();
        for (char c = itWithAttrs.first(); c != AttributedCharacterIterator.DONE; c = itWithAttrs.next()) {
            sbWithAttrs.append(c);
            Map<AttributedCharacterIterator.Attribute, Object> map = itWithAttrs.getAttributes();
            // In the headless model, font/input-method attributes are explicitly unmapped:
            if (map.containsKey(java.awt.font.TextAttribute.INPUT_METHOD_SEGMENT)) {
                throw new AssertionError("Did not expect custom INPUT_METHOD_SEGMENT attribute in raw model");
            }
        }
        if (!sbWithAttrs.toString().equals("Hello")) {
            throw new AssertionError("Expected 'Hello', got: " + sbWithAttrs);
        }
        System.out.println("   -> PASS: Requested text attributes return clean iterators with unsupported attributes explicitly identified.");

        // 10. getInsertPositionOffset behavior
        System.out.println("10. Verifying getInsertPositionOffset in composing vs non-composing states...");
        if (spike.getInsertPositionOffset() != 5) {
            throw new AssertionError("Expected markedStart (5) for insert offset when composing, got: " + spike.getInsertPositionOffset());
        }
        if (spikeNoComp.getInsertPositionOffset() != 3) {
            throw new AssertionError("Expected selectionEnd (3) for insert offset when idle, got: " + spikeNoComp.getInsertPositionOffset());
        }
        System.out.println("   -> PASS: getInsertPositionOffset returns markedStart when composing and selectionEnd when idle.");

        // 11. cancelLatestCommittedText unsupported behavior
        System.out.println("11. Verifying cancelLatestCommittedText unsupported status...");
        if (spike.cancelLatestCommittedText(null) != null) {
            throw new AssertionError("Expected null for unsupported cancelLatestCommittedText");
        }
        System.out.println("   -> PASS: cancelLatestCommittedText cleanly returns null (unsupported).");

        System.out.println("\n=== ALL AWT INPUTMETHODREQUESTS SPIKE TESTS PASSED ===");
        System.out.println("{\"test\":\"R12\",\"status\":\"PASS\",\"scope\":\"HeadlessUnitContractOnly\",\"note\":\"Native host input, selection, geometry, settlement, and accessibility remain unvalidated (Gate G0 Desktop NOT RUN / INCOMPLETE).\"}");
    }
}
