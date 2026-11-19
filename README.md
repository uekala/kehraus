# Kehraus

<img src="docs/logo.png" width="96" alt="Kehraus-Logo">

Android-App, die das eigene Handy aufräumt und sortiert, ohne von selbst etwas zu löschen.
Regeln finden, was weg könnte, und empfehlen es; in den Papierkorb kommt nur, was man bestätigt,
und der Papierkorb löscht nie von selbst.

Gedacht für das eigene Gerät, nicht für den Play Store. Die App hat keinen Internetzugang.

## Regeln

Regeln suchen, was weg könnte – sie verschieben nichts von selbst. Eingeschaltete Regeln erscheinen
als Empfehlung auf der Übersicht und in einer Meldung; jede Regel hat einen Knopf „In den Papierkorb“,
der erst nach einer Rückfrage verschiebt. Nach der Installation sind alle Regeln aus.

| Regel | Durchsucht | Voreinstellung |
| --- | --- | --- |
| Screenshots | Bildschirmfotos und Bildschirmaufnahmen | älter als 30 Tage |
| Große Videos | Videos in `DCIM`, `Movies`, `Pictures` | ab 200 MB, älter als 180 Tage |
| WhatsApp · Bilder und Videos | Bilder, Videos und GIFs | älter als 60 Tage |
| WhatsApp · Gesendete Kopien | die `Sent`-Ordner | älter als 14 Tage |
| WhatsApp · Sprachnachrichten und Audio | Sprachnachrichten, Audiodateien | älter als 90 Tage |
| WhatsApp · Dokumente | PDFs und andere Dateien | älter als 180 Tage |
| Telegram-Dateien | alles, was Telegram im Gerätespeicher ablegt | älter als 60 Tage |
| Installationsdateien | APK-Dateien im ganzen Gerätespeicher, außer in App-Ordnern | älter als 7 Tage |
| Downloads | alles im Download-Ordner | älter als 90 Tage |
| Exakte Duplikate | Dateien mit gleichem Inhalt im ganzen Gerätespeicher | ohne Altersgrenze |

Dazu als eigener Schalter: leere Ordner entfernen (in `Download`, `Pictures`, `DCIM`, `Movies`,
`Documents`, `Music`; werden direkt gelöscht, da nichts aufzuheben ist).

Ebenfalls als eigener Schalter: Downloads nach Art sortieren. Lose Dateien im Download-Ordner, die
älter als 7 Tage sind, wandern in Unterordner (Dokumente, Bilder, Videos, Audio, Archive,
Installationsdateien). Gelöscht wird dabei nichts, und die Sortierung lässt sich zurücknehmen.

Jede Regel zeigt vorab, wie viele Dateien sie im Moment treffen würde, und listet sie auf.
Versteckte Dateien und Ordner bleiben immer unberührt.

## Sicherungen

- **Nur empfehlen:** Der tägliche Lauf sortiert und zählt, verschiebt aber nichts in den Papierkorb.
- **Papierkorb ohne Frist:** Endgültig gelöscht wird nur, wenn man ihn selbst leert.
- **Schutzliste:** Ordner (mit allem darunter) und einzelne Dateien, die keine Regel anfasst.
- **Verlauf:** was wann einsortiert, verschoben oder endgültig gelöscht wurde.
- **Erinnerungen:** höchstens einmal pro Woche, wenn die Regeln mehr als 500 MB empfehlen oder im
  Papierkorb mehr als 1 GB liegt; dazu ein Hinweis bei weniger als 10 % freiem Speicher.

## Papierkorb

Was man bestätigt, wird nicht gelöscht, sondern nach `Kehraus/Papierkorb` im Gerätespeicher
verschoben. Dort bleibt es, bis man den Papierkorb leert – jede Datei lässt sich jederzeit an ihren
alten Platz zurücklegen. Platz wird also erst frei, wenn der Papierkorb geleert wird.

Die Liste, woher jede Datei kam (`index.tsv`), liegt neben den Dateien und nicht in den App-Daten.
Der Papierkorb übersteht deshalb auch eine Neuinstallation.

## Einrichten

1. APK aus dem neuesten [Release](../../releases) laden und installieren.
2. In der App „Zugriff erlauben“ antippen und den Zugriff auf alle Dateien einschalten.
3. Unter *Regeln* die Vorschau ansehen und einschalten, was passt.

Sortiert und geprüft wird einmal am Tag im Hintergrund oder sofort über „Jetzt sortieren und prüfen“.

## Bauen

Jeder Push auf `main` baut über GitHub Actions eine signierte APK und legt ein Release an;
Pull Requests werden gebaut und getestet. Lokal: `./gradlew testReleaseUnitTest assembleRelease`
(JDK 17, Android SDK 35).

Die Aufräum-Logik liegt in `app/src/main/java/de/uekala/kehraus/core` und kommt ohne Android aus;
die Tests dazu stehen in `app/src/test`.

## Signatur

`signing/sideload.jks` ist ein reiner Sideload-Schlüssel, damit Updates sich über die
vorherige Version installieren lassen. Bevor das Repo öffentlich wird, gehört der Schlüssel
in ein GitHub-Secret.

## Analyse

Der Reiter *Analyse* zeigt, wo der Platz hingeht: nach Dateiart, Ordner für Ordner zum Durchklicken
und als Liste der größten Dateien. Jede Datei lässt sich von dort in den Papierkorb legen oder schützen.
Apps und ihre Daten zählen nicht mit, darauf hat die App keinen Zugriff.

Exakte Duplikate (Inhalt Byte für Byte gleich) werden in Gruppen angezeigt. Es bleibt die Datei am
besten Platz – Kameraordner vor Bilderordnern vor Downloads vor Messenger-Ordnern, bei Gleichstand die
ältere. Die Kopien wandern auf Knopfdruck in den Papierkorb. Unter *Regeln* lassen sie sich zusätzlich
als Empfehlung einschalten.

Ebenfalls dort: Reste entfernter Apps – Ordner unter `Android/media`, deren App nicht mehr
installiert ist.

## Apps

Der Reiter *Apps* listet die installierten Apps mit Größe (App, Daten, Cache) und letzter Nutzung:
lange nicht genutzte Apps, die größten Caches und alle Apps nach Größe. Ein Tipp öffnet die
Systemseite der App mit „Cache leeren" und „Deinstallieren" – selbst kann die App fremde Apps weder
leeren noch entfernen. Sammeln sich mehr als 5 GB Cache an, erinnert die App höchstens einmal pro Woche daran. Nötig ist dafür einmalig der Nutzungsdatenzugriff in den Systemeinstellungen.

Ist der Schalter dort grau („eingeschränkte Einstellung"): unter *Einstellungen → Apps → Kehraus* oben
rechts auf die drei Punkte tippen und „Eingeschränkte Einstellungen zulassen" wählen. Android sperrt
solche Schalter bei Apps, die nicht aus einem Store kommen, zunächst.

## Geplant

- Ähnliche Fotos und Serienbilder, unscharfe Bilder
- Belege und Dokumentfotos erkennen und einsortieren
