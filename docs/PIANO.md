# Piano di lavoro: come finire Whatik senza sprechi

Aggiornato il 10 novembre 2026. È il punto di ripartenza: una sessione nuova, o un agente, legge questo file e `docs/DESIGN.md` e sa cosa fare. Va aggiornato alla fine di ogni pacchetto.

Per ripartire basta scrivere: «Leggi `docs/PIANO.md` ed esegui il prossimo pacchetto non fatto. Fermati alla fine del pacchetto.»

## Stato

| Cosa | Dove | Stato |
| --- | --- | --- |
| Ottimizzazioni fase 1 + correzioni della cattura | `main` e `android/main` | Pubblicato; da provare sul telefono |
| APK release (`whatik.apk`) e prova di avvio su emulatore | `main` e `android/main` | Pubblicato accanto a `whatik-debug.apk`; il link principale cambia solo dopo la prova sul telefono |
| Fondamenta del design (P0), screenshot JVM, specifica | `android/feature/nuovo-design` | Fatto; contiene `main` (correzioni della cattura, APK release, prova di avvio) |
| Schermate, overlay e navigazione del nuovo design | — | Da fare: pacchetti A1–E |
| iPhone | `ios/main` (`d4b873f`) | Fermo alla correzione dello sfondo |

## Cosa ha insegnato il lavoro fin qui

1. **Agenti in parallelo e limite di utilizzo non vanno d'accordo.** Il 29 ottobre cinque agenti sono stati fermati insieme e il loro lavoro, non ancora salvato, è andato perso. Da ora: un pacchetto alla volta, con commit e push a ogni schermata che compila.
2. **La macchina di lavoro viene ricreata.** L'SDK Android e la cartella temporanea spariscono: così si sono persi i due report di analisi. Da ora: tutto ciò che serve sta nel repository, e l'SDK si reinstalla con i comandi qui sotto.
3. **Una conversazione lunga costa a ogni passaggio**, perché viene riletta ogni volta. Da ora: si riparte con un contesto corto (`/compact` o una sessione nuova) e si legge questo file.
4. **Le verifiche su GitHub non costano token.** Test, build, prova di avvio su emulatore e schermate vere li può fare la CI.
5. **L'APK scaricato finora è la build di debug**: 17,7 MB e non ottimizzata. La build release pesa 1,7 MB e sull'emulatore della CI parte in 1,07 s contro 2,0 s (mediana di 5 avvii a freddo). Dal 10 novembre è pubblicata come `whatik.apk`.
6. **Maven Central risponde spesso 429 da questa macchina** («Too Many Requests») e la build si ferma. Si passa dal mirror di Google con lo script Gradle della sezione «Ambiente»: sta fuori dal repository, quindi la CI non cambia.

## Ambiente

L'SDK Android non è nell'immagine della macchina. Questi comandi lo reinstallano (verificati il 9 novembre: 11 secondi, 462 MB):

```bash
export ANDROID_HOME=/opt/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools /tmp/sdkdl && cd /tmp/sdkdl
curl -sSLo cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q -o cmdtools.zip && rm -rf $ANDROID_HOME/cmdline-tools/latest && mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest
(yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1) || true
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0" >/dev/null
```

Poi il mirror di Maven Central, sempre fuori dal repository:

```bash
mkdir -p ~/.gradle/init.d && cat > ~/.gradle/init.d/central-mirror.gradle <<'GRADLE'
def centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"
def putFirst = { repos ->
    def m = repos.maven {
        name = "centralMirror"
        url = centralMirror
        content { excludeGroupByRegex("androidx\\..*"); excludeGroupByRegex("com\\.android.*") }
    }
    repos.remove(m)
    repos.addFirst(m)
}
settingsEvaluated { settings ->
    putFirst(settings.pluginManagement.repositories)
    putFirst(settings.dependencyResolutionManagement.repositories)
}
GRADLE
```

Con questo SDK e il mirror la build completa è confermata (10 novembre): `assembleDebug assembleRelease` in 3 minuti e mezzo a cache vuota.

- Verifica completa: `export ANDROID_HOME=/opt/android-sdk && ./gradlew --no-daemon -q testDebugUnitTest assembleDebug lintDebug`
- Durante il lavoro basta `./gradlew --no-daemon -q compileDebugKotlin`.
- Screenshot (solo nel branch del design): `./gradlew --no-daemon recordRoborazziDebug`, PNG in `app/build/outputs/roborazzi/`. Se Robolectric risponde 429: `-Probolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2/`.
- Foglio di contatto, per guardare tante schermate in una sola immagine (comando da provare al primo uso): `montage app/build/outputs/roborazzi/*-light.png -resize 270x -tile 8x -geometry +6+6 foglio-light.png`

## Regole per ogni pacchetto

