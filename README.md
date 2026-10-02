# Whatik

App Android che prende gli sticker di TikTok, li converte nel formato richiesto da WhatsApp e li aggiunge a WhatsApp come pack di sticker. Si può copiare uno sticker alla volta oppure selezionarne tanti in massa.

## Come funziona

Gli sticker che crei (Condividi → *Crea sticker*) o salvi mentre scorri i video restano nella raccolta di TikTok, che non offre un pulsante di download, non li espone nelle API ufficiali e non li rende leggibili alle altre app. Whatik li tira fuori in questi modi, dal più automatico al più manuale:

1. **Cattura automatica (bolla)**: da Whatik avvii una sessione, concedi la cattura dello schermo e si apre TikTok con una bolla di Whatik sopra. Quando uno sticker è visibile tocchi la bolla e poi lo sticker ("punta e cattura"): Whatik registra 3 secondi di schermo a piena risoluzione; se in quel punto c'è movimento ritaglia la regione animata e stima il loop, altrimenti cerca i bordi dello sticker fermo sullo sfondo (colore di fondo stimato attorno al punto, componente connessa) e lo salva come sticker statico. Il pulsante *Tutti quelli in movimento* cattura invece in un colpo tutte le regioni animate sullo schermo. In entrambi i casi il riquadro trovato dal movimento viene allargato ai bordi dello sticker fermo (gli sticker TikTok sono spezzoni di video in cui spesso si muove solo una parte, e il solo movimento li taglierebbe), le file di sticker attaccati vengono separate, e le catture evidentemente sbagliate vengono scartate: regioni tagliate dal bordo dello schermo, strisce troppo allungate, pezzi di un video che si muove tutto intorno. La bolla mostra il totale della sessione, e ogni cattura produce una notifica con anteprima e nomi degli sticker: toccandola si apre Whatik con quegli sticker selezionati. Tieni premuta la bolla per terminare. Richiede il permesso di sovrapposizione e la conferma di cattura dello schermo (su Android 14 e successivi la conferma è richiesta a ogni sessione).
2. **Registrazione dello schermo** (funziona sempre, anche per gli sticker animati): registra lo schermo di TikTok mentre gli sticker sono visibili (anche più d'uno, per esempio il pannello della raccolta), poi condividi il video con Whatik o scegli *Da registrazione dello schermo*. Whatik analizza la registrazione da sola (finestra senza scorrimenti, regioni in movimento, riquadro e durata del loop per autocorrelazione) e con *Crea tutti* converte ogni sticker in WebP animato. L'editor manuale (riquadro trascinabile, pizzico, cursori di tempo) resta disponibile per i casi in cui il rilevamento non basta, per esempio uno sticker sopra un video in riproduzione.
3. **Perché non c'è il "login TikTok"**: le API ufficiali di TikTok non espongono la raccolta sticker, e il sito di TikTok aperto in un browser incorporato viene bloccato dalle sue difese anti-automazione (pagina vuota, accesso rifiutato per "troppi tentativi"). Una prima versione di questa modalità è stata provata e tolta: aggirare quei blocchi non è una strada affidabile e rischia di far bloccare l'account.
4. **Screenshot e ritocchi**: per uno sticker fermo basta anche uno screenshot; si importa e si ritaglia con l'editor. Qualunque sticker in libreria si può correggere tenendolo premuto (o con l'azione *Ritaglia* quando è il solo selezionato): riquadro con maniglie come in un editor di foto (bordi e angoli per ridimensionare, interno per spostare, due dita per ingrandire la vista, anche rettangolare: il risultato viene centrato su 512×512 trasparente), e per gli sticker animati l'intervallo da tenere. Con *Conserva anche l'originale* da uno screenshot con più sticker se ne ricavano quanti se ne vuole.
5. **Link**: con *Da link* (o condividendo un link con Whatik) si incolla l'URL diretto di un file `.awebp`/`.webp`/`.gif` oppure di una pagina: Whatik scarica l'immagine o elenca tutte le immagini trovate nella pagina, con i probabili sticker già selezionati.
6. **Galleria**: le immagini già salvate si trovano con *Cerca gli sticker TikTok sul telefono* (cartelle TikTok già selezionate), con il photo picker, da file o da un'intera cartella.

Poi:

