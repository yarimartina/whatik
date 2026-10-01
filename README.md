# Whatik

App Android che prende gli sticker di TikTok, li converte nel formato richiesto da WhatsApp e li aggiunge a WhatsApp come pack di sticker. Si può copiare uno sticker alla volta oppure selezionarne tanti in massa.

## Come funziona

1. **Porta gli sticker in Whatik** in uno di questi modi:
   - **Condividi** → in TikTok (o in galleria / file manager) scegli *Condividi* e poi *Salva in Whatik*. Funziona con una o più immagini alla volta.
   - **Cerca gli sticker TikTok sul telefono** → l'app cerca nella galleria (MediaStore) le immagini salvate da TikTok: quelle nelle cartelle `TikTok` sono già selezionate, ma si può passare a *Tutte le immagini*.
   - **Dalla galleria / Da file / Da una cartella** → selezione manuale con il photo picker, il selettore di documenti o un'intera cartella (ricorsiva).
2. **Seleziona** gli sticker nella griglia: un tocco seleziona il singolo sticker, *Seleziona tutto* li prende tutti. Si possono anche eliminare dalla libreria.
3. **Copia su WhatsApp**: scegli nome e autore del pack, poi *Converti*. Whatik:
   - converte ogni immagine in **WebP 512×512** (≤ 100 KB se statico, ≤ 500 KB se animato);
   - conserva le **animazioni** di GIF e WebP animati, rispettando il limite di 10 secondi e riducendo qualità/fotogrammi finché il file rientra nei limiti;
   - genera l'**icona del pack** (96×96 PNG);
   - distribuisce gli sticker nei pack rispettando le regole di WhatsApp: **da 3 a 30 sticker per pack** e pack **solo statici o solo animati** (si può anche scegliere di convertire gli animati in statici);
   - può aggiungere gli sticker a un **pack esistente** creato in precedenza, invece di crearne uno nuovo.
4. Il pack viene aperto in **WhatsApp** (consumer o Business) tramite l'intent ufficiale `com.whatsapp.intent.action.ENABLE_STICKER_PACK`. Nella schermata *Pack* si vedono tutti i pack creati, il loro stato su WhatsApp e si possono riaprire o eliminare.

> **Nota su TikTok.** TikTok tiene gli sticker delle chat nella propria memoria privata, che le altre app non possono leggere. Per questo Whatik lavora sugli sticker che TikTok permette di salvare o condividere (immagini, GIF, screenshot), oppure su quelli già presenti in galleria.

## Requisiti

- Android 8.0 (API 26) o superiore.
- WhatsApp o WhatsApp Business installato per l'aggiunta dei pack.
- Permesso di lettura delle immagini solo per la funzione *Cerca sticker sul telefono* (su Android 14 è possibile concedere l'accesso solo ad alcune foto).

## Compilare

```bash
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # test unitari (decoder GIF, container WebP, pianificazione pack)
```

Serve JDK 17+ e l'Android SDK (compileSdk 35). Il workflow GitHub Actions in `.github/workflows/android.yml` esegue i test, compila l'APK di debug e lo pubblica come artefatto; su un tag `v*` lo allega alla release.

## Struttura

| Percorso | Contenuto |
| --- | --- |
| `image/GifDecoder.kt` | Decoder GIF in Kotlin puro (LZW, trasparenza, disposal, interlacciamento) |
| `image/WebPContainer.kt` | Lettura/scrittura del contenitore WebP: estrae i fotogrammi dei WebP animati e ricompone animazioni (VP8X/ANIM/ANMF), cosa che Android non sa fare da solo |
| `image/FrameProducer.kt` | Produce i fotogrammi composti per GIF, WebP animati e immagini statiche |
| `image/StickerConverter.kt` | Ridimensiona a 512×512, codifica WebP e rispetta i limiti di peso e durata |
| `data/StickerLibrary.kt` | Libreria degli sticker importati (deduplica per SHA-256, importazione da URI e cartelle) |
| `data/MediaScanner.kt` | Ricerca nel MediaStore con riconoscimento delle cartelle TikTok |
| `data/PackPlanner.kt` | Suddivisione della selezione in pack (3–30, statici/animati, pack esistenti) |
| `data/PackStore.kt` / `data/StickerExporter.kt` | Pack su disco e pipeline di conversione |
| `whatsapp/StickerContentProvider.kt` | ContentProvider nel formato dell'API ufficiale WhatsApp |
| `whatsapp/WhatsAppBridge.kt` | Intent di aggiunta e verifica dei pack già presenti su WhatsApp |
| `ui/` | Interfaccia Jetpack Compose (libreria, ricerca, pack, dialoghi di esportazione) |

Le immagini dei pack vengono servite a WhatsApp dal ContentProvider `com.whatik.stickercontentprovider` (permesso di lettura `com.whatsapp.sticker.READ`), come richiesto dalla documentazione ufficiale [WhatsApp/stickers](https://github.com/WhatsApp/stickers).
