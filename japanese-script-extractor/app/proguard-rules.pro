# Preserve metadata used by libraries that inspect annotations or generic signatures.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# PDFBox optionally supports JPEG2000 via an external decoder that this app does not bundle.
# Text extraction does not require this optional image decoder.
-dontwarn com.gemalto.jp2.**
