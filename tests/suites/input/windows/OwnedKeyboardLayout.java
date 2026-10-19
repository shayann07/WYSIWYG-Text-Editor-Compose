import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** Existing Windows input-layout API setup for an owned test process. Never injects keys. */
public final class OwnedKeyboardLayout implements AutoCloseable {
  final Arena arena=Arena.ofShared(); final Linker linker=Linker.nativeLinker();
  final SymbolLookup user=SymbolLookup.libraryLookup("user32.dll",arena),kernel=SymbolLookup.libraryLookup("kernel32.dll",arena);
  final long pid=ProcessHandle.current().pid(), originalDefault, originalCaller;
  final String originalName;
  final List<String> originalLoaded;
  final Set<Long> introduced=new LinkedHashSet<>();
  final byte[] originalProfile;
  boolean profileAttempted;
  TsfThreadScope tsfThread;
  long guiThread, originalGui; boolean attempted;
  MethodHandle function(SymbolLookup library,String name,FunctionDescriptor type){return linker.downcallHandle(library.find(name).orElseThrow(),type);}
  Object call(String name,FunctionDescriptor type,Object...args)throws Throwable{return function(user,name,type).invokeWithArguments(args);}
  long pointer(MemorySegment segment){return segment.address();}
  MemorySegment handle(long value){return MemorySegment.ofAddress(value);}
  MemorySegment wide(String value){MemorySegment s=arena.allocate((value.length()+1L)*2,2);for(int i=0;i<value.length();i++)s.set(JAVA_CHAR,i*2L,value.charAt(i));s.set(JAVA_CHAR,value.length()*2L,'\0');return s;}
  String readWide(MemorySegment value,int limit){StringBuilder s=new StringBuilder();for(int i=0;i<limit;i++){char c=value.get(JAVA_CHAR,i*2L);if(c==0)break;s.append(c);}return s.toString();}
  String hex(long value){return String.format("0x%016x",value);}
  long layout(int thread)throws Throwable{return pointer((MemorySegment)call("GetKeyboardLayout",FunctionDescriptor.of(ADDRESS,JAVA_INT),thread));}
  long defaultLayout()throws Throwable{
    MemorySegment result=arena.allocate(ADDRESS);
    int status=((Number)call("SystemParametersInfoW",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS,JAVA_INT),0x59,0,result,0)).intValue();
    if(status==0)throw new IllegalStateException("Cannot read default input language");return pointer(result.get(ADDRESS,0));
  }
  List<String> loaded()throws Throwable{
    FunctionDescriptor type=FunctionDescriptor.of(JAVA_INT,JAVA_INT,ADDRESS);
    int count=((Number)call("GetKeyboardLayoutList",type,0,MemorySegment.NULL)).intValue();
    if(count<=0||count>256)throw new IllegalStateException("Unexpected loaded-layout count");
    MemorySegment values=arena.allocate(count*8L,8);int copied=((Number)call("GetKeyboardLayoutList",type,count,values)).intValue();
    List<String> result=new ArrayList<>();for(int i=0;i<copied;i++)result.add(hex(pointer(values.getAtIndex(ADDRESS,i))));return result;
  }
  List<Integer> translate(int key,long selected)throws Throwable{
    int scan=((Number)call("MapVirtualKeyExW",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS),key,0,handle(selected))).intValue();
    MemorySegment state=arena.allocate(256,1),text=arena.allocate(16,2);
    int count=((Number)call("ToUnicodeEx",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,JAVA_INT,ADDRESS),
      key,scan,state,text,8,4,handle(selected))).intValue(); // Windows 10+ bit 2 preserves keyboard/dead-key state.
    List<Integer> result=new ArrayList<>();for(int i=0;i<Math.max(0,Math.min(count,8));i++)result.add((int)text.get(JAVA_CHAR,i*2L));return result;
  }
  public OwnedKeyboardLayout()throws Throwable{
    originalDefault=defaultLayout();originalCaller=layout(0);originalLoaded=loaded();
    MemorySegment name=arena.allocate(18,2);
    if(((Number)call("GetKeyboardLayoutNameW",FunctionDescriptor.of(JAVA_INT,ADDRESS),name)).intValue()==0)throw new IllegalStateException("Cannot capture original layout name");
    originalName=readWide(name,9);
    byte[] captured;try{captured=TsfProfileProbe.captureActive();}catch(Throwable failure){captured=null;}
    originalProfile=captured;
  }
  boolean ownsForeground()throws Throwable{
    MemorySegment window=(MemorySegment)call("GetForegroundWindow",FunctionDescriptor.of(ADDRESS));
    MemorySegment owner=arena.allocate(JAVA_INT);
    int thread=((Number)call("GetWindowThreadProcessId",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS),window,owner)).intValue();
    if(Integer.toUnsignedLong(owner.get(JAVA_INT,0))!=pid)return false;
    if(guiThread==0){guiThread=Integer.toUnsignedLong(thread);originalGui=layout(thread);}return true;
  }
  public Map<String,Object> snapshot()throws Throwable{
    return Map.of("loaded",loaded(),"default",hex(defaultLayout()),"caller",hex(layout(0)),"originalName",originalName,
      "originalDefault",hex(originalDefault),"originalLoaded",originalLoaded,"gui",guiThread==0?"not-bound":hex(layout((int)guiThread)),"attempted",attempted,
      "originalProfileCaptured",originalProfile!=null,"profileAttempted",profileAttempted);
  }
  public Map<String,Object> tsf()throws Throwable{return TsfProfileProbe.query();}
  public Map<String,Object> prepareIme()throws Throwable{
    if(!ownsForeground())return Map.of("status","NeedsOwnedForeground");
    if(!attempted||(layout((int)guiThread)&0xffff)!=0x411)return Map.of("status","NeedsPreparedJapaneseLayout");
    if(originalProfile==null)return Map.of("status","RefusedMissingOriginalProfile");
    profileAttempted=true;
    if(tsfThread==null)tsfThread=new TsfThreadScope();
    Map<String,Object> result=TsfProfileProbe.activateJapaneseForProcess();
    for(String value:loaded()){
      long candidate=Long.parseUnsignedLong(value.substring(2),16);
      if((candidate&0xffff)==0x411&&!originalLoaded.contains(value))introduced.add(candidate);
    }
    if(defaultLayout()!=originalDefault)throw new IllegalStateException("Profile activation changed default; restore required");
    return result;
  }
  public Map<String,Object> prepare(String name)throws Throwable{
    if(!Set.of("00000420","00000411","00000401").contains(name))throw new IllegalArgumentException("Only the named native qualification locales are supported");
    if(!ownsForeground())return Map.of("status","NeedsOwnedForeground","state",snapshot());
    if(defaultLayout()!=originalDefault)throw new IllegalStateException("Default changed before owned test activation");
    List<String> before=loaded();attempted=true;
    long selected=pointer((MemorySegment)call("LoadKeyboardLayoutW",FunctionDescriptor.of(ADDRESS,ADDRESS,JAVA_INT),wide(name),0));
    if(selected!=0&&!before.contains(hex(selected)))introduced.add(selected);
    if(selected==0||(selected&0xffff)!=Integer.parseInt(name.substring(4),16))return Map.of("status","NativeLocaleUnavailable","state",snapshot());
    call("ActivateKeyboardLayout",FunctionDescriptor.of(ADDRESS,ADDRESS,JAVA_INT),handle(selected),0x100);
    if(defaultLayout()!=originalDefault)throw new IllegalStateException("Native activation changed default; restore required");
    Map<String,List<Integer>> letters=new LinkedHashMap<>();
    for(int key='A';key<='Z';key++){
      letters.put(Character.toString((char)key).toLowerCase(Locale.ROOT),translate(key,selected));
    }
    return Map.of("status","Prepared","selected",hex(selected),"physicalLetters",letters,"state",snapshot(),
      "scope","Keyboard layout activation only; full IME profile activation is not inferred");
  }
  public Map<String,Object> restore()throws Throwable{
    if(!attempted)return Map.of("status","NoActivation","state",snapshot());
    if(!ownsForeground())return Map.of("status","NeedsOwnedForegroundForRestore","state",snapshot());
    call("LoadKeyboardLayoutW",FunctionDescriptor.of(ADDRESS,ADDRESS,JAVA_INT),wide(originalName),0);
    call("ActivateKeyboardLayout",FunctionDescriptor.of(ADDRESS,ADDRESS,JAVA_INT),handle(originalCaller),0x100);
    Map<String,Object> profileResult=profileAttempted?TsfProfileProbe.restoreForProcess(originalProfile):Map.of("status","ProfileNotChanged");
    boolean profileRestored=Set.of("ProfileRestored","ProfileNotChanged").contains(profileResult.get("status"));
    if(profileRestored)profileAttempted=false;
    List<Map<String,Object>> unloads=new ArrayList<>();
    for(long added:introduced){
      int status=((Number)call("UnloadKeyboardLayout",FunctionDescriptor.of(JAVA_INT,ADDRESS),handle(added))).intValue();
      unloads.add(Map.of("handle",hex(added),"unloaded",status!=0));
    }
    boolean restored=profileRestored && defaultLayout()==originalDefault && layout(0)==originalCaller && layout((int)guiThread)==originalGui;
    if(restored)attempted=false;
    return Map.of("status",restored?"Restored":"RestoreNotVerified","unloads",unloads,"state",snapshot(),"profileRestore",profileResult);
  }
  public void close(){
    try{if(attempted){Map<String,Object> result=restore();if(!"Restored".equals(result.get("status")))throw new IllegalStateException("Native layout cleanup remains pending: "+result);}}
    catch(Throwable error){throw new IllegalStateException(error);}finally{try{if(tsfThread!=null)tsfThread.close();}finally{arena.close();}}
  }
  public static void main(String[] args)throws Throwable{
    try(OwnedKeyboardLayout layout=new OwnedKeyboardLayout()){
      Map<String,Object> before=layout.snapshot();
      if(!layout.hex(layout.originalCaller).equals(before.get("caller"))||!layout.originalLoaded.equals(before.get("loaded"))||
          !layout.hex(layout.originalDefault).equals(before.get("default")))throw new IllegalStateException("TSF baseline capture changed native input state");
      Map<String,Object> refusal=layout.prepare("00000420");
      if(!"NeedsOwnedForeground".equals(refusal.get("status")) || !before.equals(layout.snapshot()))
        throw new IllegalStateException("Headless capability probe must refuse activation without changing input state");
      if(!"NeedsOwnedForeground".equals(layout.prepareIme().get("status")) || !before.equals(layout.snapshot()))
        throw new IllegalStateException("Headless IME-profile activation must refuse without mutation");
      List<Integer> sample=layout.translate('K',layout.originalCaller);
      System.out.println(editor.PageProbeKt.evidenceJson(Map.of("status","READ_ONLY_LAYOUT_CHECKS_PASS","state",layout.snapshot(),
        "readOnlyKeySample",sample,"activationWithoutOwnedFocus","REFUSED_UNCHANGED")));
    }
  }
}
