import dev.shayxo.composedoc.model.*;
import dev.shayxo.composedoc.domain.*;
import dev.shayxo.composedoc.data.*;
import dev.shayxo.composedoc.layout.*;
import dev.shayxo.composedoc.ui.*;
import dev.shayxo.composedoc.workspace.*;
import dev.shayxo.composedoc.harness.*;
import editor.MainKt;
import javax.swing.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;

/** Actual worker adapters with an EDT heartbeat; no computer control or visible windows. */
public class WorkspaceIoResponsiveness {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);Files.createDirectories(root);
    var image=new java.awt.image.BufferedImage(3000,2000,java.awt.image.BufferedImage.TYPE_INT_ARGB);
    var graphics=image.createGraphics();graphics.setPaint(new java.awt.GradientPaint(0,0,java.awt.Color.BLUE,3000,2000,java.awt.Color.ORANGE));graphics.fillRect(0,0,3000,2000);graphics.dispose();
    Path png=root.resolve("owned-6mp.png");javax.imageio.ImageIO.write(image,"png",png.toFile());image=null;
    AtomicLong previous=new AtomicLong(System.nanoTime()),maxGap=new AtomicLong();AtomicInteger ticks=new AtomicInteger(),success=new AtomicInteger(),refused=new AtomicInteger();
    AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch images=new CountDownLatch(6);
    Timer heartbeat=new Timer(10,e->{long now=System.nanoTime(),gap=now-previous.getAndSet(now);maxGap.accumulateAndGet(gap,Math::max);ticks.incrementAndGet();});
    long start=System.nanoTime();SwingUtilities.invokeAndWait(()->{heartbeat.start();for(int n=0;n<6;n++)ImageResources.INSTANCE.importAsync(png,pair->{
      if(!SwingUtilities.isEventDispatchThread() || pair.getSecond().getWidth()!=3000 || pair.getSecond().getHeight()!=2000)failure.set(new AssertionError("wrong callback/dimensions"));
      success.incrementAndGet();images.countDown();return Unit.INSTANCE;
    },error->{if(error instanceof IllegalStateException && error.getMessage().contains("queue is full"))refused.incrementAndGet();else failure.set(error);images.countDown();return Unit.INSTANCE;});});
    if(!images.await(30,TimeUnit.SECONDS))throw new AssertionError("image timeout");
    long imageMs=(System.nanoTime()-start)/1_000_000;int imageTicks=ticks.get();long imageGap=maxGap.get()/1_000_000;
    if(failure.get()!=null || success.get()!=5 || refused.get()!=1 || imageTicks<2)throw new AssertionError("image worker/queue/heartbeat",failure.get());
    Document doc=WorkspaceChecksKt.workspaceFixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    LocalRecoveryStore store=new LocalRecoveryStore(root.resolve("recovery"),stage->{if(stage.equals("before-publish")){entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException x){throw new RuntimeException(x);}}return Unit.INSTANCE;});
    AtomicReference<LocalRecoveryController> controller=new AtomicReference<>();
    SwingUtilities.invokeAndWait(()->{controller.set(new LocalRecoveryController(store,()->Unit.INSTANCE,60000));controller.get().observe(doc,null);controller.get().checkpointNow();});
    if(!entered.await(10,TimeUnit.SECONDS))throw new AssertionError("recovery worker did not enter");
    int before=ticks.get();maxGap.set(0);Thread.sleep(250);int during=ticks.get()-before;long recoveryGap=maxGap.get()/1_000_000;
    if(during<10)throw new AssertionError("EDT blocked by recovery IO");release.countDown();
    var stateMethod=java.util.Arrays.stream(LocalRecoveryController.class.getMethods()).filter(m->m.getName().startsWith("state")&&m.getParameterCount()==0).findFirst().orElseThrow();
    long end=System.nanoTime()+10_000_000_000L;
    while(!Long.valueOf(1L).equals(((java.util.Map<?,?>)stateMethod.invoke(controller.get())).get("completed"))){if(System.nanoTime()>end)throw new AssertionError("checkpoint did not finish");Thread.sleep(10);}
    SwingUtilities.invokeAndWait(()->{controller.get().finish(false);heartbeat.stop();});
    System.out.println("{\"status\":\"PASS\",\"imagePixels\":6000000,\"imageEncodedBytes\":"+Files.size(png)+",\"acceptedImports\":"+success+",\"queueRefusals\":"+refused+",\"imageBatchMs\":"+imageMs+",\"imageHeartbeatTicks\":"+imageTicks+",\"imageMaxHeartbeatGapMs\":"+imageGap+",\"recoveryBlockedWorkerMs\":250,\"recoveryHeartbeatTicks\":"+during+",\"recoveryMaxHeartbeatGapMs\":"+recoveryGap+"}");
    System.exit(0);
  }
}
