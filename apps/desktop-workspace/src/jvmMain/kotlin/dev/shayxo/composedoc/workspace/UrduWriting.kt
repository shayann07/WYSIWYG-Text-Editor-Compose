package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel

/** Self-authored disposable exercise, not linguistically reviewed or a product font decision. */
fun urduWritingSample(): Document {
    val e = EditorViewModel()
    val introduction = "اردو تحریر — مشق کی دستاویز\nیہ صرف آزمائشی نمونہ ہے۔ اسے بدل کر اپنی الگ فائل محفوظ کریں۔ ماہر لسانی جائزہ نہیں ہوا۔\nDisposable writing sample — no expert linguistic review.\n"
    val paragraph = "صبح کی روشنی میں ہم نے کتاب کھولی اور اپنے شہر کے بارے میں لکھنا شروع کیا۔ گلی میں بچے کھیل رہے تھے اور باغ میں پرندوں کی آواز سنائی دے رہی تھی۔ ہر پیراگراف میں ایک خیال مکمل کریں، پھر اگلی بات نئے پیراگراف سے شروع کریں۔ "
    val text = introduction + (1..8).joinToString("\n\n") { index ->
        "حصہ $index — کتاب، قلم اور روزمرہ کی باتیں\n" + paragraph +
            "قیمت ۱۲۳ روپے، سال ۲۰۲۶، وقت 10:30، اور English notes (version $index.2) بھی اسی تحریر کا حصہ ہیں۔ " +
            "سوال: کیا یہ عبارت واضح ہے؟ جواب — اسے اپنی ضرورت کے مطابق بدلیں۔\n" +
            "This is a mixed paragraph: اردو، English, 123, ۴۵۶ — punctuation (test)."
    }
    e.dispatch(EditorIntent.Type(text, "DisposableSample", paragraphBreaks = true))
    e.dispatch(EditorIntent.Select(Selection(0, introduction.indexOf('\n'))))
    e.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
    val latin = text.indexOf("English notes")
    e.dispatch(EditorIntent.Select(Selection(latin, latin + "English notes".length)))
    e.dispatch(EditorIntent.ToggleFormat(FormatKey.ITALIC))
    // One intentional soft line break, alongside the real paragraph breaks.
    val softAt = text.indexOf("This is a mixed paragraph")
    e.dispatch(EditorIntent.Select(Selection(softAt, softAt)))
    e.dispatch(EditorIntent.Type("نرم سطر کی مثال\n", "SoftLine", paragraphBreaks = false))
    e.dispatch(EditorIntent.Select(Selection(0, introduction.indexOf('\n'))))
    e.dispatch(EditorIntent.SetTextStyle(TextStylePatch(font = StoredFont.NOTO_NASTALIQ, size = 32, color = 0x174ea6, underline = true)))
    e.dispatch(EditorIntent.SetParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER, after = 16, line = 1250))
    val english = text.indexOf("Disposable")
    e.dispatch(EditorIntent.Select(Selection(english, english + "Disposable writing sample".length)))
    e.dispatch(EditorIntent.SetTextStyle(TextStylePatch(font = StoredFont.SYSTEM, size = 18, color = 0x146c43)))
    e.dispatch(EditorIntent.SetParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.START, before = 8, after = 12))
    e.dispatch(EditorIntent.Select(Selection(latin, latin + "English notes".length)))
    e.dispatch(EditorIntent.SetTextStyle(TextStylePatch(strike = true)))
    return e.snapshot().committed
}
