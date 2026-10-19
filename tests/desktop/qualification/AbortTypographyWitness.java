import dev.shayxo.composedoc.model.*;
import dev.shayxo.composedoc.domain.*;
import dev.shayxo.composedoc.data.*;
import dev.shayxo.composedoc.layout.*;
import dev.shayxo.composedoc.ui.*;
import dev.shayxo.composedoc.workspace.*;
import dev.shayxo.composedoc.harness.*;
import editor.MainKt;
public class AbortTypographyWitness {
 public static void main(String[]args){var e=new EditorEngine();e.inputMethod("ab",0,2);e.inputMethod("ab",1,0);e.setTextStyle(new TextStylePatch(null,null,null,null,null,48,null));e.abortComposition();System.out.println("accepted="+e.snapshot().getCommitted().getText()+" typingSizeAfterAbort="+e.snapshot().getTypingStyle().getSize()+" deferredActionCancelled="+(e.snapshot().getTypingStyle().getSize()!=48));}
}
