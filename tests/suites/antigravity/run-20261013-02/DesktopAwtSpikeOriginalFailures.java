package validation;

import java.awt.Rectangle;
import java.awt.font.TextHitInfo;
import java.text.AttributedCharacterIterator;

public class DesktopAwtSpikeOriginalFailures {

    public static void main(String[] args) {
        System.out.println("=== Reproducing AWT InputMethodRequests Defects Against ORIGINAL run-01 Spike ===");

        String doc = "HelloCOMPOSEDWorld";
        int markedStart = 5;
        int markedEnd = 13;

        DesktopAwtInputMethodRequestsSpike spike = new DesktopAwtInputMethodRequestsSpike(doc, markedStart, markedEnd);

        // 1. Defect 1: getCommittedText(5, 5, null) throws
        System.out.println("\n--- Testing Defect 1: getCommittedText(5, 5, null) at composition boundary ---");
        try {
            AttributedCharacterIterator it = spike.getCommittedText(5, 5, null);
            System.out.println("Unexpectedly succeeded with: " + it);
        } catch (Throwable t) {
            System.out.println("CONFIRMED DEFECT 1: getCommittedText(5, 5, null) threw exception:");
            t.printStackTrace(System.out);
        }

        // 2. Defect 2: getTextLocation treats offset as committed-text-relative
        System.out.println("\n--- Testing Defect 2: getTextLocation offset relativity ---");
        // Per InputMethodRequests specification:
        // "The text offset is relative to the composed text if there is composed text,
        //  or relative to the committed text if there is no composed text."
        // With active composed text [5, 13), offset = 0 in composed text corresponds to live offset 5 ('C'),
        // which should be at screen x = 50 (5 * 10).
        // Let's see what the original spike returned:
        TextHitInfo compHit0 = TextHitInfo.leading(0);
        Rectangle r0 = spike.getTextLocation(compHit0);
        System.out.println("getTextLocation(leading(0)) returned Rectangle: " + r0);
        if (r0.x == 0) {
            System.out.println("CONFIRMED DEFECT 2: getTextLocation treated offset 0 as committed-text-relative (mapped to live offset 0, screen x=0) instead of composed-text-relative (live offset 5, screen x=50)!");
        }

        // 3. Defect 3: getLocationOffset returns a hit outside composed text
        System.out.println("\n--- Testing Defect 3: getLocationOffset outside composed text ---");
        // Per InputMethodRequests specification:
        // "Returns: the offset within the composed text, or null if the location is outside the composed text."
        // Screen x = 10 corresponds to live offset 1 ('e' in "Hello"), which is strictly OUTSIDE [5, 13).
        // It must return null!
        TextHitInfo hitOutside = spike.getLocationOffset(10, 50);
        System.out.println("getLocationOffset(10, 50) returned: " + hitOutside);
        if (hitOutside != null) {
            System.out.println("CONFIRMED DEFECT 3: getLocationOffset returned non-null (" + hitOutside + ") for screen coordinate outside composed text instead of returning null!");
        }
    }
}