- **Uno alla volta**, nell'ordine della tabella. Ogni pacchetto parte dalla punta del branch su GitHub e finisce con tutto caricato.
- **Mai lavoro non salvato**: commit e push dopo ogni schermata che compila, anche se il pacchetto non è finito.
- **Leggere poco**: questo file, le sezioni di `docs/DESIGN.md` indicate e i file del pacchetto. Niente esplorazioni larghe.
- **Verificare una volta sola**: compilazione durante il lavoro, verifica completa alla fine. Gli screenshot si guardano su un foglio di contatto, non uno per uno.
- **Modello**: quello più economico per i pacchetti meccanici (A2, C, D, E), quello più capace per A1, A3 e B.
- **Rapporto finale** in 15 righe al massimo. Poi si aggiorna la tabella «Stato» di questo file.
- Messaggi di commit in italiano, con le righe di attribuzione indicate dalla sessione. Niente pull request, a meno che l'autrice del progetto le chieda.
- Con lei si parla in italiano semplice e con messaggi brevi: legge dal telefono e decide a ogni tappa se continuare.

## Pacchetti, in ordine

I pesi sono stime, ricavate dagli agenti già conclusi (P0: circa 390 mila token; ottimizzazioni: 235–340 mila ciascuno; correzioni della cattura: 124 mila).

| # | Pacchetto | Branch | Peso stimato | Dopo |
| --- | --- | --- | --- | --- |
| 0 | Preparazione: `main` portato nel branch del design, APK release e prova di avvio in CI | `main`, poi design | **Fatto il 10 novembre** | APK release da provare sul telefono |
| A1 | Libreria e Pack (P1, P3) | design | ~300 mila | — |
| A2 | Schermata Cattura (P2) | design | ~150 mila | — |
| A3 | Navigazione a tre schede (P9) | design | ~300 mila | Schermate «dopo», ok dell'autrice, unione in `android/main` |
| C | Editor e «Crea pack» (P5, P6) | design | ~250 mila | Unione in `android/main` |
| B | Bolla e overlay (P7) | design | ~300 mila | Unione in `android/main` |
| D | Importazioni ed errori localizzati (P4, P8) | design | ~200 mila | Unione in `android/main` |
| E | Pulizia (P10) e controllo finale | design | ~150 mila | Unione finale, documenti aggiornati |

A1, A2 e A3 arrivano all'APK insieme: la Libreria nuova non ha più il menu «+» e l'icona dei Pack, che passano alla scheda Cattura e alla barra in basso. Dopo A3 ogni pacchetto si può pubblicare da solo.

### 0. Preparazione (fatto)
- `main` è nel branch del design: le stringhe doppie sono risolte, 140 test passano, lint senza errori.
- La CI compila i due APK insieme (stesso `versionCode`, stessa firma `sideload`) e pubblica `whatik.apk` nella release `latest` solo dopo la prova di avvio. R8 toglie il codice non usato ma non rinomina le classi (`-dontobfuscate`): errori e tracce dei crash restano leggibili.
- Resta da fare: quando l'autrice ha provato `whatik.apk` sul telefono, il link principale (README e note della release) passa a `whatik.apk`.

### Prova di avvio in CI
Il job `avvio` di `.github/workflows/android.yml` gira a ogni push su `main` e `android/**` e usa `ci/smoke.py` su un emulatore (API 34). Ripete il percorso di chi ha già l'app: installa la build di debug, importa uno sticker con «Condividi», installa sopra la build release, controlla che parta, non vada in crash e ritrovi la libreria, tocca le schede (`TABS` nello script), importa un secondo sticker e misura gli avvii a freddo.

- Risultati: artefatto `schermate` della run (PNG, foglio unico `schermate.png`, `riepilogo.md` con i testi letti sullo schermo, `logcat.txt`). Su `android/main` il foglio e il riepilogo finiscono anche nella release `latest`.
- Da una sessione: elencare gli artefatti della run, chiedere il link di download di `schermate`, scaricarlo con `curl` e leggere prima `riepilogo.md`; il foglio si guarda solo quando serve.
- Sono le schermate vere del nuovo design a costo zero: per A1–A3 basta fare push sul branch del design.

### A1. Libreria e Pack
- File: `ui/LibraryScreen.kt`, `ui/PacksScreen.kt`, `ui/PackDetailScreen.kt` e i loro scenari in `app/src/test/java/com/whatik/screenshots/ScreenScenarios.kt`.
- Specifica: `docs/DESIGN.md`, sezioni «Componenti di P0», «Libreria», «Pack», «Dettaglio pack e Aggiungi al pack».

### A2. Cattura
- File: `ui/CaptureScreen.kt` e il suo scenario.
- Specifica: sezione «Cattura».

### A3. Navigazione
- File: `ui/MainActivity.kt`, `ui/MainViewModel.kt`, più un test JVM della tabella del tasto Indietro.
- Specifica: sezioni «Navigazione» e «Shell».
- Collega i parametri nuovi che A1 e A2 hanno aggiunto alle schermate: a quel punto sono già nel branch.
- Alla fine: foglio di contatto «dopo» e link per l'autrice. Con il suo ok si unisce in `main` e `android/main`.

