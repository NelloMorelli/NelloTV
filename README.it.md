<p align="center">
  <img src="docs/icon.png" alt="Nello TV" width="160">
</p>

<h1 align="center">Nello TV</h1>

<p align="center">
  Un launcher su misura, in stile vetro, per Amazon Fire TV Stick.<br>
  <a href="README.md">English</a> · <b>Italiano</b>
</p>

---

## Indice

1. [Cos'è](#cosè)
2. [Funzioni](#funzioni)
3. [Requisiti](#requisiti)
4. [Installazione su una Fire TV](#installazione-su-una-fire-tv)
5. [Configurazione iniziale](#configurazione-iniziale)
6. [Come si usa](#come-si-usa)
7. [Impostazioni](#impostazioni)
8. [Sport, Sportio e WireGuard](#sport-sportio-e-wireguard)
9. [Aggiornamenti](#aggiornamenti)
10. [Compilare il progetto](#compilare-il-progetto)
11. [Pubblicare una versione](#pubblicare-una-versione)
12. [Struttura del progetto](#struttura-del-progetto)
13. [Problemi comuni](#problemi-comuni)

---

## Cos'è

Nello TV prende il posto della home di Amazon su una Fire TV Stick (provato su Fire TV Stick 4K Max, Fire OS 8 / Android 11). Riunisce film, serie, TV in diretta e sport in un unico posto, in stile vetro smerigliato, e apre ogni cosa nell'app che la riproduce (Nuvio, Sportio, RaiPlay, Mediaset Infinity…).

È un progetto personale: non riproduce contenuti da sé, mostra cosa c'è e apre l'app giusta.

## Funzioni

**Home e schede**
- **Esplora** — "Per te" (consigli dalla tua cronologia Nuvio, rinnovati ogni notte alle 3, più le partite della tua squadra, le sessioni della tua scuderia F1 e il film migliore di stasera su Sky Cinema), *Continua a guardare* e una griglia di app.
- **Film / Serie TV** — cataloghi TMDB (popolari, al cinema, in arrivo, di tendenza) e le Top 10 italiane per servizio di streaming. I titoli si aprono in Nuvio.
- **TV** — guida TV italiana: *In onda ora*, *Stasera*, Sky Cinema e Sky Intrattenimento. Rai apre RaiPlay, Mediaset apre Mediaset Infinity, Sky apre Sportio sul canale.
- **Sport** — partite di oggi (Serie A e squadre italiane in Europa), prossimi eventi, Formula 1, risultati; classifiche, calendario della tua squadra, pagina della partita.
- **Cerca** — film e serie, anche con la voce.

**Navigazione**
- Un grande carosello per scheda; **↑ / ↓** passano da un catalogo all'altro (i pallini a sinistra indicano dove sei), **↑** sul primo porta al menu in alto, **↓** sull'ultimo porta al dock. **Indietro** riporta sempre al menu in alto.
- Un **dock** di cinque app in basso (uno a parte per la scheda Sport), modificabile tenendo premuto su un'app.

**Menu in alto a destra**
- 🛡 stato della **VPN** e IP pubblico · 📶 **rete**, segnale e ping · 🔊 **uscita audio** (TV o cuffie Bluetooth, un tasto per cambiare) · ⚙ **impostazioni**.

**Aspetto**
- Interfaccia in vetro smerigliato sopra il tuo sfondo, selettore "liquido", pop-up che nascono dal pulsante premuto, font **Poppins**.
- **Salvaschermo sempre acceso**: lo sfondo si scurisce e compare un orologio in vetro liquido, che si sposta un poco ogni minuto per non rovinare lo schermo.
- Tutto personalizzabile: colore principale, intensità del vetro, sfondo (incluso o una tua immagine), angoli, dimensione delle card, schede e cataloghi visibili, nomi delle app, modalità notte.

**Extra**
- Avviso 10 minuti prima delle partite della tua squadra e delle sessioni della tua scuderia, sopra qualsiasi app: **OK** sul banner per guardarla.
- Sport e canali Sky si aprono in **Sportio** sul canale giusto, trovato tramite la guida TV; volendo con **WireGuard** acceso prima e spento al ritorno.
- Nello TV **si aggiorna da sola** dalle release di questo repository.
- Cache su disco di cataloghi e immagini: la home si apre subito e funziona anche offline.

## Requisiti

- Una **Amazon Fire TV Stick** (Fire OS 7 o 8).
- App con cui lavora (facoltative): **Nuvio** (film, serie, *Continua a guardare*), **Sportio** (sport e canali Sky), **WireGuard**, RaiPlay, Mediaset Infinity, DAZN, Netflix, Prime Video, YouTube.
- Per installare l'APK: **LocalSend** o **Downloader** sulla Fire TV, oppure un computer con `adb`.

## Installazione su una Fire TV

### Opzione A — solo l'APK (senza computer)

1. Sulla Fire TV: **Impostazioni → La mia Fire TV → Opzioni sviluppatore** → consenti le **app di origine sconosciuta** per LocalSend (o Downloader).
2. Invia l'APK (dall'ultima [release](../../releases)) con LocalSend, aprilo e installalo.
3. Apri **Nello TV** dalla lista delle app — solo questa prima volta.
4. Segui la [configurazione iniziale](#configurazione-iniziale). Da lì in poi il tasto Home porta a Nello TV.

### Opzione B — con un computer (`adb`)

1. Sulla Fire TV: **Impostazioni → La mia Fire TV → Opzioni sviluppatore → Debug ADB: attivo**, e annota l'indirizzo IP (**Info → Rete**).
2. Dalla cartella del progetto:
   ```sh
   ./install.sh 192.168.1.32
   ```
   Compila, installa e concede tutti i permessi in un colpo (servizio in background, impostazioni di sistema, aggiornamenti, WireGuard).

## Configurazione iniziale

Su una Fire TV nuova, Nello TV si apre con una breve configurazione. Finché non la completi, il tasto Home ti riporta al passo in cui eri.

1. **Permessi** — ognuno col suo stato (✓ o *Da attivare*); premi OK e si apre la pagina di sistema giusta:
   - **Servizio di Nello TV** (Accessibilità): tasto Home, ritorno dopo lo standby, salvaschermo e avvisi.
   - **Aggiornamenti automatici**: permette a Nello TV di installare le nuove versioni.
   - **Salvaschermo della Fire TV**: quello di Nello TV ne prende il posto, quindi deve essere attivo.
2. **La tua squadra** — una squadra di Serie A (o nessuna).
3. **Formula 1** — la scuderia che segui (o nessuna).
4. **Canali in Sportio** — scegli il numero della playlist e premilo: Sportio dovrebbe aprire RAI 1. Se invece apre la lista dei canali, premi Home e prova il numero successivo.
5. **Nuvio** — accedi con il QR, oppure salta.

Puoi rifarla da **Impostazioni → Generale → Configurazione iniziale**.

## Come si usa

| Dove | Tasto | Cosa succede |
|---|---|---|
| Menu in alto | ← → | cambia scheda |
| Menu in alto | ↓ | entra nella scheda |
| Carosello | ← → | scorre i titoli (in fondo riparte dall'inizio) |
| Carosello | ↑ / ↓ | catalogo precedente / successivo; oltre il primo → menu in alto, oltre l'ultimo → dock |
| Ovunque | Indietro | menu in alto |
| App | tieni premuto OK o ☰ | menu dell'app: sposta, aggiungi o togli dal dock, nascondi |
| Partita | tieni premuto OK o ☰ | pagina della partita (formazioni, eventi) |
| Pop-up | ↑ sulla prima riga | lo chiude e torna al suo pulsante |

## Impostazioni

Si aprono da ⚙ → **Impostazioni del launcher**: una schermata intera con le sezioni a sinistra e le opzioni a destra.

- **Generale** — sfondo, modalità notte, salvaschermo (acceso/spento, dopo quanto, prova), avvisi, aggiornamenti, configurazione iniziale.
- **Aspetto** — colore principale, vetro, oscura lo sfondo, angoli, dimensione card, velocità delle animazioni.
- **Schermata** — scheda iniziale, dock e info sotto le card, quali schede e cataloghi mostrare.
- **Dock e app** — dock, dock Sport, app nascoste, rinomina le app.
- **Sport e TV** — squadra del cuore, scuderia F1, playlist Sportio (con prova), WireGuard per lo sport, app VPN.
- **Account** — Nuvio Sync.
- **Fire TV** — *Nello TV come home*, *Apri la home di Amazon*, scorciatoie alle impostazioni della Fire TV.

## Sport, Sportio e WireGuard

- **Quale canale** — per una partita, Nello TV cerca nella guida TV un programma col nome delle due squadre vicino al calcio d'inizio (Sky Sport, DAZN, Rai, TV8…). Se la guida non lo ha: Serie A → DAZN 1, coppe europee → Sky Sport Uno, Coppa Italia → Italia 1. La F1 è sempre Sky Sport F1.
- **Come si apre** — con il link di Sportio `sportio://play/live?sourceId=<playlist>&name=<canale>`. I nomi dei canali devono essere quelli del tuo fornitore ("Sky Sport Calcio", "DAZN 1"…).
- **WireGuard** — se attivo in Impostazioni → Sport e TV e se WireGuard è installato, Nello TV accende il tunnel prima di aprire Sportio e lo spegne quando torni alla home (solo se l'ha acceso lei). In WireGuard va attivato **"Consenti app di controllo remoto"**. Usa un profilo diverso per ogni Fire TV: la stessa chiave su due dispositivi insieme fa cadere entrambi.

## Aggiornamenti

Ogni release di questo repository contiene l'APK. Nello TV controlla se ce n'è una più nuova all'avvio e in ⚙ (l'intestazione dice *aggiornato* o la nuova versione); un OK la scarica e la installa.

Il controllo confronta il **numero di build** (il tag della release, `v59`), quindi il nome della versione può restare lo stesso tra una release e l'altra.

## Compilare il progetto

1. Android SDK (compileSdk 34) e JDK 17.
2. Copia `local.properties.example` in `local.properties` e compilalo. **Questo file contiene le tue chiavi e non va mai sul repository.**

   | Chiave | Cos'è |
   |---|---|
   | `sdk.dir` | percorso dell'Android SDK |
   | `tmdb.key` | chiave API TMDB v3 (cataloghi, immagini, loghi) |
   | `topx.base` | URL di configurazione del tuo addon TopX (Top 10) |
   | `sports.base` | URL di configurazione del tuo addon sport |
   | `nuvio.key` | chiave pubblica di Nuvio Sync |
   | `update.repo` | `proprietario/repository` per gli aggiornamenti |
   | `update.token` | token GitHub a grana fine, **solo lettura**, per quel repository |
3. Compila:
   ```sh
   ./gradlew assembleRelease
   ```
   L'APK è in `app/build/outputs/apk/release/`.

## Pubblicare una versione

```sh
./release.sh "cosa è cambiato"
```

Salva le modifiche in sospeso, compila, fa il push e crea una release GitHub con l'APK, intitolata `Nello TV <versione> · build <n>`. Il nome della versione è l'unica riga di `version.properties` (es. `name=0.9.9`): cambiala quando vuoi un nuovo nome di versione.

## Struttura del progetto

```
app/src/main/java/com/nello/tvhome/
  MainActivity.kt        home: schede, caroselli, dock, menu in alto a destra
  Carousel.kt            il carosello e le sue card (titoli, partite, F1, TV)
  GlassView.kt, GlassPopup.kt, GlassPage.kt   superfici in vetro, pop-up, pagine
  FocusGroup.kt          il selettore liquido
  SettingsActivity.kt    impostazioni a schermo intero e preferenze del launcher
  OnboardingActivity.kt  configurazione iniziale
  WakeService.kt         tasto Home, ritorno dallo standby, banner (servizio di accessibilità)
  Screensaver.kt         salvaschermo e orologio in vetro liquido
  Sources.kt             TMDB e TopX
  NuvioSync.kt           Nuvio Sync (Continua a guardare, cronologia)
  Sport.kt, SportArt.kt, SportPagesActivity.kt   dati ESPN, immagini, classifiche
  Sportio.kt, TvGuide.kt link Sportio, canali e guida TV italiana
  Notifier.kt            avvisi sopra le altre app
  Updater.kt             aggiornamento automatico dalle release GitHub
  Theme.kt, Fonts.kt     personalizzazione e Poppins
```

## Problemi comuni

| Problema | Soluzione |
|---|---|
| Home apre la home di Amazon | Il servizio di Nello TV è spento: Impostazioni Fire TV → Accessibilità → Nello TV → attiva (o rifai la configurazione iniziale). |
| Una partita apre la lista dei canali di Sportio invece del canale | Numero di playlist sbagliato: Impostazioni → Sport e TV → *Playlist Sportio*, prova con *Prova: apri RAI 1*. |
| WireGuard non si accende | Attiva *Consenti app di controllo remoto* in WireGuard e concedi il permesso che Nello TV chiede. |
| Niente salvaschermo | Attiva il salvaschermo della Fire TV (Impostazioni Fire TV → Schermo e audio) e quello di Nello TV (Impostazioni → Generale). |
| Gli aggiornamenti non si installano | Consenti le "app di origine sconosciuta" per Nello TV. |

---

<p align="center"><sub>Progetto personale · font Poppins con licenza SIL Open Font License · dati da TMDB ed ESPN</sub></p>
