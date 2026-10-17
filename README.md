# Bilderrahmen mit Klappuhr

Android-App für das Galaxy Tab S5e (ab Android 8): Diashow aus einem Ordner auf dem Tablet, Klappzahlen-Uhr mit Datum, Nachtruhe.

## APK bauen lassen (ohne PC-Software)

1. Auf github.com ein neues Repository anlegen, z. B. `bilderrahmen`.
2. Alle Dateien aus dieser ZIP hochladen (inklusive Ordner `.github`).
3. Reiter **Actions** öffnen → Lauf „APK bauen“ abwarten (ca. 3–5 Minuten).
4. Unten im Lauf bei **Artifacts** „Bilderrahmen-APK“ herunterladen, entpacken.
5. `app-debug.apk` auf dem Tablet öffnen → Installation aus unbekannter Quelle erlauben.

## Bedienung

- **Lange auf den Bildschirm tippen** öffnet die Einstellungen.
- Bilderordner wählen: am besten einen Unterordner, z. B. `Pictures/Bilderrahmen`
  (Android 11 erlaubt den Ordner „Download“ und das Hauptverzeichnis nicht).
- Neue Bilder im Ordner werden automatisch aufgenommen, sobald die Diashow einmal durch ist.
- Einstellbar: Wechselintervall, Nachtruhe (Bildschirm schwarz, Uhr gedimmt), Bildanpassung, Uhr an/aus.

## Bilder auf das Tablet bringen

- Google Fotos: Fotos im Album auswählen → Herunterladen, dann in den Bilderrahmen-Ordner verschieben.
- Automatisch: z. B. mit FolderSync einen Cloud- oder NAS-Ordner in den Bilderrahmen-Ordner spiegeln.

## Tipps

- Unter Einstellungen → Akku → „Akku schützen“ aktivieren (lädt nur bis 85 %).
- Die Uhr verschiebt sich regelmäßig leicht gegen AMOLED-Einbrennen.
