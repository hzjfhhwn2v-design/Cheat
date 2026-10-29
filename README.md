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
| **BZ flip** | Mettendo un buy order e una sell offer il margine dopo la tassa è alto e il prodotto si scambia abbastanza all'ora. Se il prezzo di vendita è oltre il 25% sopra il solito (qualcuno tiene il prezzo alto) il flip viene scartato; nei primi 15 minuti, senza storico, si scartano i margini sopra il 100%. |
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

L'API di Hypixel prepara le aste nuove **ogni 60 secondi** e le pubblica circa **8 secondi dopo** (misurato): nessun mod
che usa l'API può vederle prima. FlipScout cerca di non aggiungere ritardi suoi:

- impara quando Hypixel pubblica e controlla proprio in quel momento, tenendo conto della differenza tra l'orologio
  del PC e quello di Hypixel. Finché non è cambiato legge solo l'inizio della risposta e la chiude;
- appena esce legge le prime pagine, dove finiscono le aste nuove, e ti avvisa subito: di solito entro un secondo;
- la scansione completa delle aste (45 pagine in circa 3 secondi), le vendite, il bazaar e le richieste a Coflnet vanno
  in parallelo e non bloccano gli avvisi. Solo quando un flip si basa su un unico prezzo il mod chiede prima conferma a
  Coflnet (di solito meno di un secondo).

