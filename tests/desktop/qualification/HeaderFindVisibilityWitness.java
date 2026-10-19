import dev.shayxo.composedoc.model.*;
import dev.shayxo.composedoc.domain.*;
import dev.shayxo.composedoc.data.*;
import dev.shayxo.composedoc.layout.*;
import dev.shayxo.composedoc.ui.*;
import dev.shayxo.composedoc.workspace.*;
import dev.shayxo.composedoc.harness.*;
import editor.MainKt;import javax.swing.*;import java.awt.*;import java.awt.image.*;import java.nio.file.*;
public class HeaderFindVisibilityWitness {
 static JFrame w;static FindReplaceUi find;static int before;
 public static void main(String[] args)throws Exception {
  var d=WorkspaceChecksKt.workspaceFixture();SwingUtilities.invokeAndWait(()->{w=DesktopWorkspaceKt.showEditor(null,d,null,null,null,true,null,"noto-nastaliq",true,null);w.setSize(1020,740);w.validate();find=(FindReplaceUi)w.getRootPane().getClientProperty("editor.find");});Thread.sleep(200);
  SwingUtilities.invokeAndWait(()->{before=w.getContentPane().getComponent(0).getHeight();find.open(true);});Thread.sleep(300);
  SwingUtilities.invokeAndWait(()->{var q=find.getQuery();int after=w.getContentPane().getComponent(0).getHeight();boolean pass=q.getHeight()>0 && q.getVisibleRect().height==q.getHeight() && after>before;
   System.out.println("{\"visible\":"+pass+",\"beforeHeight\":"+before+",\"afterHeight\":"+after+",\"queryHeight\":"+q.getHeight()+",\"visibleQueryHeight\":"+q.getVisibleRect().height+"}");
   try{var b=new BufferedImage(w.getWidth(),w.getHeight(),BufferedImage.TYPE_INT_RGB);var g=b.createGraphics();w.paint(g);g.dispose();javax.imageio.ImageIO.write(b,"png",Path.of(args[0]).toFile());}catch(Exception x){throw new RuntimeException(x);}finally{w.dispose();}
  });System.exit(0);
 }
}
