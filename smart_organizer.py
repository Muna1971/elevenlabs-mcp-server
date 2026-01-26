#!/usr/bin/env python3
"""
سكريبت تنظيم ذكي لملفات سطح المكتب
يحلل محتوى الملفات ويصنفها حسب السياق

Smart Desktop File Organizer
Analyzes file content and classifies them by context
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


# ╔════════════════════════════════════════════════════════════════════════════╗
# ║                         قسم التخصيص - CUSTOMIZATION                        ║
# ║          قم بتعديل هذا القسم حسب احتياجاتك الشخصية                          ║
# ║          Modify this section according to your personal needs              ║
# ╚════════════════════════════════════════════════════════════════════════════╝

# ==================== التصنيفات الرئيسية ====================
# أضف أو عدل التصنيفات الرئيسية حسب احتياجاتك
# Add or modify main categories according to your needs

# [التصنيف الأول] - غيّر الاسم والكلمات المفتاحية
# [CATEGORY 1] - Change name and keywords
CATEGORY_1_NAME = "[اسم التصنيف الأول]"  # مثال: "العمل" أو "Work"
CATEGORY_1_KEYWORDS = {
    "general": [
        # أضف كلمات مفتاحية عامة لهذا التصنيف
        # Add general keywords for this category
        "[كلمة مفتاحية 1]",
        "[كلمة مفتاحية 2]",
        "[keyword 1]",
        "[keyword 2]",
    ],
    # التصنيفات الفرعية - أضف أو احذف حسب الحاجة
    # Subcategories - add or remove as needed
    "[تصنيف فرعي 1]": [
        "[كلمات مفتاحية للتصنيف الفرعي 1]",
    ],
    "[تصنيف فرعي 2]": [
        "[كلمات مفتاحية للتصنيف الفرعي 2]",
    ],
    "[تصنيف فرعي 3]": [
        "[كلمات مفتاحية للتصنيف الفرعي 3]",
    ],
}

# [التصنيف الثاني] - غيّر الاسم والكلمات المفتاحية
# [CATEGORY 2] - Change name and keywords
CATEGORY_2_NAME = "[اسم التصنيف الثاني]"  # مثال: "الدراسة" أو "Study"
CATEGORY_2_KEYWORDS = [
    # أضف كلمات مفتاحية لهذا التصنيف
    # Add keywords for this category
    "[كلمة مفتاحية 1]",
    "[كلمة مفتاحية 2]",
    "[keyword 1]",
    "[keyword 2]",
]

# [التصنيف الثالث] - غيّر الاسم والكلمات المفتاحية
# [CATEGORY 3] - Change name and keywords
CATEGORY_3_NAME = "[اسم التصنيف الثالث]"  # مثال: "مشاريع شخصية" أو "Personal Projects"
CATEGORY_3_KEYWORDS = [
    # أضف كلمات مفتاحية لهذا التصنيف
    # Add keywords for this category
    "[كلمة مفتاحية 1]",
    "[كلمة مفتاحية 2]",
    "[keyword 1]",
    "[keyword 2]",
]

# قائمة التصنيفات الفرعية للتصنيف الأول (إذا أردت تقسيمات فرعية)
# List of subcategories for Category 1 (if you want subdivisions)
CATEGORY_1_SUBCATEGORIES = [
    "[تصنيف فرعي 1]",
    "[تصنيف فرعي 2]",
    "[تصنيف فرعي 3]",
]

# مجلد التصنيفات الفرعية الرئيسي (اختياري)
# Main subcategory folder name (optional)
SUBCATEGORY_FOLDER_NAME = "[اسم مجلد التصنيفات الفرعية]"  # مثال: "الأقسام" أو "Departments"

# ==================== مسارات مخصصة (اختياري) ====================
# Custom paths (optional)
# أضف مسارات OneDrive أو مسارات خاصة إذا كان سطح المكتب في مكان غير تقليدي
# Add OneDrive paths or custom paths if your desktop is in a non-standard location

CUSTOM_DESKTOP_PATHS = [
    # أضف مسارات مخصصة هنا إذا لزم الأمر
    # Add custom paths here if needed
    # مثال / Example:
    # Path.home() / "OneDrive - اسم المؤسسة" / "Desktop",
    # Path.home() / "OneDrive - Organization Name" / "Desktop",
]

# ╔════════════════════════════════════════════════════════════════════════════╗
# ║                    نهاية قسم التخصيص - END OF CUSTOMIZATION                ║
# ╚════════════════════════════════════════════════════════════════════════════╝


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
    category_2_score = count_keyword_matches(full_text, CATEGORY_2_KEYWORDS)
    category_3_score = count_keyword_matches(full_text, CATEGORY_3_KEYWORDS)

    # حساب نقاط التصنيف الأول
    category_1_score = count_keyword_matches(full_text, CATEGORY_1_KEYWORDS["general"])

    # تحديد التصنيف الرئيسي
    scores = {
        CATEGORY_2_NAME: category_2_score,
        CATEGORY_3_NAME: category_3_score,
        CATEGORY_1_NAME: category_1_score
    }

    max_category = max(scores, key=scores.get)
    max_score = scores[max_category]

    # إذا لم يكن هناك تطابق واضح
    if max_score == 0:
        return ("غير مصنف", None, None)

    # تصنيف فرعي للتصنيف الأول
    if max_category == CATEGORY_1_NAME:
        subcategory = None
        sub_subcategory = None

        # تحديد التصنيف الفرعي
        sub_scores = {}
        for sub in CATEGORY_1_SUBCATEGORIES:
            if sub in CATEGORY_1_KEYWORDS:
                sub_scores[sub] = count_keyword_matches(full_text, CATEGORY_1_KEYWORDS[sub])

        if sub_scores:
            max_sub = max(sub_scores, key=sub_scores.get)
            if sub_scores[max_sub] > 0:
                subcategory = SUBCATEGORY_FOLDER_NAME
                sub_subcategory = max_sub

        return (CATEGORY_1_NAME, subcategory, sub_subcategory)

    return (max_category, None, None)


# ==================== دوال التنظيم ====================

def get_desktop_path() -> Path:
    """الحصول على مسار سطح المكتب"""
    home = Path.home()

    # التحقق من المسارات المخصصة أولاً
    for path in CUSTOM_DESKTOP_PATHS:
        if path.exists():
            return path

    if os.name == 'nt':
        # Windows - التحقق من OneDrive
        onedrive_paths = [
            home / "OneDrive" / "Desktop",
            home / "OneDrive" / "سطح المكتب",
        ]
        for path in onedrive_paths:
            if path.exists():
                return path

        # المسار العادي
        desktop = home / "Desktop"
        if not desktop.exists():
            desktop = home / "سطح المكتب"
    else:
        # Linux / Mac
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


def organize_desktop(dry_run: bool = True):
    """تنظيم ملفات سطح المكتب"""
    desktop = get_desktop_path()

    if not desktop.exists():
        print(f"[ERROR] لم يتم العثور على سطح المكتب: {desktop}")
        return

    print(f"[FOLDER] مسار سطح المكتب: {desktop}")
    print("=" * 60)

    # التحقق من المكتبات المتاحة
    print("\n[LIBS] المكتبات المتاحة:")
    print(f"   * PyPDF2 (لقراءة PDF): {'[OK]' if HAS_PYPDF2 else '[X] غير مثبتة'}")
    print(f"   * python-docx (لقراءة Word): {'[OK]' if HAS_DOCX else '[X] غير مثبتة'}")

    if not HAS_PYPDF2 or not HAS_DOCX:
        print("\n[TIP] لتثبيت المكتبات المفقودة:")
        print("   pip install PyPDF2 python-docx")

    print("\n" + "=" * 60)

    results = {
        CATEGORY_1_NAME: [],
        CATEGORY_2_NAME: [],
        CATEGORY_3_NAME: [],
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
        if main_cat == CATEGORY_1_NAME and sub_cat:
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

        print(f"\n[FILE] {item.name}")
        print(f"   -> {path_display}")

        if main_cat in results:
            results[main_cat].append(item.name)
        else:
            results["غير مصنف"].append(item.name)

        if not dry_run:
            move_file(item, dest_path)

    # ملخص
    print("\n" + "=" * 60)
    print("[SUMMARY] ملخص التصنيف:")
    print("-" * 40)
    for category, files in results.items():
        if files:
            print(f"\n[FOLDER] {category}: {len(files)} ملف")
            for f in files[:3]:
                print(f"   * {f}")
            if len(files) > 3:
                print(f"   ... و {len(files) - 3} ملفات أخرى")

    print("\n" + "=" * 60)
    if dry_run:
        print("[PREVIEW] هذا عرض تجريبي - لم يتم نقل أي ملفات")
        print("\n[TIP] لتنفيذ التنظيم فعليا:")
        print("   python smart_organizer.py --run")
    else:
        total = sum(len(f) for f in results.values())
        print(f"[DONE] تم تنظيم {total} ملف بنجاح!")


def print_current_config():
    """عرض التكوين الحالي"""
    print("""
[CONFIG] التصنيفات الحالية:
   |
   +-- """ + CATEGORY_1_NAME + """
   |   |
   |   +-- """ + SUBCATEGORY_FOLDER_NAME + """
   |       |""")
    for sub in CATEGORY_1_SUBCATEGORIES:
        print(f"   |       +-- {sub}")
    print(f"""   |
   +-- {CATEGORY_2_NAME}
   |
   +-- {CATEGORY_3_NAME}
    """)


# ==================== التشغيل ====================

if __name__ == "__main__":
    import sys

    print("\n" + "=" * 60)
    print("[SMART ORGANIZER] اداة التنظيم الذكي لسطح المكتب")
    print("=" * 60)

    print_current_config()

    if len(sys.argv) > 1 and sys.argv[1] == "--run":
        print("[RUNNING] جاري تنظيم الملفات...\n")
        organize_desktop(dry_run=False)
    else:
        print("[PREVIEW] عرض تجريبي:\n")
        organize_desktop(dry_run=True)
