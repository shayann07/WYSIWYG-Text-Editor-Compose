import dev.shayxo.composedoc.model.*;
import dev.shayxo.composedoc.domain.*;
import dev.shayxo.composedoc.data.*;
import dev.shayxo.composedoc.layout.*;
import dev.shayxo.composedoc.ui.*;
import dev.shayxo.composedoc.workspace.*;
import dev.shayxo.composedoc.harness.*;
import editor.MainKt;import javax.swing.*;import java.awt.*;import java.awt.event.*;import java.nio.file.*;import androidx.compose.ui.graphics.SkiaBackedPath;
/** Independent logical membership expectations; backend rectangles locate cells, not decide membership. */
public class SelectionCoverageWitness {
 static JFrame w;static EditorSurface s;
 static void owner(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
 static void check(boolean b,String name){if(!b)throw new AssertionError(name);}
 public static void main(String[]args)throws Exception{
  var async=new java.util.concurrent.atomic.AtomicReference<Throwable>();Thread.setDefaultUncaughtExceptionHandler((t,e)->{async.set(e);e.printStackTrace();});int rows=0,cells=0,clicks=0;
  try{for(boolean paged:new boolean[]{false,true})for(float zoom:new float[]{.5f,.75f,1f}){
   EditorEngine e=new EditorEngine();e.replace("abc אבג xyz\nאבג abc דהו\n\n"+"کتاب مُحَمَّد ".repeat(30),"fixture",true,new Selection(0,0),null);
   owner(()->{w=DesktopWorkspaceKt.showEditor(null,e.snapshot().getCommitted(),null,null,null,true,null,"noto-nastaliq",paged,null);s=DesktopWorkspaceKt.windowCurrentSurface(w);s.getView().changeZoom(zoom);w.setSize(1020,740);w.validate();});
   long deadline=System.nanoTime()+20_000_000_000L;while(s.getView().getMeasure()==null){if(System.nanoTime()>deadline)throw new Error("No layout");Thread.sleep(20);}
   final int[] result={0,0};owner(()->{
    var original=s.getEngine().snapshot();var g=s.getView().getMeasure().invoke(original);var p=g.getParagraphs().getFirst();
    for(int[] range:new int[][]{{0,3},{4,7},{8,11},{2,9}}){
     var path=androidx.compose.ui.graphics.SkiaBackedPath_skikoKt.asSkiaPath(p.getLayout().getPathForRange(range[0],range[1]));
     for(int i=0;i<11;i++)if(p.getText().charAt(i)!=' '){var box=p.getLayout().getBoundingBox(i);boolean expected=i>=range[0]&&i<range[1];check(path.contains((box.getLeft()+box.getRight())*.5f,(box.getTop()+box.getBottom())*.5f)==expected,"Selected/unselected logical cell "+i+" range "+range[0]+":"+range[1]);result[0]++;}
     s.getEngine().move(new Selection(range[0],range[1]),true,TypingSide.LEFT,WrapAffinity.DOWNSTREAM);var forward=s.getEngine().snapshot().getSelection();s.getEngine().move(new Selection(range[1],range[0]),true,TypingSide.LEFT,WrapAffinity.DOWNSTREAM);var back=s.getEngine().snapshot().getSelection();check(forward.getStart()==back.getStart()&&forward.getEnd()==back.getEnd(),"directional selected scope");
    }
    // These interior Latin/Hebrew edges have unique fixture associations. No universal bidi bijection assertion.
    for(int at:new int[]{1,5,6,9}){var caret=g.caret(at,true,WrapAffinity.DOWNSTREAM);var ev=new MouseEvent(s,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,Math.round(caret.getLeft()/g.getScale()),Math.round((caret.getTop()+caret.getHeight()*.5f)/g.getScale()),1,false,MouseEvent.BUTTON1);for(var listener:s.getMouseListeners())listener.mousePressed(ev);check(s.getEngine().snapshot().getSelection().getFocus()==at,"Expected fixture click association "+at);result[1]++;}
    check(s.getEngine().snapshot().getCommitted().equals(original.getCommitted())&&s.getEngine().snapshot().getUndoCount()==0,"geometry mutated content/history");
    s.getEngine().move(new Selection(2,9),true,TypingSide.LEFT,WrapAffinity.DOWNSTREAM);
   });Thread.sleep(80);owner(()->{try{var bitmap=new java.awt.image.BufferedImage(w.getWidth(),w.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=bitmap.createGraphics();w.paint(graphics);graphics.dispose();javax.imageio.ImageIO.write(bitmap,"png",Path.of(args[0],"selection-"+paged+"-"+zoom+".png").toFile());}catch(Exception ex){throw new RuntimeException(ex);}finally{w.dispose();w=null;}});rows++;cells+=result[0];clicks+=result[1];
  }check(async.get()==null,"Async UI failure");System.out.println("PASS "+rows+" view/zoom rows, "+cells+" selected/unselected cell memberships, "+clicks+" actual pointer-handler associations; no content/history mutation");System.exit(0);
  }finally{owner(()->{if(w!=null)w.dispose();});}
 }
}
