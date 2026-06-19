package ai.muna.assistant

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a minimal, valid .docx (Word) file from a title + plain text. */
object DocxBuilder {

    fun build(file: File, title: String, content: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entry(zip, "[Content_Types].xml", CONTENT_TYPES)
            entry(zip, "_rels/.rels", RELS)
            entry(zip, "word/document.xml", documentXml(title, content))
        }
    }

    private fun entry(zip: ZipOutputStream, name: String, data: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun documentXml(title: String, content: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>")
        if (title.isNotBlank()) {
            sb.append("<w:p><w:pPr><w:bidi/><w:jc w:val=\"both\"/></w:pPr>")
            sb.append("<w:r><w:rPr><w:rtl/><w:b/><w:sz w:val=\"32\"/></w:rPr>")
            sb.append("<w:t xml:space=\"preserve\">").append(esc(title)).append("</w:t></w:r></w:p>")
        }
        for (line in content.split("\n")) {
            sb.append("<w:p><w:pPr><w:bidi/><w:jc w:val=\"both\"/></w:pPr>")
            sb.append("<w:r><w:rPr><w:rtl/><w:sz w:val=\"24\"/></w:rPr>")
            sb.append("<w:t xml:space=\"preserve\">").append(esc(line)).append("</w:t></w:r></w:p>")
        }
        sb.append("<w:sectPr><w:bidi/></w:sectPr></w:body></w:document>")
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"

    private const val RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
}
