# Pack Browser (Fabric 26.3)

Durchsuche Texturepacks von [Modrinth](https://modrinth.com/resourcepacks) direkt in Minecraft und lade sie mit einem Klick in deinen `resourcepacks`-Ordner.

## Benutzung

**Optionen → Ressourcenpakete** öffnen und auf **Pack Browser** klicken (oben rechts oder links – je nachdem, wo Platz ist; funktioniert auch mit Mods, die das Ressourcenpaket-Menü ersetzen). Alternativ: der kleine **⬇**-Knopf direkt neben „Ressourcenpakete …“ im Optionsmenü. Oben gibt es drei Tabs:

### Modrinth
Packs von Modrinth suchen, filtern (Version, Sortierung, Kategorie/Auflösung) und mit **Herunterladen** oder Doppelklick direkt in `resourcepacks` laden. Jeder Download wird per SHA-1 geprüft.

### Aus Downloads
Für Seiten mit Download-Knopf (PvPRP, Planet Minecraft, CurseForge …): Pack ganz normal im Browser runterladen, er taucht hier automatisch auf. Doppelklick = importieren.
- Zeigt Bild, Beschreibung und für welche Minecraft-Version der Pack gemacht ist (alte 1.8-Packs werden markiert).
- ZIPs mit mehreren Packs oder einem Unterordner werden automatisch richtig entpackt.
- Der Ordner ist einstellbar (Standard: dein Downloads-Ordner).

### Link einfügen
Link einfügen und Enter drücken (auch mehrere Links auf einmal).
- **Sofort geladen:** MediaFire-Dateien und ganze **MediaFire-Ordner** (Massen-Download inkl. Unterordner), Modrinth-Links, GitHub, Dropbox, Google Drive, Discord und alle direkten .zip-Links.
- **PvPRP, CurseForge, PacksMC, Planet Minecraft, YouTube** und andere Seiten mit Download-Knopf öffnen sich im Browser (oder oben auf eine der Seiten klicken). Was du dort herunterlädst, wird **automatisch importiert**, solange der Auto-Import läuft (10 Minuten, verlängert sich nach jedem Download).

Danach mit **Fertig** zurück und den Pack links in der Liste aktivieren.

## Voraussetzungen

- Minecraft **26.3** mit **Fabric Loader ≥ 0.20.1**
- Fabric API ist **nicht** nötig (die benötigten Teile sind in der Jar enthalten)
- Java 25

## Bauen

```
./gradlew build
```

Die fertige Jar liegt danach in `build/libs/`. GitHub Actions baut bei jedem Push automatisch, und die Jar hängt als Artefakt am Workflow-Run.
