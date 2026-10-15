# MinimaLauncher

Launcher Android personale, fork di [Olauncher](https://github.com/tanujnotes/Olauncher)
di Tanuj (GPL-3.0). Il codice resta sotto licenza GPL-3.0 (vedi `LICENSE`).

- applicationId: `app.minimalauncher` (debug: `app.minimalauncher.debug`)
- Il package Kotlin resta `app.olauncher`, così è più facile importare aggiornamenti da upstream.

## Installazione con Obtainium

1. Installa [Obtainium](https://github.com/ImranR98/Obtainium/releases) sul telefono.
2. In Obtainium tocca **Aggiungi app** e incolla l'URL
   `https://github.com/fabrizioolocco-collab/Launcher`.
3. Tocca **Aggiungi** e poi **Installa**. Obtainium ti avvisa a ogni nuova release.

## Rilasci

Si fa il push di un tag `vX.Y` (es. `v0.1`). L'Action `Release` compila l'APK firmato
e lo pubblica come GitHub Release.

- versionName = `X.Y`
- versionCode = `X*1000 + Y` (Y < 1000)

La firma usa questi GitHub Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. Il keystore non va **mai** nel repository.

## Build di debug

A ogni push l'Action `Build` produce un APK di debug, scaricabile come artifact.
Il pacchetto è `app.minimalauncher.debug` e convive con la versione di release.
