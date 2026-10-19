package editor

/** Self-authored short strings; code points are exported verbatim in each receipt. */
internal data class LanguageFixture(val id:String,val text:String,val notes:String)
internal val multilingualFixtures=listOf(
    LanguageFixture("ur","مُحَمَّد کی کتاب، قیمت ۱۲۳ روپے۔","Urdu joining, marks, punctuation and extended Arabic-Indic digits"),
    LanguageFixture("ar","السَّلَامُ عليكم، لا إله — ١٢٣ ABC","Arabic joining, lam-alef, marks, Arabic-Indic digits, Latin"),
    LanguageFixture("fa","کتاب‌های فارسی؛ قیمت ۱۲۳ تومان","Persian kaf/yeh, ZWNJ U+200C and digits"),
    LanguageFixture("he","שָׁלוֹם עוֹלָם — English 12.5!","Hebrew niqqud, punctuation and mixed Latin/numbers"),
    LanguageFixture("en","Café, cafe\u0301 — naïve A\u030A; résumé.","Precomposed/decomposed accents; no normalization"),
    LanguageFixture("hi","नमस्ते! क्षमा करें, किताब पढ़ें।","Devanagari conjuncts and dependent vowel signs"),
    LanguageFixture("bn","বাংলা বই পড়ি; প্রশ্ন ও উত্তর।","Bengali conjuncts and dependent vowel signs"),
    LanguageFixture("th","ภาษาไทยมีน้ำใจและหนังสือดี","Thai combining marks, no ordinary word spaces"),
    LanguageFixture("zh","你好，世界！今天阅读中文。","Han and punctuation; synthetic composition separate"),
    LanguageFixture("ja","日本語の本を読む。かなと漢字。","Kana/kanji; native candidate qualification separate"),
    LanguageFixture("ko","안녕하세요. 한글 책을 읽어요.","Hangul syllables; native composition separate"),
    LanguageFixture("unicode","𐐀 𝄞 👩🏽‍💻 👨‍👩‍👧‍👦 🇵🇰 ✈️ a\u0301\u0323 \u2067اردو 12\u2069","Supplementary, skin/ZWJ, flag, VS16, marks, RLI/PDI")
)
internal val mixedFixture="اردو (English 12.50) — العربية [2026-10-19] — שָׁלוֹם; https://example.org/a?q=1, writer@example.org \u2066ABC 123\u2069"
