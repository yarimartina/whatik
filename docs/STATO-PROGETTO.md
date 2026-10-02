# Whatik: stato del progetto

Fotografia di quello che esiste al 2 ottobre 2026, scritta prima di riorganizzare il lavoro e
di iniziare la versione iOS. Il codice Android di riferimento è il commit `415afaf` del branch
`ccr-9919d90b-bs5jr4` (build CI n. 15).

## In breve

Whatik prende gli sticker che si creano o si salvano su TikTok scorrendo i video (raccolta
"Salvati" / "Usati di recente", non quelli delle chat) e li trasforma in pack di sticker per
WhatsApp, uno alla volta o in massa. TikTok non offre un modo per scaricarli, quindi l'app li
ricava dallo schermo: cattura dal vivo sopra TikTok, registrazioni dello schermo, screenshot e
link. Poi li converte nel formato di WhatsApp e gestisce i pack.

## Cosa fa l'app Android

| Area | Funzione | Stato |
| --- | --- | --- |
| Cattura dal vivo | Bolla flottante sopra TikTok con menu circolare (Punta, Tutti, Termina) | Provata sul telefono |
| Cattura "Punta" | Tocco sullo sticker, zona inquadrata, schermo bloccato 8 s, 20 fps, animato o statico deciso dall'app | Provata: funziona bene |
| Cattura "Tutti" | Riconosce le tessere del pannello, registra solo il pannello, elabora le tessere una alla volta con evidenziazione | Provata: 11 tessere trovate, 10 sticker nuovi |
| Correzioni build 14-15 | Riquadri allineati sui telefoni con foro fotocamera, file tagliate saltate, sfondo bianco reso trasparente | Da confermare sul telefono |
| Registrazione | Analisi automatica di un video registrato con il registratore di sistema | Validata su una registrazione reale |
| Screenshot e editor | Ritaglio libero con maniglie (bordi, angoli, interno), pizzico, intervallo di tempo per gli animati | Funziona |
| Link | URL diretto di un file o pagina da cui estrarre le immagini | Funziona sui file; le pagine TikTok spesso bloccano |
| Galleria | Ricerca delle cartelle TikTok, photo picker, file, cartelle intere | Funziona |
| Conversione | WebP 512×512, ≤100 KB statico, ≤500 KB animato, ≤10 s, icona 96×96 | Coperta da test |
| Pack misti | Selezione mista in un solo pack animato (fermi = 2 fotogrammi), oppure separati o tutto statico | Funziona |
| Gestione pack | Elenco, aggiunta dalla libreria, rimozione, rinomina, eliminazione, unione con overflow in pack numerati | Funziona |
| WhatsApp | ContentProvider ufficiale + intent `ENABLE_STICKER_PACK`, verifica dei pack già aggiunti | Funziona |
| Distribuzione | APK firmato con chiave fissa, `versionCode` crescente, link fisso alla release `latest` | Gli aggiornamenti si installano sopra |

## Come è fatta

Kotlin, Jetpack Compose, minSdk 26, targetSdk 35. Circa 9.200 righe di codice e 1.050 di test
(74 test JVM), stringhe in italiano e inglese.

| Pacchetto | Righe | Ruolo |
| --- | --- | --- |
| `image/` | ~2.800 | Codec GIF e contenitore WebP scritti a mano, conversione, rilevatore di movimento e loop, sticker fermi, rifinitura, griglia delle tessere, rimozione dello sfondo |
| `capture/` | ~1.450 | Servizio MediaProjection, bolla, menu circolare, mirino, overlay di blocco con evidenziazione delle tessere |
| `data/` | ~1.050 | Libreria con deduplica SHA-256, pianificazione dei pack, archivio dei pack, esportazione, unione, link |
| `ui/` | ~3.700 | Schermate Compose e `MainViewModel` |
| `whatsapp/` | ~215 | ContentProvider e intent verso WhatsApp |

Il percorso di uno sticker catturato con "Tutti":

