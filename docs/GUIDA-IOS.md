# Whatik su iPhone: guida

Whatik prende gli sticker che salvi su TikTok scorrendo i video e li trasforma in pack per
WhatsApp. Questa guida spiega come installare l'app, come usarla e cosa fare se qualcosa non va.

## 1. Installare l'app

L'app non è sull'App Store. Il file è sempre allo stesso indirizzo e si aggiorna a ogni nuova
versione stabile:

**https://github.com/yarimartina/whatik/releases/download/ios-latest/Whatik.ipa**

Un'app fuori dall'App Store deve essere firmata da chi la installa. Il modo gratuito è
Sideloadly, che firma l'app con il tuo Apple ID.

### Con Sideloadly (gratis, serve un computer)

1. Sul computer installa Sideloadly da https://sideloadly.io. Su Windows serve anche iTunes, la versione scaricata dal sito Apple e non quella del Microsoft Store.
2. Scarica `Whatik.ipa` dal link qui sopra.
3. Collega l'iPhone con il cavo, sbloccalo e tocca *Autorizza* se te lo chiede.
4. Apri Sideloadly, trascina dentro `Whatik.ipa`, scrivi il tuo Apple ID e premi *Start*. La password serve solo a firmare l'app e va ad Apple.
5. Sull'iPhone apri *Impostazioni → Generali → VPN e gestione dispositivi*, tocca il tuo Apple ID e scegli *Autorizza*.
6. Da iOS 16 in poi: *Impostazioni → Privacy e sicurezza → Modalità sviluppatore*, attivala e riavvia l'iPhone.

Con un Apple ID gratuito l'app smette di aprirsi dopo 7 giorni: basta ripetere il punto 4.
Sideloadly può rinnovarla da solo se il computer resta acceso e l'iPhone è sulla stessa rete Wi-Fi.
AltStore, la versione classica, funziona allo stesso modo.

### Altre strade

| Strada | Cosa serve | Pro e contro |
| --- | --- | --- |
| TestFlight | Account Apple Developer da 99 €/anno di chi pubblica l'app | Basta un link d'invito e l'app TestFlight, niente computer |
| AltStore PAL (solo Unione Europea) | Lo stesso account e la notarizzazione di Apple | Si aggiunge un link nell'app AltStore PAL, niente computer |
| Simulatore su Mac | Xcode | Per provarla senza iPhone: file `Whatik-simulator.zip` nella stessa release |

Il fatto di essere nell'Unione Europea non basta per installare la IPA direttamente: le regole
europee chiedono comunque che l'app sia notarizzata da Apple, e per notarizzarla serve
l'account a pagamento.

## 2. Catturare gli sticker

Su iPhone nessuna app può disegnare sopra TikTok o registrare lo schermo da sola, quindi si
usa il registratore di iOS e Whatik analizza il video dopo.

### Tutto il pannello (consigliato)

1. Se nel Centro di Controllo manca la registrazione dello schermo: *Impostazioni → Centro di Controllo → Registrazione schermo*.
2. Su TikTok apri i commenti di un video, tocca l'icona degli sticker e scegli *Salvati* o *Usati di recente*.
3. Apri il Centro di Controllo e avvia la registrazione.
4. Torna sul pannello e **tienilo fermo 6–8 secondi senza scorrere**: Whatik deve vedere almeno due giri di ogni animazione.
5. Ferma la registrazione toccando la barra rossa in alto.
6. In Whatik, scheda **Cattura → Scegli la registrazione**.

Whatik trova le tessere del pannello, capisce quali sono animate e quanto dura il loro loop,
rende trasparente lo sfondo bianco e ti mostra l'elenco: togli la spunta a quelli che non vuoi
e tocca **Crea**. Il video che continua a girare sopra il pannello non dà fastidio.

Se l'ultima fila è nascosta dalla barra in basso, quegli sticker vengono saltati con un
avviso: scorri il pannello e fai un'altra registrazione. Gli sticker già presi non si duplicano.

### Solo sticker fermi

Uno screenshot del pannello basta: **Cattura → Scegli lo screenshot**. Ogni tessera diventa uno
sticker con lo sfondo trasparente.

### Altri modi

Dalla scheda **Libreria**, tasto **+**: foto e GIF dal rullino, file (anche WebP), link a
un'immagine o a una pagina, oppure *Incolla* dagli appunti.

## 3. Sistemare uno sticker

Tocca uno sticker in Libreria per aprire l'editor:

- trascina i bordi o gli angoli del riquadro per ridimensionarlo, l'interno per spostarlo;
- *Rendi quadrato* e *Tutta l'immagine* rimettono il riquadro in un colpo;
- per gli sticker animati due cursori scelgono l'inizio e la fine;
- *Rendi trasparente lo sfondo uniforme* toglie il colore di fondo attorno al soggetto;
- *Sostituisci l'originale* evita di avere due copie.

## 4. Creare i pack e aggiungerli a WhatsApp

1. In **Libreria** tocca *Seleziona*, scegli gli sticker (o *Tutti*) e poi **Copia su WhatsApp**.
2. Scegli nome, autore ed emoji. Se ci sono sticker fermi e animati insieme scegli come trattarli; il default è un solo pack animato, come fa Sticker Maker.
3. Puoi aggiungere gli sticker a un pack che esiste già o crearne uno nuovo.
4. Nella scheda **Pack** apri il pack e tocca **Aggiungi a WhatsApp**: si apre WhatsApp e chiede di confermare.

WhatsApp accetta pack da 3 a 30 sticker. Dal dettaglio di un pack puoi aggiungere sticker
dalla libreria, toglierne, rinominarlo, unirlo ad altri pack o eliminarlo.

## 5. Se qualcosa non va

| Problema | Cosa fare |
| --- | --- |
| L'app non si apre dopo una settimana | Con l'Apple ID gratuito la firma dura 7 giorni: reinstallala con Sideloadly |
| "Sviluppatore non attendibile" | *Impostazioni → Generali → VPN e gestione dispositivi* → autorizza il tuo Apple ID |
| Sideloadly non vede l'iPhone | Cavo dati, iPhone sbloccato, *Autorizza questo computer*; su Windows iTunes dal sito Apple |
| Nessuno sticker trovato | Registra di nuovo tenendo il pannello fermo almeno 6 secondi |
| Uno sticker animato è fermo | Il loop era più lungo della parte ferma del video: registra più a lungo |
| Mancano gli sticker dell'ultima fila | Erano tagliati dal bordo: scorri il pannello e registra di nuovo |
| "Aggiungi a WhatsApp" non fa nulla | Serve WhatsApp installato e un pack con almeno 3 sticker |

Le segnalazioni di bug vanno nelle issue del repository: https://github.com/yarimartina/whatik/issues

## 6. Per chi sviluppa

- Codice: cartella `ios/`; dettagli tecnici in `ios/README.md`.
- Branch: `ios/main` è la versione stabile, le correzioni vanno in `ios/fix/<descrizione>` (vedi `docs/BRANCH.md`).
- Ogni push su `ios/main` compila, esegue i test nel simulatore, fotografa le schermate e aggiorna il link della IPA.
