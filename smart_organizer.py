#!/usr/bin/env python3
"""
سكريبت تنظيم ذكي لملفات سطح المكتب
يحلل محتوى الملفات ويصنفها حسب السياق
"""

import os
import shutil
import re
from pathlib import Path
from typing import Optional, Tuple

# محاولة استيراد المكتبات لقراءة الملفات
try:
    import PyPDF2
    HAS_PYPDF2 = True
except ImportError:
    HAS_PYPDF2 = False

try:
    from docx import Document
    HAS_DOCX = True
except ImportError:
    HAS_DOCX = False


# ==================== التصنيفات والكلمات المفتاحية ====================

# كلمات مفتاحية للعمل (التعليم المستمر)
WORK_KEYWORDS = {
    "general": [
        "تعليم مستمر", "تدريب", "تطوير مهني", "ورشة عمل", "برنامج تدريبي",
        "continuing education", "professional development", "training",
        "workshop", "course", "curriculum", "منهج", "خطة دراسية"
    ],
    "اللغة العربية": [
        "اللغة العربية", "النحو", "الصرف", "البلاغة", "arabic language",
        "قواعد اللغة", "الإملاء", "التعبير", "الأدب العربي"
    ],
    "اللغة الإنجليزية": [
        "english", "اللغة الإنجليزية", "grammar", "vocabulary",
        "IELTS", "TOEFL", "english course", "انجليزي"
    ],
    "اللغة الفرنسية": [
        "français", "french", "اللغة الفرنسية", "فرنسي", "grammaire"
    ],
    "اللغة الأردية": [
        "urdu", "اردو", "الأردية", "اللغة الأردية", "پاکستان", "اردو زبان",
        "نستعليق", "اردو ادب", "pakistan", "urdu language"
    ],
    "اللغة الروسية": [
        "russian", "русский", "الروسية", "اللغة الروسية", "روسي",
        "россия", "русский язык", "russia", "russian language"
    ],
    "مشروعات متفرقة": [
        "مشروع", "project", "خطة", "plan", "تقرير", "report"
    ]
}

# كلمات مفتاحية للدراسة (رسالة الماجستير - اللسانيات والخطاب)
STUDY_KEYWORDS = [
    "لسانيات", "linguistics", "خطاب", "discourse", "تحليل الخطاب",
    "discourse analysis", "سيميائية", "semiotics", "براغماتية", "pragmatics",
    "صوتيات", "phonetics", "phonology", "morphology", "syntax",
    "semantics", "دلالة", "تداولية", "نظرية اللغة", "language theory",
    "ماجستير", "master", "thesis", "رسالة", "بحث علمي", "research",
    "منهجية البحث", "methodology", "أطروحة", "dissertation",
    "sociolinguistics", "علم اللغة الاجتماعي", "psycholinguistics",
    "نص", "text", "textual", "نصي", "تأويل", "hermeneutics"
]

# كلمات مفتاحية لـ AIGO Center (الذكاء الاصطناعي والبزنس)
AIGO_KEYWORDS = [
    "ذكاء اصطناعي", "artificial intelligence", "AI", "machine learning",
    "deep learning", "تعلم آلي", "تعلم عميق", "neural network",
    "شبكات عصبية", "python", "data science", "علم البيانات",
    "chatbot", "GPT", "ChatGPT", "Claude", "prompt", "بروفت",
    "automation", "أتمتة", "digital marketing", "تسويق رقمي",
    "business", "بزنس", "startup", "ريادة", "entrepreneurship",
    "freelance", "عمل حر", "online course", "دورة أونلاين",
    "AIGO", "consulting", "استشارات", "coaching", "تدريب ذكاء"
]


# ==================== دوال قراءة الملفات ====================

def read_pdf(file_path: Path) -> str:
    """قراءة محتوى ملف PDF"""
    if not HAS_PYPDF2:
        return ""
    try:
        with open(file_path, 'rb') as f:
            reader = PyPDF2.PdfReader(f)
            text = ""
            # قراءة أول 5 صفحات فقط للسرعة
            for i, page in enumerate(reader.pages[:5]):
                text += page.extract_text() or ""
            return text
    except Exception:
        return ""


def read_docx(file_path: Path) -> str:
    """قراءة محتوى ملف Word"""
    if not HAS_DOCX:
        return ""
    try:
        doc = Document(file_path)
        return "\n".join([para.text for para in doc.paragraphs[:50]])
    except Exception:
        return ""


def read_text(file_path: Path) -> str:
    """قراءة محتوى ملف نصي"""
    try:
        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
            return f.read(10000)  # أول 10000 حرف
    except Exception:
        return ""


