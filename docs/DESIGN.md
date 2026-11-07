# Nuovo design di Whatik (Android)

Specifica del redesign, ricavata dall'analisi UX/UI (37 problemi con file e riga) e approvata con i mockup.
Questo file sostituisce il report di analisi, che era in una cartella temporanea e non c'è più.

- Branch di lavoro: `android/feature/nuovo-design` (parte da `main` con le ottimizzazioni).
- Mockup: pagina di design privata dell'autrice del progetto (8 schermate + sistema di design + confronto con l'app attuale).
- Le stringhe nuove e modificate sono **già tutte** in `res/values/strings.xml` e `res/values-en/strings.xml` (passo P0): qui sono indicate per chiave.

## Stato dei passi

| Passo | Contenuto | Stato |
| --- | --- | --- |
| P0 | Tema, componenti condivisi, stringhe it/en, colori e misure degli overlay | Fatto (commit `a9afb90`) |
| Correzioni cattura | Avvisi fuori dalla registrazione, doppioni contati a parte, conferma sulla pressione lunga della bolla | Su `main`: unire in questo branch prima di P7 |
| P1 | Libreria (`ui/LibraryScreen.kt`) | Da fare |
| P2 | Cattura (`ui/CaptureScreen.kt`) | Da fare |
| P3 | Pack (`ui/PacksScreen.kt`, `ui/PackDetailScreen.kt`) | Da fare |
| P4 | Importazioni (`ui/ScanScreen.kt`, `ui/LinkScreens.kt`, `ui/ShareReceiverActivity.kt`; eliminare `ui/SelectableRow.kt`) | Da fare |
| P5 | Editor (`ui/EditorScreen.kt`) | Da fare |
| P6 | Crea pack (`ui/ExportDialogs.kt`) | Da fare |
| P7 | Overlay e notifiche (`capture/*`, nuovo `capture/OverlayStyle.kt`) | Da fare |
| P8 | Motivi d'errore localizzati (`data/StickerLibrary.kt`) | Da fare |
| P9 | Shell e navigazione (`ui/MainActivity.kt`, `ui/MainViewModel.kt`) | Da fare, da unire dopo P1–P4 |
| P10 | Pulizia di stringhe e parametri non più usati, icona a tema | Ultimo |

Regole: P1–P8 toccano file diversi e si fanno in parallelo. Ogni passo mantiene compatibili le firme delle composable che `MainActivity.kt` chiama: si **aggiungono** solo parametri con valore di default; quelli non più usati restano fino a P10. Nessuna dipendenza nuova.

## Navigazione

Tre destinazioni sempre visibili in una `NavigationBar`, nell'ordine del lavoro (prendere, scegliere, mandare):

| Tab | Etichetta | Icona | Contenuto |
| --- | --- | --- | --- |
| 1 | **Cattura** | `RadioButtonChecked` (outlined/filled) + puntino se la sessione è attiva | Sessione con la bolla, tutte le altre fonti, suggerimenti |
| 2 | **Libreria** | `EmojiEmotions` | Griglia, selezione, «Crea pack», ritaglio, eliminazione; Informazioni nel menu ⋮ |
| 3 | **Pack** | `Collections` | Elenco pack con stato WhatsApp, poi dettaglio |

- Avvio: `LIBRARY` se la libreria ha almeno uno sticker, altrimenti `CAPTURE`. Notifiche e condivisioni portano dove portano oggi.
- Secondo livello (senza barra, con freccia): Dettaglio pack, Aggiungi al pack, Editor, Cerca sul telefono, Immagini trovate.
- Indietro: secondo livello → tab di provenienza (Scan/Link → Cattura; Dettaglio → Pack; Aggiungi → Dettaglio; Editor → Libreria, ignorato durante la conversione); Cattura e Pack → Libreria; Libreria → prima svuota la selezione, poi esce. Con una selezione attiva, Indietro la svuota sempre per prima.
- In selezione (Libreria, Dettaglio pack) la barra di navigazione è sostituita da `SelectionBottomBar` con azioni etichettate; il FAB resta sopra.
- Resta l'`enum Screen` di `MainViewModel`, niente navigation-compose. Transizioni con `AnimatedContent` (dissolvenza 200 ms fra tab, scorrimento 250 ms verso il secondo livello), spente se le animazioni di sistema sono a 0.
- Parole uniche ovunque: **Cattura**, **Libreria**, **Pack**; **Aggiungi a WhatsApp** / **Apri in WhatsApp**; **Crea pack**; **animato** / **fermo**.

