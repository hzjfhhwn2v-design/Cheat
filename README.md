# FlipScout

Mod per **Hypixel SkyBlock** che cerca aste BIN e occasioni al bazaar sotto prezzo e te le segnala in chat.
Clicchi la notifica e si apre l'asta (`/viewauction <id>`) oppure il bazaar (`/bz <oggetto>`).

> **Non compra niente da solo.** Mostra informazioni e, quando clicchi, apre la schermata: l'acquisto lo fai tu.
> Hypixel vieta i mod che comprano o cliccano al posto tuo; questo non lo fa. Usalo comunque a tuo rischio.

Disponibile per **Forge 1.8.9** e per **Fabric** dalla 1.21.7 alla 26.3 (con Fabric API).

## Cosa segnala

| Tipo | Quando arriva la notifica |
|---|---|
| **AH** | Un'asta BIN appena messa in vendita costa meno di quanto l'oggetto si rivende, al netto delle tasse, con il profitto e la percentuale minimi che hai impostato. |
| **BZ flip** | Mettendo un buy order e una sell offer il margine dopo la tassa è alto e il prodotto si scambia abbastanza all'ora. |
| **BZ crollo** | Il prezzo di acquisto istantaneo è sceso molto sotto la mediana dell'ultima ora e mezza (qualcuno ha svenduto). |
| **BZ→NPC** | Comprare al bazaar e rivendere a un NPC rende. |

Ogni notifica mostra prezzo, valore stimato, profitto e vendite al giorno; **passandoci sopra col mouse** vedi da dove viene
la stima (BIN più basso degli altri venditori, mediana delle vendite, NEU, Coflnet) e le tasse.

Esempio:

```
[FlipScout] AH Hyperion » 820M → 900M | +48.5M (5.9%) | vol 6.5/g | 4s [APRI]
[FlipScout] BZ crollo Enchanted Diamond » compra a 120.5 (di solito 162, -26%) | ~+1.2M [BAZAAR]
```

L'ultima parte della riga AH (`4s`) dice da quanto tempo l'asta è in vendita.

## Quanto è veloce

L'API di Hypixel pubblica le aste nuove **una volta al minuto**: nessun mod che usa l'API può vederle prima di
quell'aggiornamento. FlipScout cerca di non aggiungere ritardi suoi:

- poco prima dell'aggiornamento previsto chiede ogni mezzo secondo se è uscito, tenendo conto della differenza tra
  l'orologio del PC e quello di Hypixel. Finché non è cambiato legge solo l'inizio della risposta e la chiude;
- appena esce legge le prime pagine, dove finiscono le aste nuove, e ti avvisa subito: di solito entro un secondo circa;
- la scansione completa delle aste, le vendite, il bazaar e le richieste a Coflnet vanno in parallelo e non bloccano
  gli avvisi. Solo quando un flip si basa su un unico prezzo il mod chiede prima conferma a Coflnet (di solito meno di
  un secondo).

In pratica un'asta ti arriva in chat tra 1 e 60 secondi circa dopo la messa in vendita, a seconda di quando cade
rispetto all'aggiornamento di Hypixel. Chi guarda l'asta in gioco la vede subito, quindi i flip più evidenti a volte
spariscono prima che tu clicchi.

## Installazione

