# Kehraus

Android-App, die das eigene Handy von selbst aufräumt: Regeln legen fest, was weg kann,
ein Papierkorb mit Frist fängt alles auf, bevor es endgültig gelöscht wird.

Gedacht für das eigene Gerät, nicht für den Play Store. Die App hat keinen Internetzugang.

## Regeln

Jede Regel ist einzeln ein- und ausschaltbar. Nach der Installation sind alle aus.

| Regel | Durchsucht | Voreinstellung |
| --- | --- | --- |
| Screenshots | Bildschirmfotos und Bildschirmaufnahmen | älter als 30 Tage |
| WhatsApp-Medien | Bilder, Videos und GIFs aus WhatsApp | älter als 60 Tage |
| Downloads | alles im Download-Ordner | älter als 90 Tage |
| Große Videos | Videos in `DCIM`, `Movies`, `Pictures` | ab 200 MB, älter als 180 Tage |

Jede Regel zeigt vorab, wie viele Dateien sie im Moment treffen würde, und listet sie auf.
Versteckte Dateien und Ordner bleiben immer unberührt.

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

- Doppelte und ähnliche Fotos finden
- Fotos nach Regeln in Ordner sortieren