## Componenti di P0 (`com.whatik.ui.components`)

- Tema (`com.whatik.ui.theme`): `WhatikTheme(darkTheme, content)` senza colore dinamico; `WhatikTheme.extended` (`brand`, `success`, `onSuccess`, `successContainer`, `onSuccessContainer`); `Spacing { xxs 2, xs 4, s 8, m 12, l 16, xl 24, xxl 32, xxxl 48, fabClearance 88 }` (dp); `@WhatikPreviews`, `WhatikPreviewSurface`.
- `EXTRA_OPEN_CAPTURE = "com.whatik.extra.OPEN_CAPTURE"` in `ui/Intents.kt`.
- `rememberAnimationsEnabled()`; `StickerImage(model, contentDescription, modifier, animate, contentScale)`; `StickerTile(model, contentDescription, selected, onClick, modifier, animated, enabled, showSelectionIndicator, caption, onLongClick, onLongClickLabel, animate)`; `SelectionIndicator`; `AnimatedBadge`; `StickerGrid(modifier, minCellSize = 104.dp, contentPadding = StickerGridPadding, state, content)` (il padding include già lo spazio del FAB); `EmptyState(icon, title, body, modifier, actionLabel, onAction)`; `StatusChip(text, kind: StatusKind { Success, Neutral, Warning })`; `PermissionItem(title, supporting, granted, required, actionLabel, onAction)`; `SourceListItem(icon, title, supporting, onClick)`; `SectionHeader(text)`; `BusyBanner(text, progress)`; `SelectionBottomBar(actions: List<BarAction>)` con `BarAction(icon, label, enabled, destructive, onClick)`; `WhatikNavigationBar(current: TopLevelDestination { CAPTURE, LIBRARY, PACKS }, onSelect, captureActive)`; `ConfirmDialog(title, body, confirmLabel, onConfirm, onDismiss, destructive)` (chi la usa la chiude); `BlockingProgressDialog(title, body, progress, onCancel, onDismissRequest)`; `DialogContainerColor`; `PackThumbnailStrip(files, max = 6, size = 44.dp)`.
- Regole d'uso:
  - Ogni `AlertDialog` delle schermate usa `containerColor = DialogContainerColor`: con il colore predefinito di M3 i pulsanti di testo `primary` scendono a 4,35:1.
  - `SectionHeader` non va dentro una card `surfaceContainerHigh`.
  - Nelle griglie passa modelli che restano gli stessi fra una ricomposizione e l'altra, per esempio `remember(item.fileName) { file }` o l'Uri già salvato: così si ricompone solo la cella toccata.
  - `animate = false` per le immagini piccole e numerose (icona del pack, miniature della Cattura).

## Specifiche per schermata

### Shell (P9: `MainActivity.kt`, `MainViewModel.kt`)
- Schermata iniziale come sopra.
- Ogni schermata di primo livello riceve lo slot `bottomBar` = `WhatikNavigationBar(current, onSelect, captureActive = captureUi.running)`; la Cattura si apre con `vm.openCapture()`.
- `back()` come sopra; in `EDITOR` restituisce `true` senza fare nulla durante la conversione.
- Mai `vm.navigate()` durante la composizione: `LaunchedEffect`.
- `when (screen)` dentro `AnimatedContent`.
- Link in caricamento → `BlockingProgressDialog(link_loading)`; chiuderlo nasconde solo il dialogo, l'esito arriva in snackbar.
- `EXTRA_OPEN_CAPTURE` → `openCapture()`.
- Permessi bloccati: se dopo un rifiuto `shouldShowRequestPermissionRationale` è falso, i pulsanti aprono `ACTION_APP_NOTIFICATION_SETTINGS` (notifiche) o `ACTION_APPLICATION_DETAILS_SETTINGS` (foto).
- `MergeState.deleteSources` spento di default.
- Plurali `msg_deleted_count`, `msg_duplicates_count`, `msg_failed_count`, `msg_pack_skipped_n`; `err_video_unreadable` al posto del testo fisso.
- «Crea pack con questi» dalla Cattura: `vm.selectCaptured(createdIds)` poi `vm.openExport()`.
- Da sistemare qui: `isAnyWhatsAppInstalled` chiamato durante la composizione sul main thread; `importScanSelection` usi l'importazione a gruppi `library.importDocuments(...)`.

