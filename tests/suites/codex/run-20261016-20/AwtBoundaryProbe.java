import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.awt.font.FontRenderContext;
import java.awt.Font;
public class AwtBoundaryProbe {
 public static void main(String[] args) {
  TextHitInfo before=TextHitInfo.beforeOffset(0);
  if(before.getCharIndex()!=-1 || before.isLeadingEdge() || before.getInsertionIndex()!=0) throw new AssertionError();
  TextLayout layout=new TextLayout("abc",new Font("Dialog",Font.PLAIN,12),new FontRenderContext(null,true,true));
  float[] start=layout.getCaretInfo(before),end=layout.getCaretInfo(TextHitInfo.afterOffset(3));
  if(!Float.isFinite(start[0]) || !Float.isFinite(end[0]))throw new AssertionError();
  System.out.println("PASS: TextHitInfo.beforeOffset(0) = trailing(-1), insertion 0; TextLayout accepts both boundary aliases. Not a live IME test.");
 }
}
