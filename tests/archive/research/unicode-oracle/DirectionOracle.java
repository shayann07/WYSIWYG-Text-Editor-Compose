import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.unicode.bidi.BidiPBAReference;
import org.unicode.bidi.BidiReference;

// Classified tokens, not Unicode code-point classification. Upstream algorithm
// remains unmodified. Input is parity TAB tokens; output is embedding levels.
public class DirectionOracle {
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            byte parity = Byte.parseByte(line.substring(0, 1));
            String text = line.substring(2);
            if (text.isEmpty()) { System.out.println("[]"); continue; }
            byte[] codes = new byte[text.length()];
            byte[] types = new byte[text.length()];
            int[] values = new int[text.length()];
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                codes[i] = ch == 'L' ? BidiReference.L : ch == 'R' ? BidiReference.R : BidiReference.ON;
                int open = "([{".indexOf(ch);
                int close = ")]}".indexOf(ch);
                if (open >= 0) { types[i] = BidiPBAReference.o; values[i] = open + 1; }
                else if (close >= 0) { types[i] = BidiPBAReference.c; values[i] = close + 1; }
            }
            BidiReference reference = new BidiReference(codes, types, values, parity);
            byte[] levels = reference.getLevels(new int[] { text.length() });
            StringBuilder output = new StringBuilder("[");
            for (int i = 0; i < levels.length; i++) {
                if (i != 0) output.append(',');
                output.append(levels[i]);
            }
            System.out.println(output.append(']'));
        }
    }
}
