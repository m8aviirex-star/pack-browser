# Pack Browser (Fabric 26.3)

Durchsuche Texturepacks von [Modrinth](https://modrinth.com/resourcepacks) direkt in Minecraft und lade sie mit einem Klick in deinen `resourcepacks`-Ordner.

## Benutzung

1. **Optionen → Ressourcenpakete** öffnen.
2. Oben links auf **Pack Browser** klicken.
3. Suchen, filtern (Version, Sortierung, Kategorie/Auflösung) und einen Pack auswählen.
4. **Herunterladen** klicken (oder Doppelklick auf den Pack). Die Datei landet direkt in `.minecraft/resourcepacks`.
5. Mit **Fertig** zurück: Der neue Pack steht links in der Liste und kann aktiviert werden.

- **Nur 26.3 / Alle Versionen**: Zeigt nur Packs, die für deine Minecraft-Version markiert sind, oder alle Packs.
- Gibt es keine Datei für deine Version, wird die neueste Version geladen, und du bekommst einen Hinweis.
- Jeder Download wird per SHA-1-Prüfsumme kontrolliert.
- Shift-Klick auf Sortierung oder Kategorie blättert rückwärts.

## Voraussetzungen

- Minecraft **26.3** mit **Fabric Loader ≥ 0.20.1**
- **Fabric API**
- Java 25

## Bauen

```
./gradlew build
```

Die fertige Jar liegt danach in `build/libs/`. GitHub Actions baut bei jedem Push automatisch, und die Jar hängt als Artefakt am Workflow-Run.