def get_file_content(file_path: Path) -> str:
    """الحصول على محتوى الملف حسب نوعه"""
    suffix = file_path.suffix.lower()

    if suffix == '.pdf':
        return read_pdf(file_path)
    elif suffix in ['.docx', '.doc']:
        return read_docx(file_path)
    elif suffix in ['.txt', '.md', '.rtf']:
        return read_text(file_path)
    else:
        # للملفات الأخرى، نستخدم اسم الملف فقط
        return file_path.stem


def analyze_filename(filename: str) -> str:
    """تحليل اسم الملف للحصول على كلمات مفيدة"""
    # إزالة الامتداد والرموز
    name = Path(filename).stem
    name = re.sub(r'[_\-\.]', ' ', name)
    return name


# ==================== دوال التصنيف ====================

def count_keyword_matches(text: str, keywords: list) -> int:
    """حساب عدد الكلمات المفتاحية الموجودة في النص"""
    text_lower = text.lower()
    count = 0
    for keyword in keywords:
        if keyword.lower() in text_lower:
            count += 1
    return count


def classify_file(file_path: Path) -> Tuple[str, Optional[str], Optional[str]]:
    """
    تصنيف الملف وإرجاع (التصنيف الرئيسي، التصنيف الفرعي، التصنيف الفرعي الثاني)
    """
    # الحصول على المحتوى
    content = get_file_content(file_path)
    filename_text = analyze_filename(file_path.name)
    full_text = f"{filename_text} {content}"

    # حساب التطابقات لكل تصنيف
    study_score = count_keyword_matches(full_text, STUDY_KEYWORDS)
    aigo_score = count_keyword_matches(full_text, AIGO_KEYWORDS)

    # حساب نقاط العمل
    work_score = count_keyword_matches(full_text, WORK_KEYWORDS["general"])

    # تحديد التصنيف الرئيسي
    scores = {
        "رسالة الماجستير": study_score,
        "AIGO Center": aigo_score,
        "العمل": work_score
    }

    max_category = max(scores, key=scores.get)
    max_score = scores[max_category]

    # إذا لم يكن هناك تطابق واضح
    if max_score == 0:
        return ("غير مصنف", None, None)

    # تصنيف فرعي للعمل
    if max_category == "العمل":
        subcategory = None
        sub_subcategory = None

        # تحديد قسم اللغة
        lang_scores = {}
        for lang in ["اللغة العربية", "اللغة الإنجليزية", "اللغة الفرنسية", "اللغة الأردية", "اللغة الروسية"]:
            lang_scores[lang] = count_keyword_matches(full_text, WORK_KEYWORDS[lang])

        max_lang = max(lang_scores, key=lang_scores.get)
        if lang_scores[max_lang] > 0:
            subcategory = "قسم اللغات"
            sub_subcategory = max_lang
        else:
            # مشروعات متفرقة
            if count_keyword_matches(full_text, WORK_KEYWORDS["مشروعات متفرقة"]) > 0:
                subcategory = "مشروعات متفرقة"

        return ("العمل", subcategory, sub_subcategory)

    return (max_category, None, None)


# ==================== دوال التنظيم ====================

def get_desktop_path() -> Path:
    """الحصول على مسار سطح المكتب"""
    home = Path.home()

    if os.name == 'nt':
        # التحقق من OneDrive أولاً (Windows)
        onedrive_paths = [
            home / "OneDrive - Mohamed Bin Zayed University for Humanities" / "Desktop",
            home / "OneDrive - Mohamed Bin Zayed University for Humanities" / "سطح المكتب",
            home / "OneDrive" / "Desktop",
            home / "OneDrive" / "سطح المكتب",
            home / "OneDrive - Personal" / "Desktop",
        ]
        for path in onedrive_paths:
            if path.exists():
                return path

        # المسار العادي
        desktop = home / "Desktop"
        if not desktop.exists():
            desktop = home / "سطح المكتب"
    else:
        desktop = home / "Desktop"
        if not desktop.exists():
            desktop = home / "سطح المكتب"

    return desktop


def move_file(file_path: Path, destination_folder: Path) -> Path:
    """نقل الملف مع التعامل مع التكرار"""
    destination_folder.mkdir(parents=True, exist_ok=True)
    destination = destination_folder / file_path.name

    if destination.exists():
        base = file_path.stem
        ext = file_path.suffix
        counter = 1
        while destination.exists():
            destination = destination_folder / f"{base}_{counter}{ext}"
            counter += 1

    shutil.move(str(file_path), str(destination))
    return destination