### C. Editor e «Crea pack»
- File: `ui/EditorScreen.kt`, `ui/ExportDialogs.kt` e i loro scenari.
- Specifica: sezioni «Editor» e «Crea pack».

### B. Bolla e overlay
- File: `capture/BubbleOverlay.kt`, `RadialMenuOverlay.kt`, `AimOverlay.kt`, `CaptureLockOverlay.kt`, `CaptureService.kt`, nuovo `capture/OverlayStyle.kt`, `screenshots/OverlayScreenshotsTest.kt`.
- Specifica: sezione «Overlay e notifiche».
- Le tre correzioni funzionali ci sono già (`setNotice`, `EndConfirmOverlay`, `resultSummary()`): vanno ridisegnate secondo la specifica, non rifatte.
- Le ottimizzazioni di `CaptureService` (copia della sola zona, decodifiche pigre, pulizia su IO) non si toccano.

### D. Importazioni ed errori
- File: `ui/ScanScreen.kt`, `ui/LinkScreens.kt`, `ui/ShareReceiverActivity.kt`, `ui/SelectableRow.kt` (da eliminare), `data/StickerLibrary.kt` (solo i testi d'errore).
- Specifica: sezioni «Cerca sul telefono, Immagini trovate, link, condivisione» e «P8 e P10».

### E. Pulizia e controllo finale
- Specifica: sezione «P8 e P10».
- Aggiornare `README.md`, `docs/STATO-PROGETTO.md` e questo file.

## Facoltativi, dopo

- **F. Prestazioni, fase 2.** Il report dell'analisi è andato perso; resta questo riassunto.
  - L: in `image/StickerConverter.kt`, stimare la dimensione prima della prima passata della ricerca qualità/dimensione (per una clip da 4 s: da 120–240 codifiche a circa 70–90, stima). Niente tentativo lossless per gli sticker fermi delle catture (parametro `preferLossless`). Cambia i byte prodotti: è un compromesso di qualità da valutare.
  - M: avvio senza lettura di file e JSON sul main thread (`data/StickerLibrary.kt`, `data/PackStore.kt`, `whatsapp/StickerContentProvider.kt`, `ui/MainViewModel.kt`, `ui/LibraryScreen.kt`). Stima: 50–150 ms in meno. Va fatto dopo il design, perché tocca gli stessi file.
  - N: fotogrammi grigi come `ByteArray` (12 MB di memoria in meno) ed eventualmente calcolati durante la cattura (`image/StickerDetector.kt`, `image/VideoAnalyzer.kt`, `capture/CapturedFrames.kt`, `capture/CaptureService.kt`).
  - O: Baseline Profile. Rimandato: per generarlo serve un dispositivo o un emulatore, e conta solo sulla build release.
- **G. iPhone.** Portare le correzioni utili e riallineare l'aspetto. La cattura dal vivo su iPhone non esiste, quindi bolla e overlay non si applicano.

## Debiti noti

- `StickerPassthrough.MAX_FRAMES = 100` è una copia del limite privato di `StickerConverter`: va unificato.
- `MainViewModel.openAnimated` ripete lo smistamento GIF/WebP di `FrameProducer.open`.
- `MainViewModel.importScanSelection` importa un file alla volta: deve usare `library.importDocuments(...)` (pacchetto A3).
- `isAnyWhatsAppInstalled` è chiamato durante la composizione in `MainActivity.kt` e in `ExportDialogs.kt` (pacchetti A3 e C).
- `CapturedFrames.REGION_DECODE` è spento: serve un margine di 2 colonne e una prova su dispositivo prima di accenderlo.
- Le anteprime animate nelle griglie girano tutte insieme quando le animazioni di sistema sono attive. Se sul telefono la griglia scatta, si passa `animate = false` alle tessere.
- Annullare una cattura in corso sarebbe una funzione nuova: non è in piano.

## Prove sul telefono

Niente di ciò che è stato pubblicato da ottobre è stato ancora confermato su un telefono.

1. «Tutti» su un pannello con una tessera tagliata dal bordo: l'avviso compare come testo fuori dalla zona registrata, gli sticker non hanno scritte sopra, l'elaborazione è più rapida.
2. Uno sticker già presente: il messaggio dice «già in libreria» e il numero sulla bolla conta solo i nuovi.
3. Tenere premuta la bolla: chiede conferma. Trascinarla funziona come prima.
4. «Punta»: la cornice segue lo sticker toccato e il ritaglio è quello.
5. Un meme su fondo chiaro tiene il suo sfondo; i bordi del pannello spariscono.
6. Creare un pack con sticker animati già in libreria: WhatsApp li accetta, la velocità è giusta, l'icona è corretta, ed è quasi istantaneo.
7. Analisi di una registrazione dello schermo: più veloce, colori giusti.
8. Editor su uno screenshot grande: si apre più in fretta.
9. Importare una cartella con molte immagini: molto più veloce.