- **Seleziona** gli sticker nella griglia: un tocco seleziona il singolo sticker, *Seleziona tutto* li prende tutti.
- **Copia su WhatsApp**: scegli nome e autore del pack, poi *Converti*. Whatik converte ogni immagine in **WebP 512×512** (≤ 100 KB se statico, ≤ 500 KB se animato), conserva le **animazioni** di GIF e WebP animati entro i 10 secondi riducendo qualità e fotogrammi quando serve, genera l'**icona del pack** e distribuisce gli sticker rispettando le regole di WhatsApp: **da 3 a 30 sticker per pack** e pack **solo statici o solo animati**. Una selezione mista finisce di default in **un solo pack animato**, con gli sticker fermi trasformati in animazioni di due fotogrammi identici (lo stesso trucco di Sticker Maker); in alternativa pack separati per tipo o tutto statico. Si può anche aggiungere a un **pack esistente**.
- Il pack viene aperto in **WhatsApp** (consumer o Business) tramite l'intent ufficiale `com.whatsapp.intent.action.ENABLE_STICKER_PACK`. Nella schermata *Pack* si vedono tutti i pack creati e il loro stato su WhatsApp; *Gestisci* apre il dettaglio del pack: aggiunta di sticker dalla libreria (convertiti al tipo del pack, fino a 30), rimozione degli sticker selezionati, rinomina, eliminazione e **unione con altri pack** dell'app. Nell'unione gli sticker oltre i 30 finiscono in pack nuovi numerati e, se i tipi sono diversi, si sceglie se il risultato è animato (gli sticker fermi diventano due fotogrammi) o tutto statico; i pack uniti si possono eliminare automaticamente. Ogni modifica incrementa `image_data_version`, così WhatsApp ricarica le immagini.

## Requisiti

- Android 8.0 (API 26) o superiore.
- WhatsApp o WhatsApp Business installato per l'aggiunta dei pack.
- Permesso di lettura delle immagini solo per la funzione *Cerca sticker sul telefono* (su Android 14 è possibile concedere l'accesso solo ad alcune foto); accesso a Internet solo per l'importazione da link; sovrapposizione, cattura dello schermo e notifiche solo per la cattura automatica.

## Scaricare l'APK

A ogni push GitHub Actions esegue i test, compila l'app e aggiorna la release **latest**. Il link all'ultima build è sempre lo stesso:

**https://github.com/yarimartina/whatik/releases/latest/download/whatik-debug.apk**

Sul telefono basta aprire il file e consentire l'installazione da origini sconosciute. Gli aggiornamenti si installano sopra la versione precedente perché:

- ogni build ha un `versionCode` crescente nel tempo (minuti dal 1° gennaio 2026, calcolato alla compilazione, anche per le build locali);
- tutte le build sono firmate con la stessa chiave `app/keystore/whatik-sideload.jks`, inclusa nel repository. È una chiave pensata solo per il sideload di questo progetto (password nel `build.gradle.kts`): per una pubblicazione sul Play Store andrebbe creata una chiave privata separata, tenuta fuori dal repo.

Un tag `v*` (es. `v1.0.0`) crea inoltre una release con quel numero di versione e l'APK allegato.

**Se un aggiornamento non si installa** ("app non installata", "pacchetto in conflitto"): la copia sul telefono è stata firmata con un'altra chiave. È il caso delle primissime build (APK inviato a mano, artefatto della run #1), firmate con chiavi temporanee. Disinstallala una volta e reinstalla dal link: da lì in poi gli aggiornamenti funzionano. La schermata *Informazioni* nell'app mostra versione e impronta della firma installata; quella della chiave del repository inizia con `42:5D:1E:18`.

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
| `image/StaticStickerFinder.kt` | Bordi di uno sticker fermo attorno al punto toccato (stima dello sfondo, componente connessa) |
| `image/StickerRefiner.kt` | Allarga la regione in movimento ai bordi dello sticker fermo e scarta le catture sbagliate (bordo, strisce, aree enormi) |
| `capture/` | Sessione di cattura automatica: servizio in primo piano con MediaProjection, bolla flottante, fotogrammi catturati → rilevatore → libreria |
| `data/UrlImporter.kt` | Importazione da link: file immagine diretto o ricerca delle immagini in una pagina |
| `data/StickerLibrary.kt` | Libreria degli sticker importati (deduplica per SHA-256, importazione da URI e cartelle) |
| `data/MediaScanner.kt` | Ricerca nel MediaStore con riconoscimento delle cartelle TikTok |
| `data/PackPlanner.kt` | Suddivisione della selezione in pack (3–30, statici/animati, pack esistenti) |
| `data/PackStore.kt` / `data/StickerExporter.kt` | Pack su disco e pipeline di conversione |
| `data/PackManager.kt` | Aggiunta dalla libreria, unione di pack, cambio di tipo statico/animato |
| `whatsapp/StickerContentProvider.kt` | ContentProvider nel formato dell'API ufficiale WhatsApp |
| `whatsapp/WhatsAppBridge.kt` | Intent di aggiunta e verifica dei pack già presenti su WhatsApp |
| `ui/` | Interfaccia Jetpack Compose (libreria, ricerca, pack, dialoghi di esportazione) |

Le immagini dei pack vengono servite a WhatsApp dal ContentProvider `com.whatik.stickercontentprovider` (permesso di lettura `com.whatsapp.sticker.READ`), come richiesto dalla documentazione ufficiale [WhatsApp/stickers](https://github.com/WhatsApp/stickers).
