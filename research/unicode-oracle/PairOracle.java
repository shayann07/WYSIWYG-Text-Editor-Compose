import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import org.unicode.bidi.BidiPBAReference;
import org.unicode.bidi.BidiReference;

// Runs the unmodified Unicode-hosted 2017 Java reference. Its ReadMe declares
// Unicode 8.0, so the caller must not treat this as current-version conformance.
public class PairOracle {
    public static void main(String[] args) throws Exception {
        Field pairsField = BidiPBAReference.class.getDeclaredField("pairPositions");
        pairsField.setAccessible(true);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String text; (text = input.readLine()) != null;) {
            byte[] codes = new byte[text.length()];
            byte[] types = new byte[text.length()];
            int[] values = new int[text.length()];
            for (int i = 0; i < text.length(); i++) {
                codes[i] = BidiReference.ON;
                int open = "([{".indexOf(text.charAt(i));
                int close = ")]}".indexOf(text.charAt(i));
                if (open >= 0) { types[i] = BidiPBAReference.o; values[i] = open + 1; }
                else if (close >= 0) { types[i] = BidiPBAReference.c; values[i] = close + 1; }
            }
            BidiPBAReference reference = new BidiPBAReference();
            reference.runAlgorithm(codes, types, values, BidiReference.L, (byte) 0);
            StringBuilder output = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Collection<?>) pairsField.get(reference)) {
                BidiPBAReference.BracketPair pair = (BidiPBAReference.BracketPair) item;
                if (!first) output.append(',');
                output.append('[').append(pair.getOpener()).append(',').append(pair.getCloser()).append(']');
                first = false;
            }
            System.out.println(output.append(']'));
        }
    }
}
