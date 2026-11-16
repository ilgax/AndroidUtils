# Nothing in the watch app or :protocol is looked up by name (no Class.forName, no reflection, no serialization
# libraries); manifest classes (Application, activities, InstallResultReceiver, RemoteTileService) are kept by AGP's
# generated rules, androidx.wear.tiles/protolayout ship their own consumer rules for the protobuf-lite messages, and
# Keystore/Mac/MessageDigest/KeyAgreement are JCA provider names (strings), not app classes. So no -keep is needed.

# Names stay readable: obfuscating saved only ~11 KB of a ~211 KB APK. Line numbers are kept for stack traces.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
