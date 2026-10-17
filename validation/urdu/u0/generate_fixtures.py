"""
Deterministic Urdu acceptance fixtures generator for U0 gate.
Generates at least 200 deterministic fixtures across 9 categories defined in URDU-ACCEPTANCE.md.
"""

import json
import os
import unicodedata

CATEGORIES = [
    "common_prose",
    "letter_distinctions",
    "marks_and_joining",
    "mixed_scripts_and_numbers",
    "bidi_controls",
    "nastaliq_geometry",
    "spacing_and_justification",
    "structure",
    "input_and_mutation"
]

def make_fixture(fixture_id, category, title, text, purpose, provenance="Self-authored standard Urdu test fixture", forbidden=None, bidi="RTL", req_nastaliq=True):
    raw_code_units = "".join(f"\\u{ord(c):04x}" for c in text)
    code_points_hex = [f"{ord(c):04X}" for c in text]
    forbidden_list = forbidden or []
    
    return {
        "id": fixture_id,
        "category": category,
        "title": title,
        "text": text,
        "rawCodeUnits": raw_code_units,
        "codePointsHex": code_points_hex,
        "purpose": purpose,
        "provenance": provenance,
        "expectedOutcome": {
            "bidiDirection": bidi,
            "joiningValid": True,
            "requiresNastaliq": req_nastaliq,
            "canonicalInvariance": True,
            "forbiddenInCanonical": forbidden_list
        },
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
        ("شام کے سائے گہرے ہو رہے تھے اور ہوا میں ہلکی سی خنکی تھی۔", "Atmospheric descriptive clause.", "Fiction anthology"),
        ("تاریخ اس بات کی گواہ ہے کہ قومیں تعلیم سے ہی عروج پاتی ہیں۔", "Historical thesis sentence.", "Academic historical discourse"),
        ("پھولوں کی خوشبو سے سارا چمن مہک اٹھا تھا۔", "Poetic imagery in standard prose.", "Classical Urdu novel"),
        ("انسان اپنی سوچ اور عمل سے ہی پہچانا جاتا ہے۔", "Philosophical observation on human character.", "Reflective prose essay"),
        ("سورج مشرق سے نکلتا ہے اور مغرب میں غروب ہوتا ہے۔", "Basic geographical statement testing directional vocabulary.", "Primary geography textbook"),
        ("قائداعظم محمد علی جناح نے پاکستان کے قیام کے لیے انتھک محنت کی۔", "Biographical tribute with historical names.", "Pakistan studies textbook"),
        ("علامہ اقبال نے نوجوانوں کو خودی کا درس دیا اور شاہین سے تشبیہ دی۔", "Literary and philosophical exposition.", "National poetry critique"),
        ("دریا کی لہریں خاموشی سے ساحل سے ٹکرا رہی تھیں۔", "Fluid descriptive sentence with Yeh and Noon Ghunna.", "Modern short story"),
        ("زندگی مسلسل جدوجہد اور عمل کا دوسرا نام ہے۔", "Existential statement testing Dal-Waw-Jeem-Heh-Dal.", "Philosophical tract"),
        ("صحت مند جسم میں ہی صحت مند دماغ پرورش پاتا ہے۔", "Hygiene maxim testing Sad-Heh-Teh.", "Health sciences brochure"),
        ("پہاڑوں کی بلند چوٹیاں برف سے ڈھکی ہوئی تھیں۔", "Geographic description with retroflex Ddal and Kaf-Heh.", "Travelogue northern areas"),
        ("عدل و انصاف کسی بھی پرامن معاشرے کی بنیادی ضرورت ہے۔", "Legal philosophical prose with Waw-e-Atf.", "Jurisprudence treatise"),
        ("ہماری ثقافت میں مہمان نوازی کو بہت اعلیٰ مقام حاصل ہے۔", "Cultural values exposition.", "Sociology textbook"),
        ("پرانی یادیں دل کے نہاں خانوں میں چراغ کی طرح جلتی رہتی ہیں۔", "Reflective emotional prose.", "Autobiographical memoir"),
        ("شہر کی پررونق سڑکوں پر رات گئے تک گہما گہمی رہتی ہے۔", "Urban description with Gaf-Heh-Meem-Alif.", "City journalism report"),
        ("خاموشی بسا اوقات ہزار لفظوں سے زیادہ اثر رکھتی ہے۔", "Proverbial expression testing Alif-Maqsurah and Bay.", "Literary aphorism collection"),
        ("بچوں کی تربیت میں والدین اور اساتذہ کا کردار کلیدی ہے۔", "Pedagogical discourse testing Kaf-Lam-Yeh-Dal-Yeh.", "Child psychology publication"),
        ("آزادی ایک ایسی نعمت ہے جس کی کوئی قیمت ادا نہیں کی جا سکتی۔", "Constitutional liberty reflection.", "Civic editorial")
    ]
    for text, purpose, prov in prose_items:
        fixtures.append(make_fixture(next_id(), cat, f"Prose: {text[:20]}...", text, purpose, provenance=prov))

    # ==========================================
    # 2. Urdu Letter Distinctions (32 fixtures)
    # ==========================================
    cat = "letter_distinctions"
    distinction_items = [
        # Urdu Kaf (06A9) vs Arabic Kaf (0643)
        ("کتاب کا پہلا باب", "Verify Urdu Keheh U+06A9 is preserved and not converted to Arabic Kaf U+0643.", ["0643"]),
        ("کامران نے اپنا کام مکمل کر لیا۔", "Verify Urdu Keheh U+06A9 across initial, medial, and final positions.", ["0643"]),
        ("شکر اور صبر انسان کو باوقار بناتے ہیں۔", "Medial Urdu Keheh U+06A9 in ligature.", ["0643"]),
        ("نیک نیتی اور سچی لگن", "Final and initial Urdu Keheh U+06A9.", ["0643"]),
        # Urdu Choti Yeh (06CC) vs Arabic Yeh (064A)
        ("پانی زندگی کی بنیادی ضرورت ہے۔", "Verify Urdu Choti Yeh U+06CC in final position without two bottom dots.", ["064A"]),
        ("دوستی اور محبت کا رشتہ", "Verify Urdu Choti Yeh U+06CC in isolation and joined.", ["064A"]),
        ("روشنی ہر طرف پھیل گئی۔", "Verify Urdu Choti Yeh U+06CC terminal form curvature.", ["064A"]),
        ("گاڑی تیزی سے جا رہی تھی۔", "Urdu Yeh U+06CC paired with retroflex Rreh.", ["064A"]),
        # Urdu Bari Yeh (06D2)
        ("سارے لڑکے اپنے اپنے کمرے سے نکلے", "Verify pure Urdu Bari Yeh U+06D2 sequence without Choti Yeh.", ["06CC", "064A"]),
        ("سارے بچے خوشی سے کھیل رہے تھے۔", "Verify Urdu Bari Yeh U+06D2 terminal flat tail curve.", ["064A"]),
        ("ہم نے اچھے کپڑے پہنے ہوئے تھے۔", "Multiple Bari Yeh U+06D2 words in succession.", ["064A"]),
        ("آنے والے کل کے لیے تیاری کریں", "Bari Yeh U+06D2 in prepositional context.", ["064A"]),
        ("بڑے بوڑھے ہمیشہ سچ کہتے ہیں۔", "Urdu Bari Yeh U+06D2 after retroflex letters.", ["064A"]),
        # Noon Ghunna (06BA)
        ("ماں کی دعا جنت کی ہوا ہے۔", "Verify Noon Ghunna U+06BA without dot in final position.", []),
        ("جہاں کہیں بھی سچائی کی بات ہوگی", "Multiple final Noon Ghunna U+06BA tokens.", []),
        ("ہم وہاں شام تک انتظار کریں گے۔", "Terminal Noon Ghunna U+06BA in adverbial phrases.", []),
        ("آنکھوں میں نمی اور ہونٹوں پر مسکراہٹ", "Medial nasalized Noon (U+0646) followed by Khah/Teh.", []),
        # Gol Heh (06C1) vs Do-Chashmi Heh (06BE)
        ("یہ ہمارا کمرہ ہے اور وہ سارا سامان عمدہ ہے۔", "Urdu Gol Heh U+06C1 initial, medial, and final forms without Do-Chashmi Heh.", ["0647", "06BE"]),
        ("پھول اور پتے باغ میں کھل اٹھے", "Do-Chashmi Heh U+06BE aspirated Peh (پھ) and Kaf (کھ).", ["06C1", "0647"]),
        ("تھالی میں گرم کھانا رکھ دیا گیا۔", "Do-Chashmi Heh U+06BE aspirated Teh (تھ) and Kaf (کھ).", ["06C1", "0647"]),
        ("جھوٹ مت بولو اور سچ مت چھپاؤ", "Do-Chashmi Heh U+06BE aspirated Jeem (جھ) and Cheh (چھ).", ["06C1", "0647"]),
        ("دھوپ اور چھاؤں کا کھیل دیکھا", "Do-Chashmi Heh U+06BE aspirated Dal (دھ) and Cheh (چھ).", ["06C1", "0647"]),
        ("گھوڑا میدان میں دوڑتا گیا۔", "Do-Chashmi Heh U+06BE aspirated Gaf (گھ).", ["06C1", "0647"]),
        ("وجہ اور نتیجہ کا باہمی تعلق", "Urdu Gol Heh U+06C1 in medial and final joining.", ["06BE", "0647"]),
        # Retroflex consonants: Tteh (0679), Ddal (0688), Rreh (0691)
        ("ٹماٹر اور ٹوپی میز پر رکھے ہیں۔", "Urdu Tteh U+0679 with small Toha mark.", []),
        ("ڈاکیا خطوط کا ڈبہ لایا۔", "Urdu Ddal U+0688 with small Toha mark.", []),
        ("پہاڑ کی سڑک بہت ٹیڑھی میڑھی تھی۔", "Urdu Rreh U+0691 with small Toha mark.", []),
        ("چھوٹا ڈبہ سڑک کے کنارے پڑا ملا۔", "Combined retroflex cluster: Tteh, Ddal, and Rreh.", []),
        # Peh (067E), Tcheh (0686), Zheh (0698), Gaf (06AF)
        ("پاکستان کا سبز ہلالی پرچم لہرایا۔", "Urdu Peh U+067E with three inferior dots.", []),
        ("چاندنی رات میں چراغ جل اٹھے۔", "Urdu Tcheh U+0686 with three interior dots.", []),
        ("شدید ژالہ باری سے فصلیں تباہ ہو گئیں۔", "Urdu Zheh U+0698 with three superior dots.", []),
        ("گلاب کے پھول پر شبنم کے قطرے چمک رہے تھے۔", "Urdu Gaf U+06AF with secondary upper bar.", []),
        ("عالیہ نے زکوٰۃ اور صلوٰۃ کا اہتمام کیا۔", "Heh Goal with Teh Marbuta / Alif Khanjariah (U+06C3 / U+0670).", [])
    ]
    for text, purpose, forb in distinction_items:
        fixtures.append(make_fixture(next_id(), cat, f"Distinction: {text[:22]}...", text, purpose, forbidden=forb))

    # ==========================================
    # 3. Marks and Joining Controls (30 fixtures)
    # ==========================================
    cat = "marks_and_joining"
    mark_items = [
        ("مُحَمَّد رَسُولُ اللہ", "Tashkeel: Shaddah (U+0651), Fatha (U+064E), Damma (U+064F) in sacred name.", "Classical Quranic/Hadith Arabic quotation in Urdu"),
        ("اَلْحَمْدُ لِلّٰہِ رَبِّ الْعٰلَمِیْنَ", "Full vocalized basmalah/fatiha phrase with Khari Zabar U+0670.", "Scriptural citation"),
        ("دَرْس و تَدْرِیس کا عَمَل", "Urdu Sukun / Jazm (U+0652) marking consonant clusters.", "Standard pedagogical Urdu"),
        ("کِتَابِ زِنْدَگِی کَا نَیَا بَاب", "Urdu Kasra / Zer (U+0650) vowel markers.", "Vocalized literary Urdu"),
        ("پُل پَار کَر کے گَھر پَہُنچُو", "Urdu Damma / Pesh (U+064F) vowel markers.", "Vocalized children's literature"),
        ("فوراً جواب دیں اور تاخیر مت کریں۔", "Tanween Fathatan (U+064B) on Alef.", "Administrative communication"),
        ("مثلاً یہ بات بالکل واضح ہے۔", "Tanween Fathatan (U+064B) on Alef in common adverb.", "Academic expository prose"),
        ("تعظیماً تمام حاضرین کھڑے ہو گئے۔", "Tanween Fathatan (U+064B) in adverbial phrase.", "Ceremonial report"),
        ("عیسیٰ علیہ السلام اور موسیٰ کلیم اللہ", "Khari Zabar / Superscript Alef (U+0670) on Alef Maqsurah (U+0649).", "Religious historical text"),
        ("رحمٰن اور رحیم کے نام سے", "Khari Zabar (U+0670) over Meem.", "Devotional opening formula"),
        ("لہٗ ملک السمٰوٰت والارض", "Inverted Damma / Ulti Pesh (U+0657) over Heh.", "Quranic citation in Urdu commentary"),
        ("میرے لئے چائے کا ایک کپ لائیے۔", "Hamza above Bari Yeh (U+0644 U+06D2 U+0654).", "Conversational standard Urdu"),
        ("ہم سب خوشی سے شریک ہوئے تھے۔", "Hamza above Bari Yeh in verb form (ہوئے).", "Narrative memoir"),
        ("کوئی بھی شخص اس سے بے خبر نہیں تھا۔", "Hamza on Yeh chair (U+0626) in indefinite pronoun.", "Expository essay"),
        ("سب مل کر اندر آؤ اور کھانا کھاؤ۔", "Hamza on Waw (U+0624) in imperative verbs.", "Colloquial dialogue"),
        ("بے‌شمار نعمتیں اور بے‌انتہا فضل", "Zero Width Non-Joiner (ZWNJ U+200C) preventing affix ligation in 'بے‌شمار'.", "Morphological boundary acceptance"),
        ("اہل‌قلم اور اصحاب‌فکر کا اجتماع", "ZWNJ U+200C between Persian compound noun constituents.", "Literary conference proceedings"),
        ("نو‌وارد مسافروں کا استقبال کیا گیا۔", "ZWNJ U+200C after Waw prefix.", "Journalistic news"),
        ("دکھ‌درد میں ساتھ نبھانا ہی انسانیت ہے۔", "ZWNJ U+200C in dvandva compound noun.", "Moral philosophy"),
        ("انٹر‌نیٹ کے مثبت اور منفی اثرات", "ZWNJ U+200C in loan compound 'انٹر‌نیٹ'.", "Information technology guide"),
        ("ب‍", "Zero Width Joiner (ZWJ U+200D) forcing cursive initial form of Bay.", "Orthographic technical demonstration"),
        ("‍ج‍", "ZWJ U+200D forcing cursive medial form of Jeem.", "Orthographic technical demonstration"),
        ("‍س", "ZWJ U+200D forcing cursive final form of Seen.", "Orthographic technical demonstration"),
        ("مُدَّعی اور مُدَّعا علیہ", "Stacked marks: Shaddah (U+0651) + Fatha (U+064E) on Dal.", "Legal terminology"),
        ("شِدَّتِ جذبات پر قابو پانا", "Stacked marks: Shaddah (U+0651) + Fatha (U+064E) on Dal in Izafat.", "Psychological treatise"),
        ("مُعَلِّم اور مُتَعَلِّم کا باہمی ادب", "Stacked marks: Shaddah (U+0651) + Kasra (U+0650) on Lam.", "Classical educational treatise"),
        ("مُقَدَّس مقامات کی زیارت", "Stacked marks: Shaddah (U+0651) + Fatha (U+064E) on Dal.", "Travel and devotional guide"),
        ("سَخْتْ محنت اور لَگَن", "Multiple Sukun marks on adjacent consonants.", "Vocalized primer"),
        ("حُسْنِ تَدْبِیر سے کام لینا", "Multiple Tashkeel marks across Izafat construction.", "Strategic essay"),
        ("َامتحان شروع ہو چکا ہے۔", "Leading isolated combining Fatha (U+064E) at string start.", "Parser robustness edge test")
    ]
    for text, purpose, prov in mark_items:
        fixtures.append(make_fixture(next_id(), cat, f"Mark: {text[:20]}...", text, purpose, provenance=prov))

    # ==========================================
    # 4. Mixed Scripts and Numbers (30 fixtures)
    # ==========================================
    cat = "mixed_scripts_and_numbers"
    mixed_items = [
        ("قیمت ۱۲۳ روپے مقرر کی گئی ہے۔", "Urdu/Eastern Arabic-Indic digits (U+06F1..U+06F3).", "Standard trade price tag", "MIXED"),
        ("سالانہ پیداوار ۴۵۶۷۸ ٹن ریکارڈ ہوئی۔", "Extended Eastern Arabic-Indic digits (U+06F4..U+06F8).", "Economic survey report", "MIXED"),
        ("کل تعداد ۹۰۸ افراد پر مشتمل ہے۔", "Eastern Arabic-Indic digits with zero (U+06F9, U+06F0, U+06F8).", "Census statistics", "MIXED"),
        ("مجموعی رقم: ١٢٣٤٥ دينار فقط", "Arabic-Indic digits (U+0661..U+0665) contrast test.", "International banking voucher", "MIXED"),
        ("آرڈر نمبر 1234567 وصول پا گیا ہے۔", "ASCII Western digits (0-9) inside Urdu sentence.", "E-commerce delivery notification", "MIXED"),
        ("قیمت $150 یا تقریبا ۴۲۰۰۰ روپے ہے", "Western currency symbol ($) and digits with Urdu digits and text.", "Financial forex commentary", "MIXED"),
        ("ڈالر کی قیمت 278.50 روپے پر مستحکم رہی۔", "Decimal point in Western number within Urdu context.", "Business currency report", "MIXED"),
        ("پٹرول کی قیمت میں 4.5 فیصد اضافہ کیا گیا۔", "Percentage in Western notation embedded in Urdu text.", "Daily news financial bulletin", "MIXED"),
        ("تاریخ پیدائش: ۱۴ اگست ۱۹۴۷ء", "Urdu Sanah / Year marker sign U+0601 with Eastern digits.", "National identity document", "MIXED"),
        ("کانفرنس 25 December 2026 کو منعقد ہوگی۔", "Latin English date embedded in Urdu sentence.", "Conference invitation letter", "MIXED"),
        ("رابطہ نمبر: +92-300-1234567 پر کال کریں۔", "International telephone number with plus and hyphens.", "Customer support contact card", "MIXED"),
        ("ہماری ویب سائٹ https://urdu-editor.org ملاحظہ کریں۔", "Full HTTPS URL embedded inside Urdu paragraph.", "Digital product marketing", "MIXED"),
        ("برائے رابطہ ای میل کریں: support@editor.com شکریہ۔", "Email address embedded in Urdu sentence.", "Support contact correspondence", "MIXED"),
        ("فائل کا نام report_final_v2.pdf محفوظ کریں۔", "Latin file name with underscores and dot extension.", "Technical user manual", "MIXED"),
        ("اس سافٹ ویئر کا نام WYSIWYG Compose Editor ہے۔", "English uppercase product name in Urdu sentence.", "Software release note", "MIXED"),
        ("کیا آپ کو اردو لکھنا پسند ہے؟", "Urdu question mark (U+061F) at sentence end.", "Social survey questionnaire", "RTL"),
        ("احمد، علی، عثمان اور بلال آئے تھے۔", "Urdu comma (U+060C) in list of names.", "Classroom attendance record", "RTL"),
        ("محنت کرو؛ کیونکہ محنت ہی کامیابی کا راستہ ہے۔", "Urdu semicolon (U+061B) separating clauses.", "Grammar and style guide", "RTL"),
        ("یہاں بات ختم ہوتی ہے۔", "Urdu full stop / Khatma (U+06D4).", "Editorial conclusion", "RTL"),
        ("«اقوال زریں کا نیا انتخاب»", "Guillemets quote marks (U+00AB / U+00BB) around Urdu book title.", "Publishing catalogue", "RTL"),
        ("”اردو ہماری قومی زبان ہے“", "Curved quotation marks (U+201D / U+201C) in Urdu quotation.", "Constitutional essay", "RTL"),
        ("دیوان غالب (نسخہ حمیدیہ) ایک نایاب نسخہ ہے۔", "Parentheses () enclosing Urdu title qualification.", "Literary bibliographical entry", "RTL"),
        ("شرح کلام اقبال [جلد دوم، صفحہ ۴۵]", "Square brackets [] enclosing volume and page citation.", "Academic reference footnote", "RTL"),
        ("ریاضی کا بنیادی اصول: 2 + 2 = 4 ہوتا ہے۔", "Mathematical equation with Western digits in Urdu context.", "Elementary math primer", "MIXED"),
        ("مساوات ۲ + ۳ = ۵ پر غور فرمائیں۔", "Mathematical equation with Eastern Urdu digits.", "Mathematics textbook in Urdu", "MIXED"),
        ("الجبرائی کلیہ: (a + b)² = a² + 2ab + b² ہے", "Algebraic identity formula embedded in Urdu sentence.", "Secondary school algebra textbook", "MIXED"),
        ("درجہ حرارت 35°C سے تجاوز کر گیا۔", "Degree Celsius symbol with Western numerals.", "Weather forecast bulletin", "MIXED"),
        ("اس کا وزن 50 kg اور قد 175 cm ہے۔", "SI units (kg, cm) following Western numerals in Urdu.", "Medical checkup summary", "MIXED"),
        ("مجموعی رقبہ 1,250 sq km پر محیط ہے۔", "Formatted thousand separator with Latin abbreviations.", "Geographic survey monograph", "MIXED"),
        ("متبادل راستہ A-101 یا موٹروے M-2 اختیار کریں۔", "Alphanumeric route designations embedded in Urdu traffic advice.", "Highways authority advisory", "MIXED")
    ]
    for text, purpose, prov, bidi in mixed_items:
        fixtures.append(make_fixture(next_id(), cat, f"Mixed: {text[:20]}...", text, purpose, provenance=prov, bidi=bidi))

    # ==========================================
    # 5. Bidi Controls (26 fixtures)
    # ==========================================
    cat = "bidi_controls"
    bidi_items = [
        ("فائل \u2066document_report.docx\u2069 محفوظ ہو گئی۔", "Left-to-Right Isolate (LRI U+2066) protecting Latin filename.", "LRI isolation trace", "MIXED"),
        ("کتاب \u2067دیوانِ غالب\u2069 کا مطالعہ کریں", "Right-to-Left Isolate (RLI U+2067) isolating Urdu title.", "RLI isolation trace", "RTL"),
        ("پیغام کا متن: \u2068Hello World!\u2069 موصول ہوا", "First Strong Isolate (FSI U+2068) isolating dynamic message.", "FSI isolation trace", "MIXED"),
        ("مسافر \u2066flight-PK-786\u2069 پر سوار ہوئے۔", "LRI ... PDI sequence isolating flight code.", "Airline announcement log", "MIXED"),
        ("جملہ نمبر ۱: \u2066Step 1: Open Settings\u2069 پر کلک کریں", "Nested isolate: LRI command sequence inside RTL instruction.", "Software instructions guide", "MIXED"),
        ("تصدیقی کوڈ \u2066AUTH-9942\u2069 درج فرمائیں۔", "LRI isolating security PIN alphanumeric code.", "Security authentication SMS", "MIXED"),
        ("Urdu text inside English: \u2067پاکستان زندہ باد\u2069 was chanted.", "RLI isolating Urdu slogan within English sentence.", "English diplomatic news report", "MIXED"),
        ("URL: \u2066https://example.com/test?param=1&lang=ur\u2069 دیکھیں", "LRI isolating complex URL with query parameters.", "Web development documentation", "MIXED"),
        ("قیمت \u200E$100\u200E فی کس مقرر کی گئی ہے۔", "Left-to-Right Mark (LRM U+200E) anchoring currency expression.", "Pricing schedule", "MIXED"),
        ("فون نمبر \u200E+92-300-1112233\u200E درج کریں۔", "LRM U+200E anchoring telephone plus sign.", "Directory listing", "MIXED"),
        ("پہلا باب\u200F: پس منظر اور تمہید", "Right-to-Left Mark (RLM U+200F) stabilizing punctuation direction.", "Academic book layout", "RTL"),
        ("سال ۲۰۱۰ء\u200F-۲۰۲۰ء کی دہائی", "RLM U+200F stabilizing hyphen between Urdu year markers.", "Decade economic overview", "RTL"),
        ("رقم: 500\u061C- روپے کی کٹوتی", "Arabic Letter Mark (ALM U+061C) forcing RTL placement of negative sign.", "Accounting journal ledger", "MIXED"),
        ("درجہ حرارت 10\u061C- ڈگری سینٹی گریڈ ریکارڈ ہوا۔", "ALM U+061C stabilizing minus sign before temperature.", "Meteorological observation record", "MIXED"),
        ("متن \u2066Outer LTR \u2067Inner RTL\u2069 End Outer\u2069 کا ٹیسٹ", "Nested isolates: LRI containing RLI containing Urdu text.", "Bidi conformance test suite", "MIXED"),
        ("متن \u2067بیرونی \u2066Inner LTR\u2069 اندرونی\u2069 کا ٹیسٹ", "Nested isolates: RLI containing LRI containing English text.", "Bidi conformance test suite", "MIXED"),
        ("ٹیسٹ: \u2066غیر بند شدہ آئسولیٹ متن", "Unterminated isolate (LRI without PDI) to verify parser stability.", "Bidi parser fuzz testing", "MIXED"),
        ("غلط پی ڈی آئی ٹیسٹ \u2069 بلا وجہ کا پی ڈی آئی", "Premature/stray Pop Directional Isolate (PDI) robustness test.", "Bidi parser fuzz testing", "RTL"),
        ("متن \u202ALeft-to-Right Embedding\u202C کا اختتام", "LRE (U+202A) legacy embedding control preservation test.", "Legacy document import validation", "MIXED"),
        ("متن \u202BRight-to-Left Embedding\u202C کا اختتام", "RLE (U+202B) legacy embedding control preservation test.", "Legacy document import validation", "RTL"),
        ("متن \u202DLeft-to-Right Override\u202C فورس", "LRO (U+202D) directional override test.", "Security and display test", "MIXED"),
        ("متن \u202ERight-to-Left Override\u202C فورس", "RLO (U+202E) directional override test.", "Security and display test", "RTL"),
        ("سبق ۱: \u2066Chapter A\u2069 اور سبق ۲: \u2066Chapter B\u2069", "Multiple sequential LRI...PDI pairs in a single line.", "Curriculum syllabus table", "MIXED"),
        ("شروع \u2066A\u2066B\u2066C\u2069\u2069\u2069 اختتام", "Triply nested LRI isolates terminated sequentially.", "Stack overflow and depth test", "MIXED"),
        ("اردو \u200E(English)\u200E اردو متن جاری ہے۔", "LRM surrounding Latin parentheses to prevent glyph inversion.", "Translation handbook", "MIXED"),
        ("کوڈ: \u2066val text: String = \"اردو\"\u2069", "LRI isolating programming language assignment with Urdu string literal.", "Compose Multiplatform code example", "MIXED")
    ]
    for text, purpose, prov, bidi in bidi_items:
        fixtures.append(make_fixture(next_id(), cat, f"Bidi: {text[:20]}...", text, purpose, provenance=prov, bidi=bidi))

    # ==========================================
    # 6. Nastaliq Geometry (28 fixtures)
    # ==========================================
    cat = "nastaliq_geometry"
    geom_items = [
        ("خط نستعلیق کی دلکشی اور نزاکت", "Classic multi-tier sloping ligature: 'نستعلیق'.", "Calligraphic exposition"),
        ("علمی اور سائنسی تحقیق کا سفر", "Deep cascading ligature: 'تحقیق'.", "Academic editorial"),
        ("اسناد کی باقاعدہ تصدیق کروائیں", "Complex ligature cluster: 'تصدیق'.", "Administrative compliance bulletin"),
        ("جملے کی نحوی تشکیل اور ساخت", "High ascender with cascading teeth: 'تشکیل'.", "Linguistics journal"),
        ("تخلیق کار کا شاہکار فن پارہ", "Descending cascading ligature: 'تخلیق'.", "Art criticism monograph"),
        ("خوشخطی حسن بیان کا نام ہے۔", "Distinctive Nastaliq combination: 'خوشخطی'.", "Calligraphy manual preface"),
        ("واقعات کا ایک طویل سلسلہ شروع ہوا", "Repetitive Seen-Lam-Seen cascade: 'سلسلہ'.", "Narrative history"),
        ("کسی قسم کی جھجک محسوس نہ کریں", "Aspirated cluster with repeated Jim heads: 'جھجک'.", "Motivational essay"),
        ("شہر لکھنؤ کی تاریخی عمارتیں", "Classic high-stack ligature: 'لکھنؤ'.", "Cultural heritage survey"),
        ("ایک باوقار اور پرکشش شخصیت", "Three-tier cascading stack: 'شخصیت'.", "Biographical tribute"),
        ("عدالت نے حتمی اور منصفانہ فیصلہ سنا دیا", "Steep downward slant: 'فیصلہ'.", "Legal reporting"),
        ("خلوص اور محبت تمام فاصلے مٹا دیتی ہے", "Jim-Heh-Bay-Teh cascade: 'محبت'.", "Literary prose anthology"),
        ("روشن اور تابناک مستقبل کی نوید", "Meem-Seen-Teh-Qaf-Bay-Lam cascade: 'مستقبل'.", "Youth leadership editorial"),
        ("مجلس شوریٰ کا ہنگامی اجلاس طلب کیا گیا", "Meem-Jeem-Lam-Seen cascade: 'مجلس'.", "Parliamentary report"),
        ("مریض کی طبیعت اب سنبھل رہی ہے", "Tteh-Bay-Yeh-Ain-Teh cascade: 'طبیعت'.", "Clinical healthcare log"),
        ("تلخ حقیقت سے آنکھیں چرانا ممکن نہیں", "Heh-Qaf-Yeh-Qaf-Teh cascade: 'حقیقت'.", "Philosophical dialogue"),
        ("مصلحت پسندی کبھی سچ کا نعم البدل نہیں ہو سکتی", "Meem-Sad-Lam-Heh-Teh cascade: 'مصلحت'.", "Moral discourse"),
        ("دشمن کو عبرتناک شکست کا سامنا کرنا پڑا", "Sheen-Kaf-Seen-Teh cascade: 'شکست'.", "Military history narrative"),
        ("صبح و شام تسبیح و استغفار کا ورد", "Teh-Seen-Bay-Yeh-Heh cascade: 'تسبیح'.", "Spiritual devotion manual"),
        ("ملکی معیشت کی بحالی کے لیے ٹھوس اقدامات", "Meem-Ain-Yeh-Sheen-Teh cascade: 'معیشت'.", "Economic planning whitepaper"),
        ("مشکلات کے باوجود ہمت نہ ہارو", "Meem-Sheen-Kaf-Lam cascade: 'مشکلات'.", "Psychological counselling guide"),
        ("خوبصورت باغات اور شاداب وادیاں", "Khah-Waw-Bay-Sad-Waw-Reh-Teh cascade: 'خوبصورت'.", "Geography travel guide"),
        ("حکمت اور دانائی کی باتیں سنو", "Heh-Kaf-Meem-Teh cascade: 'حکمت'.", "Traditional wisdom folklore"),
        ("قسمت کا لکھا کوئی نہیں مٹا سکتا", "Qaf-Seen-Meem-Teh cascade: 'قسمت'.", "Classical poetry commentary"),
        ("نصیحت پر کان دھرنا دانشمندی کی علامت ہے", "Noon-Sad-Yeh-Heh-Teh cascade: 'نصیحت'.", "Ethics primer"),
        ("مسلسل مشق سے مہارت حاصل ہوتی ہے", "Meem-Seen-Lam-Seen-Lam cascade: 'مسلسل'.", "Sports training syllabus"),
        ("حقیقی خوشی دوسروں کے کام آنے میں ہے", "Heh-Qaf-Yeh-Qaf-Yeh cascade: 'حقیقی'.", "Civic charity brochure"),
        ("باہمی ہم آہنگی اور باہمی تعاون", "Heh-Meem-Alif-Heh-Noon-Gaf-Yeh cascade: 'ہم آہنگی'.", "Social harmony declaration")
    ]
    for text, purpose, prov in geom_items:
        fixtures.append(make_fixture(next_id(), cat, f"Nastaliq: {text[:20]}...", text, purpose, provenance=prov))

    # ==========================================
    # 7. Spacing and Justification (26 fixtures)
    # ==========================================
    cat = "spacing_and_justification"
    space_items = [
        ("پہلا لفظ اور دوسرا لفظ", "Standard ASCII spaces (U+0020) between Urdu words.", "Basic spacing sanity test", False),
        ("جناب\u00A0صدر نے اجلاس سے خطاب فرمایا۔", "Non-Breaking Space (NBSP U+00A0) preventing line split after title.", "Parliamentary hansard report", False),
        ("ملاحظہ فرمائیں صفحہ\u00A0۲۵ پر تفصیلی رپورٹ۔", "NBSP U+00A0 keeping page label and number on same line.", "Editorial index", False),
        ("لاہور\u00A0، پنجاب ، پاکستان", "NBSP U+00A0 before punctuation mark.", "Postal address registry", False),
        ("ڈاکٹر\u00A0محمد\u00A0اقبال کے افکار", "Multiple NBSP U+00A0 tokens in an honored person's name.", "Academic biography", False),
        ("شیریں\u202Fکلام اور خوش\u202Fگفتار", "Narrow Non-Breaking Space (NNBSP U+202F) between compound adjective stems.", "Morphological boundary acceptance", False),
        ("نو\u202Fطرز مرصع اردو کی اولین داستانوں میں سے ہے", "NNBSP U+202F in compound book title.", "Urdu literary history", False),
        ("پہلا کالم\tدوسرا کالم\tتیسرا کالم", "Horizontal tab (U+0009) alignment in RTL text run.", "RTL tabular layout test", False),
        ("سطر اول کا متن یہاں ختم ہوا۔\nسطر دوم کا متن یہاں سے شروع ہوتا ہے۔", "Hard newline (U+000A) between two complete Urdu sentences.", "Paragraph break test", False),
        ("سطر اول\r\nسطر دوم ونڈوز فارمیٹ", "Windows CRLF (U+000D U+000A) line break preservation.", "Operating system line ending test", False),
        ("تاریخ کا درست مطالعہ کریں", "Baseline un-stretched text verifying NO Tatweel (U+0640) injected.", "Canonical text fidelity", False),
        ("تـــــاریـــــخ کا مطالعہ", "Explicit Tatweel / Kashida (U+0640) in storage (to be rejected or verified as explicit).", "Kashida handling audit", False),
        ("ســـــلام مسنون و بعد", "Letter opening with decorative Tatweel (U+0640).", "Epistolary archival text", False),
        ("یہ ایک نہایت مختصر آخری سطر ہے۔", "Short last line in paragraph to verify avoidance of awkward stretching.", "Typesetting justification policy", False),
        ("پہلا پیراگراف اختتام پذیر ہوا۔   تین خالی جگہیں", "Multiple consecutive ASCII spaces preservation.", "Whitespace collapse policy check", False),
        ("   سطر کے آغاز میں خالی جگہ", "Leading whitespace in RTL paragraph.", "Indentation preservation check", False),
        ("سطر کے اختتام پر خالی جگہ   ", "Trailing whitespace in RTL paragraph.", "Trailing whitespace line break check", False),
        ("کلمہ طیبہ   اور   کلمہ شہادت", "Multiple inter-word spacing preservation.", "Spaced religious formulas", False),
        ("عنوان ۱:\u00A0مقدمہ\tصفحہ نمبر:\u00A0۱", "Combined NBSP and Tab within table of contents entry.", "Table of contents specification", False),
        ("جناب\u202Fوزیر\u202Fاعظم کی زیر صدارت کابینہ اجلاس", "NNBSP U+202F within compound honorific title.", "Official government press release", False),
        ("قسط نمبر\u00A0(۵) شائع ہو چکی ہے۔", "NBSP U+00A0 preceding parenthesized installment number.", "Literary magazine publication", False),
        ("حصہ اول\u00A0—\u00A0تعارفی پس منظر", "NBSP U+00A0 flanking em-dash in section heading.", "Formal book typography", False),
        ("سال\u00A0۲۰۲۶ء کے اہداف کا تعین", "NBSP U+00A0 preventing separation of Urdu year sign.", "Corporate strategic plan", False),
        ("درجہ حرارت\u00A0۴۰° تک پہنچ گیا۔", "NBSP U+00A0 between metric quantity and number.", "Weather report", False),
        ("نمبر شمار\tتفصیل\tقیمت (روپے)", "Table header row with RTL tab delimiters.", "Invoicing specification", False),
        ("آخری جملہ یہاں تمام ہوا۔", "Short terminal sentence testing flush-right ragged-left finish.", "Typography finishing test", False)
    ]
    for text, purpose, prov, req_n in space_items:
        fixtures.append(make_fixture(next_id(), cat, f"Spacing: {text[:20]}...", text, purpose, provenance=prov, req_nastaliq=req_n))

    # ==========================================
    # 8. Structure (28 fixtures)
    # ==========================================
    cat = "structure"
    struct_items = [
        ("باب اول: اردو زبان کی تاریخ اور ارتقا", "Heading level 1 (H1) document title.", "Literary history monograph"),
        ("فصل دوم: دکن میں اردو شاعری کے ابتدائی نقوش", "Heading level 2 (H2) section divider.", "Literary history monograph"),
        ("۱.۱ دہلی کا دبستان شاعری اور اس کے نمایاں شعرا", "Heading level 3 (H3) with Eastern decimal numbering.", "Literary history monograph"),
        ("۱.۱.۱ میر تقی میر اور خدائے سخن کی حیثیت", "Heading level 4 (H4) subsection title.", "Literary history monograph"),
        ("• اردو رسم الخط کی بنیادی خصوصیات\n• خط نستعلیق کے اصول و قواعد\n• جدید کمپیوٹر فونٹس کے تقاضے", "Bulleted list in RTL document order.", "Textbook pedagogy syllabus"),
        ("۱۔ طالب علم وقت کا پابند ہو۔\n۲۔ باقاعدگی سے ہوم ورک مکمل کرے۔\n۳۔ اساتذہ اور ساتھیوں کا احترام کرے۔", "Eastern Urdu numbered list (۱۔ ۲۔ ۳۔).", "School code of conduct"),
        ("الف۔ تحریری امتحان (کل نمبر: ۱۰۰)\nب۔ زبانی امتحان (کل نمبر: ۵۰)\nج۔ عملی کارکردگی (کل نمبر: ۵۰)", "Urdu Abjad numbered list (الف۔ ب۔ ج۔).", "Examination board syllabus"),
        ("ہزاروں خواہشیں ایسی کہ ہر خواہش پہ دم نکلے\nبہت نکلے مرے ارمان لیکن پھر بھی کم نکلے", "Classical sher (two hemistichs) by Mirza Ghalib.", "Diwan-e-Ghalib (Public Domain)"),
        ("دل ناداں تجھے ہوا کیا ہے\nآخر اس درد کی دوا کیا ہے", "Classical sher (two hemistichs) by Mirza Ghalib.", "Diwan-e-Ghalib (Public Domain)"),
        ("نہ سنو گر برا کہے کوئی\nنہ کہو گر برا کرے کوئی", "Didactic couplet by Mirza Ghalib.", "Diwan-e-Ghalib (Public Domain)"),
        ("ستاروں سے آگے جہاں اور بھی ہیں\nابھی عشق کے امتحان اور بھی ہیں", "Inspirational couplet by Allama Muhammad Iqbal.", "Bal-e-Jibril (Public Domain)"),
        ("خودی کو کر بلند اتنا کہ ہر تقدیر سے پہلے\nخدا بندے سے خود پوچھے بتا تیری رضا کیا ہے", "Famous philosophical couplet by Allama Iqbal.", "Bal-e-Jibril (Public Domain)"),
        ("تیری دعا سے قضا تو بدل نہیں سکتی\nمگر ہے اس سے یہ ممکن کہ تو بدل جائے", "Reflective spiritual couplet by Allama Iqbal.", "Zarb-e-Kaleem (Public Domain)"),
        ("گلوں میں رنگ بھرے باد نو بہار چلے\nچلے بھی آؤ کہ گلشن کا کاروبار چلے", "Modern classical couplet by Faiz Ahmad Faiz.", "Dast-e-Saba (Public Domain)"),
        ("مجھ سے پہلی سی محبت مرے محبوب نہ مانگ\nمیں نے سمجھا تھا کہ تو ہے تو درخشاں ہے حیات", "Famous opening couplet by Faiz Ahmad Faiz.", "Naqsh-e-Faryadi (Public Domain)"),
        ("«علم بڑی دولت ہے اور جہالت سب سے بڑی محرومی ہے۔ انسان کو چاہیے کہ ہر وقت کچھ نیا سیکھنے کی جستجو میں رہے۔»", "Blockquote paragraph with traditional quotation markers.", "Educational essay anthology"),
        ("پہلا پیراگراف: تعارف اور پس منظر۔\n\nدوسرا پیراگراف: دلائل اور تجزیہ۔\n\nتیسرا پیراگراف: حاصل کلام اور نتائج۔", "Multi-paragraph essay structure separated by double newlines.", "Academic essay format"),
        ("حاشیہ نمبر [۱]: دیکھیے تاریخ فرشتہ، جلد اول، صفحہ ۱۲۰۔", "Footnote reference and text layout.", "Academic citation handbook"),
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
    for text, purpose, prov in struct_items:
        fixtures.append(make_fixture(next_id(), cat, f"Structure: {text[:20]}...", text, purpose, provenance=prov))

    # ==========================================
    # 9. Input & Mutation Edge Cases (28 fixtures)
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
    for f in fixtures:
        counts[f["category"]] = counts.get(f["category"], 0) + 1
    for cat, count in counts.items():
        print(f"  {cat}: {count}")

    output_path = os.path.join(os.path.dirname(__file__), "fixtures.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(fixtures, f, ensure_ascii=False, indent=2)
    print(f"Written {len(fixtures)} fixtures to {output_path}")

if __name__ == "__main__":
    main()