### Libreria (P1: `LibraryScreen.kt`)
1. `TopAppBar` «Libreria» (`title_library`). Azioni: Seleziona tutto (`action_select_all`) se ci sono sticker; ⋮ (`action_more`) con Informazioni.
2. In selezione: X (`action_clear_selection`), titolo plurale `title_selected_count`, `TextButton` Seleziona/Deseleziona tutto.
3. `BusyBanner(library_busy)` sotto la barra durante un'importazione.
4. Con sticker e senza selezione: riga `library_hint` «Tocca per selezionare · tieni premuto per ritagliare».
5. `StickerGrid` di `StickerTile(contentDescription = nome, animated, onClick = seleziona, onLongClick = ritaglia, onLongClickLabel = action_crop)`.
6. In selezione: FAB esteso `action_export` «Crea pack (%d)» (`primary`).
7. Barra in basso: senza selezione la navigazione; in selezione `SelectionBottomBar([Ritaglia (attivo con 1 selezionato), Elimina (distruttivo)])`.
- Vuoto: `EmptyState(EmojiEmotions, empty_title, empty_body, empty_action_capture → Cattura)`.
- Elimina: `ConfirmDialog(delete_confirm_title, delete_confirm_body, action_delete, destructive)`.
- Informazioni: `info_title`, versione, firma in monospazio, `info_update_hint`, `action_ok`.
- Nuovo parametro `bottomBar`; i vecchi `onImport*`, `onScan`, `onOpenPacks` restano fino a P10.
- Fatto quando: TalkBack legge nome, «animato», stato di selezione e le azioni; nessuna istruzione sotto 14 sp.

### Cattura (P2: `CaptureScreen.kt`)
Colonna scorrevole, padding 16, 24 fra le sezioni.
1. `TopAppBar` «Cattura» (primo livello, senza freccia).
2. `SectionHeader(capture_live_title)` «Dal vivo su TikTok».
3. Card sessione (`surfaceContainerHigh`, padding 16):
   - Non attiva:
     - `capture_live_body`.
     - Tre righe con icona in cerchio 40 dp `brand` (`ic_aim`, `ic_motion`, `ic_close`): Punta, Tutti, Termina con `capture_mode_*_body`.
     - Divisore, poi `capture_permissions_title` (testo `titleSmall`, non `SectionHeader`).
     - `PermissionItem` bolla, obbligatorio, `capture_step_overlay_help`.
     - Solo da Android 13: `PermissionItem` notifiche, consigliato; se bloccate, `capture_notifications_blocked` + `action_open_settings`.
     - Pulsante pieno `capture_start` «Avvia e apri TikTok», attivo se la bolla è concessa.
     - Sotto: `capture_start_disabled_hint` o `capture_consent_hint`, ed eventuale messaggio di stato.
   - Attiva:
     - Puntino `success` (pulsa, spento senza animazioni) + `capture_running_title`.
     - Messaggio di stato (`liveRegion`) e `capture_running`.
     - Riga con `Button(capture_open_tiktok)` e `OutlinedButton(capture_stop)`, che va a capo a scala carattere 2,0.
4. Card sticker della sessione (anche a sessione finita):
   - Titolo plurale `capture_session_captured`.
   - `LazyRow` di miniature 72 dp al primo fotogramma.
   - `Button(capture_create_pack)` «Crea pack con questi (%d)», `TextButton(capture_review)`, `capture_hint_crop`.
5. `SectionHeader(capture_sources_title)` + card con 6 `SourceListItem`:
   - registrazione (`Videocam`)
   - galleria (`PhotoLibrary`)
   - cerca sul telefono (`ImageSearch`)
   - link (`Link`)
   - file (`InsertDriveFile`)
   - cartella (`FolderOpen`)

   Titoli e testi sono `source_*_title` e `source_*_body`.
