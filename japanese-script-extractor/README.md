# 日文台本提取器

Android Kotlin app for extracting selectable text from PDFs into UTF-8 TXT files, fully offline.

## Features
- Select a folder and recursively scan for PDF files.
- Select one or multiple PDFs through Android's document picker.
- Extract text with PDFBox-Android.
- Save UTF-8 TXT files in a `台本TXT` folder without modifying source PDFs.
- Optional page separators.
- Scanned image-only PDFs are reported as having no extractable text; OCR is not included in this first version.

## Build
From this directory, run `gradle assembleDebug`.
