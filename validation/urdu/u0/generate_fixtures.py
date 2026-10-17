"""
Deterministic Urdu acceptance fixtures generator for U0 gate.
Generates deterministic fixtures across 11 required categories defined in URDU-ACCEPTANCE.md,
including malformed UTF-16 edge cases, formatting boundaries, Undo/Redo, and page/cell structures.
"""

import json
import os
import re
import unicodedata

CATEGORIES = [
    "common_prose",
    "letter_distinctions",
    "marks_and_joining",
    "mixed_scripts_and_numbers",
    "bidi_controls",
    "malformed_and_utf16_edge_cases",
    "formatting_and_undo_redo",
    "nastaliq_geometry",
    "spacing_and_justification",
    "structure_and_future_page_cells",
    "input_and_mutation"
]

def make_fixture(fixture_id, category, title, text, purpose, provenance="Self-authored standard Urdu test fixture", forbidden=None, bidi="RTL", req_nastaliq=True, execution_scope="EXECUTABLE_PROTOTYPE", formatting_spans=None, undo_redo_plan=None, future_domain=None, operations=None):
    encoded_utf16 = text.encode("utf-16le", errors="surrogatepass")
    utf16_code_units = [int.from_bytes(encoded_utf16[i:i+2], "little") for i in range(0, len(encoded_utf16), 2)]
    utf16_length = len(utf16_code_units)
    scalar_count = len(text)
    raw_code_units = "".join(f"\\u{cu:04x}" for cu in utf16_code_units)
    code_points_hex = [f"{ord(c):04X}" for c in text]
    forbidden_list = forbidden or []
    
    if operations is None:
        if execution_scope == "EXECUTABLE_PROTOTYPE":
            if category == "formatting_and_undo_redo":
                operations = ["insert_text", "apply_formatting", "verify_spans", "undo", "verify_baseline", "redo", "verify_spans", "save_load_roundtrip"]
            elif category == "input_and_mutation":
                operations = ["insert_text", "verify_text", "verify_code_units", "save_load_roundtrip"]
            elif category == "malformed_and_utf16_edge_cases":
                operations = ["insert_raw_units", "verify_code_units_preserved", "verify_no_crash", "save_load_roundtrip"]
            else:
                operations = ["insert_text", "verify_text", "verify_utf16_code_units", "save_load_roundtrip"]
        else:
            operations = []

    expected_outcome = {
        "bidiDirection": bidi,
        "joiningValid": True,
        "requiresNastaliq": req_nastaliq,
        "canonicalInvariance": True,
        "forbiddenInCanonical": forbidden_list,
        "supportsPrototypeExecution": (execution_scope == "EXECUTABLE_PROTOTYPE")
    }
    if formatting_spans:
        expected_outcome["formattingSpans"] = formatting_spans
    if undo_redo_plan:
        expected_outcome["undoRedoPlan"] = undo_redo_plan
    if future_domain:
        expected_outcome["futureFeatureDomain"] = future_domain
    
    return {
        "id": fixture_id,
        "category": category,
        "title": title,
        "text": text,
        "scalarCount": scalar_count,
        "utf16Length": utf16_length,
        "utf16CodeUnits": utf16_code_units,
        "rawCodeUnits": raw_code_units,
        "codePointsHex": code_points_hex,
        "purpose": purpose,
        "provenance": provenance,
        "executionScope": execution_scope,
        "operations": operations,
        "expectedOutcome": expected_outcome,
        "reviewerStatus": "UNREVIEWED_SYNTHETIC"
    }