def organize_desktop(dry_run: bool = True, custom_path: str = None):
    """تنظيم ملفات المجلد المحدد"""
    if custom_path:
        desktop = Path(custom_path)
    else:
        desktop = get_desktop_path()

    if not desktop.exists():
        print(f"❌ لم يتم العثور على المجلد: {desktop}")
        return

    print(f"📂 مسار المجلد: {desktop}")
    print("=" * 60)

    # التحقق من المكتبات المتاحة
    print("\n📚 المكتبات المتاحة:")
    print(f"   • PyPDF2 (لقراءة PDF): {'✅' if HAS_PYPDF2 else '❌ غير مثبتة'}")
    print(f"   • python-docx (لقراءة Word): {'✅' if HAS_DOCX else '❌ غير مثبتة'}")

    if not HAS_PYPDF2 or not HAS_DOCX:
        print("\n💡 لتثبيت المكتبات المفقودة:")
        print("   pip install PyPDF2 python-docx")

    print("\n" + "=" * 60)

    results = {
        "العمل": [],
        "رسالة الماجستير": [],
        "AIGO Center": [],
        "غير مصنف": []
    }

    # فحص الملفات
    supported_extensions = ['.pdf', '.doc', '.docx', '.txt', '.md', '.ppt', '.pptx', '.xls', '.xlsx']

    for item in desktop.iterdir():
        if item.is_dir() or item.name.startswith('.'):
            continue

        if item.suffix.lower() not in supported_extensions:
            continue

        # تصنيف الملف
        main_cat, sub_cat, sub_sub_cat = classify_file(item)

        # بناء المسار
        if main_cat == "العمل" and sub_cat:
            if sub_sub_cat:
                dest_path = desktop / main_cat / sub_cat / sub_sub_cat
            else:
                dest_path = desktop / main_cat / sub_cat
        else:
            dest_path = desktop / main_cat

        # عرض النتيجة
        path_display = f"{main_cat}"
        if sub_cat:
            path_display += f" / {sub_cat}"
        if sub_sub_cat:
            path_display += f" / {sub_sub_cat}"

        print(f"\n📄 {item.name}")
        print(f"   ➜ {path_display}")

        results[main_cat].append(item.name)

        if not dry_run:
            move_file(item, dest_path)

    # ملخص
    print("\n" + "=" * 60)
    print("📊 ملخص التصنيف:")
    print("-" * 40)
    for category, files in results.items():
        if files:
            print(f"\n📁 {category}: {len(files)} ملف")
            for f in files[:3]:
                print(f"   • {f}")
            if len(files) > 3:
                print(f"   ... و {len(files) - 3} ملفات أخرى")

    print("\n" + "=" * 60)
    if dry_run:
        print("🔍 هذا عرض تجريبي - لم يتم نقل أي ملفات")
        print("\n💡 لتنفيذ التنظيم فعلياً:")
        print("   python smart_organizer.py --run")
    else:
        total = sum(len(f) for f in results.values())
        print(f"✨ تم تنظيم {total} ملف بنجاح!")


# ==================== التشغيل ====================

if __name__ == "__main__":
    import sys
    import argparse

    parser = argparse.ArgumentParser(description='أداة التنظيم الذكي للملفات')
    parser.add_argument('--run', action='store_true', help='تنفيذ التنظيم فعلياً')
    parser.add_argument('--path', type=str, help='مسار المجلد المراد تنظيمه')
    args = parser.parse_args()

    print("\n" + "=" * 60)
    print("🧠 أداة التنظيم الذكي للملفات")
    print("=" * 60)
    print("""
📁 التصنيفات:
   ├── العمل (التعليم المستمر)
   │   ├── قسم اللغات
   │   │   ├── اللغة العربية
   │   │   ├── اللغة الإنجليزية
   │   │   ├── اللغة الفرنسية
   │   │   ├── اللغة الأردية
   │   │   └── اللغة الروسية
   │   └── مشروعات متفرقة
   │
   ├── رسالة الماجستير (اللسانيات والخطاب)
   │
   └── AIGO Center (الذكاء الاصطناعي والبزنس)
    """)

    if args.run:
        print("⚡ جاري تنظيم الملفات...\n")
        organize_desktop(dry_run=False, custom_path=args.path)
    else:
        print("🔍 عرض تجريبي:\n")
        organize_desktop(dry_run=True, custom_path=args.path)
        print("\n💡 لتنفيذ التنظيم فعلياً:")
        if args.path:
            print(f'   python smart_organizer.py --path "{args.path}" --run')
        else:
            print("   python smart_organizer.py --run")
