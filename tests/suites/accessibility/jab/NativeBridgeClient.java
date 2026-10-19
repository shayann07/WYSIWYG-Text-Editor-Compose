import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.*;
import java.io.*;
import static java.lang.foreign.ValueLayout.*;

/** JDK 23 tooling only: official JAB ABI against one PID-verified owned fixture.
 * No mouse, keyboard, focus activation, generic desktop automation or persistent settings. */
public final class NativeBridgeClient implements AutoCloseable {
  final Arena arena = Arena.ofConfined();
  final Linker linker = Linker.nativeLinker();
  final SymbolLookup bridge, user;
  final List<Long> references = new ArrayList<>();
  final MethodHandle release, contextInfo, child, peek, translate, dispatch;
  int vm;
  long editorContext;
  final List<Map<String,Object>> tree = new ArrayList<>();
  int releasedReferences;
  MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor type) {
    return linker.downcallHandle(library.find(name).orElseThrow(()->new IllegalStateException("Missing native ABI export: "+name)),type);
  }
  Object call(MethodHandle handle, Object... args) throws Throwable {return handle.invokeWithArguments(args);}
  int integer(MethodHandle handle, Object... args) throws Throwable {return ((Number)call(handle,args)).intValue();}
  MemorySegment wide(String text) {
    MemorySegment result=arena.allocate((text.length()+1L)*2,2);
    for(int i=0;i<text.length();i++) result.set(JAVA_CHAR,i*2L,text.charAt(i));
    result.set(JAVA_CHAR,text.length()*2L,'\0'); return result;
  }
  String string(MemorySegment bytes, long offset, int length) {
    StringBuilder result=new StringBuilder();
    for(int i=0;i<length;i++){char c=bytes.get(JAVA_CHAR,offset+i*2L);if(c==0)break;result.append(c);}return result.toString();
  }
  void pump() throws Throwable {
    MemorySegment message=arena.allocate(64,8);
    while(integer(peek,message,MemorySegment.NULL,0,0,1)!=0){call(translate,message);call(dispatch,message);}
  }
  NativeBridgeClient(String dll,String title,long expectedPid) throws Throwable {
    try {
    bridge=SymbolLookup.libraryLookup(Path.of(dll),arena); user=SymbolLookup.libraryLookup("user32.dll",arena);
    release=bind(bridge,"releaseJavaObject",FunctionDescriptor.ofVoid(JAVA_INT,JAVA_LONG));
    contextInfo=bind(bridge,"getAccessibleContextInfo",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_LONG,ADDRESS));
    child=bind(bridge,"getAccessibleChildFromContext",FunctionDescriptor.of(JAVA_LONG,JAVA_INT,JAVA_LONG,JAVA_INT));
    peek=bind(user,"PeekMessageW",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT));
    translate=bind(user,"TranslateMessage",FunctionDescriptor.of(JAVA_INT,ADDRESS));
    dispatch=bind(user,"DispatchMessageW",FunctionDescriptor.of(JAVA_LONG,ADDRESS));
    call(bind(bridge,"Windows_run",FunctionDescriptor.ofVoid()));
    MethodHandle find=bind(user,"FindWindowW",FunctionDescriptor.of(ADDRESS,ADDRESS,ADDRESS));
    MethodHandle pid=bind(user,"GetWindowThreadProcessId",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS));
    MethodHandle javaWindow=bind(bridge,"isJavaWindow",FunctionDescriptor.of(JAVA_INT,ADDRESS));
    MethodHandle root=bind(bridge,"getAccessibleContextFromHWND",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS));
    MemorySegment targetTitle=wide(title), owner=arena.allocate(JAVA_INT), vmOut=arena.allocate(JAVA_INT), context=arena.allocate(JAVA_LONG);
    long deadline=System.nanoTime()+12_000_000_000L; boolean connected=false;
    while(System.nanoTime()<deadline){
      pump(); MemorySegment window=(MemorySegment)call(find,MemorySegment.NULL,targetTitle);
      if(window.address()!=0){
        integer(pid,window,owner);
        if(Integer.toUnsignedLong(owner.get(JAVA_INT,0))!=expectedPid)throw new IllegalStateException("Target HWND belongs to another PID");
        if(integer(javaWindow,window)!=0 && integer(root,window,vmOut,context)!=0){
          vm=vmOut.get(JAVA_INT,0);long ac=context.get(JAVA_LONG,0);references.add(ac);walk(ac,0);connected=true;break;
        }
      }
      Thread.sleep(20); // bounded native handshake for the known live fixture
    }
    if(!connected)throw new IllegalStateException("JAB did not connect to the PID-verified owned window within 12 seconds");
    if(editorContext==0)throw new IllegalStateException("Owned window tree contains no Document editor AccessibleText");
    } catch(Throwable failure) {try {close();} catch(Throwable cleanup) {failure.addSuppressed(cleanup);} throw failure;}
  }
  void walk(long context,int depth) throws Throwable {
    if(tree.size()>=500 || depth>=32)throw new IllegalStateException("Native accessibility tree exceeded bounded fixture");
    MemorySegment info=arena.allocate(6188,4);
    if(integer(contextInfo,vm,context,info)==0)throw new IllegalStateException("Native getAccessibleContextInfo refused");
    String name=string(info,0,1024),role=string(info,4608,256);int count=info.get(JAVA_INT,6148),text=info.get(JAVA_INT,6180);
    tree.add(Map.of("depth",depth,"name",name,"role",role,"children",count,"accessibleText",text!=0));
    if(name.equals("Document editor")){if(editorContext!=0)throw new IllegalStateException("Duplicate document editor in native tree");if(text==0)throw new IllegalStateException("Native editor lacks AccessibleText");editorContext=context;}
    if(count<0 || count>200)throw new IllegalStateException("Invalid native child count");
    for(int i=0;i<count;i++){long next=((Number)call(child,vm,context,i)).longValue();if(next!=0){references.add(next);walk(next,depth+1);}}
  }
  Map<String,Object> snapshot() throws Throwable {
    MemorySegment info=arena.allocate(12,4);
    int status=integer(bind(bridge,"getAccessibleTextInfo",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT,JAVA_INT)),vm,editorContext,info,0,0);
    if(status==0)throw new IllegalStateException("Native text info failed");
    int count=info.get(JAVA_INT,0);if(count<0||count>10000)throw new IllegalStateException("Native text count exceeds fixture");
    StringBuilder text=new StringBuilder();
    MethodHandle range=bind(bridge,"getAccessibleTextRange",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_INT,ADDRESS,JAVA_SHORT));
    for(int start=0;start<count;start+=800){int end=Math.min(count,start+800);MemorySegment value=arena.allocate(2048,2);
      if(integer(range,vm,editorContext,start,end-1,value,(short)1024)==0)throw new IllegalStateException("Native text range failed");text.append(string(value,0,1024));}
    MemorySegment selection=arena.allocate(2056,4);
    if(integer(bind(bridge,"getAccessibleTextSelectionInfo",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_LONG,ADDRESS)),vm,editorContext,selection)==0)throw new IllegalStateException("Native selection read failed");
    return Map.of("count",count,"caret",info.get(JAVA_INT,4),"text",text.toString(),"selection",List.of(selection.get(JAVA_INT,0),selection.get(JAVA_INT,4)),
      "selectedText",string(selection,8,1024));
  }
  Map<String,Object> request(Map<?,?> request) throws Throwable {
    String command=(String)request.get("command");int status=1;
    if(command.equals("set-text")){
      String value=(String)request.get("text");if(value.indexOf('\0')>=0||value.length()>1023)throw new IllegalArgumentException("JAB wchar string constraint");
      status=integer(bind(bridge,"setTextContents",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_LONG,ADDRESS)),vm,editorContext,wide(value));
    }else if(command.equals("select")){
      status=integer(bind(bridge,"selectTextRange",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_INT)),vm,editorContext,
        ((Number)request.get("start")).intValue(),((Number)request.get("end")).intValue());
    }else if(command.equals("rect")){
      MemorySegment rect=arena.allocate(16,4);int index=((Number)request.get("index")).intValue();
      status=integer(bind(bridge,"getAccessibleTextRect",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT)),vm,editorContext,rect,index);
      return Map.of("status",status,"bounds",List.of(rect.get(JAVA_INT,0),rect.get(JAVA_INT,4),rect.get(JAVA_INT,8),rect.get(JAVA_INT,12)));
    }else if(command.equals("attributes")){
      MemorySegment attrs=arena.allocate(8192,4);int index=((Number)request.get("index")).intValue();
      status=integer(bind(bridge,"getAccessibleTextAttributes",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_INT,ADDRESS)),vm,editorContext,index,attrs);
      return Map.of("status",status,"bold",attrs.get(JAVA_INT,0)!=0,"italic",attrs.get(JAVA_INT,4)!=0,"fontFamily",string(attrs,1048,256),"fontSize",attrs.get(JAVA_INT,1560));
    }else if(!command.equals("snapshot"))throw new IllegalArgumentException("Unsupported native AT fixture operation");
    return Map.of("status",status,"frame",snapshot());
  }
  public void close(){
    RuntimeException failure=null;
    for(long object:references)try{call(release,vm,object);releasedReferences++;}catch(Throwable error){if(failure==null)failure=new IllegalStateException("JAB reference release failed",error);else failure.addSuppressed(error);}
    arena.close();if(failure!=null)throw failure;
  }
  static final Writer OUTPUT = new BufferedWriter(new OutputStreamWriter(System.out,java.nio.charset.StandardCharsets.UTF_8));
  static void emit(Object value)throws IOException{OUTPUT.write(editor.PageProbeKt.evidenceJson(value));OUTPUT.write("\n");OUTPUT.flush();}
  public static void main(String[] args)throws Throwable{
    Timer deadline=new Timer("Native JAB fixture deadline",true);
    deadline.schedule(new TimerTask(){public void run(){Runtime.getRuntime().halt(124);}},85000);
    try {
    NativeBridgeClient client=new NativeBridgeClient(args[0],args[1],Long.parseLong(args[2]));
    try(client){
      emit(Map.of("status","READY","tree",client.tree,"frame",client.snapshot(),"acquiredCookies",client.references.size()));
      BufferedReader input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));
      for(String line;(line=input.readLine())!=null;){
        Map<?,?> request=(Map<?,?>)new editor.SimpleJson(line).parse();
        if("shutdown".equals(request.get("command"))){emit(Map.of("status","BYE"));break;}
        client.pump();emit(client.request(request));
      }
    }
    emit(Map.of("status","CLOSED","releasedCookies",client.releasedReferences,"acquiredCookies",client.references.size()));
    } finally {deadline.cancel();}
  }
}