1. Scarica il jar dalla pagina **Actions** del repository: apri l'ultima esecuzione riuscita di **Build** e in fondo,
   sotto *Artifacts*, prendi:
   - `flipscout-forge-1.8.9` per Forge 1.8.9;
   - per Fabric (serve anche [Fabric API](https://modrinth.com/mod/fabric-api)) quello della tua versione:

     | Artifact | Versioni di Minecraft |
     |---|---|
     | `flipscout-fabric-1.21.8` | 1.21.7, 1.21.8 |
     | `flipscout-fabric-1.21.10` | 1.21.9, 1.21.10 |
     | `flipscout-fabric-1.21.11` | 1.21.11 |
     | `flipscout-fabric-26.1.2` | 26.1.x |
     | `flipscout-fabric-26.2` | 26.2.x |
     | `flipscout-fabric-26.3` | 26.3.x |
2. Estrai lo zip e metti il `.jar` nella cartella `mods`.
3. Entra su Hypixel. Al primo avvio il mod scarica tutte le aste (qualche decina di MB): finché non ha finito non
   arrivano notifiche delle aste. `/flipscout status` mostra a che punto è.

Per compilarlo da solo:

```bash
cd forge-1.8.9 && ./gradlew build    # Java 17 per Gradle; Java 8 lo scarica da solo
cd fabric-1.21 && ./gradlew build    # Gradle su Java 25; per un'altra 1.21.x: -PminecraftVersion=... -PfabricVersion=...
cd fabric-26 && ./gradlew build      # Java 25; Minecraft 26.x
```

Il jar finisce in `build/libs`. Il codice comune sta in `core/` (`cd core && ./gradlew test` per i test), quello
comune alle build Fabric in `fabric-common/`; le versioni usate per ogni build Fabric sono in
`.github/workflows/build.yml`.

## Comandi

`/fsc` è l'abbreviazione di `/flipscout`. Il tab completa comandi, impostazioni e ID degli oggetti.

| Comando | Cosa fa |
|---|---|
| `/flipscout` | stato in breve |
| `/flipscout on` / `off` | accende o spegne tutto (spento non fa nessuna richiesta) |
| `/flipscout ah on\|off` | notifiche delle aste |
| `/flipscout bz on\|off` | notifiche del bazaar; anche `bz flip\|crash\|npc on\|off` per i singoli tipi |
| `/flipscout top [ah\|bz] [n]` | le migliori occasioni di questo momento, anche quelle già in vendita da tempo |
| `/flipscout recent` | ultime 30 notifiche, ancora cliccabili |
| `/flipscout open` | apre l'ultima notifica (su Forge c'è anche un tasto in *Controlli → FlipScout*) |
| `/flipscout set <chiave> <valore>` | cambia un'impostazione, es. `set ah.minProfit 2m` |
| `/flipscout config [filtro]` | mostra le impostazioni (clic per modificarle) |
| `/flipscout blacklist add\|remove\|list [ID]` | oggetti da ignorare, es. `blacklist add HYPERION` |
| `/flipscout status` | stato di ogni fonte dati ed eventuali errori |
| `/flipscout reload` | rilegge il file delle impostazioni |
| `/flipscout test` | mostra una notifica di prova |

## Impostazioni

Stanno in `config/flipscout/flipscout.properties` (ogni voce è commentata). Puoi modificarle lì e fare
`/flipscout reload`, oppure usare `/flipscout set`. Gli importi accettano `500k`, `2m`, `1.5b`.

| Chiave | Predefinito | Significato |
|---|---|---|
| `budget` | `0` | spesa massima per un flip (0 = nessun limite) |
| `onlyInSkyblock` | `on` | notifiche solo quando sei in SkyBlock |
| `sound` | `on` | suono a ogni notifica |
| `blacklist` | vuota | ID ignorati, separati da virgola |
| `ah.minProfit` | `500k` | profitto minimo dopo le tasse |
| `ah.minPercent` | `5` | profitto minimo in % del prezzo |
| `ah.minVolume` | `1` | vendite al giorno minime (se il dato è noto) |
| `ah.requireVolume` | `off` | scarta gli oggetti di cui non si conoscono le vendite |
| `ah.strategy` | `conservative` | `conservative` = il minore tra BIN più basso e mediana vendite; `lbin`; `sales` |
| `ah.fullScanMinutes` | `30` | ogni quanto riscaricare tutte le aste |
| `ah.pollPages` | `2` | pagine di aste nuove controllate ogni minuto |
| `bz.intervalSeconds` | `20` | ogni quanto controllare il bazaar |
| `bz.flip.minMarginPercent` | `5` | margine minimo dei flip con ordini |
| `bz.flip.minProfitPerHour` | `1m` | profitto potenziale minimo all'ora |
| `bz.crash.dropPercent` | `20` | calo minimo per segnalare un crollo |
| `bz.crash.minProfit` | `500k` | profitto minimo stimato di un crollo |
| `bz.npc.minProfit` | `200k` | profitto minimo di un flip bazaar → NPC |
| `source.neu` / `source.coflnet` | `on` | usa i dati di NEU e di Coflnet |

Le tasse (`ah.fee.*`, `bz.tax`) sono quelle attuali di Hypixel: creazione asta 1% sotto i 10M, 2% fino a 100M,
2,5% sopra, più 1% sul ritiro dei coins da 1M in su; bazaar 1,25%. Se Hypixel le cambia, aggiornale lì.

## Come stima i prezzi

Fonti:

- **API ufficiale di Hypixel** (pubblica, senza chiave): tutte le aste attive, le aste vendute nell'ultimo minuto,
  il bazaar e i prezzi di vendita agli NPC. Con le vendite il mod costruisce un suo storico, salvato in
  `config/flipscout/sales.bin`, quindi le stime migliorano dopo qualche ora di gioco.
- **NotEnoughUpdates** (`moulberry.codes`): BIN più basso e media delle vendite degli ultimi 3 giorni.
- **Coflnet** (`sky.coflnet.com`): mediana delle vendite, chiesta solo per confermare un possibile flip quando mancano
  dati propri, con cache e un limite di richieste al minuto.

Il valore di un oggetto (strategia `conservative`) è il **minore** tra il BIN più basso degli *altri* venditori e la
mediana delle vendite recenti: un oggetto vale quanto si vende, ma non più di quanto lo si può comprare. Se c'è un solo
riferimento serve il doppio della percentuale minima e la notifica ha un ⚠. Un oggetto del bazaar messo all'asta viene
valutato al prezzo di vendita istantanea del bazaar.

Per non confrontare cose diverse: i pet sono raggruppati per tipo, rarità e fascia di livello (e skin), i libri per
incantesimo e livello, le rune per tipo e livello, le armature con attributi per combinazione di attributi.

## Da sapere

- `/bz` lontano dall'hub richiede il Booster Cookie.
- Su Fabric 1.21.6 e successive, se Hypixel non dichiara il comando `/viewauction` Minecraft chiede conferma prima
  di eseguirlo (un clic in più). `/flipscout open` lo invia direttamente.
- Consumo di rete: qualche MB al minuto, soprattutto per la scansione completa delle aste (i controlli ogni mezzo
  secondo leggono solo l'inizio della risposta). Per ridurlo alza `ah.fullScanMinutes` e `bz.intervalSeconds`, oppure
  metti `ah.pollPages` a 1.
- Se una fonte non risponde il mod continua con le altre; `/flipscout status` mostra quale ha problemi. Gli indirizzi
  delle fonti sono modificabili (`url.hypixel`, `url.neu`, `url.coflnet`).
- Sul launcher ufficiale la 1.8.9 gira con un Java 8 vecchio che non riconosce alcuni certificati HTTPS recenti:
  il mod include le radici necessarie e le usa solo per le proprie connessioni.
