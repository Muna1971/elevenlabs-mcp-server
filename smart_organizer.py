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
        # التعليم المستمر
        "تعليم مستمر", "التعليم المستمر", "مركز التعليم", "CEC", "LCEC", "LCEC-ADV",
        "تدريب", "تطوير مهني", "ورشة عمل", "برنامج تدريبي", "دورة", "دورات",
        "continuing education", "professional development", "training", "workshop",
        # الشهادات والحضور
        "شهادة", "شهادات", "certificate", "appreciation", "حضور", "غياب", "attendance",
        # الخطط والتقارير
        "خطة", "خطط", "تشغيلية", "plan", "تقرير", "report", "إنجازات", "إنجاز",
        # الاجتماعات والمبادرات
        "اجتماع", "محضر", "مبادرة", "مبادرات", "meeting",
        # الجامعة
        "MBZUH", "جامعة", "university", "أكاديمي",
        # أخرى
        "نموذج", "استبانة", "مرشحين", "وزارة", "فعالية", "فعاليات"
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
    "ماجستير", "رسالتي", "رسالة الماجستير", "thesis", "بحث علمي", "research",
    "منهجية البحث", "methodology", "أطروحة", "dissertation",
    "sociolinguistics", "علم اللغة الاجتماعي", "psycholinguistics",
    "نص", "text", "textual", "نصي", "تأويل", "hermeneutics",
    "الفصل الأول", "الفصل الثاني", "الفصل الثالث"
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
    "AIGO", "consulting", "استشارات", "coaching", "تدريب ذكاء",
    "المهارات الشخصية", "الذكاء الاصطناعى"
]

# امتدادات الملفات
IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp', '.ico', '.tiff']
VIDEO_EXTENSIONS = ['.mp4', '.mkv', '.avi', '.mov', '.wmv', '.flv', '.webm']
AUDIO_EXTENSIONS = ['.mp3', '.wav', '.flac', '.aac', '.ogg', '.wma', '.m4a']
ARCHIVE_EXTENSIONS = ['.zip', '.rar', '.7z', '.tar', '.gz']
DOCUMENT_EXTENSIONS = ['.pdf', '.doc', '.docx', '.txt', '.md', '.rtf', '.ppt', '.pptx', '.xls', '.xlsx']
SHORTCUT_EXTENSIONS = ['.lnk', '.url']
HTML_EXTENSIONS = ['.html', '.htm']


# ==================== دوال قراءة الملفات ====================

def read_pdf(file_path: Path) -> str:
    """قراءة محتوى ملف PDF"""
    if not HAS_PYPDF2:
        return ""
    try:
        with open(file_path, 'rb') as f:
            reader = PyPDF2.PdfReader(f)
            text = ""
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
            return f.read(10000)
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
        return file_path.stem


def analyze_filename(filename: str) -> str:
    """تحليل اسم الملف للحصول على كلمات مفيدة"""
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


def classify_by_extension(file_path: Path) -> Optional[str]:
    """تصنيف الملف حسب الامتداد"""
    suffix = file_path.suffix.lower()
    name_lower = file_path.name.lower()

    # تجاهل الاختصارات
    if suffix in SHORTCUT_EXTENSIONS:
        return "اختصارات"

    # الصور
    if suffix in IMAGE_EXTENSIONS:
        if "whatsapp" in name_lower:
            return "صور/واتساب"
        elif "screenshot" in name_lower:
            return "صور/لقطات شاشة"
        elif name_lower.startswith("img_"):
            return "صور/كاميرا"
        else:
            return "صور/أخرى"

    # الفيديوهات
    if suffix in VIDEO_EXTENSIONS:
        if "whatsapp" in name_lower:
            return "فيديوهات/واتساب"
        else:
            return "فيديوهات/أخرى"

    # الصوتيات
    if suffix in AUDIO_EXTENSIONS:
        return "صوتيات"

    # الأرشيفات
    if suffix in ARCHIVE_EXTENSIONS:
        if "camscanner" in name_lower:
            return "العمل/CamScanner"
        return "أرشيفات"

    # HTML
    if suffix in HTML_EXTENSIONS:
        return "ملفات HTML"

    return None


