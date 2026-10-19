import dev.shayxo.composedoc.model.*;
import dev.shayxo.composedoc.domain.*;
import dev.shayxo.composedoc.data.*;
import dev.shayxo.composedoc.layout.*;
import dev.shayxo.composedoc.ui.*;
import dev.shayxo.composedoc.workspace.*;
import dev.shayxo.composedoc.harness.*;
import editor.MainKt;import javax.swing.*;import java.nio.file.*;import java.util.*;
/** Negative control against the accepted old artifact; setup is synthetic, termination is real. */
public class OldUnfinishedLossChild {
 static JFrame w;static EditorSurface s;static LocalRecoveryController controller;
 public static void main(String[] args)throws Exception {
  Path out=Path.of(args[0]);Files.createDirectories(out);var d=LocalRecoveryChecksKt.crashSpecimen("baseline");
  SwingUtilities.invokeAndWait(()->{
   w=DesktopWorkspaceKt.showEditor(null,d,null,null,null,true,null,"noto-nastaliq",true,out.resolve("recovery"));s=DesktopWorkspaceKt.windowCurrentSurface(w);
   s.getEngine().move(new Selection(0,0),true,TypingSide.LEFT,WrapAffinity.DOWNSTREAM);s.getEngine().inputMethod("نامکمل کِ 👩🏽‍💻",0,3);
   controller=(LocalRecoveryController)w.getRootPane().getClientProperty("editor.localRecovery");controller.checkpointNow();
  });
  var state=Arrays.stream(LocalRecoveryController.class.getMethods()).filter(m->m.getName().startsWith("state")&&m.getParameterCount()==0).findFirst().orElseThrow();long deadline=System.nanoTime()+15_000_000_000L;
  while(!Long.valueOf(1L).equals(((Map<?,?>)state.invoke(controller)).get("completed"))){if(System.nanoTime()>deadline)throw new Error("old checkpoint timeout");Thread.sleep(10);}
  final Document[] documents=new Document[2];SwingUtilities.invokeAndWait(()->{var f=s.getEngine().snapshot();if(f.getMarked()==null||f.getVisible().getText().equals(f.getCommitted().getText()))throw new Error("No actual old provisional state");documents[0]=f.getCommitted();documents[1]=f.getVisible();});
  Files.write(out.resolve("expected-committed-v7.astra"),DocumentFiles.INSTANCE.encode(documents[0]));Files.write(out.resolve("expected-visible-v7.astra"),DocumentFiles.INSTANCE.encode(documents[1]));
  System.out.println("READY "+ProcessHandle.current().pid()+" "+controller.getSession());System.out.flush();new java.util.concurrent.CountDownLatch(1).await();
 }
}