6. `SectionHeader(capture_tips_title)` + `OutlinedCard` con `capture_tips`.
- Nuovi parametri (default vuoti): `onCreatePackFromCaptured`, `onImportVideo`, `onImportGallery`, `onScan`, `onImportLink`, `onImportFiles`, `onImportFolder`, `notificationsBlocked`, `onOpenNotificationSettings`, `bottomBar`.

### Pack (P3: `PacksScreen.kt`)
1. `TopAppBar` «Pack», senza freccia.
2. Una card cliccabile per ogni pack (descrizione TalkBack `pack_open_a11y`):
   - Icona 56 dp al primo fotogramma, nome (2 righe al massimo), conteggio plurale `pack_count_animated_n` / `pack_count_still_n`, freccia.
   - `StatusChip`:
     - pack incompleto → Warning `pack_status_missing` (mancano 3 − n);
     - aggiunto → Success `pack_status_added`;
     - altrimenti Neutral `pack_status_not_added`.
   - `PackThumbnailStrip`.
   - Se il pack è valido, `Button(action_add_to_whatsapp)`, oppure `FilledTonalButton(action_reopen_whatsapp)` se è già aggiunto.
   - Niente «Gestisci» e niente «Elimina» sulla card.
3. Vuoto: `EmptyState(Collections, packs_empty_title, packs_empty, packs_empty_action → Libreria)`.
- Nuovi parametri: `onGoToLibrary`, `bottomBar`. L'eliminazione si sposta nel dettaglio.

### Dettaglio pack e Aggiungi al pack (P3: `PackDetailScreen.kt`)
- Dettaglio:
  1. Freccia, nome del pack, ⋮ con `pack_action_rename` e `pack_action_delete` (in `error`). «Unisci» non è più nel menu.
  2. In selezione: X, titolo plurale; in basso `SelectionBottomBar([pack_action_remove, distruttivo])`. `BackHandler` svuota la selezione.
  3. Intestazione: icona 64 dp, `pack_meta` («%1$d/%2$d sticker · animati/fermi»), `StatusChip`.
  4. Pulsante WhatsApp come nell'elenco, più `OutlinedButton(pack_action_merge_short)`, attivo se ci sono altri pack.
  5. `BusyBanner(pack_progress)` durante la conversione.
  6. `pack_detail_hint`.
  7. `StickerGrid` di `StickerTile(a11y_pack_sticker)`.
  8. FAB `pack_action_add` se non c'è selezione e il pack ha meno di 30 sticker.
  - Vuoto: `EmptyState(AddPhotoAlternate, pack_empty, …, pack_action_add)`.
- Dialoghi:
  - Rimuovi: `ConfirmDialog(pack_remove_confirm, pack_remove_body, destructive)`.
  - Elimina: `ConfirmDialog(pack_delete_title, pack_delete_confirm, destructive)`.
  - Unisci:
    - Righe alte almeno 56 dp, cliccabili per intero, con checkbox.
    - Se i tipi sono diversi, la scelta `merge_result_type`.
    - Switch `merge_delete_sources` **spento** di default; quando è acceso compare `merge_delete_sources_help` in `error`.
    - Riepilogo `merge_summary`.
  - Rinomina: invariato.
- Aggiungi al pack:
  - Titolo `pack_add_title`, poi `pack_add_hint` (posti liberi · tipo) e `BusyBanner` durante la conversione.
  - A posti esauriti: riga `pack_add_full` e tessere non selezionate disattivate.
  - FAB `pack_add_fab`. Vuoto: `pack_add_empty`.
- Fatto quando: Indietro con una selezione la svuota; oltre i posti liberi le tessere sono disattivate e annunciate come tali.

### Cerca sul telefono, Immagini trovate, link, condivisione (P4)
- Cerca sul telefono:
  - Barra: freccia, `title_scan`, `action_rescan` quando i risultati sono caricati.
  - Caricamento: rotella + `scan_loading`.
  - Permesso mancante: `EmptyState(PhotoLibrary, scan_permission_title, scan_permission_body, action_grant_permission)`; se bloccato, `scan_permission_blocked` + `action_open_settings`.
  - Caricato: filtri, `scan_hint`, Seleziona/Deseleziona tutto, `StickerGrid` con `caption` = nome della cartella.
  - Vuoto: `scan_empty` + `action_rescan`.
  - FAB `action_import_count`.
  - Nuovi parametri: `permissionBlocked`, `onOpenAppSettings`.
