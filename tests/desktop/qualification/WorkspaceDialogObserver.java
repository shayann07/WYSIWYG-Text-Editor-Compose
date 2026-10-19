import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Read-only owned-process diagnostics. Never focuses, selects, clicks or mutates a document. */
public final class WorkspaceDialogObserver {
  static final BlockingQueue<String> rows=new LinkedBlockingQueue<>(1000);
  static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
  static String title(Window w){return w instanceof Frame?((Frame)w).getTitle():w instanceof Dialog?((Dialog)w).getTitle():w.getClass().getSimpleName();}
  static String component(Component c){return c==null?"null":quote(c.getClass().getSimpleName()+"/"+c.getName());}
  static void log(String s){if(!rows.offer("{\"time\":"+System.currentTimeMillis()+","+s+"}"))throw new IllegalStateException("Observer diagnostic queue full");}
  static String state(){
    var k=KeyboardFocusManager.getCurrentKeyboardFocusManager();StringBuilder s=new StringBuilder("\"focus\":"+component(k.getFocusOwner())+",\"windows\":[");boolean first=true;
    for(Window w:Window.getWindows())if(w.isDisplayable()){
      if(!first)s.append(',');first=false;Rectangle b=w.getBounds();
      s.append("{\"title\":").append(quote(title(w))).append(",\"class\":").append(quote(w.getClass().getName())).append(",\"owner\":").append(w.getOwner()==null?"null":quote(title(w.getOwner()))).append(",\"active\":").append(w.isActive()).append(",\"focused\":").append(w.isFocused()).append(",\"enabled\":").append(w.isEnabled()).append(",\"bounds\":[").append(b.x).append(',').append(b.y).append(',').append(b.width).append(',').append(b.height).append("],\"modal\":").append(w instanceof Dialog&&((Dialog)w).isModal()).append(",\"lists\":[");
      java.util.List<JList<?>> lists=new java.util.ArrayList<>();collect(w,lists);for(int n=0;n<lists.size();n++){if(n>0)s.append(',');JList<?> l=lists.get(n);s.append("{\"name\":").append(quote(String.valueOf(l.getName()))).append(",\"selected\":").append(l.getSelectedIndex()).append(",\"count\":").append(l.getModel().getSize()).append('}');}s.append("]}");
    }return s.append(']').toString();
  }
  static void collect(Component c,java.util.List<JList<?>> out){if(c instanceof JList<?>)out.add((JList<?>)c);if(c instanceof Container)for(Component child:((Container)c).getComponents())collect(child,out);}
  public static void main(String[] args)throws Exception{
    String root=java.util.Arrays.stream(args).filter(x->x.startsWith("--root=")).findFirst().orElseThrow().substring(7);Path file=Path.of(root,"dialog-observer-"+ProcessHandle.current().pid()+".jsonl");
    Thread writer=new Thread(()->{try{for(;;)Files.writeString(file,rows.take()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){e.printStackTrace();}});writer.setDaemon(true);writer.start();
    Toolkit.getDefaultToolkit().addAWTEventListener(e->{
      if(e instanceof MouseEvent && (e.getID()==MouseEvent.MOUSE_PRESSED||e.getID()==MouseEvent.MOUSE_RELEASED||e.getID()==MouseEvent.MOUSE_CLICKED)){
        MouseEvent m=(MouseEvent)e;log("\"event\":\"mouse\",\"id\":"+m.getID()+",\"target\":"+component(m.getComponent())+",\"point\":["+m.getX()+","+m.getY()+"],"+state());
      }else if(e instanceof KeyEvent && e.getID()==KeyEvent.KEY_PRESSED){KeyEvent k=(KeyEvent)e;log("\"event\":\"key\",\"code\":"+k.getKeyCode()+",\"target\":"+component(k.getComponent())+","+state());}
    },AWTEvent.MOUSE_EVENT_MASK|AWTEvent.KEY_EVENT_MASK);
    SwingUtilities.invokeAndWait(()->{final String[] previous={""};new Timer(200,e->{String s=state();if(!s.equals(previous[0])){previous[0]=s;log("\"event\":\"state\","+s);}}).start();});
    editor.MainKt.main(args);
  }
}
