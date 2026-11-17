# Kehraus

Android-App, die das eigene Handy von selbst aufräumt: Regeln legen fest, was weg kann,
ein Papierkorb mit Frist fängt alles auf, bevor es endgültig gelöscht wird.

Gedacht für das eigene Gerät, nicht für den Play Store. Die App hat keinen Internetzugang.

## Regeln

Jede Regel ist einzeln ein- und ausschaltbar. Nach der Installation sind alle aus.

| Regel | Durchsucht | Voreinstellung |
| --- | --- | --- |
| Screenshots | Bildschirmfotos und Bildschirmaufnahmen | älter als 30 Tage |
| Große Videos | Videos in `DCIM`, `Movies`, `Pictures` | ab 200 MB, älter als 180 Tage |
| WhatsApp · Bilder und Videos | Bilder, Videos und GIFs | älter als 60 Tage |
| WhatsApp · Gesendete Kopien | die `Sent`-Ordner | älter als 14 Tage |
| WhatsApp · Sprachnachrichten und Audio | Sprachnachrichten, Audiodateien | älter als 90 Tage |
| WhatsApp · Dokumente | PDFs und andere Dateien | älter als 180 Tage |
| Telegram-Dateien | alles, was Telegram im Gerätespeicher ablegt | älter als 60 Tage |
| Installationsdateien | APK-Dateien im Download-Ordner | älter als 7 Tage |
| Downloads | alles im Download-Ordner | älter als 90 Tage |

Dazu als eigener Schalter: leere Ordner entfernen (in `Download`, `Pictures`, `DCIM`, `Movies`,
`Documents`, `Music`; werden direkt gelöscht, da nichts aufzuheben ist).

Jede Regel zeigt vorab, wie viele Dateien sie im Moment treffen würde, und listet sie auf.
Versteckte Dateien und Ordner bleiben immer unberührt.

## Sicherungen

- **Papierkorb mit Frist:** nichts wird sofort gelöscht (siehe unten).
- **Schutzliste:** Ordner (mit allem darunter) und einzelne Dateien, die keine Regel anfasst.
- **Notbremse:** Würde eine Regel in einem Lauf mehr als 500 Dateien oder 5 GB verschieben, hält
  sie an und wartet auf eine Bestätigung in der App. Das betrifft vor allem den ersten Lauf.
- **Verlauf:** was wann von welcher Regel verschoben oder endgültig gelöscht wurde.
- **Speicher knapp:** Unter 10 % freiem Speicher weist die App darauf hin, wie viel im Papierkorb liegt.

## Papierkorb

Treffer werden nicht gelöscht, sondern nach `Kehraus/Papierkorb` im Gerätespeicher verschoben.
Erst nach der Frist (Voreinstellung 30 Tage) löscht der nächste Lauf sie endgültig – bis dahin
lässt sich jede Datei an ihren alten Platz zurücklegen. Platz wird also erst frei, wenn die Frist
abgelaufen ist oder der Papierkorb von Hand geleert wird.

Die Liste, woher jede Datei kam (`index.tsv`), liegt neben den Dateien und nicht in den App-Daten.
Der Papierkorb übersteht deshalb auch eine Neuinstallation.

## Einrichten

1. APK aus dem neuesten [Release](../../releases) laden und installieren.
2. In der App „Zugriff erlauben“ antippen und den Zugriff auf alle Dateien einschalten.
3. Unter *Regeln* die Vorschau ansehen und einschalten, was passt.

Aufgeräumt wird einmal am Tag im Hintergrund oder sofort über „Jetzt aufräumen“.

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

## Geplant

- Speicher-Analyse, doppelte und ähnliche Fotos
- Fotos und Downloads nach Regeln in Ordner sortieren
- Ungenutzte Apps auflisten
