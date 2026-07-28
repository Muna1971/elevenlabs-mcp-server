# ذاكرة المشروع — Project Memory

> هذا الملف يُقرأ تلقائياً من Claude Code في بداية كل جلسة. يحتوي على المهارات
> والأنماط المستخلصة من العمل السابق ليتم إعادة استخدامها عند إنشاء سكربتات
> لسطح المكتب أو أي جهاز آخر.
>
> This file is read automatically by Claude Code at the start of every session.
> It captures reusable skills/patterns for building desktop (or any device) scripts.

---

## المهارات المستخلصة من سكربت التنظيم الذكي (smart_organizer.py)
## Skills learned from the Smart Desktop Organizer

### 1. الاستيراد الآمن للمكتبات الاختيارية — Graceful optional imports
لا تفشل إذا كانت المكتبة غير مثبتة؛ استخدم علماً منطقياً (flag) وتراجع بأمان.
```python
try:
    import PyPDF2
    HAS_PYPDF2 = True
except ImportError:
    HAS_PYPDF2 = False
```
أعلِم المستخدم بما هو مفقود واعرض أمر التثبيت: `pip install PyPDF2 python-docx`.

### 2. وضع المعاينة الآمن أولاً — Dry-run by default
أي سكربت يعدّل/ينقل/يحذف ملفات يجب أن **يعاين افتراضياً** ولا ينفّذ إلا بعلَم صريح.
```python
python smart_organizer.py          # معاينة فقط (dry_run=True)
python smart_organizer.py --run    # التنفيذ الفعلي (dry_run=False)
```
القاعدة: **لا تلمس ملفات المستخدم دون معاينة ومطالبة صريحة بالتنفيذ.**

### 3. اكتشاف المسارات عبر الأنظمة — Cross-platform path detection
تعامل مع Windows/OneDrive/Linux/Mac ومع أسماء المجلدات العربية.
```python
if os.name == 'nt':               # Windows
    home / "OneDrive" / "Desktop"
    home / "OneDrive" / "سطح المكتب"
else:                             # Linux / Mac
    home / "Desktop"
    home / "سطح المكتب"
```
وفّر دائماً قائمة `CUSTOM_DESKTOP_PATHS` لمسارات غير تقليدية.

### 4. النقل الآمن مع منع الكتابة فوق الملفات — Safe move, no overwrite
عند وجود ملف بنفس الاسم، أضف عدّاداً بدل الكتابة فوقه، واحفظ الامتداد.
```python
base, ext, counter = file.stem, file.suffix, 1
while destination.exists():
    destination = folder / f"{base}_{counter}{ext}"
    counter += 1
```
استخدم `folder.mkdir(parents=True, exist_ok=True)` قبل النقل. **انقل — لا تحذف.**

### 5. قراءة محتوى الملفات حسب النوع — Content extraction by type
دالة موحّدة تختار القارئ حسب الامتداد، مع حدود للأداء:
- PDF: أول 5 صفحات فقط.
- Word: أول 50 فقرة.
- نصوص: أول 10000 حرف، مع `encoding='utf-8', errors='ignore'`.
- غير المدعوم: استخدم اسم الملف فقط للتصنيف.
لُفّ كل قارئ في `try/except` يُرجع `""` عند الفشل (لا يتوقف السكربت أبداً).

### 6. التصنيف بالنقاط عبر الكلمات المفتاحية — Keyword scoring classification
ادمج (اسم الملف + المحتوى)، احسب تطابقات الكلمات لكل تصنيف، واختر الأعلى نقاطاً.
عند انعدام التطابق (score == 0) ضع الملف في مجلد **"غير مصنف"** بدل التخمين.

### 7. قسم تخصيص معزول ومزدوج اللغة — Isolated bilingual config block
ضع كل الإعدادات (أسماء التصنيفات، الكلمات المفتاحية، المسارات) في **قسم واحد
واضح في أعلى الملف** بين فواصل بصرية، بتعليقات عربية/إنجليزية وقيم نائبة `[...]`
وأمثلة جاهزة. هذا يجعل غير المبرمج قادراً على التخصيص دون لمس منطق الكود.

### 8. مخرجات واضحة وملخّص — Clear output with summary
استخدم وسوماً نصية `[OK] [X] [FILE] [FOLDER] [SUMMARY] [DONE]` بدل الاعتماد على
الإيموجي (أكثر توافقاً مع طرفية Windows)، واختم دائماً بملخّص: كم ملف في كل تصنيف.

---

## قواعد عامة لأي سكربت أجهزة — General rules for any device script
1. **الأمان أولاً**: معاينة افتراضية، لا حذف، لا كتابة فوق الملفات دون عدّاد.
2. **لا يتوقف أبداً**: غلّف كل عملية I/O بـ `try/except` وتراجع بأمان.
3. **عبر الأنظمة**: افحص `os.name` وادعم مسارات Windows/Mac/Linux + OneDrive.
4. **دعم العربية**: `encoding='utf-8'` دائماً، وادعم أسماء المجلدات العربية.
5. **CLI بسيط**: علَم `--run` للتنفيذ، والافتراضي معاينة، مع رسالة تلميح واضحة.
6. **مزدوج اللغة**: تعليقات ومخرجات عربية/إنجليزية عند استهداف مستخدم عربي.
7. **قابل للتخصيص**: افصل الإعدادات عن المنطق في قسم واضح بأعلى الملف.

---

## سياق المستودع — Repo context
- المستودع أصلاً: **ElevenLabs MCP Server** (خادم MCP لتحويل النص إلى كلام).
- أُضيف لاحقاً مشروع منفصل: `smart_organizer.py` + `README_smart_organizer.txt`.
- المبرمجة/المالكة: منى الكندي.