1. La bolla chiede una fotografia dello schermo e `TileGridFinder` trova le tessere (sfondo del pannello, isole di contenuto, filtri di forma, completamento della griglia, tessere tagliate scartate).
2. Si registra solo l'area delle tessere a 15 fps per 8 s, con lo schermo bloccato.
3. `StickerDetector` trova le regioni in movimento e il periodo del loop. Per ogni tessera: se contiene un loop diventa animata con quei tempi, altrimenti statica.
4. `BackgroundRemovingFrameProducer` rende trasparente lo sfondo della tessera e restringe il riquadro.
5. `StickerConverter` produce il WebP e la libreria lo salva, scartando i doppioni.

## Validazione

- Test unitari su tutta la logica pura: rilevatore, ritaglio, griglia, sfondo, pianificazione, codec, conversione.
- Rilevatore e griglia provati anche su materiale reale dell'utente, con harness fuori dal repository: una registrazione dello schermo e due screenshot del pannello.
- I file WebP prodotti sono stati verificati con Pillow.
- Prove sul telefono: un solo dispositivo Android, quello dell'utente. Le soglie sono tarate lì.

## Cose provate e tolte

- **Login e sito web di TikTok**: il sito in un browser incorporato viene bloccato dalle difese anti-automazione ("troppi tentativi", pagina vuota). Rimosso.
- **Ritaglio con cursore e riquadro fisso**: sostituito dal riquadro libero con maniglie su richiesta esplicita.
- **Ritaglio basato solo sul movimento**: tagliava gli sticker in cui si muove solo una parte. Sostituito dall'allargamento ai bordi della tessera ferma.

## Da riorganizzare

- **Branch**: tutto il lavoro sta su `ccr-9919d90b-bs5jr4`; manca un `main` stabile su cui fare pull request.
- **`CaptureService.kt` (767 righe)**: fa proiezione, overlay, cattura, analisi ed esportazione. Va diviso in sessione di cattura, overlay ed elaborazione, e le due pipeline "Punta" e "Tutti" hanno codice simile da unificare.
- **`MainViewModel.kt` (964 righe)**: un view model per schermata.
- **Materiale di prova**: registrazioni e screenshot reali usati per tarare le soglie non sono nel repository. Vanno aggiunti come fixture con test di regressione.
- **Soglie**: tarate su un solo telefono e un solo tema di TikTok (pannello chiaro). Servono prove con tema scuro e altri schermi.
- **Codec scritti a mano**: GIF e contenitore WebP in Kotlin funzionano, ma su iOS si usa libwebp; valutare libwebp anche su Android per avere un solo codec.
- **Chiave di firma nel repository**: va bene solo per il sideload. Per uno store serve una chiave privata fuori dal repo.
- **README**: la descrizione della cattura è un unico paragrafo molto lungo; va spezzata in una guida d'uso e una parte tecnica.
- **Nessun test strumentale o di interfaccia**: overlay e servizio di cattura sono provati solo a mano.

## Versione iOS (branch `ios`)

iOS non permette a un'app di disegnare sopra le altre né di registrare lo schermo in modo
continuo mentre si usa TikTok. Cambia quindi il modo di catturare, mentre il resto si porta.

| Android | iOS |
| --- | --- |
| Bolla sopra TikTok, "Punta" e "Tutti" dal vivo | Registrazione con il registratore di iOS (Centro di Controllo), poi analisi in Whatik con la stessa logica di "Tutti": griglia delle tessere, loop, sfondo trasparente |
| Screenshot e editor | Uguale: analisi automatica dello screenshot del pannello o ritaglio con maniglie |
| ContentProvider + intent | Appunti con tipo `net.whatsapp.third-party.sticker-pack` e apertura di `whatsapp://stickerPack`, come l'esempio ufficiale per iOS |
| Codec GIF/WebP in Kotlin | libwebp per WebP, ImageIO per GIF/PNG/JPEG/HEIC |
| APK con link fisso | IPA non firmata nella release `ios-latest`, da installare con Sideloadly o AltStore (oppure TestFlight con un account sviluppatore) |

La logica pura (rilevatore, griglia, sfondo, pianificazione, conversione, archivio dei pack) è
in un pacchetto Swift separato, `ios/WhatikCore`, che si compila e si testa anche su Linux.
