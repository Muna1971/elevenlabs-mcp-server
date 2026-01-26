#!/usr/bin/env python3
"""
سكريبت تنظيم ملفات سطح المكتب
يقوم بترتيب الملفات في مجلدات حسب نوعها
"""

import os
import shutil
from pathlib import Path

# تصنيف الملفات حسب الامتدادات
FILE_CATEGORIES = {
    "الصور": [".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".webp", ".ico", ".tiff"],
    "المستندات": [".pdf", ".doc", ".docx", ".txt", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".rtf"],
    "الفيديوهات": [".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm"],
    "الصوتيات": [".mp3", ".wav", ".flac", ".aac", ".ogg", ".wma", ".m4a"],
    "الأرشيفات": [".zip", ".rar", ".7z", ".tar", ".gz", ".bz2"],
    "البرامج": [".exe", ".msi", ".dmg", ".deb", ".rpm", ".app"],
    "الأكواد": [".py", ".js", ".html", ".css", ".java", ".cpp", ".c", ".h", ".json", ".xml"],
}


def get_desktop_path():
    """الحصول على مسار سطح المكتب حسب نظام التشغيل"""
    home = Path.home()

    # Windows
    if os.name == 'nt':
        desktop = home / "Desktop"
        if not desktop.exists():
            desktop = home / "سطح المكتب"
    # macOS / Linux
    else:
        desktop = home / "Desktop"
        if not desktop.exists():
            # للأنظمة العربية
            desktop = home / "سطح المكتب"

    return desktop


def get_category(file_extension):
    """تحديد تصنيف الملف حسب امتداده"""
    ext = file_extension.lower()
    for category, extensions in FILE_CATEGORIES.items():
        if ext in extensions:
            return category
    return "أخرى"


def organize_desktop(dry_run=False):
    """تنظيم ملفات سطح المكتب"""
    desktop = get_desktop_path()

    if not desktop.exists():
        print(f"❌ لم يتم العثور على سطح المكتب: {desktop}")
        return

    print(f"📂 مسار سطح المكتب: {desktop}")
    print("-" * 50)

    moved_count = 0

    for item in desktop.iterdir():
        # تجاهل المجلدات والملفات المخفية
        if item.is_dir() or item.name.startswith('.'):
            continue

        # تحديد التصنيف
        category = get_category(item.suffix)

        # إنشاء مجلد التصنيف
        category_folder = desktop / category

        if dry_run:
            print(f"📄 {item.name} ← {category}")
        else:
            category_folder.mkdir(exist_ok=True)

            # نقل الملف
            destination = category_folder / item.name

            # التعامل مع الملفات المكررة
            if destination.exists():
                base = item.stem
                ext = item.suffix
                counter = 1
                while destination.exists():
                    destination = category_folder / f"{base}_{counter}{ext}"
                    counter += 1

            shutil.move(str(item), str(destination))
            print(f"✅ {item.name} ← {category}")
            moved_count += 1

    print("-" * 50)
    if dry_run:
        print("🔍 هذا عرض تجريبي - لم يتم نقل أي ملفات")
    else:
        print(f"✨ تم نقل {moved_count} ملف بنجاح!")


if __name__ == "__main__":
    import sys

    print("=" * 50)
    print("🗂️  أداة تنظيم سطح المكتب")
    print("=" * 50)

    # التشغيل التجريبي أولاً
    if len(sys.argv) > 1 and sys.argv[1] == "--run":
        print("\n⚡ جاري تنظيم الملفات...\n")
        organize_desktop(dry_run=False)
    else:
        print("\n🔍 عرض تجريبي (لن يتم نقل الملفات):\n")
        organize_desktop(dry_run=True)
        print("\n💡 لتنفيذ التنظيم فعلياً، شغّل:")
        print("   python organize_desktop.py --run")
