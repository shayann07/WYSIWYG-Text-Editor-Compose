import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** Read-only TSF COM query. ABI: Microsoft WinSDK msctf.h; Windows x64/JDK 23 tooling. */
public final class TsfProfileProbe {
  static final String CLASS_ID="33c53a50-f456-4884-b049-85fd643ecfed";
  static final String MANAGER_ID="71c6e74c-0f28-11d8-a82a-00065b84435c";
  static final String PROFILES_ID="1f02b6c5-7842-4ee6-8a0b-9a24183a95ca";
  static final String KEYBOARD_CATEGORY="34745c63-b2f0-4784-8b67-5e12c8701a31";
  static final String JAPANESE_TIP="03b5835f-f03c-411b-9ce2-aa23e1171e36";
  static final String JAPANESE_PROFILE="a76c93d9-5523-4e90-aafa-4db112f9ac76";
  static MemorySegment guid(Arena arena,String text){
    UUID id=UUID.fromString(text);MemorySegment result=arena.allocate(16,4);
    long most=id.getMostSignificantBits(),least=id.getLeastSignificantBits();
    result.set(JAVA_INT,0,(int)(most>>>32));result.set(JAVA_SHORT,4,(short)(most>>>16));result.set(JAVA_SHORT,6,(short)most);
    for(int i=0;i<8;i++)result.set(JAVA_BYTE,8+i,(byte)(least>>>(56-i*8)));return result;
  }
  static String guid(MemorySegment data,long offset){
    return String.format("%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",data.get(JAVA_INT,offset),
      Short.toUnsignedInt(data.get(JAVA_SHORT,offset+4)),Short.toUnsignedInt(data.get(JAVA_SHORT,offset+6)),
      data.get(JAVA_BYTE,offset+8)&255,data.get(JAVA_BYTE,offset+9)&255,data.get(JAVA_BYTE,offset+10)&255,data.get(JAVA_BYTE,offset+11)&255,
      data.get(JAVA_BYTE,offset+12)&255,data.get(JAVA_BYTE,offset+13)&255,data.get(JAVA_BYTE,offset+14)&255,data.get(JAVA_BYTE,offset+15)&255);
  }
  static String hex(int value){return String.format("0x%08x",value);}
  static Map<String,Object> profile(MemorySegment data){
    return Map.of("type",data.get(JAVA_INT,0),"language",String.format("0x%04x",Short.toUnsignedInt(data.get(JAVA_SHORT,4))),
      "clsid",guid(data,8),"profile",guid(data,24),"category",guid(data,40),
      "substituteHkl",String.format("0x%016x",data.get(ADDRESS,56).address()),"caps",hex(data.get(JAVA_INT,64)),
      "hkl",String.format("0x%016x",data.get(ADDRESS,72).address()),"flags",hex(data.get(JAVA_INT,80)));
  }
  public static Map<String,Object> query()throws Throwable{
    Linker linker=Linker.nativeLinker();
    try(Arena arena=Arena.ofConfined()){
      SymbolLookup ole=SymbolLookup.libraryLookup("ole32.dll",arena);
      MethodHandle init=linker.downcallHandle(ole.find("CoInitializeEx").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT));
      MethodHandle uninit=linker.downcallHandle(ole.find("CoUninitialize").orElseThrow(),FunctionDescriptor.ofVoid());
      MethodHandle create=linker.downcallHandle(ole.find("CoCreateInstance").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,ADDRESS,ADDRESS));
      int initialized=(int)init.invokeExact(MemorySegment.NULL,2);
      if(initialized<0&&initialized!=0x80010106)throw new IllegalStateException("COM initialization failed: "+hex(initialized));
      MemorySegment manager=MemorySegment.NULL;MethodHandle release=null;
      try{
        MemorySegment result=arena.allocate(ADDRESS);
        int created=(int)create.invokeExact(guid(arena,CLASS_ID),MemorySegment.NULL,1,guid(arena,MANAGER_ID),result);
        Map<String,Object> report=new LinkedHashMap<>();report.put("comInit",hex(initialized));report.put("create",hex(created));
        report.put("javaThread",Thread.currentThread().getName());report.put("scope","Read-only active/installed TSF profiles; no activation or registry mutation");
        if(created!=0)return report;
        manager=result.get(ADDRESS,0);if(manager.address()==0)throw new IllegalStateException("Null successful TSF manager");
        MemorySegment vtable=manager.reinterpret(8).get(ADDRESS,0).reinterpret(11*8L);
        release=linker.downcallHandle(vtable.getAtIndex(ADDRESS,2),FunctionDescriptor.of(JAVA_INT,ADDRESS));
        MethodHandle active=linker.downcallHandle(vtable.getAtIndex(ADDRESS,10),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS));
        MethodHandle get=linker.downcallHandle(vtable.getAtIndex(ADDRESS,5),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,JAVA_SHORT,ADDRESS,ADDRESS,ADDRESS,ADDRESS));
        MemorySegment activeData=arena.allocate(88,8),targetData=arena.allocate(88,8);
        int activeStatus=(int)active.invokeExact(manager,guid(arena,KEYBOARD_CATEGORY),activeData);
        int targetStatus=(int)get.invokeExact(manager,1,(short)0x411,guid(arena,JAPANESE_TIP),guid(arena,JAPANESE_PROFILE),MemorySegment.NULL,targetData);
        report.put("activeStatus",hex(activeStatus));if(activeStatus==0)report.put("active",profile(activeData));
        report.put("japaneseStatus",hex(targetStatus));if(targetStatus==0)report.put("japanese",profile(targetData));
        MethodHandle query=linker.downcallHandle(vtable.getAtIndex(ADDRESS,0),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS));
        MemorySegment legacyOut=arena.allocate(ADDRESS);
        int legacyStatus=(int)query.invokeExact(manager,guid(arena,PROFILES_ID),legacyOut);
        report.put("enabledInterfaceStatus",hex(legacyStatus));
        if(legacyStatus==0){
          MemorySegment legacy=legacyOut.get(ADDRESS,0),legacyTable=legacy.reinterpret(8).get(ADDRESS,0).reinterpret(19*8L);
          MethodHandle releaseLegacy=linker.downcallHandle(legacyTable.getAtIndex(ADDRESS,2),FunctionDescriptor.of(JAVA_INT,ADDRESS));
          try{
            MethodHandle enabled=linker.downcallHandle(legacyTable.getAtIndex(ADDRESS,18),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_SHORT,ADDRESS,ADDRESS));
            MemorySegment value=arena.allocate(JAVA_INT);
            int enabledStatus=(int)enabled.invokeExact(legacy,guid(arena,JAPANESE_TIP),(short)0x411,guid(arena,JAPANESE_PROFILE),value);
            report.put("japaneseEnabledStatus",hex(enabledStatus));if(enabledStatus==0)report.put("japaneseEnabled",value.get(JAVA_INT,0)!=0);
          }finally{int remaining=(int)releaseLegacy.invokeExact(legacy);}
        }
        return report;
      }finally{
        if(release!=null){int remaining=(int)release.invokeExact(manager);}
        if(initialized>=0)uninit.invokeExact();
      }
    }
  }
  public static void main(String[] args)throws Throwable{
    System.out.println(editor.PageProbeKt.evidenceJson(query()));
  }
  // These entry points are used only by the PID-guarded owned GUI fixture.
  // No FORSESSION, ENABLEPROFILE, DISABLEPROFILE or default-setting call exists.
  public static byte[] captureActive()throws Throwable{return (byte[])managedOperation(0,null);}
  @SuppressWarnings("unchecked")
  public static Map<String,Object> activateJapaneseForProcess()throws Throwable{
    if(!Boolean.TRUE.equals(query().get("japaneseEnabled")))return Map.of("status","RefusedProfileNotEnabled");
    return (Map<String,Object>)managedOperation(1,null);
  }
  @SuppressWarnings("unchecked")
  public static Map<String,Object> restoreForProcess(byte[] original)throws Throwable{return (Map<String,Object>)managedOperation(2,original);}
  static boolean sameProfile(MemorySegment a,MemorySegment b){
    Map<String,Object> x=profile(a),y=profile(b);
    return List.of("type","language","clsid","profile","hkl").stream().allMatch(key->Objects.equals(x.get(key),y.get(key)));
  }
  static Object managedOperation(int operation,byte[] token)throws Throwable{
    Linker linker=Linker.nativeLinker();
    try(Arena arena=Arena.ofConfined()){
      SymbolLookup ole=SymbolLookup.libraryLookup("ole32.dll",arena);
      MethodHandle init=linker.downcallHandle(ole.find("CoInitializeEx").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT));
      MethodHandle uninit=linker.downcallHandle(ole.find("CoUninitialize").orElseThrow(),FunctionDescriptor.ofVoid());
      MethodHandle create=linker.downcallHandle(ole.find("CoCreateInstance").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,ADDRESS,ADDRESS));
      int initialized=(int)init.invokeExact(MemorySegment.NULL,2);
      if(initialized<0&&initialized!=0x80010106)throw new IllegalStateException("COM init failed: "+hex(initialized));
      MemorySegment manager=MemorySegment.NULL;MethodHandle release=null;
      try{
        MemorySegment out=arena.allocate(ADDRESS);
        int created=(int)create.invokeExact(guid(arena,CLASS_ID),MemorySegment.NULL,1,guid(arena,MANAGER_ID),out);
        if(created!=0){if(operation==0)return null;return Map.of("status","RefusedManagerUnavailable","hresult",hex(created));}
        manager=out.get(ADDRESS,0);MemorySegment table=manager.reinterpret(8).get(ADDRESS,0).reinterpret(11*8L);
        release=linker.downcallHandle(table.getAtIndex(ADDRESS,2),FunctionDescriptor.of(JAVA_INT,ADDRESS));
        MethodHandle active=linker.downcallHandle(table.getAtIndex(ADDRESS,10),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS));
        MemorySegment before=arena.allocate(88,8);int beforeStatus=(int)active.invokeExact(manager,guid(arena,KEYBOARD_CATEGORY),before);
        if(operation==0)return beforeStatus==0?before.toArray(JAVA_BYTE):null;
        if(beforeStatus!=0)return Map.of("status","RefusedMissingActiveProfile","hresult",hex(beforeStatus));
        MemorySegment desired=arena.allocate(88,8);
        if(operation==1){
          MethodHandle get=linker.downcallHandle(table.getAtIndex(ADDRESS,5),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,JAVA_SHORT,ADDRESS,ADDRESS,ADDRESS,ADDRESS));
          int found=(int)get.invokeExact(manager,1,(short)0x411,guid(arena,JAPANESE_TIP),guid(arena,JAPANESE_PROFILE),MemorySegment.NULL,desired);
          if(found!=0)return Map.of("status","RefusedTargetUnavailable","hresult",hex(found));
        }else{
          if(token==null||token.length!=88)return Map.of("status","RefusedMissingOriginalProfile");
          MemorySegment.copy(MemorySegment.ofArray(token),0,desired,0,88);
        }
        MethodHandle qi=linker.downcallHandle(table.getAtIndex(ADDRESS,0),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS));
        MemorySegment legacyOut=arena.allocate(ADDRESS);int acquired=(int)qi.invokeExact(manager,guid(arena,PROFILES_ID),legacyOut);
        int languageStatus=0x80004005;
        if(acquired==0){
          MemorySegment legacy=legacyOut.get(ADDRESS,0),lt=legacy.reinterpret(8).get(ADDRESS,0).reinterpret(15*8L);
          MethodHandle releaseLegacy=linker.downcallHandle(lt.getAtIndex(ADDRESS,2),FunctionDescriptor.of(JAVA_INT,ADDRESS));
          try{MethodHandle language=linker.downcallHandle(lt.getAtIndex(ADDRESS,14),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_SHORT));
            languageStatus=(int)language.invokeExact(legacy,desired.get(JAVA_SHORT,4));}
          finally{int remaining=(int)releaseLegacy.invokeExact(legacy);}
        }
        if(languageStatus!=0)return Map.of("status","ProfileChangeNotVerified","languageHresult",hex(languageStatus));
        MethodHandle activate=linker.downcallHandle(table.getAtIndex(ADDRESS,3),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,JAVA_SHORT,ADDRESS,ADDRESS,ADDRESS,JAVA_INT));
        int changed=(int)activate.invokeExact(manager,desired.get(JAVA_INT,0),desired.get(JAVA_SHORT,4),desired.asSlice(8,16),desired.asSlice(24,16),desired.get(ADDRESS,72),0x10000000);
        MemorySegment after=arena.allocate(88,8);int afterStatus=(int)active.invokeExact(manager,guid(arena,KEYBOARD_CATEGORY),after);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status",changed==0&&afterStatus==0&&sameProfile(after,desired)?(operation==1?"ProfileActivated":"ProfileRestored"):"ProfileChangeNotVerified");
        result.put("hresult",hex(changed));result.put("afterStatus",hex(afterStatus));result.put("flags","FORPROCESS_ONLY");
        result.put("languageHresult",hex(languageStatus));
        result.put("before",profile(before));if(afterStatus==0)result.put("after",profile(after));return result;
      }finally{if(release!=null){int remaining=(int)release.invokeExact(manager);}if(initialized>=0)uninit.invokeExact();}
    }
  }
}
