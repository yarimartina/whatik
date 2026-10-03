# Organizzazione dei branch

Un solo repository con le due app: Android nella cartella `app/`, iPhone in `ios/`, documenti
in `docs/`. I branch separano le **linee di sviluppo** delle due piattaforme: ognuna ha la sua
build stabile e i suoi rami per correzioni e novità.

## L'albero

```
main                                  progetto completo, riceve solo versioni stabili
├── android/main                      Android stabile → APK al link fisso
│   ├── android/fix/<descrizione>     un bug Android per ramo
│   └── android/feature/<descrizione> una novità Android per ramo
└── ios/main                          iPhone stabile → IPA al link fisso
    ├── ios/fix/<descrizione>         un bug iOS per ramo
    └── ios/feature/<descrizione>     una novità iOS per ramo
```

Le modifiche che toccano entrambe le piattaforme o solo i documenti (per esempio questa pagina)
vanno in un ramo `common/<descrizione>` nato da `main`.

## Regole sui nomi

- Prefisso della piattaforma, poi il tipo, poi una descrizione breve: `android/fix/tessere-tagliate`, `ios/feature/condividi-da-tiktok`.
- Descrizione in minuscolo, parole separate da trattini, senza date né nomi di persone.
- Tipi ammessi: `fix` per un bug, `feature` per una novità.
- Un ramo per bug o per novità: si chiude con l'unione e poi si cancella.

## Cosa fa la CI su ogni branch

| Branch | Android (`android.yml`) | iPhone (`ios.yml`) | Link pubblici |
| --- | --- | --- | --- |
| `android/main` | test, APK, pubblicazione | non parte | aggiorna l'APK |
| `ios/main` | non parte | test, IPA, schermate, pubblicazione | aggiorna l'IPA |
| `android/fix/…`, `android/feature/…` | test e APK come artefatto | non parte | invariati |
| `ios/fix/…`, `ios/feature/…` | non parte | test e IPA come artefatto | invariati |
| `main` | test e APK come artefatto | test e IPA come artefatto | invariati |
| tag `android-v1.2.0` | release con l'APK di quella versione | non parte | nuova release |
| tag `ios-v1.0.0` | non parte | release con IPA e schermate | nuova release |

Le build Android partono solo se cambia qualcosa fuori da `ios/`; quelle iPhone solo se cambia
qualcosa in `ios/` o nel loro workflow. Così una correzione su una piattaforma non ricompila
l'altra.

Link fissi, aggiornati solo dai rispettivi `main`:

- APK: https://github.com/yarimartina/whatik/releases/latest/download/whatik-debug.apk
- IPA: https://github.com/yarimartina/whatik/releases/download/ios-latest/Whatik.ipa

Gli artefatti dei rami di prova si scaricano dalla pagina della run in GitHub Actions, sezione
*Artifacts*: chi usa il link fisso non riceve build non finite.

## Correggere un bug

Esempio su Android; per iPhone basta sostituire `android` con `ios`.

```bash
git switch android/main && git pull
git switch -c android/fix/tessere-tagliate
# ... modifiche, commit ...
git push -u origin android/fix/tessere-tagliate
```

1. La CI compila e testa il ramo; l'APK di prova è tra gli artefatti della run.
2. Quando la correzione funziona, si apre una pull request verso `android/main` e la si unisce.
3. La CI di `android/main` pubblica l'APK al link fisso.
4. Si porta la versione stabile in `main` con una pull request da `android/main` a `main`.
5. Si cancella il ramo `android/fix/tessere-tagliate`.

Se un bug riguarda la logica comune alle due app (per esempio il rilevatore degli sticker,
che esiste in Kotlin e in Swift), si apre un ramo `fix` per ciascuna piattaforma con la stessa
descrizione: `android/fix/loop-troppo-corto` e `ios/fix/loop-troppo-corto`.

## Versioni

Le versioni stabili si segnano con un tag sul rispettivo `main`:

```bash
git switch android/main && git tag android-v1.1.0 && git push origin android-v1.1.0
git switch ios/main && git tag ios-v1.0.0 && git push origin ios-v1.0.0
```

## Stato attuale

- Il branch predefinito di GitHub è `main`. I vecchi branch `ios` e `ccr-9919d90b-bs5jr4` sono stati cancellati.
- `main`, `android/main` e `ios/main` hanno gli stessi file, con le correzioni `android/fix/sfondo-sticker` (i bordi bianchi si tolgono senza bucare lo sfondo di un meme, anche su iPhone) e `android/fix/punta-delinea-sticker` (Punta evidenzia e ritaglia lo sticker toccato).
- I branch di correzione già uniti (`android/fix/...`) si possono cancellare da *Code → Branches* quando non servono più.
- Facoltativo: proteggere `main`, `android/main` e `ios/main` da *Settings → Branches*; in quel caso si aggiornano solo con pull request.