def build_all_fixtures():
    fixtures = []
    fid = 1

    def next_id():
        nonlocal fid
        val = f"UTXT-{fid:03d}"
        fid += 1
        return val

    # ==========================================
    # 1. Common Urdu Prose (28 fixtures)
    # ==========================================
    cat = "common_prose"
    prose_items = [
        ("یہ ایک اردو دستاویز ہے۔", "Basic Urdu declaration with Khatma.", "Standard modern Urdu"),
        ("پاکستان میں تعلیم اور تحقیق کی اہمیت روز بروز بڑھ رہی ہے۔", "Administrative and academic prose style.", "Contemporary Pakistani educational report"),
        ("علم حاصل کرنا ہر مرد اور عورت پر فرض ہے۔", "Classical maxim testing high-frequency words.", "Traditional educational aphorism"),
        ("محنت کامیابی کی کلید ہے۔", "Short motivational sentence with Kaf-Lam ligature.", "Traditional idiom"),
        ("اردو زبان برصغیر پاک و ہند کی ایک انتہائی خوبصورت اور شیریں زبان ہے۔", "Flowing descriptive sentence with multiple adjectives.", "Literary essay"),
        ("صبح کا وقت تھا اور باغ میں پرندے چہچہا رہے تھے۔", "Descriptive narrative prose with Choti Heh and Bari Yeh.", "Narrative prose fiction"),
        ("کتابیں انسان کی بہترین دوست ہوتی ہیں۔", "General statement with Noon Ghunna and Bari Yeh.", "Library catalogue preface"),
        ("وقت کی قدر کرو کیونکہ گزرا ہوا وقت کبھی واپس نہیں آتا۔", "Complex conditional clause with Waw and Kaf.", "Didactic literature"),
        ("سچائی میں ہمیشہ برکت اور سکون ہوتا ہے۔", "Ethical statement testing Seen-Choti Yeh.", "Moral philosophy"),
        ("ہمیں اپنے وطن کی ترقی اور خوشحالی کے لیے مل جل کر کام کرنا چاہیے۔", "Compound civic duty statement with Noon Ghunna.", "Civic education curriculum"),
        ("شام کے سائے گہرے ہو رہے تھے اور ہوا میں خنکی بڑھتی جا رہی تھی۔", "Descriptive setting with multiple joining combinations.", "Literary prose novel"),
        ("تاریخ ہمیں بتاتی ہے کہ زندہ قومیں ہمیشہ اپنے اسلاف کے کارناموں پر فخر کرتی ہیں۔", "Historical expository prose with retroflex consonants.", "Historical treatise"),
        ("طالب علموں کو جدید علوم کے ساتھ ساتھ اخلاقی اقدار بھی سیکھنی چاہئیں۔", "Pedagogical guideline sentence with Arabic loanwords.", "Pedagogical policy paper"),
        ("موسمِ بہار میں چاروں طرف رنگ برنگے پھول کھلتے ہیں اور فضا معطر ہو جاتی ہے۔", "Poetic prose with Izafat and aspiration (Pheh).", "Nature essay"),
        ("اس نئی کتاب میں اردو شاعری کے مختلف ادوار کا تفصیلی جائزہ پیش کیا گیا ہے۔", "Scholarly review prose.", "Literary review journal"),
        ("آج کل کی مصروف زندگی میں صحت کی دیکھ بھال ایک بہت بڑا چیلنج بن چکا ہے۔", "Health awareness prose testing modern journalistic phrasing.", "Health journalism"),
        ("قائداعظم محمد علی جناح نے فرمایا کہ کام، کام اور بس کام کرو۔", "Historical quotation with punctuation and repetition.", "National archives biography"),
        ("علامہ اقبال نے نوجوانوں کو خودی کی پہچان اور بلندی کا درس دیا۔", "Intellectual history sentence with Choti Yeh.", "Philosophical study"),
        ("کسی بھی معاشرے کی ترقی کے لیے انصاف اور مساوات کا قیام ناگزیر ہے۔", "Social justice statement with Arabic abstract nouns.", "Legal and social philosophy"),
        ("دریا کے کنارے پرانے درخت خاموشی سے گزرتے ہوئے پانی کو دیکھ رہے تھے۔", "Personification in narrative Urdu.", "Creative fiction short story"),
        ("کمپیوٹر سائنس اور انفارمیشن ٹیکنالوجی نے دنیا کے فاصلے سمیٹ دیے ہیں۔", "Modern technology terminology in Urdu prose.", "Technology editorial"),
        ("اس خوبصورت وادی میں سبزہ زار، آبشاریں اور بلند پہاڑ سیاحوں کا دل موہ لیتے ہیں۔", "Tourism descriptive passage.", "Travelogue narrative"),
        ("بچوں کی تربیت میں والدین اور اساتذہ کا کردار سب سے زیادہ نمایاں ہوتا ہے۔", "Family and parenting discourse.", "Child psychology publication"),
        ("قومی زبان کا تحفظ اور فروغ ہر محب وطن شہری کی اولین ذمہ داری ہے۔", "Language policy essay.", "Linguistic society gazette"),
        ("وہ رات تاریک تھی لیکن آسمان پر چمکتے ہوئے تارے راستہ دکھا رہے تھے۔", "Atmospheric descriptive passage.", "Literary novel"),
        ("محنت، دیانت اور لگن سے کام کرنے والے انسان کو کوئی بھی ناکام نہیں کر سکتا۔", "Ethical aphorism testing comma separation in RTL.", "Inspirational literature"),
        ("آئین اور قانون کی حکمرانی ہی کسی بھی جمہوری ریاست کی بقا کی ضامن ہے۔", "Constitutional legal discourse.", "Constitutional law review"),
        ("اس تاریخی عمارت کے دالان اور محرابیں اپنے شاندار ماضی کی داستان سنا رہی ہیں۔", "Architectural description with Nastaliq ligatures.", "Heritage conservation report")
    ]
    for text, purpose, prov in prose_items:
        fixtures.append(make_fixture(next_id(), cat, f"Prose: {text[:20]}...", text, purpose, provenance=prov))

    # ==========================================
    # 2. Urdu Letter Distinctions (33 fixtures)
    # ==========================================
    cat = "letter_distinctions"
    letters_items = [
        ("کتاب اور قلم", "U+06A9 (Keheh) canonical preservation.", "Common school phrase", ["0643"]),
        ("پاکستانی قوم", "U+06A9 medial form canonical preservation.", "National identity statement", ["0643"]),
        ("باریک بینی", "U+06A9 final form canonical preservation.", "Literary phrase", ["0643"]),
        ("کامل یقین", "U+06A9 initial form canonical preservation.", "Philosophical phrase", ["0643"]),
        ("پانی اور روٹی", "U+06CC (Urdu Yeh) canonical preservation.", "Everyday household phrase", ["064A"]),
        ("زندگی کی خوبصورتی", "U+06CC medial and final canonical preservation.", "Philosophical essay", ["064A"]),
        ("گاڑی چلی", "U+06CC final form after retroflex consonant.", "Everyday narrative", ["064A"]),
        ("یقین کامل", "U+06CC initial form canonical preservation.", "Religious maxim", ["064A"]),
        ("گھوڑے دوڑتے ہیں", "U+06D2 (Bari Yeh) canonical preservation.", "Grammatical plural masculine", ["064A"]),
        ("ہم نے دیکھا", "U+06D2 (Bari Yeh) in pronoun and past tense verb.", "Common past tense clause", ["064A"]),
        ("آنے والے دن", "U+06D2 in future/participle marker.", "Standard Urdu future participle", ["064A"]),
        ("پھول کھلے", "U+06D2 final form plural concord.", "Standard plural clause", ["064A"]),
        ("ماں کی دعا", "U+06BA (Noon Ghunna) isolated/final.", "High-frequency kinship noun", []),
        ("جہاں میں تم ہو", "U+06BA (Noon Ghunna) final in spatial adverb.", "Poetic verse clause", []),
        ("آنکھوں میں آنسو", "U+06BA medial Noon Ghunna context (with Nun).", "Idiomatic emotional phrase", []),
        ("کتابیں پڑھیں", "U+06BA final in plural noun and imperative.", "Pedagogical command", []),
        ("ہمیشہ خوش رہو", "U+06C1 (Heh Goal) canonical preservation.", "Blessing idiom", ["0647"]),
        ("ہوا کا جھونکا", "U+06C1 initial form canonical preservation.", "Nature description", ["0647"]),
        ("شہر کی گلیاں", "U+06C1 medial form canonical preservation.", "Urban description", ["0647"]),
        ("وجہ تسمیہ", "U+06C1 final form canonical preservation.", "Scholarly etymology phrase", ["0647"]),
        ("گھر کا دروازہ", "U+06BE (Do-Chashmi Heh) in aspirated Ghaf.", "Standard architectural phrase", ["0647"]),
        ("پھول اور پھل", "U+06BE (Do-Chashmi Heh) in aspirated Peh.", "Nature binomial pair", ["0647"]),
        ("جھولے میں بچہ", "U+06BE (Do-Chashmi Heh) in aspirated Jeem.", "Narrative description", ["0647"]),
        ("ڈھول کی تھاپ", "U+06BE (Do-Chashmi Heh) in aspirated Ddal and Teh.", "Musical description", ["0647"]),
        ("ٹماٹر اور ٹوکری", "U+0679 (Tteh retroflex) initial preservation.", "Marketplace items", ["062A"]),
        ("چھوٹی پلیٹ", "U+0679 (Tteh retroflex) final preservation.", "Household dining items", ["062A"]),
        ("ڈاکیا ڈاک لایا", "U+0688 (Ddal retroflex) initial preservation.", "Standard primary school reader sentence", ["062F"]),
        ("پہاڑ کی چوٹی", "U+0691 (Rreh retroflex) medial/final.", "Geographical description", ["0631"]),
        ("بڑی گاڑی", "U+0691 (Rreh retroflex) medial in adjective and noun.", "Automotive description", ["0631"]),
        ("پیلا پھول", "U+067E (Peh) canonical preservation.", "Botanical color phrase", ["0628"]),
        ("چاندنی رات", "U+0686 (Cheh) canonical preservation.", "Nocturnal poetic phrase", ["062C"]),
        ("ژالہ باری ہوئی", "U+0698 (Zheh) initial canonical preservation.", "Meteorological report", ["0632"]),
        ("گلاب کا پودا", "U+06AF (Gaf) canonical preservation.", "Gardening phrase", ["0643"]),
        ("یہ اردو کتاب ہے اور هذا كتاب عربي ہے", "Coexistence of Urdu Keheh (U+06A9) and Arabic Kaf (U+0643), Urdu Yeh (U+06CC) and Arabic Yeh (U+064A) in a single bilingual passage.", "Bilingual Urdu-Arabic reference text", None)
    ]
    for text, purpose, prov, forb in letters_items:
        fixtures.append(make_fixture(next_id(), cat, f"Letters: {text[:20]}...", text, purpose, provenance=prov, forbidden=forb))

    # ==========================================
    # 3. Marks & Joining Controls (30 fixtures)
    # ==========================================
    cat = "marks_and_joining"
    marks_items = [
        ("سَلام", "U+064E (Fatha/Zabar) on initial Seen.", "Vocalized greeting"),
        ("دِل", "U+0650 (Kasra/Zer) on initial Dal.", "Vocalized heart noun"),
        ("پُل", "U+064F (Damma/Pesh) on initial Peh.", "Vocalized bridge noun"),
        ("مُحَمَّد", "Shaddah (U+0651) + Fatha (U+064E) stacked above Meem.", "High-frequency sacred name"),
        ("رَبِّ", "Shaddah (U+0651) + Kasra (U+0650) stacked below Beh.", "Vocalized divine title"),
        ("کُلّ", "Shaddah (U+0651) alone on final Lam.", "Vocalized universal quantifier"),
        ("مَسْجِد", "Sukun/Jazm (U+0652) on medial Seen.", "Vocalized mosque noun"),
        ("شُکْرًا", "Tanween Fath/Do Zabar (U+064B) on Alef.", "Arabic loanword of gratitude"),
        ("عَمَلًا", "Tanween Fath (U+064B) adverbial marker.", "Adverbial loanword"),
        ("عِیسٰی", "Dagger Alef / Khari Zabar (U+0670) on final Alef Maksura.", "Biblical/Quranic proper name"),
        ("موسیٰ", "Khari Zabar (U+0670) on final Choti Yeh.", "Prophetic proper name"),
        ("دینِ حق", "Zer-e-Izafat (U+0650) connecting two nouns.", "Classical theological phrase"),
        ("جذبۂ دل", "Hamza-e-Izafat (U+06C2) on Heh Goal.", "Classical poetic Izafat"),
        ("قطرۂ شبنم", "Hamza-e-Izafat (U+06C2) connecting noun and modifier.", "Romantic poetry trope"),
        ("شعلۂ جوالہ", "Hamza-e-Izafat on noun ending in Heh Goal.", "High literary metaphor"),
        ("بے‌شک", "ZWNJ (U+200C) preventing false joining between prefix and stem.", "Orthographic word boundary"),
        ("دل‌چسپ", "ZWNJ (U+200C) between compound noun stems.", "Compound adjective"),
        ("نو‌آموز", "ZWNJ (U+200C) separating prefix ending in non-joiner from Alef-Madda.", "Compound agent noun"),
        ("ایک‌طرفہ", "ZWNJ (U+200C) in numeral compound.", "Administrative legal adjective"),
        ("اہل‌کار", "ZWNJ (U+200C) between compound administrative title.", "Civil service job title"),
        ("ک‍تاب", "ZWJ (U+200D) forcing cursive initial joining of Kaf in isolation.", "Typographic display testing"),
        ("‍ش‍", "ZWJ (U+200D) on both sides forcing medial Seen form in isolation.", "Typographic glyph chart"),
        ("ت‍", "ZWJ (U+200D) forcing initial Teh form in isolation.", "Typographic glyph chart"),
        ("‍ت", "ZWJ (U+200D) forcing final Teh form in isolation.", "Typographic glyph chart"),
        ("گائے کا دودھ", "Hamza above Bari Yeh in feminine animal noun.", "Domestic livestock phrase"),
        ("چائے کی پیالی", "Hamza above Bari Yeh in beverage noun.", "Everyday household beverage"),
        ("کوئی بات نہیں", "Hamza above Choti Yeh in indefinite pronoun.", "Common conversational idiom"),
        ("کئی لوگ", "Hamza above Choti Yeh in plural quantifier.", "Sociological quantifier"),
        ("ہُد ہُد", "Two identical syllables with Pesh (U+064F) and Sukun.", "Avian proper noun"),
        ("بِسْمِ اللّٰہِ", "Complex ligated basmala segment with stacked diacritics.", "Traditional invocation")
    ]
    for text, purpose, prov in marks_items:
        fixtures.append(make_fixture(next_id(), cat, f"Marks: {text[:20]}...", text, purpose, provenance=prov))

    # ==========================================
    # 4. Mixed Scripts, Numbers & Punctuation (30 fixtures)
    # ==========================================
    cat = "mixed_scripts_and_numbers"
    mixed_items = [
        ("قیمت ۱۲۳ روپے", "Urdu Eastern Arabic-Indic digits (U+06F1..U+06F3).", "Commercial price tag"),
        ("سال ۲۰۲۶ء", "Year in Eastern digits followed by Sanah symbol (U+0601).", "Chronological publication date"),
        ("مورخہ ۱۵ اگست ۱۹۴۷ء", "Full date in Eastern Arabic digits.", "Historical independence date"),
        ("فون نمبر: ۰۵۱-۱۲۳۴۵۶۷", "Telephone number with hyphen in RTL context.", "Directory listing"),
        ("صفحہ نمبر ۴۵", "Page citation with Eastern digits.", "Academic citation"),
        ("قیمت 123 روپے", "Western ASCII digits inside Urdu sentence.", "Modern retail price display"),
        ("سن 2026 میں", "Western digits followed by Urdu postposition.", "Contemporary news report"),
        ("درجہ حرارت 25°C ہے", "Scientific measurement with ASCII digits and Celsius symbol.", "Weather report"),
        ("وزن 50 کلوگرام ہے", "Metric weight measurement with ASCII digits.", "Commerce listing"),
        ("رفتار 100 کلومیٹر فی گھنٹہ", "Speed measurement with English numerals and Urdu phrase.", "Traffic signage"),
        ("رابطہ کریں: info@example.com برائے معلومات", "Email address embedded inside Urdu RTL clause.", "Corporate footer"),
        ("ویب سائٹ https://urdu.org ملاحظہ فرمائیں۔", "Full URL starting with https:// embedded in Urdu.", "Web directory announcement"),
        ("دستاویز www.gov.pk پر دستیاب ہے۔", "Web domain without protocol inside Urdu.", "Government portal announcement"),
        ("یہ (اردو) متن ہے۔", "Balanced ASCII parentheses in Urdu context.", "Editorial explanatory phrase"),
        ("اقبال [۱۹۰۸ء] کے مطابق", "Square brackets enclosing Eastern digits in citation.", "Scholarly citation"),
        ("شعر کی خوبصورتی {حسنِ کلام} میں ہے۔", "Curly braces in literary analysis.", "Literary criticism"),
        ("جناب صدر نے کہا: «ہم پرامن قوم ہیں۔»", "Guillemets (« ») quoting speech in Urdu.", "Diplomatic reporting"),
        ("استاد نے پوچھا: ”کیا تم نے سبق یاد کیا؟“", "Curly quotation marks (“ ”) with Urdu question mark.", "Grammar textbook dialogue"),
        ("کیا آپ کا نام احمد ہے؟", "Urdu/Arabic question mark (U+061F).", "Direct interrogation"),
        ("لاہور، اسلام آباد اور کراچی", "Urdu/Arabic comma (U+060C) in list.", "Geographical list"),
        ("محنت کرو؛ تاکہ کامیاب ہو سکو۔", "Urdu/Arabic semicolon (U+061B) in clause.", "Compound causal sentence"),
        ("پاکستان — ایک تعارف", "Em-dash (U+2014) in book title.", "Book title"),
        ("اردو – انگریزی لغت", "En-dash (U+2013) between language names.", "Lexicographical work title"),
        ("قیمت: $100 امریکی ڈالر", "Dollar currency symbol with ASCII digits.", "Foreign exchange bulletin"),
        ("رقم: £50 پاؤنڈ", "British Pound currency symbol in Urdu text.", "Remittance receipt"),
        ("مساوات: x + y = z ثابت کریں۔", "Algebraic equation embedded in Urdu math problem.", "Mathematics textbook"),
        ("فارمولا: E = mc² آئن سٹائن کا ہے۔", "Physics formula with superscript 2.", "Physics primer"),
        ("ٹیم A نے ٹیم B کو شکست دی۔", "Isolated Latin capital letters as identifiers.", "Sports journalism"),
        ("پی سی بی (PCB) کا فیصلہ", "Latin acronym in parentheses after Urdu transliteration.", "Sports board news"),
        ("ورژن v5.0 ریلیز ہو گیا۔", "Software version identifier in tech announcement.", "Software release note")
    ]
    for text, purpose, prov in mixed_items:
        fixtures.append(make_fixture(next_id(), cat, f"Mixed: {text[:20]}...", text, purpose, provenance=prov))

    # ==========================================
    # 5. Bidi Controls & Isolates (26 fixtures)
    # ==========================================
    cat = "bidi_controls"
    bidi_items = [
        ("اردو \u2066English\u2069 متن", "LRI (U+2066) + PDI (U+2069) embedding English inside Urdu.", "UAX #9 Section 3.1"),
        ("Start \u2067اردو\u2069 End", "RLI (U+2067) + PDI (U+2069) embedding Urdu inside English.", "UAX #9 Section 3.1", "LTR"),
        ("پہلا \u2068123-ABC\u2069 دوسرا", "FSI (U+2068) + PDI (U+2069) neutral isolate resolved as LTR.", "UAX #9 Section 3.1"),
        ("پہلا \u2068اردو-123\u2069 دوسرا", "FSI (U+2068) + PDI (U+2069) neutral isolate resolved as RTL.", "UAX #9 Section 3.1"),
        ("اردو \u2066Latin \u2067عربی\u2069 More\u2069 متن", "Nested LRI enclosing RLI, properly closed by dual PDI.", "Nested UAX #9 isolates"),
        ("English \u2067اردو \u2066Latin\u2069 مزید\u2069 End", "Nested RLI enclosing LRI, properly closed by dual PDI.", "Nested UAX #9 isolates", "LTR"),
        ("متن \u2067اردو \u2067عربی\u2069\u2069 ختم", "Nested dual RLI closed by dual PDI.", "Multi-level RTL nesting"),
        ("متن \u2066Level1 \u2066Level2\u2069\u2069 ختم", "Nested dual LRI inside RTL sentence.", "Multi-level LTR nesting"),
        ("اردو \u200E(English)\u200E متن", "LRM (U+200E) forcing English parentheses to LTR ordering.", "Directional mark placement"),
        ("English \u200F(اردو)\u200F End", "RLM (U+200F) forcing Urdu parentheses to RTL ordering.", "Directional mark placement", "LTR"),
        ("قیمت \u061C-123 روپے", "ALM (U+061C) forcing minus sign into Arabic/Urdu number order.", "Arabic letter mark with negative number"),
        ("حرارت \u061C-5 درجے", "ALM (U+061C) preceding minus on temperature.", "Negative temperature in Urdu"),
        ("فائل \u2066filename.txt\u2069 محفوظ ہو گئی۔", "LRI enclosing file path with extension inside Urdu.", "Operating system file dialog"),
        ("کوڈ \u2066print(\"ہیلو\")\u2069 چلائیں۔", "LRI enclosing programming statement with internal Urdu string.", "Coding tutorial for Urdu speakers"),
        ("رابطہ \u2066user@host.org\u2069 پر کریں", "LRI isolating email address.", "User interface communication panel"),
        ("لنک \u2066https://site.pk/صفحہ\u2069 ملاحظہ کریں", "LRI isolating IRI with Urdu percent-path.", "Web navigation instruction"),
        ("کمانڈ \u2066git commit -m \"اردو\"\u2069 درج کریں۔", "LRI isolating terminal command with Urdu parameter.", "Developer documentation"),
        ("نمبر \u2066+92-300-1234567\u2069 پر فون کریں۔", "LRI isolating international telephone format.", "Commercial contact listing"),
        ("ریاضی: \u2066a² + b² = c²\u2069 کلیہ", "LRI isolating mathematical Pythagorean theorem.", "Geometry textbook"),
        ("حالت: \u2066[RUNNING]\u2069 فعال ہے۔", "LRI isolating ASCII bracketed status token.", "Server management dashboard"),
        ("آئی ڈی: \u2066UUID-1234-5678\u2069 درج کریں۔", "LRI isolating uppercase UUID token.", "Database form instruction"),
        ("ماڈل: \u2066GPT-4o\u2069 جدید ترین ہے۔", "LRI isolating hybrid alphanumeric model name.", "Artificial intelligence article"),
        ("خطا: \u2066ERROR 404: Not Found\u2069 واقع ہوئی۔", "LRI isolating HTTP error message.", "Network debugging log"),
        ("ٹائپ: \u2066Array<String>\u2069 متغیر", "LRI isolating generic programming type.", "Programming language manual"),
        ("ٹیگ: \u2066<div dir=\"rtl\">\u2069 برائے بلاک", "LRI isolating HTML markup fragment.", "Web development curriculum"),
        ("قانون: \u2066Article 25-A\u2069 برائے تعلیم", "LRI isolating legal constitutional section.", "Legal rights booklet")
    ]
    for item in bidi_items:
        text, purpose, prov = item[0], item[1], item[2]
        bidi = item[3] if len(item) > 3 else "RTL"
        fixtures.append(make_fixture(next_id(), cat, f"Bidi: {text[:20]}...", text, purpose, provenance=prov, bidi=bidi))

    # ==========================================
    # 6. Malformed & Raw UTF-16 Edge Cases (20 fixtures)
    # ==========================================
    cat = "malformed_and_utf16_edge_cases"
    malformed_items = [
        ("\U0001F600", "Valid supplementary character 😀 (U+1F600, SMP smiling face emoji, 2 UTF-16 code units).", "Unicode supplementary plane character"),
        ("\U0001F469\u200D\U0001F4BB", "Valid emoji sequence 👩‍💻 (woman technologist: U+1F469 + ZWJ + U+1F4BB, 5 UTF-16 code units).", "Complex emoji ZWJ sequence"),
        ("\uD83D\u0627", "Lone high surrogate (U+D83D) followed by Urdu Alef (U+0627).", "Malformed UTF-16 surrogate followed by BMP letter"),
        ("\uD83D", "Unmatched high surrogate (U+D83D) without low surrogate.", "Malformed UTF-16 edge case"),
        ("\uDE00", "Unmatched low surrogate (U+DE00) without high surrogate.", "Malformed UTF-16 edge case"),
        ("\uDE00\uD83D", "Inverted surrogate pair (low surrogate before high surrogate).", "Malformed UTF-16 sequence"),
        ("\u064E", "Lone combining mark Fatha at start of paragraph/buffer.", "Lone diacritic at buffer start"),
        ("\u0651", "Lone combining mark Shaddah at start of paragraph/buffer.", "Lone diacritic at buffer start"),
        ("\u0650", "Lone combining mark Kasra at start of paragraph/buffer.", "Lone diacritic at buffer start"),
        ("\u064F", "Lone combining mark Damma at start of paragraph/buffer.", "Lone diacritic at buffer start"),
        ("\u0654", "Lone combining mark Hamza Above at start of paragraph/buffer.", "Lone diacritic at buffer start"),
        ("\u064E\u0651\u064F", "Triple stacked combining marks without base letter.", "Orphan combining cluster"),
        (" \u064E ", "Combining mark surrounded by spaces without base consonant.", "Space-isolated mark"),
        ("اردو\uFFFFمتن", "Unicode non-character U+FFFF embedded within Urdu prose.", "Non-character code point boundary"),
        ("اردو\uFFFEمتن", "Unicode non-character U+FFFE embedded within Urdu prose.", "Byte-order non-character boundary"),
        ("\u2067اردو", "Unclosed Right-to-Left Isolate (RLI without terminating PDI).", "Unterminated bidi isolate"),
        ("\u2066English", "Unclosed Left-to-Right Isolate (LRI without terminating PDI).", "Unterminated bidi isolate", "LTR"),
        ("\u2068متن", "Unclosed First Strong Isolate (FSI without terminating PDI).", "Unterminated bidi isolate"),
        ("\u2069اردو", "Pop Directional Isolate (PDI) with stack underflow (no prior isolate).", "Isolate stack underflow"),
        ("\u2067\u0627\u2066B\u2069", "Nested isolates with mismatched PDI count (2 opened, 1 closed).", "Isolate stack mismatch"),
        (" \u200C ", "Zero Width Non-Joiner (ZWNJ) surrounded by whitespace.", "Isolated joiner without letter context"),
        (" \u200D ", "Zero Width Joiner (ZWJ) surrounded by whitespace.", "Isolated joiner without letter context"),
        ("متن\u200C\u200C\u200Cمتن", "Multiple consecutive ZWNJ characters between words.", "Redundant joining control sequence")
    ]
    for item in malformed_items:
        text, purpose, prov = item[0], item[1], item[2]
        bidi = item[3] if len(item) > 3 else "RTL"
        fixtures.append(make_fixture(next_id(), cat, f"Malformed/Edge: {purpose[:24]}", text, purpose, provenance=prov, bidi=bidi))

    # ==========================================
    # 7. Formatting Boundaries & Exact Undo/Redo (20 fixtures)
    # ==========================================
    cat = "formatting_and_undo_redo"
    formatting_items = [
        ("اردو عنوان", "Bold style applied to entire Urdu heading.", "Heading formatting", [{"start": 0, "end": 10, "bold": True}]),
        ("خوبصورت خط", "Italic style applied to Urdu phrase.", "Emphasis formatting", [{"start": 0, "end": 10, "italic": True}]),
        ("نستعلیق فونٹ", "Bold on first word, plain on second word.", "Word boundary formatting", [{"start": 0, "end": 7, "bold": True}]),
        ("یہ اہم نکتہ ہے", "Bold on middle emphatic adjective.", "Medial word formatting", [{"start": 3, "end": 6, "bold": True}]),
        ("کتاب [تفصیل] صفحہ", "Bold styling on bracketed cross-reference.", "Link/reference boundary", [{"start": 5, "end": 13, "bold": True}]),
        ("عنوان: تفصیلی جائزہ", "Bold on label prefix before colon.", "Label formatting", [{"start": 0, "end": 6, "bold": True}]),
        ("• پہلا اہم نکتہ", "Bold bullet marker with plain Urdu text.", "List item marker formatting", [{"start": 0, "end": 2, "bold": True}]),
        ("شعر: دل ہی تو ہے", "Bold on speaker/genre tag before colon.", "Poetry tag boundary", [{"start": 0, "end": 4, "bold": True}]),
        ("اردو اور English متن", "Bold applied to Urdu segment, plain English, plain Urdu.", "Mixed-script style boundary", [{"start": 0, "end": 8, "bold": True}]),
        ("قیمت ۱۲۳ روپے", "Bold applied to Eastern Arabic numerals.", "Numeral styling", [{"start": 5, "end": 8, "bold": True}]),
        ("خطِ نستعلیق", "Italic on Persian/Urdu compound with Zer-e-Izafat.", "Izafat styled phrase", [{"start": 0, "end": 11, "italic": True}]),
        ("نظم: شکوہ و جوابِ شکوہ", "Bold title with italic subtitle.", "Complex styled title", [{"start": 0, "end": 4, "bold": True}, {"start": 5, "end": 22, "italic": True}]),
        ("محبت اور قربانی", "Undo step 1: reverts bold on 'محبت', restoring plain state.", "Undo formatting reversibility", [{"start": 0, "end": 4, "bold": True}]),
        ("سچائی کی فتح", "Undo step 2: reverts text insertion of 'کی فتح', restoring 'سچائی'.", "Undo text mutation reversibility", None),
        ("قومی ترانہ", "Redo step: reapplies bold formatting to 'قومی ترانہ'.", "Redo formatting reversibility", [{"start": 0, "end": 9, "bold": True}]),
        ("مستقبل کی منصوبہ بندی", "Cut selection of 'منصوبہ بندی', undo restores text and bold style.", "Cut and Undo preservation", [{"start": 10, "end": 21, "bold": True}]),
        ("دیانت دار ملازم", "Backspace across bold/plain boundary: undo restores both style and letter.", "Boundary backspace undo", [{"start": 0, "end": 9, "bold": True}]),
        ("شاندار عمارت", "Forward delete across style boundary: undo restores boundary.", "Boundary delete undo", [{"start": 0, "end": 6, "bold": True}]),
        ("مُحَمَّد رسول اللہ", "Bold style applied across vocalized name with Shaddah/Fatha.", "Diacritic style preservation", [{"start": 0, "end": 7, "bold": True}]),
        ("کالم الف: ۱۰۰ روپے", "Tabular column label bold with plain numeral and currency.", "Tabular cell style", [{"start": 0, "end": 8, "bold": True}])
    ]
    for text, purpose, prov, spans in formatting_items:
        fixtures.append(make_fixture(next_id(), cat, f"Format/Undo: {text[:20]}...", text, purpose, provenance=prov, formatting_spans=spans, undo_redo_plan=["apply_style", "verify_units", "undo", "verify_baseline", "redo", "verify_units"]))

    # ==========================================
    # 8. Nastaliq Geometry & Ligatures (28 fixtures)
    # ==========================================
    cat = "nastaliq_geometry"
    geom_items = [
        ("نستعلیق", "Four-level cascading baseline in canonical script name.", "Script name standard ligature"),
        ("تحقیق", "Teh-Hah-Qaf-Yeh-Qaf steep diagonal sloping cascade.", "Academic high-frequency word"),
        ("تصدیق", "Teh-Sad-Dal-Yeh-Qaf steep cascade.", "Administrative authentication word"),
        ("تشکیل", "Teh-Sheen-Kaf-Yeh-Lam vertical ligature stack.", "Morphological linguistic term"),
        ("تخلیق", "Teh-Khah-Lam-Yeh-Qaf complex cascading ligature.", "Artistic creation term"),
        ("خوشخطی", "Calligraphic phrase with descending Khah and Choti Yeh.", "Calligraphy manual heading"),
        ("سلسلہ", "Three consecutive Seen teeth cascading into Lam-Heh.", "Genealogical and serial phrase"),
        ("جھجک", "Multiple Jeem/Cheh forms with Do-Chashmi Heh.", "Psychological hesitation noun"),
        ("لکھنؤ", "Lam-Kaf-Heh-Noon-Waw with Hamza on high Waw.", "Historical cultural capital"),
        ("شخصیت", "Sheen-Khah-Sad-Yeh-Teh tall vertical ligature.", "Biographical noun"),
        ("فیصل", "Feh-Yeh-Sad-Lam sloping ligature.", "Proper name and judicial term"),
        ("محبت", "Meem-Hah-Beh-Teh classic cascading love noun.", "Poetic vocabulary"),
        ("مستقبل", "Meem-Seen-Teh-Qaf-Beh-Lam six-letter cascade.", "Temporal future noun"),
        ("طبیعت", "Tah-Beh-Yeh-Ayn-Teh medical disposition noun.", "Everyday health inquiry"),
        ("حقیقت", "Hah-Qaf-Yeh-Qaf-Teh steep truth noun.", "Philosophical truth noun"),
        ("مقتدر", "Meem-Qaf-Teh-Dal-Reh cascading authority adjective.", "Institutional title word"),
        ("مستحکم", "Meem-Seen-Teh-Hah-Kaf-Meem stable adjective.", "Political stability essay"),
        ("استقامت", "Alef-Seen-Teh-Qaf-Alef-Meem-Teh steadfastness noun.", "Moral steadfastness treatise"),
        ("خوشخبری", "Khah-Waw-Sheen-Khah-Beh-Reh-Yeh cascading good news.", "Journalistic headline"),
        ("تذبذب", "Teh-Zal-Beh-Zal-Beh alternating dental consonants.", "State of doubt noun"),
        ("جھٹپٹا", "Aspirated Jeem + retroflex Teh + Peh + Tteh evening twilight.", "Twilight temporal noun"),
        ("کھڑکھڑاہٹ", "Multiple aspirated and retroflex letters with Alif separators.", "Onomatopoeic sound noun"),
        ("چٹان", "Cheh-Tteh-Alef-Noon solid rock noun.", "Geological description"),
        ("للہ", "Lam-Lam-Heh sacred ligature context.", "Theological discourse"),
        ("محمد رسول اللہ", "Multiple sacred ligatures in high-frequency invocation.", "Creedal declaration"),
        ("صلی اللہ علیہ وسلم", "Honorific durud phrase ligature.", "Traditional eulogy ligature"),
        ("رحمۃ اللہ علیہ", "Posthumous blessing ligature phrase.", "Biographical honorific"),
        ("عزوجل", "Divine majesty ligature.", "Theological formula")
    ]
    for text, purpose, prov in geom_items:
        fixtures.append(make_fixture(next_id(), cat, f"Geometry: {text[:20]}...", text, purpose, provenance=prov, execution_scope="CORPUS_ONLY"))

    # ==========================================
    # 9. Spacing & Justification (26 fixtures)
    # ==========================================
    cat = "spacing_and_justification"
    spacing_items = [
        ("ایک دو تین", "Standard U+0020 space separating single words.", "Counting baseline"),
        ("جناب صدر\nمعزز حاضرین", "U+000A newline separating two spoken address paragraphs.", "Speech salutation"),
        ("پہلی سطر\r\nدوسری سطر", "CRLF (U+000D U+000A) Windows line ending.", "Windows text file persistence"),
        ("کالم ۱\tکالم ۲", "Horizontal Tab (U+0009) separating table columns in plain text.", "Tabular plain text data"),
        ("ملاحظہ فرمائیں صفحہ\u00A0۲۵ پر تفصیلی رپورٹ۔", "NBSP U+00A0 keeping page label and number on same line.", "Editorial index"),
        ("پنجاب کا رقبہ\u00A0۲،۰۵،۳۴۴ مربع کلومیٹر ہے۔", "NBSP U+00A0 between statistical label and numeric quantity.", "Geographical gazetteer"),
        ("ڈاکٹر\u00A0اقبال نے خطبہ دیا", "NBSP U+00A0 between academic title and proper noun.", "Biographical article"),
        ("لاہور میں ۲۵\u202Fفیصد بارش ہوئی", "NNBSP U+202F before percent sign.", "Statistical report"),
        ("صرف ۵\u202Fروپے", "NNBSP U+202F between digit and currency unit.", "Price tag"),
        ("یہ بہت، بہت ضروری بات ہے۔", "Comma followed by space in emphatic repetition.", "Emphatic rhetorical sentence"),
        ("کیا، کیوں، اور کیسے؟", "Serial commas followed by question mark in list.", "Interrogative list"),
        ("سوچو؛ پھر بولو۔", "Semicolon with following space separating antithetical clauses.", "Didactic advice"),
        ("اس کا جواب: بالکل نہیں۔", "Colon followed by space introducing direct answer.", "Dialogue transcript"),
        ("انہوں نے کہا: ”ہم متفق ہیں۔“", "Quotation mark adjacency to sentence-final Khatma.", "News interview"),
        ("حوالہ [۱] دیکھیں۔", "Square bracket adjacent to punctuation and trailing word.", "Scholarly citation"),
        ("ایک مختصر سطر۔", "Short last line of paragraph testing no stretched tatweel.", "Short paragraph termination"),
        ("ہاں۔", "Single-word short last line ending with Khatma.", "Minimal terminal line"),
        ("شکریہ۔", "Polite closing short line.", "Letter sign-off"),
        ("والسلام۔", "Traditional Arabic-Urdu letter sign-off short line.", "Epistolary closure"),
        ("مورخہ: ۱۰ جنوری", "Short header line ending with date.", "Memorandum header"),
        ("اردو زبان کی تاریخ بہت وسیع اور قدیم ہے جو مختلف تہذیبوں کے ملاپ سے پروان چڑھی۔", "Long line testing natural inter-word spacing without kashida injection.", "Literary history", ["0640"]),
        ("کتاب کے مطالعے سے انسان کے شعور اور آگہی میں بے پناہ اضافہ ہوتا چلا جاتا ہے۔", "Full justification test verifying no Tatweel U+0640 injected into canonical text.", "Educational essay", ["0640"]),
        ("یہ ایک طویل پیراگراف ہے جس کا مقصد اردو متن میں لفظوں کے باہمی فاصلے کی جانچ کرنا ہے۔", "Justification paragraph testing space distribution across multiple words.", "Typesetting test specimen", ["0640"]),
        ("نظم و ضبط کی پابندی ہر ادارے کی کامیابی کے لیے ریڑھ کی ہڈی کی حیثیت رکھتی ہے۔", "Administrative discipline paragraph.", "Corporate handbook", ["0640"]),
        ("تعلیم کا اصل مقصد انسان کو اچھے اور برے میں تمیز سکھانا اور باشعور بنانا ہے۔", "Philosophical education paragraph.", "Moral education syllabus", ["0640"]),
        ("قدرتی مناظر کی خوبصورتی انسانی روح کو تازگی اور فکری سکون عطا کرتی ہے۔", "Aesthetic nature paragraph.", "Environmental psychology paper", ["0640"])
    ]
    for item in spacing_items:
        text, purpose, prov = item[0], item[1], item[2]
        forb = item[3] if len(item) > 3 else None
        fixtures.append(make_fixture(next_id(), cat, f"Spacing: {text[:20]}...", text, purpose, provenance=prov, forbidden=forb))

    # ==========================================
    # 10. Structure & Future Page/Cell Shapes (32 fixtures)
    # ==========================================
    cat = "structure_and_future_page_cells"
    # Executable structural items (22 items)
    exec_struct_items = [
        ("عنوان اول: پاکستان کا جغرافیہ", "H1 primary section heading in Urdu.", "Geography textbook"),
        ("ذیلی عنوان: دریائے سندھ اور اس کے معاون دریا", "H2 secondary subsection heading.", "Hydrology chapter"),
        ("تیسرا درجہ: زراعت اور آبپاشی", "H3 sub-subsection heading.", "Agronomy report"),
        ("۱۔ دیانت داری اور سچائی\n۲۔ محنت اور استقلال\n۳۔ حب الوطنی اور قربانی", "Numbered list in Urdu using Eastern Arabic numerals.", "Civic education curriculum"),
        ("• اردو رسم الخط کی خصوصیات\n• خطِ نستعلیق کی تاریخ\n• کمپیوٹر پر اردو کی ترویج", "Unordered bulleted list in Urdu with RTL bullet alignment.", "Computer science lecture outline"),
        ("دل ناداں تجھے ہوا کیا ہے\nآخر اس درد کی دوا کیا ہے", "Classical Ghazal couplet (Ghalib) in two hemistichs.", "Diwan-e-Ghalib (1841)"),
        ("ہزاروں خواہشیں ایسی کہ ہر خواہش پہ دم نکلے\nبہت نکلے مرے ارمان لیکن پھر بھی کم نکلے", "Classical Ghazal couplet (Ghalib) testing long horizontal Misra.", "Diwan-e-Ghalib (1841)"),
        ("ستاروں سے آگے جہاں اور بھی ہیں\nابھی عشق کے امتحان اور بھی ہیں", "Inspirational couplet (Iqbal) in two rhyming hemistichs.", "Bal-e-Jibril (1935)"),
        ("خودی کو کر بلند اتنا کہ ہر تقدیر سے پہلے\nخدا بندے سے خود پوچھے بتا تیری رضا کیا ہے", "Famous pedagogical couplet (Iqbal) testing multi-tiered ligatures.", "Bal-e-Jibril (1935)"),
        ("بول کہ لب آزاد ہیں تیرے\nبول زباں اب تک تیری ہے", "Modern free-verse poetry couplet (Faiz Ahmad Faiz).", "Dast-e-Saba (1952)"),
        ("ہم پرورشِ لوح و قلم کرتے رہیں گے\nجو دل پہ گزرتی ہے رقم کرتے رہیں گے", "Literary commitment couplet (Faiz Ahmad Faiz).", "Dast-e-Tah-e-Sang (1965)"),
        ("حاشیہ نمبر [۱]: تاریخ ادب اردو، از ڈاکٹر جمیل جالبی، جلد اول، صفحہ ۱۲۵۔", "Footnote reference citation.", "Literary history monograph"),
        ("حاشیہ نمبر [۲]: کلیات میر، تدوین ڈاکٹر عبادت بریلوی، صفحہ ۴۵۰۔", "Footnote reference citation.", "Literary research journal"),
        ("عنوانِ کالم | کالم نگار | تاریخِ اشاعت", "Table header row with pipe delimiters.", "Newspaper editorial index"),
        ("باب سوئم: جدید اردو افسانے کے موضوعات", "H2 chapter header.", "Urdu fiction critical studies"),
        ("نتیجہ بحث:\nمذکورہ بالا تمام شواہد سے یہ بات پایہ ثبوت کو پہنچتی ہے کہ اردو زبان کا دائرہ کار بین الاقوامی ہے۔", "Conclusion section with bold lead-in.", "Doctoral dissertation summary"),
        ("سفارشات برائے پالیسی سازی:\n۱۔ پرائمری سطح پر مادری زبان میں تعلیم دی جائے۔\n۲۔ کتب خانوں کو جدید خطوط پر استوار کیا جائے۔", "Numbered policy recommendations.", "National education commission"),
        ("سرورق: قومی انسائیکلوپیڈیا\nاشاعت: اسلام آباد\nسالِ اشاعت: ۲۰۲۶ء", "Front matter title block.", "National publishing corporation"),
        ("اختتامیہ:\nہم اپنے تمام قارئین کے شکر گزار ہیں جنہوں نے اس تحقیقی کام میں رہنمائی فرمائی۔", "Epilogue acknowledgment block.", "Scholarly monograph"),
        ("ضمیمہ الف: تاریخی دستاویزات کا عکس", "Appendix title.", "Archival compilation"),
        ("فہرست مضامین:\n۱۔ پیش لفظ ...................... ۵\n۲۔ باب اول ...................... ۱۲", "Table of contents with dot leaders.", "Monograph table of contents"),
        ("کتابیات:\n۱۔ آزاد، محمد حسین: آب حیات، لاہور، ۱۹۰۷ء۔\n۲۔ حالی، الطاف حسین: یادگار غالب، دہلی، ۱۸۹۷ء۔", "Bibliography citations list.", "Academic monograph bibliography")
    ]
    for text, purpose, prov in exec_struct_items:
        fixtures.append(make_fixture(next_id(), cat, f"Structure: {text[:20]}...", text, purpose, provenance=prov, execution_scope="EXECUTABLE_PROTOTYPE"))

    # Future structural/page cases (10 items)
    future_struct_items = [
        ("خانہ جدول: کالم اول\nیہ پہلے پیراگراف کا متن ہے۔\n\nیہ دوسرے پیراگراف کا متن ہے جو اسی خانے میں واقع ہے۔", "Rich table cell containing multiple distinct RTL paragraphs.", "Table cell layout specification", "TABLE_CELL_LAYOUT"),
        ("خانہ جدول: عنوان فہرست\n• پہلا ذیلی نکتہ\n• دوسرا ذیلی نکتہ", "Table cell containing an Urdu heading followed by an embedded bullet list.", "Table cell layout specification", "TABLE_CELL_LAYOUT"),
        ("خانہ جدول: Urdu Text (English Annotation) ۱۲۳", "Table cell with mixed Urdu-English bidirectional content.", "Table cell layout specification", "TABLE_CELL_LAYOUT"),
        ("سرورق / بالائی حاشیہ: تاریخِ پاکستان — باب اول\tصفحہ ۱۵", "Running page header with chapter title aligned right and page number left.", "Page layout specification", "PAGE_LAYOUT_AND_FOOTNOTES"),
        ("پائینتی حاشیہ: ادارہ فروغِ قومی زبان، اسلام آباد\tصفحہ ۲۵", "Running page footer with publishing imprint and page count.", "Page layout specification", "PAGE_LAYOUT_AND_FOOTNOTES"),
        ("یہ صفحہ اول کا آخری جملہ ہے جس کے بعد صفحہ ٹوٹ جاتا ہے۔\n[PAGE_BREAK]\nیہ صفحہ دوم کا پہلا جملہ ہے۔", "Page break boundary following terminal Urdu punctuation.", "Pagination break specification", "PAGE_LAYOUT_AND_FOOTNOTES"),
        ("[۱] حاشیہ برائے صفحہ ۴۵: کلیاتِ اقبال، طبعِ اول، صفحہ ۸۹۔", "Page footnote container anchored to reference [۱] at page bottom.", "Page footnote specification", "PAGE_LAYOUT_AND_FOOTNOTES"),
        ("[۲] حاشیہ تفصیلی: شواہد النبوۃ، فارسی متن مع اردو ترجمہ و تعلیقات، دہلی، ۱۸۸۲ء۔", "Multi-line scholarly footnote body at page boundary.", "Page footnote specification", "PAGE_LAYOUT_AND_FOOTNOTES"),
        ("مصرعِ اول: دل ہی تو ہے نہ سنگ و خشت\tمصرعِ دوم: درد سے بھر نہ آئے کیوں", "Two-column poetry layout pairing Misra-e-Oola and Misra-e-Sani.", "Poetry layout specification", "POETRY_TWO_COLUMN_LAYOUT"),
        ("«اقتباس: علم کی جستجو ہر دور میں انسانی ارتقا کی بنیادی محرک رہی ہے جس کے بغیر کوئی قوم ترقی کی منازل طے نہیں کر سکتی۔»", "Blockquote block with indented RTL margins.", "Blockquote layout specification", "BLOCKQUOTE_LAYOUT")
    ]
    for text, purpose, prov, domain in future_struct_items:
        fixtures.append(make_fixture(next_id(), cat, f"Future Page/Cell: {purpose[:24]}", text, purpose, provenance=prov, execution_scope="FUTURE_STRUCTURAL_PAGE", future_domain=domain))

    # ==========================================
    # 11. Input & Mutation Edge Cases (28 fixtures)
    # ==========================================
    cat = "input_and_mutation"
    input_items = [
        ("محمد", "Base unvocalized Urdu name for mutation testing.", "Mutation baseline"),
        ("مُحَمَّد", "Vocalized name testing diacritic deletion mutation back to base 'محمد'.", "Diacritic deletion trace"),
        ("نستعلیق", "Baseline word for testing medial character backspace mutation.", "Medial backspace baseline"),
        ("نستلیق", "Word after deleting 'ع' (Ayn) from medial ligature to verify re-shaping.", "Medial backspace outcome"),
        ("آ", "Precomposed Alef with Madda (U+0622).", "Precomposed Madda test"),
        ("ا\u0653", "Decomposed Alef + combining Madda (U+0627 + U+0653) to verify canonical equivalence.", "Decomposed Madda test"),
        ("پ", "Urdu Peh typed via AltGr or phonetic key 'p'.", "Keymapping test"),
        ("ٹ", "Urdu Tteh typed via Shift+T on phonetic keyboard.", "Keymapping test"),
        ("ڈ", "Urdu Ddal typed via Shift+D on phonetic keyboard.", "Keymapping test"),
        ("ڑ", "Urdu Rreh typed via Shift+R on phonetic keyboard.", "Keymapping test"),
        ("ں", "Urdu Noon Ghunna typed via Shift+N on phonetic keyboard.", "Keymapping test"),
        ("ے", "Urdu Bari Yeh typed via 'e' or 'y' on phonetic keyboard.", "Keymapping test"),
        ("ء", "Standalone Hamza typed via 'u' or Shift+U on phonetic keyboard.", "Keymapping test"),
        ("ۂ", "Heh Goal with Hamza Above (U+06C2) single code unit.", "Precomposed Hamza-e-Izafat"),
        ("ہ\u0654", "Heh Goal + combining Hamza Above (U+06C1 + U+0654) sequence.", "Decomposed Hamza-e-Izafat"),
        ("ؤ", "Waw with Hamza Above (U+0624) precomposed.", "Precomposed Waw-Hamza"),
        ("و\u0654", "Waw + combining Hamza Above (U+0648 + U+0654) decomposed sequence.", "Decomposed Waw-Hamza"),
        ("ئ", "Yeh with Hamza Above (U+0626) precomposed.", "Precomposed Yeh-Hamza"),
        ("ی\u0654", "Urdu Yeh + combining Hamza Above (U+06CC + U+0654) decomposed sequence.", "Decomposed Urdu Yeh-Hamza"),
        ("ے\u0654", "Urdu Bari Yeh + combining Hamza Above (U+06D2 + U+0654) sequence.", "Decomposed Bari Yeh-Hamza"),
        ("ک‍", "Incomplete typing state: initial Kaf waiting for next key.", "IME partial composition trace"),
        ("کتاب", "Completed typing state of initial Kaf into word.", "IME committed composition trace"),
        ("قلم ", "Word followed by space waiting for next word.", "Word boundary space entry"),
        ("قلم۔", "Word followed by immediate Khatma punctuation.", "Punctuation boundary entry"),
        ("اردو  متن", "Two consecutive spaces typed between words.", "Multiple space typing event"),
        ("سلام\nوعلیکم", "Newline inserted between words.", "Enter key split line trace"),
        ("تحریر 123", "Typing transition from Urdu to Latin digits.", "Script boundary typing trace"),
        ("ٹیسٹ Text", "Typing transition from Urdu to Latin alphabetic characters.", "Bidi boundary typing trace")
    ]
    for text, purpose, prov in input_items:
        fixtures.append(make_fixture(next_id(), cat, f"Input: {text[:20]}...", text, purpose, provenance=prov))

    return fixtures

def main():
    fixtures = build_all_fixtures()
    print(f"Total fixtures generated: {len(fixtures)}")
    
    # Check category distribution
    counts = {}
    scope_counts = {}
    for f in fixtures:
        counts[f["category"]] = counts.get(f["category"], 0) + 1
        scope_counts[f["executionScope"]] = scope_counts.get(f["executionScope"], 0) + 1
    print("Categories:")
    for cat, count in counts.items():
        print(f"  {cat}: {count}")
    print("Execution Scopes:")
    for scope, count in scope_counts.items():
        print(f"  {scope}: {count}")

    output_path = os.path.join(os.path.dirname(__file__), "fixtures.json")
    dumped = json.dumps(fixtures, ensure_ascii=False, indent=2)
    # Strictly escape any lone surrogates U+D800..U+DFFF as \uXXXX to produce strict RFC 8259 UTF-8
    strict_utf8 = re.sub(r'[\ud800-\udfff]', lambda m: f"\\u{ord(m.group()):04x}", dumped)
    with open(output_path, "wb") as f:
        f.write(strict_utf8.encode("utf-8"))
    print(f"Written {len(fixtures)} fixtures to {output_path} (strict UTF-8 with escaped surrogates)")

if __name__ == "__main__":
    main()