- Dialogo link: `link_hint`; campo URL con azione «Vai» sulla tastiera; conferma `link_search` «Continua».
- Immagini trovate:
  - Sotto il titolo `link_source` «Da %s» con l'host della pagina.
  - Griglia di `StickerTile` con il nome come didascalia; FAB `action_import_count`.
  - `SelectableRow.kt` va eliminato.
- Condividi:
  - Il Toast distingue nuovi · `msg_duplicates_count` · `msg_failed_count`; se non c'è niente, `share_nothing`.
  - `err_video_unreadable` al posto del testo fisso.

### Editor (P5: `EditorScreen.kt`)
1. Freccia (disattivata durante la conversione); titolo `title_editor` «Ritaglia» per le immagini, `title_editor_video` per i video. `BackHandler(enabled = converting) {}`.
2. Anteprima fuori dallo scroll:
   - Forma large, fondo nero; altezza `(altezza disponibile × 0,45)` fra 240 e 380 dp, misurata con `BoxWithConstraints`.
   - Presa delle maniglie: tolleranza 24 dp (zona di 48 dp). Su riquadri piccoli gli angoli hanno la precedenza.
3. Chip `editor_full_image`, `editor_make_square`.
4. Area scorrevole:
   - Video: rilevamento, proposte (miniature 72 dp, `editor_proposal_a11y`), `SectionHeader(editor_manual_title)`.
   - Suggerimento `editor_hint_*`.
   - Intervallo da animare: `editor_range`, `RangeSlider`, `editor_range_values`, `editor_duration`.
   - Nome dello sticker.
   - Per uno sticker della libreria: switch `editor_keep_original` con `editor_keep_original_help`.
5. Barra fissa in basso (`navigationBarsPadding().imePadding()`):
   - In conversione: avanzamento con testo.
   - Video con proposte: `Button(editor_create_all)` e `OutlinedButton(editor_create)`.
   - Altrimenti: `Button(editor_create)` a tutta larghezza.
- Fatto quando: su 360×640 dp «Crea sticker» si vede senza scorrere; Indietro di sistema durante la conversione non fa nulla.

### Crea pack (P6: `ExportDialogs.kt`)
- `ExportDialog` diventa un `ModalBottomSheet` (`skipPartiallyExpanded = true`):
  1. `export_title` «Crea pack» + sottotitolo plurale `export_subtitle`.
  2. Tipo, solo se ci sono animati:
     - Con fermi e animati: `export_mix_title` + tre radio con supporto (`export_mix_all_animated` consigliato, `export_mix_separate`, `export_mix_all_static`).
     - Solo animati: `export_kind_title` (`export_kind_animated` consigliato, `export_mix_all_static`).
     - Righe intere cliccabili, alte almeno 56 dp.
  3. Destinazione per gruppo: menu a tendina con `export_group_*_n`, voci `export_target_new` e `export_target_existing`.
  4. Solo se si crea un pack nuovo: `SectionHeader(export_new_pack_section)`, nome e autore.
  5. Riepilogo `export_summary_*`.
  6. Avvisi `export_warning_small`, `export_no_whatsapp` in `errorContainer`.
  7. Pulsanti: Annulla + `action_convert` «Crea pack», oppure `export_confirm_existing` «Aggiungi al pack» se si aggiunge solo a pack esistenti.
- `ProgressDialog`: `progress_title`, barra determinata, `progress_body`, Annulla; non si chiude toccando fuori.
- `ResultDialog`:
  - Titolo plurale `result_title_n`.
  - Una riga per pack, con il pulsante WhatsApp o `StatusChip(Warning)`.
  - Eventuali `result_failures`.
  - `action_done` «Fatto» + `action_open_packs`.
  - L'apertura automatica di WhatsApp per un pack solo resta.
- `isAnyWhatsAppInstalled` non va chiamato durante la composizione.
- Fatto quando: con soli animati non compare «fermi e animati insieme»; con soli pack esistenti il pulsante dice «Aggiungi al pack» e nome e autore non compaiono.

## Overlay e notifiche (P7)

Nuovo `capture/OverlayStyle.kt` con:
- `dpPx`, `spPx`;
- sfondo a pillola;
- stili di testo in sp da `dimens.xml`;
- area di tocco minima di 48 dp;
- colori `R.color.overlay_*`.

