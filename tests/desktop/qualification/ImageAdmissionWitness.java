import dev.shayxo.composedoc.model.*;
import dev.shayxo.composedoc.domain.*;
import dev.shayxo.composedoc.data.*;
import dev.shayxo.composedoc.layout.*;
import dev.shayxo.composedoc.ui.*;
import dev.shayxo.composedoc.workspace.*;
import dev.shayxo.composedoc.harness.*;
import editor.MainKt;import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
/** Independent bytes for admission boundaries; only production decoder performs validation. */
public class ImageAdmissionWitness {
 static byte[] encode(String format,int type)throws Exception {var image=new java.awt.image.BufferedImage(32,16,type);var b=new ByteArrayOutputStream();if(!javax.imageio.ImageIO.write(image,format,b))throw new Error("encoder unavailable");return b.toByteArray();}
 static byte[] exif(byte[] jpeg,int orientation)throws Exception {
  var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);o.writeBytes("Exif");o.writeShort(0);o.writeShort(0x4d4d);o.writeShort(42);o.writeInt(8);o.writeShort(1);o.writeShort(0x112);o.writeShort(3);o.writeInt(1);o.writeShort(orientation);o.writeShort(0);o.writeInt(0);
  var result=new ByteArrayOutputStream();var r=new DataOutputStream(result);r.write(jpeg,0,2);r.writeShort(0xffe1);r.writeShort(b.size()+2);r.write(b.toByteArray());r.write(jpeg,2,jpeg.length-2);return result.toByteArray();
 }
 static void refuse(byte[] b,String label){try{ImageResources.INSTANCE.validate(b);throw new AssertionError("admitted "+label);}catch(IllegalArgumentException expected){}}
 public static void main(String[] args)throws Exception {
  var png=encode("png",java.awt.image.BufferedImage.TYPE_INT_ARGB);var jpeg=encode("jpeg",java.awt.image.BufferedImage.TYPE_INT_RGB);var transparent=ImageResources.INSTANCE.validate(png);
  var decoded=javax.imageio.ImageIO.read(new ByteArrayInputStream(transparent.getSecond().bytes()));if((decoded.getRGB(0,0)>>>24)!=0)throw new Error("alpha lost");
  var normal=ImageResources.INSTANCE.validate(exif(jpeg,1));if(!normal.getSecond().getMime().equals("image/jpeg"))throw new Error("identity EXIF rejected");refuse(exif(jpeg,6),"rotated EXIF");
  refuse(Arrays.copyOf(jpeg,jpeg.length-4),"truncated JPEG");var corrupt=png.clone();corrupt[16]^=1;refuse(corrupt,"CRC corruption");
  var oversize=png.clone();ByteBuffer.wrap(oversize).putInt(16,9000);var crc=new CRC32();crc.update(oversize,12,17);ByteBuffer.wrap(oversize).putInt(29,(int)crc.getValue());refuse(oversize,"oversize decoded width");
  refuse(new byte[4000001],"encoded budget");refuse(new byte[100],"unsupported signature");
  Path root=Path.of(args[0]);Files.createDirectories(root);Path misleading=root.resolve("jpeg-with-png-extension.png");Files.write(misleading,jpeg);if(!((ImageResource)((kotlin.Pair<?,?>)ImageResources.class.getMethod("import",Path.class).invoke(ImageResources.INSTANCE,misleading)).getSecond()).getMime().equals("image/jpeg"))throw new Error("trusted extension");
  System.out.println("{\"status\":\"PASS\",\"cases\":[\"transparent alpha\",\"identity EXIF\",\"rotated EXIF refusal\",\"truncated JPEG\",\"PNG CRC\",\"decoded dimension bound\",\"encoded byte bound\",\"signature refusal\",\"extension independence\"]}");
 }
}
