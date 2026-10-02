# Whatik per iPhone

Versione iOS di Whatik: prende gli sticker che si salvano su TikTok scorrendo i video e li
trasforma in pack per WhatsApp, uno alla volta o in massa. È la stessa app della versione
Android, con le differenze imposte da iOS descritte sotto.

## Installare l'app

Ogni push sul branch `ios/main` compila l'app e aggiorna la release **ios-latest**
(gli altri branch iOS compilano e testano senza toccare il link; vedi `docs/BRANCH.md`).
La guida passo passo per installarla e usarla è in [`docs/GUIDA-IOS.md`](../docs/GUIDA-IOS.md).

- **https://github.com/yarimartina/whatik/releases/download/ios-latest/Whatik.ipa** (iPhone)
- **https://github.com/yarimartina/whatik/releases/download/ios-latest/Whatik-simulator.zip** (simulatore)

Su iPhone non esiste l'equivalente di "installa APK": un'app fuori dall'App Store deve essere
firmata. La IPA è pubblicata senza firma, la firma la mette chi la installa.

**Con un computer (Windows o Mac), gratis, con Sideloadly**

1. Installa [Sideloadly](https://sideloadly.io) e iTunes (solo su Windows, la versione dal sito Apple).
2. Collega l'iPhone con il cavo e sblocca il telefono.
3. Trascina `Whatik.ipa` in Sideloadly, inserisci il tuo Apple ID e premi Start.
4. Su iPhone: Impostazioni → Generali → VPN e gestione dispositivi → fidati del tuo Apple ID.
5. Su iOS 16 e successivi: Impostazioni → Privacy e sicurezza → Modalità sviluppatore → attiva, poi riavvia.

Con un Apple ID gratuito l'app scade dopo 7 giorni: basta ripetere il punto 3 (Sideloadly può
farlo da solo se il computer resta acceso). AltStore funziona allo stesso modo.

**Con TestFlight**: serve un account Apple Developer (99 €/anno). Con quello la CI può firmare e
caricare la build su TestFlight, e chi la prova riceve solo un link d'invito.

**Solo per vederla**: nella stessa release ci sono le schermate PNG dell'app, prese
automaticamente dal simulatore con contenuti dimostrativi.

**Senza iPhone**: `Whatik-simulator.zip` si apre nel simulatore di Xcode su un Mac
(`xcrun simctl install booted Whatik.app`) oppure si carica su [Appetize.io](https://appetize.io)
per provarla dal browser.

## Come si usa

1. Su TikTok apri i commenti di un video, tocca l'icona degli sticker e scegli *Salvati* o *Usati di recente*.
2. Avvia la registrazione dello schermo dal Centro di Controllo.
3. Tieni fermo il pannello 6–8 secondi, poi ferma la registrazione.
4. In Whatik, scheda *Cattura* → *Scegli la registrazione*. Whatik trova le tessere del pannello,
   capisce quali sono animate e quanto dura il loop, toglie i bordi bianchi del pannello (un meme tiene il suo sfondo) e ti fa scegliere
   quali sticker creare.
5. In *Libreria* seleziona gli sticker e tocca *Copia su WhatsApp*; in *Pack* tocca *Aggiungi a WhatsApp*.

Per gli sticker fermi basta uno screenshot del pannello (*Scegli lo screenshot*). Dalla
Libreria si importano anche foto, GIF, WebP, file e link, e ogni sticker si può ritagliare con
il riquadro a maniglie.

## Differenze dalla versione Android

| Android | iPhone |
| --- | --- |
| Bolla sopra TikTok, cattura dal vivo ("Punta" e "Tutti") | Registrazione con il registratore di iOS e analisi in Whatik con la stessa logica di "Tutti". iOS non permette alle app di disegnare sopra le altre né di registrare lo schermo da sole. |
| WhatsApp legge i pack da un ContentProvider | Il pack passa a WhatsApp dagli appunti (`net.whatsapp.third-party.sticker-pack`) aprendo `whatsapp://stickerPack`, come nell'esempio ufficiale per iOS |
| Whatik sa se un pack è già su WhatsApp | Non si può sapere: si può sempre ripetere l'aggiunta |
| Ricerca delle cartelle TikTok in galleria | Rullino, File, link e appunti |

Tutto il resto è uguale: conversione in WebP 512×512 (≤100 KB fermi, ≤500 KB animati, ≤10 s),
pack misti con gli sticker fermi trasformati in due fotogrammi, pack esistenti, gestione
dei pack (aggiunta dalla libreria, rimozione, rinomina, unione con overflow in pack numerati),
deduplica degli sticker.

## Struttura

| Percorso | Contenuto |
| --- | --- |
| `WhatikCore/Sources/WhatikCore` | Logica pura in Swift, portata dalla versione Android: rilevatore di movimento e loop, sticker fermi, rifinitura, griglia delle tessere, pulizia dei bordi del pannello, ritaglio, pianificazione dei pack, libreria, archivio dei pack, dati per WhatsApp |
| `WhatikCore/Sources/WhatikMedia` | Codifica e decodifica WebP con libwebp, conversione degli sticker, operazioni sui pack |
| `WhatikCore/Tests` | 74 test XCTest (gli stessi della versione Android più quelli su WebP e pack), girano anche su Linux |
| `WhatikTests` | Test nel simulatore delle parti solo iOS: CoreGraphics, GIF con ImageIO, video con AVFoundation, analisi completa di uno screenshot e di una registrazione sintetica del pannello con un video che scorre sopra |
| `Whatik/Media` | Decodifica con ImageIO, lettura dei video con AVFoundation, analisi delle registrazioni, invio a WhatsApp, link |
| `Whatik/Views` | Interfaccia SwiftUI (iOS 16+) |
| `project.yml` | Progetto Xcode per XcodeGen |

La CI (`.github/workflows/ios.yml`) compila l'app per iPhone e per il simulatore, esegue entrambi i
gruppi di test, avvia l'app nel simulatore con `-WhatikDemo` per le schermate e pubblica tutto
nella release `ios-latest`.

## Compilare

```bash
cd ios/WhatikCore && swift test        # nucleo e test, anche su Linux
cd ios && xcodegen generate            # crea Whatik.xcodeproj (serve un Mac con Xcode 16)
open Whatik.xcodeproj
```