In pratica un'asta ti arriva in chat tra 9 e 70 secondi circa dopo la messa in vendita (in media una quarantina), a
seconda di quando cade rispetto all'aggiornamento di Hypixel. `/flipscout status` mostra il ritardo misurato. Chi
guarda l'asta in gioco la vede subito, quindi i flip più evidenti a volte spariscono prima che tu clicchi.

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
| `/flipscout recent` | ultime 30 notifiche, ancora cliccabili; segna le aste già vendute e dopo quanti secondi |
| `/flipscout open` | apre l'ultima notifica, o ti avvisa se l'asta è già stata comprata (su Forge c'è anche un tasto in *Controlli → FlipScout*) |
| `/flipscout guadagni` | quanto hai guadagnato: sessione, oggi, totale |
| `/flipscout sessione [pausa\|riprendi\|reset\|storico]` | la sessione: dettagli, pausa, ripresa, reset (la vecchia va nello storico) |
| `/flipscout totale [reset]` | totale e ultimi 7 giorni; `totale reset conferma` lo azzera |
| `/flipscout flip` | gli ultimi 10 flip chiusi: comprato, venduto, profitto |
| `/flipscout magazzino [svuota]` | cose comprate e non ancora rivendute, e vendite da ritirare |
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
| `ah.upgrades` | `on` | conta il valore degli upgrade (vedi sotto) |
| `ah.upgradePercent` | `80` | quanta parte del valore degli upgrade contare, in % |
| `ah.fullScanMinutes` | `30` | ogni quanto riscaricare tutte le aste |
| `ah.pollPages` | `2` | pagine di aste nuove controllate ogni minuto |
| `bz.intervalSeconds` | `20` | ogni quanto controllare il bazaar |
| `bz.flip.minMarginPercent` | `5` | margine minimo dei flip con ordini |
| `bz.flip.minProfitPerHour` | `1m` | profitto potenziale minimo all'ora |
| `bz.crash.dropPercent` | `20` | calo minimo per segnalare un crollo |
| `bz.crash.minProfit` | `500k` | profitto minimo stimato di un crollo |
| `bz.npc.minProfit` | `200k` | profitto minimo di un flip bazaar → NPC |
| `source.neu` / `source.coflnet` | `on` | usa i dati di NEU e di Coflnet |
| `tracker.enabled` | `on` | riconosce in chat acquisti e vendite e calcola i guadagni |
| `tracker.messages` | `on` | scrive in chat quando registra un acquisto all'asta o chiude un flip |

Le tasse (`ah.fee.*`, `bz.tax`) sono quelle attuali di Hypixel: creazione asta 1% sotto i 10M, 2% fino a 100M,
2,5% sopra, più 1% sul ritiro dei coins da 1M in su; bazaar 1,25%. Se Hypixel le cambia, aggiornale lì.

## Come stima i prezzi

Fonti:

- **API ufficiale di Hypixel** (pubblica, senza chiave): tutte le aste attive, le aste vendute nell'ultimo minuto,
  il bazaar e i prezzi di vendita agli NPC. Con le vendite il mod costruisce un suo storico, salvato in
  `config/flipscout/sales.bin`, quindi le stime migliorano dopo qualche ora di gioco.
- **NotEnoughUpdates** (`moulberry.codes`): BIN più basso e media delle vendite degli ultimi 3 giorni.
- **Coflnet** (`sky.coflnet.com`): mediana delle vendite, per gli oggetti di cui il mod non ha ancora vendite sue né la
  media di NEU. La chiede in anticipo, in background, partendo dagli oggetti più costosi (al massimo 6 richieste al
  minuto, ogni risposta vale 6 ore), e per confermare un possibile flip. In tutto al massimo 15 richieste al minuto.

Il valore di un oggetto (strategia `conservative`) è il **minore** tra il BIN più basso degli *altri* venditori e la
mediana delle vendite recenti: un oggetto vale quanto si vende, ma non più di quanto lo si può comprare. Se c'è un solo
riferimento serve il doppio della percentuale minima e la notifica ha un ⚠; se quel riferimento è il BIN più basso,
servono anche almeno 3 altre aste dello stesso oggetto, perché con una o due spesso è solo un prezzo esagerato. E se
in quel caso il margine supera il 100%, il flip arriva solo se Coflnet lo conferma: per gli oggetti scambiati di rado
capita che tutte le aste in vendita siano gonfiate. Un oggetto del bazaar messo all'asta viene valutato al prezzo di
vendita istantanea del bazaar.

Per non confrontare cose diverse: i pet sono raggruppati per tipo, rarità e fascia di livello (e skin), i libri per
incantesimo e livello, le rune per tipo e livello, le armature con attributi per combinazione di attributi, le armi
di Mida per fascia dell'offerta vinta all'asta nera, i party hat per colore, emoji e anno.

**Upgrade.** Un'arma piena di upgrade messa al prezzo di una base è un'occasione. Il mod riconosce:

- recombobulator, hot e fuming potato book;
- stelle: quelle a essenza (e oggetti) secondo i costi che Hypixel pubblica per ogni oggetto, e le master star dopo la
  quinta stella degli oggetti da dungeon. Le stelle in più delle armature del Crimson Isle si pagano con essenza
  crimson, non con master star;
- incantesimi che non vengono dal tavolo, gemme;
- pergamene della Necron's Blade, Art of War e Art of Peace, Wood Singularity, Farming for Dummies, Transmission
  Tuner e simili;
- tinture, skin applicate, parti dei trapani e delle canne da pesca, oggetti tenuti dai pet.

Ogni pezzo vale il suo prezzo (vendita istantanea al bazaar, altrimenti all'asta) per una percentuale che dipende dal
tipo: 100% per master star, gemme e pergamene, 85% per gli incantesimi, 80% per il recombobulator, 75% per l'essenza
delle stelle, e così via.

- **Cosa conta:** la differenza rispetto agli upgrade dell'asta più economica dello stesso oggetto, e di questa solo
  l'80% (`ah.upgradePercent`). Gli upgrade possono al massimo raddoppiare il valore.
- **Anche in meno:** se l'asta più economica ha più upgrade di quella che stai guardando, il valore scende (fino alla
  metà), così un oggetto base non sembra un affare solo perché le altre aste sono potenziate.
- **Più margine se serve:** se un'asta è un flip solo grazie agli upgrade, serve il doppio della percentuale minima e la
  notifica dice `+upgrade`.
- **Dettagli:** passando il mouse vedi i pezzi contati.

## Guadagni

Il mod legge i messaggi di Hypixel in chat e tiene il conto di quello che compri e vendi:

- **Aste:**
  - "You purchased …" quando compri un BIN;
  - "[Auction] … bought …" quando qualcuno compra la tua asta mentre sei online;
  - "You collected … coins from selling …" quando ritiri i coins, anche per le vendite fatte mentre eri offline.

  Ogni vendita conta una volta sola.
- **Bazaar:** acquisti e vendite istantanee, buy order e sell offer quando li ritiri, e i buy order "flippati"
  direttamente in sell offer (al prezzo del buy order).

Ogni vendita viene abbinata all'acquisto più vecchio dello stesso oggetto, anche se nel frattempo l'hai riforgiato. Il
profitto è l'incasso dopo le tasse meno quanto l'avevi pagato. Le vendite di cose che non hai comprato (drop, farming)
non contano nel profitto.

- **Sessione:** parte da sola. `/flipscout sessione pausa` smette di contare (il totale continua), `riprendi` riparte e
  `reset` la mette nello storico e ne inizia una nuova. Il tempo conta solo mentre giochi con la sessione attiva.
- **Oggi e totale:** contano sempre. `/flipscout totale reset conferma` azzera il totale.
- **Dove sta:** tutto è salvato in `config/flipscout/guadagni.json`.

Le aste vinte con un'offerta (non BIN) non hanno il prezzo nel messaggio, quindi non vengono contate come acquisti. Se un
acquisto o una vendita non viene riconosciuto, la riga esatta si trova in `logs/latest.log`: serve per correggere il
riconoscimento.

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