**Bolla**
- Cerchio 56 dp `overlay_brand`, ombra 6 dp, icona sempre visibile.
- Contatore in un badge separato (`overlay_badge_*`).
- Stati: riposo; elaborazione (`overlay_processing` + anello che gira); esito «+n» (`overlay_success`, 2,5 s); errore «!» (`overlay_error`, 2,5 s).
- Pillola di esito accanto alla bolla, `FLAG_NOT_TOUCHABLE`, 2,5 s: `capture_status_created` (+ `capture_status_duplicates`), `capture_status_none`, `capture_failed`. Sostituisce i Toast di esito.
- Trascinamento oltre la soglia di sistema; al rilascio la bolla si aggancia al bordo più vicino (margine 8 dp) e resta dentro lo schermo meno gli inset.
- Pressione lunga con vibrazione: pillola di conferma `bubble_end_confirm` con «Termina». Azione di accessibilità equivalente; `contentDescription` `bubble_a11y`.

**Menu circolare**
- Scrim al 40% su tutto lo schermo.
- Raggio 96 dp, pulsanti da 56 dp: Punta e Tutti `overlay_brand`, Termina `overlay_neutral`.
- Etichette in pillola, 14 sp in grassetto.
- Comparsa scala 0,6→1 in 150 ms, spenta senza animazioni.

**Mirino Punta**
- Pillola in alto sotto la barra di stato (inset + 8 dp): `aim_hint`, `aim_hint_secondary`, «Annulla» alto 48 dp.
- Vibrazione al tocco.

**Blocco con conto alla rovescia**
- Pillola di stato:
  - `lock_label` + secondi `lock_seconds` a cifre tabellari;
  - `lock_sublabel`;
  - barra 4 dp `overlay_progress_fill` su `overlay_progress_track`;
  - riga degli avvisi (`setNotice`).
- Sopra la zona se c'è spazio, altrimenti sotto; a schermo intero sotto la barra di stato.
- **Nessun Toast fra la fotografia e la fine della registrazione.**
- Elaborazione: `lock_processing`; le tessere fatte hanno il riempimento e una spunta bianca.
- Testi in sp; `liveRegion` per TalkBack.

**Notifiche**
- Sessione: la notifica apre la Cattura con `EXTRA_OPEN_CAPTURE`.
- Risultato:
  - `setOnlyAlertOnce(true)`, titolo plurale `capture_result_title`, testo `capture_result_text` sul totale della sessione, niente `setSubText`.
  - Doppioni contati a parte; «aggiunto» solo per gli sticker davvero aggiunti.

Fatto quando:
- con «Tutti» e tessere tagliate, i fotogrammi salvati non contengono il messaggio;
- la bolla non esce dallo schermo;
- TalkBack annuncia bolla e blocco;
- a scala carattere 2,0 nessun testo degli overlay trabocca.

## P8 e P10
- P8: i motivi di `ImportResult.Failed` in `StickerLibrary.kt` letti da `getString(R.string.err_*)`.
- P10:
  - Togliere le stringhe e i parametri non più usati in `LibraryScreen`, `CaptureScreen`, `PacksScreen` e `MainActivity`.
  - Facoltativa: l'icona a tema (`ic_launcher_monochrome`).
  - Fatto quando lint non segnala risorse inutilizzate fra quelle tolte.

## Verifiche sul telefono
- Android 14 QPR2 e successivi: cosa succede se nel dialogo di registrazione si sceglie «una sola app».
- «Tutti» su un pannello con l'ultima fila tagliata: i messaggi non devono comparire negli sticker.
- TalkBack su Libreria, Dettaglio pack, Editor e overlay; scala carattere 1,3 e 2,0; tema scuro; un telefono con Android 8.
- La pillola della bolla non deve rubare tocchi a TikTok.

## Screenshot JVM
- Registrare: `export ANDROID_HOME=/opt/android-sdk; ./gradlew --no-daemon recordRoborazziDebug`. I PNG finiscono in `app/build/outputs/roborazzi/`.
- Gli scenari sono in `app/src/test/java/com/whatik/screenshots/` (dati finti in `FakeData.kt`).
- Se il download di Robolectric risponde 429, aggiungere `-Probolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2/`.
