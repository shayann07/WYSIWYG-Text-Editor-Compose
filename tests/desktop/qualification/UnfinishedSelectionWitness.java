import dev.shayxo.composedoc.model.*;
import dev.shayxo.composedoc.domain.*;
import dev.shayxo.composedoc.data.*;
import dev.shayxo.composedoc.layout.*;
import dev.shayxo.composedoc.ui.*;
import dev.shayxo.composedoc.workspace.*;
import dev.shayxo.composedoc.harness.*;
import editor.MainKt;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic mounted production-handler regression; never native desktop evidence. */
public final class UnfinishedSelectionWitness {
  static JFrame frame;static JList<?> list;
  static <T extends Component>T find(Component c,Class<T> cls,String name){if(cls.isInstance(c)&&name.equals(c.getName()))return cls.cast(c);if(c instanceof Container)for(Component x:((Container)c).getComponents()){T v=find(x,cls,name);if(v!=null)return v;}return null;}
  static JButton textButton(Component c,String text){if(c instanceof JButton&&text.equals(((JButton)c).getText()))return (JButton)c;if(c instanceof Container)for(Component x:((Container)c).getComponents()){JButton b=textButton(x,text);if(b!=null)return b;}return null;}
  static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
  static void waitFor(java.util.function.BooleanSupplier test)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){AtomicBoolean done=new AtomicBoolean();edt(()->done.set(test.getAsBoolean()));if(done.get())return;Thread.sleep(20);}throw new AssertionError("Owned mounted condition timed out");}
  public static void main(String[] args)throws Exception{
    if(args.length>2&&args[2].equals("recent")){recent(args);return;}
    Path source=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);Path root=output.resolve("copied-unfinished");
    try(var paths=Files.walk(source)){for(Path p:paths.toList()){Path target=root.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(target);else Files.copy(p,target);}}
    CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(10);AtomicReference<Throwable> failure=new AtomicReference<>();Thread.setDefaultUncaughtExceptionHandler((t,e)->{failure.set(e);e.printStackTrace();});
    try{
      edt(()->{frame=UnfinishedRecoveryUiKt.showUnfinishedRecovery(root,"noto-nastaliq",true,false,true);});
      waitFor(()->{list=find(frame,JList.class,"unfinished-list");return list!=null&&list.getModel().getSize()==2;});
      String[] ids=new String[2];edt(()->{ids[0]=((UnfinishedEntry)list.getModel().getElementAt(0)).getId();ids[1]=((UnfinishedEntry)list.getModel().getElementAt(1)).getId();list.setSelectedIndex(0);});
      // Hold the real shared SwingWorker pool, not the document/store implementation.
      for(int n=0;n<10;n++)new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{started.countDown();release.await();return null;}}.execute();
      if(!started.await(5,TimeUnit.SECONDS))throw new AssertionError("Workers did not enter barrier");
      SwingUtilities.invokeLater(()->find(frame,JButton.class,"unfinished-discard").doClick());
      waitFor(()->java.util.Arrays.stream(Window.getWindows()).anyMatch(w->w instanceof JDialog&&w.isVisible()&&((JDialog)w).getTitle().equals("Discard unfinished record")));
      edt(()->{Window dialog=java.util.Arrays.stream(Window.getWindows()).filter(w->w instanceof JDialog&&w.isVisible()&&((JDialog)w).getTitle().equals("Discard unfinished record")).findFirst().orElseThrow();textButton(dialog,"Yes").doClick();});
      edt(()->list.setSelectedIndex(1));release.countDown();
      waitFor(()->list.getModel().getSize()==1);
      String[] visible={null},owner={null};edt(()->{UnfinishedEntry e=(UnfinishedEntry)list.getModel().getElementAt(0);visible[0]=e.getId();owner[0]=e.getOwner();});
      UnfinishedStore store=new UnfinishedStore(new LocalRecoveryStore(root,x->kotlin.Unit.INSTANCE),64L*1024*1024,16);
      var kept=store.entries(owner[0]);boolean exact=kept.size()==1&&kept.get(0).getId().equals(ids[1])&&visible[0].equals(ids[1]);
      String json="{\"status\":\""+(exact?"PASS":"FAIL")+"\",\"captured\":\""+ids[0]+"\",\"other\":\""+ids[1]+"\",\"visible\":\""+visible[0]+"\",\"persisted\":\""+kept.get(0).getId()+"\",\"evidenceLevel\":\"Synthetic mounted actual confirmation/action/worker; not native interaction\"}";
      Files.writeString(output.resolve("results.json"),json+"\n");System.out.println(json);
      if(!exact)throw new AssertionError("Completed discard removed the newly selected UI row instead of the captured record");
      if(failure.get()!=null)throw new AssertionError(failure.get());
    }finally{release.countDown();edt(()->{for(Window w:Window.getWindows())w.dispose();});}
  }
  static void recent(String[] args)throws Exception{
    Path output=Path.of(args[1]);Files.createDirectories(output);Path root=output.resolve("recovery");WorkspaceStore store=new WorkspaceStore(root.resolve("workspace"));String[] ids={java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString()};
    for(int n=0;n<2;n++)store.update(new WorkspaceEntry(ids[n],null,"Owned-"+n,null,1+n,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),new Selection(0,0),0,true,1,"noto-nastaliq",false,ProcessHandle.current().pid(),0,0,""),x->kotlin.Unit.INSTANCE);
    CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(10);Document document=DocumentFiles.INSTANCE.read(Path.of(args[0]));
    try{
      edt(()->{frame=DesktopWorkspaceKt.showEditor(null,document,null,null,null,true,null,"noto-nastaliq",true,root);((WorkspaceUi)frame.getRootPane().getClientProperty("editor.workspace")).showRecent(false);});
      AtomicReference<JDialog> dialog=new AtomicReference<>();
      waitFor(()->{for(Window w:Window.getWindows())if(w instanceof JDialog&&w.isVisible()&&((JDialog)w).getTitle().equals("Recent documents / restore last workspace")){dialog.set((JDialog)w);list=find(w,JList.class,"workspace-recent-list");return list!=null&&list.getModel().getSize()==2;}return false;});
      String[] captured={null},other={null};edt(()->{captured[0]=((WorkspaceEntry)((kotlin.Pair<?,?>)list.getModel().getElementAt(0)).getFirst()).getId();other[0]=((WorkspaceEntry)((kotlin.Pair<?,?>)list.getModel().getElementAt(1)).getFirst()).getId();list.setSelectedIndex(0);});
      for(int n=0;n<10;n++)new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{started.countDown();release.await();return null;}}.execute();if(!started.await(5,TimeUnit.SECONDS))throw new AssertionError("Worker barrier");
      edt(()->{find(dialog.get(),JButton.class,"recent-remove").doClick();list.setSelectedIndex(1);});release.countDown();waitFor(()->list.getModel().getSize()==1);
      String[] visible={null};edt(()->visible[0]=((WorkspaceEntry)((kotlin.Pair<?,?>)list.getModel().getElementAt(0)).getFirst()).getId());
      boolean exact=visible[0].equals(other[0])&&store.entries().stream().anyMatch(e->e.getId().equals(other[0]))&&store.entries().stream().noneMatch(e->e.getId().equals(captured[0]));
      String json="{\"status\":\""+(exact?"PASS":"FAIL")+"\",\"captured\":\""+captured[0]+"\",\"other\":\""+other[0]+"\",\"visible\":\""+visible[0]+"\",\"evidenceLevel\":\"Synthetic mounted actual recent remove handler; not native interaction\"}";Files.writeString(output.resolve("results.json"),json+"\n");System.out.println(json);if(!exact)throw new AssertionError("Recent remove completed against the newly selected UI row");
    }finally{release.countDown();edt(()->{for(Window w:Window.getWindows())w.dispose();});}
  }
}
