<p align="center"><img src="assets/icon.png" alt="Memoize icon" width="96" height="96"></p>

<h1 align="center">Memoize</h1>

A small, native Mac app for [Memos](https://github.com/usememos/memos). Capture a thought, browse your notes, and keep writing offline.

- Floating Markdown editor with syntax colors, file attachments, and smart lists
- Instant keyboard navigation, local search, and tag filtering
- Automatic saves and background sync, with conflict copies and confirmed deletion

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/screenshot-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="assets/screenshot.png">
  <img src="assets/screenshot.png" alt="Memoize's notes menu and floating editor with sample notes">
</picture>

## Install

**macOS 26 or newer, Apple silicon.** Tested with Memos 0.31.0. Download Memoize from [GitHub Releases](https://github.com/bgwastu/memoize/releases).

### Mac — option 1: Terminal

Run this in an empty folder for a fresh install:

```sh
curl -fL -o Memoize.zip \
  "$(curl -fsSL 'https://api.github.com/repos/bgwastu/memoize/releases/latest' \
    | sed -nE 's/.*"browser_download_url": "(https:[^"]+\/Memoize-[^"]+\.zip)".*/\1/p' | head -n 1)" \
  && ditto -x -k Memoize.zip . \
  && xattr -cr Memoize.app \
  && mv Memoize.app /Applications/ \
  && rm Memoize.zip \
  && open /Applications/Memoize.app
```

The app is ad hoc signed and isn't notarized. `xattr -cr` clears its download quarantine. Later updates use Memoize's in-app updater.

### Mac — option 2: no Terminal

1. Download the newest `Memoize-*.zip` from [Releases](https://github.com/bgwastu/memoize/releases) and unzip it.
2. Drag **Memoize.app** into **Applications**, then open it.
3. If macOS blocks it, go to **System Settings → Privacy & Security → Open Anyway** after the first launch attempt. See [Apple's instructions](https://support.apple.com/en-us/102445).

Open Settings with **⌘,**, enter your Memos URL and access token, and connect.

## Shortcuts

| Shortcut | Action |
| --- | --- |
| ⌥⌘X | Show or hide Memoize; configurable |
| ⌘N | New note |
| ⌘[ / ⌘] | Older / newer note; past newest opens a blank draft |
| ⌘F / ⇧⌘F | Find in note / search all notes |
| ⌘` | Switch between notes list and editor |
| ⌘0 | Toggle the notes list |
| Return / Tab / ⇧Tab | Continue a list / nest / outdent |

## Development

Requires Xcode 26 or newer. From the project directory:

```sh
Scripts/build.sh debug
Scripts/test.sh
open build/Memoize.app
```

AI disclosure: Human validated.
