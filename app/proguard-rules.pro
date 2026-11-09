# Il ContentProvider degli sticker viene interrogato da WhatsApp per nome di classe.
-keep class com.whatik.whatsapp.StickerContentProvider { *; }

# I nomi delle classi restano quelli veri: l'app è a codice aperto e gli errori mostrati a chi la
# usa (e le tracce dei crash) devono restare leggibili. R8 continua a togliere il codice non usato.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization: libreria e pack sono salvati in JSON. Le regole arrivano già dentro la
# libreria (dalla 1.5); queste sono le stesse, ripetute per sicurezza: senza i serializer i dati
# salvati non si leggerebbero più.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
