import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
/** Isolated installed-API proof, not an adopted PDF exporter. No output PDF is created. */
public class Capability {
 public static void main(String[] args)throws Exception {
  boolean pdf=false;try{Class.forName("org.jetbrains.skia.Document");pdf=true;}catch(ClassNotFoundException expected){}
  System.out.println("SKIA_PDF_DOCUMENT="+pdf);
  for(String name:List.of("org.jetbrains.skia.paragraph.Paragraph","org.jetbrains.skia.TextBlob","org.apache.pdfbox.pdmodel.PDPageContentStream")){
   var methods=Arrays.stream(Class.forName(name).getDeclaredMethods()).map(m->m.getName()+Arrays.toString(m.getParameterTypes())).sorted().toList();System.out.println("API "+name+"="+methods);
  }
  try(PDDocument document=new PDDocument();var input=Files.newInputStream(Path.of(args[0]))){
   PDType0Font font=PDType0Font.load(document,input,false);
   System.out.println("FONT_EMBEDDED="+font.isEmbedded());System.out.println("FONT_NAME="+font.getName());
   try{System.out.println("STRING_ENCODE_BYTES="+font.encode("اردو ABC کتاب English 123 (۲۰۲۶) — مُحَمَّد").length);}catch(IllegalArgumentException expected){System.out.println("STRING_ENCODE_REFUSED="+expected.getMessage());}
  }
  System.out.println("BOUNDARY=Actual editor positioned glyph IDs / clusters / fallback fonts not exposed by Paragraph; string encoding is not that boundary");
 }
}
