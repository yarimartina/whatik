# Whatik

App Android che prende gli sticker di TikTok, li converte nel formato richiesto da WhatsApp e li aggiunge a WhatsApp come pack di sticker. Si può copiare uno sticker alla volta oppure selezionarne tanti in massa.

## Come funziona

Gli sticker che crei (Condividi → *Crea sticker*) o salvi mentre scorri i video restano nella raccolta di TikTok, che non offre un pulsante di download e che le altre app non possono leggere. Whatik li tira fuori in questi modi:

1. **Registrazione dello schermo** (il metodo che funziona sempre, anche per gli sticker animati): registra lo schermo di TikTok mentre gli sticker sono visibili (anche più d'uno, per esempio il pannello della raccolta), poi condividi il video con Whatik o scegli *Da registrazione dello schermo*. Whatik analizza la registrazione da sola: individua la parte senza scorrimenti, trova le regioni che si muovono (gli sticker) mentre l'interfaccia resta ferma, calcola per ciascuna il riquadro e la durata del loop (autocorrelazione del movimento) e mostra le anteprime. Con *Crea tutti* ogni sticker viene estratto a 10 fps e convertito in WebP animato, senza inquadrare nulla a mano. L'editor manuale (riquadro trascinabile, pizzico, cursori di tempo) resta disponibile per i casi in cui il rilevamento non basta, per esempio uno sticker sopra un video in riproduzione.
2. **Screenshot**: per uno sticker fermo basta uno screenshot; si importa e si ritaglia con lo stesso editor (azione *Ritaglia* quando è selezionato un solo sticker).
3. **Link**: gli sticker TikTok sono file WebP animati (`.awebp`) sul CDN di TikTok. Con *Da link* (o condividendo un link con Whatik) si incolla l'URL diretto del file oppure di una pagina: Whatik scarica l'immagine o elenca tutte le immagini trovate nella pagina, con i probabili sticker già selezionati.
4. **Galleria**: le immagini già salvate si trovano con *Cerca gli sticker TikTok sul telefono* (cartelle TikTok già selezionate), con il photo picker, da file o da un'intera cartella.

Poi:

- **Seleziona** gli sticker nella griglia: un tocco seleziona il singolo sticker, *Seleziona tutto* li prende tutti.
- **Copia su WhatsApp**: scegli nome e autore del pack, poi *Converti*. Whatik converte ogni immagine in **WebP 512×512** (≤ 100 KB se statico, ≤ 500 KB se animato), conserva le **animazioni** di GIF e WebP animati entro i 10 secondi riducendo qualità e fotogrammi quando serve, genera l'**icona del pack** e distribuisce gli sticker rispettando le regole di WhatsApp: **da 3 a 30 sticker per pack**, pack **solo statici o solo animati** (oppure animati convertiti in statici), con la possibilità di aggiungere a un **pack esistente**.
- Il pack viene aperto in **WhatsApp** (consumer o Business) tramite l'intent ufficiale `com.whatsapp.intent.action.ENABLE_STICKER_PACK`. Nella schermata *Pack* si vedono tutti i pack creati, il loro stato su WhatsApp e si possono riaprire o eliminare.

## Requisiti

- Android 8.0 (API 26) o superiore.
- WhatsApp o WhatsApp Business installato per l'aggiunta dei pack.
- Permesso di lettura delle immagini solo per la funzione *Cerca sticker sul telefono* (su Android 14 è possibile concedere l'accesso solo ad alcune foto); accesso a Internet solo per l'importazione da link.

## Scaricare l'APK

A ogni push GitHub Actions esegue i test, compila l'app e aggiorna la release **latest**. Il link all'ultima build è sempre lo stesso:

**https://github.com/yarimartina/whatik/releases/latest/download/whatik-debug.apk**

Sul telefono basta aprire il file e consentire l'installazione da origini sconosciute. Gli aggiornamenti si installano sopra la versione precedente perché:

- ogni build ha un `versionCode` crescente (il numero della run di Actions);
- tutte le build sono firmate con la stessa chiave `app/keystore/whatik-sideload.jks`, inclusa nel repository. È una chiave pensata solo per il sideload di questo progetto (password nel `build.gradle.kts`): per una pubblicazione sul Play Store andrebbe creata una chiave privata separata, tenuta fuori dal repo.

Un tag `v*` (es. `v1.0.0`) crea inoltre una release con quel numero di versione e l'APK allegato.

## Compilare

```bash
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # test unitari (decoder GIF, container WebP, pianificazione pack)
```

Serve JDK 17+ e l'Android SDK (compileSdk 35). Con `-PbuildNumber=N` si imposta il `versionCode` (la CI usa il numero della run).

## Struttura

| Percorso | Contenuto |
| --- | --- |
| `image/GifDecoder.kt` | Decoder GIF in Kotlin puro (LZW, trasparenza, disposal, interlacciamento) |
| `image/WebPContainer.kt` | Lettura/scrittura del contenitore WebP: estrae i fotogrammi dei WebP animati e ricompone animazioni (VP8X/ANIM/ANMF), cosa che Android non sa fare da solo |
| `image/FrameProducer.kt` | Produce i fotogrammi composti per GIF, WebP animati e immagini statiche |
| `image/StickerConverter.kt` | Ridimensiona a 512×512, codifica WebP e rispetta i limiti di peso e durata |
| `image/VideoFrameProducer.kt` / `image/Crop.kt` | Estrazione dei fotogrammi da una registrazione dello schermo (con cache su disco) e ritaglio quadrato |
| `image/StickerDetector.kt` / `image/VideoAnalyzer.kt` | Rilevamento automatico degli sticker animati in una registrazione (finestra stabile, pixel in movimento, componenti connesse, periodo del loop) |
| `data/UrlImporter.kt` | Importazione da link: file immagine diretto o ricerca delle immagini in una pagina |
| `data/StickerLibrary.kt` | Libreria degli sticker importati (deduplica per SHA-256, importazione da URI e cartelle) |
| `data/MediaScanner.kt` | Ricerca nel MediaStore con riconoscimento delle cartelle TikTok |
| `data/PackPlanner.kt` | Suddivisione della selezione in pack (3–30, statici/animati, pack esistenti) |
| `data/PackStore.kt` / `data/StickerExporter.kt` | Pack su disco e pipeline di conversione |
| `whatsapp/StickerContentProvider.kt` | ContentProvider nel formato dell'API ufficiale WhatsApp |
| `whatsapp/WhatsAppBridge.kt` | Intent di aggiunta e verifica dei pack già presenti su WhatsApp |
| `ui/` | Interfaccia Jetpack Compose (libreria, ricerca, pack, dialoghi di esportazione) |

Le immagini dei pack vengono servite a WhatsApp dal ContentProvider `com.whatik.stickercontentprovider` (permesso di lettura `com.whatsapp.sticker.READ`), come richiesto dalla documentazione ufficiale [WhatsApp/stickers](https://github.com/WhatsApp/stickers).