def classify_file(file_path: Path) -> Tuple[str, Optional[str], Optional[str]]:
    """تصنيف الملف وإرجاع (التصنيف الرئيسي، التصنيف الفرعي، التصنيف الفرعي الثاني)"""

    # أولاً: التصنيف حسب الامتداد
    ext_category = classify_by_extension(file_path)
    if ext_category:
        parts = ext_category.split("/")
        if len(parts) == 2:
            return (parts[0], parts[1], None)
        return (parts[0], None, None)

    # ثانياً: التصنيف حسب المحتوى (للمستندات)
    suffix = file_path.suffix.lower()
    if suffix not in DOCUMENT_EXTENSIONS:
        return ("غير مصنف", None, None)

    # الحصول على المحتوى
    content = get_file_content(file_path)
    filename_text = analyze_filename(file_path.name)
    full_text = f"{filename_text} {content}"

    # حساب التطابقات
    study_score = count_keyword_matches(full_text, STUDY_KEYWORDS)
    aigo_score = count_keyword_matches(full_text, AIGO_KEYWORDS)
    work_score = count_keyword_matches(full_text, WORK_KEYWORDS["general"])

    # تحديد التصنيف الرئيسي
    scores = {
        "رسالة الماجستير": study_score,
        "AIGO Center": aigo_score,
        "العمل": work_score
    }

    max_category = max(scores, key=scores.get)
    max_score = scores[max_category]

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
            if count_keyword_matches(full_text, WORK_KEYWORDS["مشروعات متفرقة"]) > 0:
                subcategory = "مشروعات متفرقة"

        return ("العمل", subcategory, sub_subcategory)

    return (max_category, None, None)


# ==================== دوال التنظيم ====================

def get_desktop_path() -> Path:
    """الحصول على مسار سطح المكتب"""
    home = Path.home()

    if os.name == 'nt':
        # المسار الخاص بـ MBZUH
        mbzuh_desktop = home / "OneDrive - Mohamed Bin Zayed University for Humanities" / "MBZUH" / "OneDrive - Mohamed Bin Zayed University for Humanities" / "سطح المكتب"
        if mbzuh_desktop.exists():
            return mbzuh_desktop

        # OneDrive العادي
        onedrive_paths = [
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

    print("\n📚 المكتبات المتاحة:")
    print(f"   • PyPDF2 (لقراءة PDF): {'✅' if HAS_PYPDF2 else '❌ غير مثبتة'}")
    print(f"   • python-docx (لقراءة Word): {'✅' if HAS_DOCX else '❌ غير مثبتة'}")

    if not HAS_PYPDF2 or not HAS_DOCX:
        print("\n💡 لتثبيت المكتبات المفقودة:")
        print("   pip install PyPDF2 python-docx")

    print("\n" + "=" * 60)

    results = {}
    errors = []

    for item in desktop.iterdir():
        # تجاهل المجلدات والملفات المخفية والملفات المؤقتة
        if item.is_dir() or item.name.startswith('.') or item.name.startswith('~$'):
            continue

        try:
            # تصنيف الملف
            main_cat, sub_cat, sub_sub_cat = classify_file(item)

            # بناء المسار
            if sub_cat:
                if sub_sub_cat:
                    dest_path = desktop / main_cat / sub_cat / sub_sub_cat
                else:
                    dest_path = desktop / main_cat / sub_cat
            else:
                dest_path = desktop / main_cat

            # عرض النتيجة
            path_display = main_cat
            if sub_cat:
                path_display += f" / {sub_cat}"
            if sub_sub_cat:
                path_display += f" / {sub_sub_cat}"

            print(f"\n📄 {item.name}")
            print(f"   ➜ {path_display}")

            # تسجيل النتيجة
            if main_cat not in results:
                results[main_cat] = []
            results[main_cat].append(item.name)

            if not dry_run:
                move_file(item, dest_path)

        except Exception as e:
            errors.append(f"{item.name}: {str(e)}")
            print(f"\n❌ خطأ في {item.name}: {str(e)}")

    # ملخص
    print("\n" + "=" * 60)
    print("📊 ملخص التصنيف:")
    print("-" * 40)

    total = 0
    for category, files in sorted(results.items()):
        if files:
            print(f"\n📁 {category}: {len(files)} ملف")
            for f in files[:3]:
                print(f"   • {f}")
            if len(files) > 3:
                print(f"   ... و {len(files) - 3} ملفات أخرى")
            total += len(files)

    if errors:
        print(f"\n⚠️ أخطاء: {len(errors)}")

    print("\n" + "=" * 60)
    if dry_run:
        print("🔍 هذا عرض تجريبي - لم يتم نقل أي ملفات")
    else:
        print(f"✨ تم تنظيم {total} ملف بنجاح!")


# ==================== التشغيل ====================

if __name__ == "__main__":
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
   │   ├── قسم اللغات (العربية، الإنجليزية، الفرنسية، الأردية، الروسية)
   │   ├── مشروعات متفرقة
   │   └── CamScanner
   │
   ├── رسالة الماجستير (اللسانيات والخطاب)
   │
   ├── AIGO Center (الذكاء الاصطناعي والبزنس)
   │
   ├── صور (واتساب، لقطات شاشة، كاميرا، أخرى)
   │
   ├── فيديوهات (واتساب، أخرى)
   │
   ├── أرشيفات
   │
   └── غير مصنف
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
