# AMNY: E-Ink Mode

A small Android app for the Onyx Boox Go 6 that downloads the amNY digital edition as a PDF.

## How it works
1. Reads https://www.amny.com/digital-editions/ and lists every edition with its Calameo book ID.
2. Opens the Calameo reader in a WebView, presses its **Download** button, and catches the short-lived signed link (`book-aws.calameo.com/.../book.pdf?...`, valid for 5 minutes).
3. Saves the PDF with Android's DownloadManager to `Download/amNY/amNY-YYYY-MM-DD.pdf` and opens it in NeoReader.

If the automatic press ever fails, the reader stays on screen. Tap Calameo's download button yourself and the app still saves the file.

## Build
Push this repo to GitHub. The **Build APK** workflow compiles it and attaches `AMNY-E-Ink-Mode.apk` to the release tagged `latest`.

## Install on the Boox
Open the release page in the Boox browser, download `AMNY-E-Ink-Mode.apk`, and allow installs from that source when Android asks.
Optional: in the Boox App settings, turn on "Allow running in background" and set the app's E-ink refresh mode to Regal or Balanced.
