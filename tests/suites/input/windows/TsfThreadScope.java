import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Owned fixture thread's TSF lifetime. Never enables a profile or changes defaults. */
final class TsfThreadScope implements AutoCloseable {
  final Thread owner=Thread.currentThread();final Arena arena=Arena.ofConfined();final Linker linker=Linker.nativeLinker();
  MethodHandle uninit,release,deactivate;MemorySegment manager=MemorySegment.NULL;int initialized=-1;boolean active;
  TsfThreadScope()throws Throwable{
    try{
      SymbolLookup ole=SymbolLookup.libraryLookup("ole32.dll",arena);
      MethodHandle init=linker.downcallHandle(ole.find("CoInitializeEx").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT));
      uninit=linker.downcallHandle(ole.find("CoUninitialize").orElseThrow(),FunctionDescriptor.ofVoid());
      initialized=(int)init.invokeExact(MemorySegment.NULL,2);
      if(initialized<0&&initialized!=0x80010106)throw new IllegalStateException("TSF thread COM initialization failed");
      MethodHandle create=linker.downcallHandle(ole.find("CoCreateInstance").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,ADDRESS,ADDRESS));
      MemorySegment out=arena.allocate(ADDRESS);
      int result=(int)create.invokeExact(TsfProfileProbe.guid(arena,"529a9e6b-6587-4f23-ab9e-9c7d683e3c50"),MemorySegment.NULL,1,
        TsfProfileProbe.guid(arena,"aa80e801-2021-11d2-93e0-0060b067b86e"),out);
      if(result!=0)throw new IllegalStateException("TSF thread manager creation failed");
      manager=out.get(ADDRESS,0);MemorySegment table=manager.reinterpret(8).get(ADDRESS,0).reinterpret(5*8L);
      release=linker.downcallHandle(table.getAtIndex(ADDRESS,2),FunctionDescriptor.of(JAVA_INT,ADDRESS));
      MethodHandle activate=linker.downcallHandle(table.getAtIndex(ADDRESS,3),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS));
      deactivate=linker.downcallHandle(table.getAtIndex(ADDRESS,4),FunctionDescriptor.of(JAVA_INT,ADDRESS));
      MemorySegment client=arena.allocate(JAVA_INT);result=(int)activate.invokeExact(manager,client);
      if(result!=0)throw new IllegalStateException("TSF thread activation failed");active=true;
    }catch(Throwable failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
  }
  public void close(){
    if(Thread.currentThread()!=owner)throw new IllegalStateException("TSF thread scope must close on its creating thread");
    try{if(active){int result=(int)deactivate.invokeExact(manager);active=false;if(result!=0)throw new IllegalStateException("TSF thread deactivation failed");}}
    catch(Throwable error){throw new IllegalStateException(error);}
    finally{try{if(release!=null){int remaining=(int)release.invokeExact(manager);}if(uninit!=null&&initialized>=0)uninit.invokeExact();}
      catch(Throwable error){throw new IllegalStateException(error);}finally{arena.close();}}
  }
}
