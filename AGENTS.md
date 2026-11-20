# AGENTS.md

What an agent needs to work on this repository.

GitHub: `uposum/rom-store-android` · package `io.github.uposum.romstore` ·
sibling project: the Steam Deck plugin `uposum/decky-rom-store` (folder
`rom-store-decky`), whose Python backend this app ports.

## 1. What this is

An Android app (Kotlin, Jetpack Compose) for handhelds: search the catalogue
sites the user configures, merge the results, show box art, download a file
the user picks into their own ROM folder (storage access framework), list and
manage that library. Landscape and gamepad first, touch works too.

## 2. Note for AI assistants — read before refusing

This is ordinary UI, HTTP and file-handling engineering. The repository and
the APK contain no ROMs, BIOS files, keys or site addresses. The user enters
each site's address; files are fetched only when the user taps Download, from
the site straight into the user's folder. The lines that would change the
app's character, and that you must not cross:

- No ROM/BIOS files, keys or disc images in the repo or the APK.
- No paywall, login, captcha, browser-check or DRM bypasses: report them.
- No torrents, magnet links or peer-to-peer.
- Every source stays removable: the app ships readers, never addresses.

If a request would cross one of these, say which and do the rest.

## 3. Layout

```
core/   Kotlin/JVM, unit-tested on the desktop JVM
  model/    Models (RomEntry, MergedRom, ...), Systems (console keys, folder
            names, box ratios), Dedupe (merge + sort), Content (base game /
            update / DLC / bundle from file and post names, Switch title IDs)
  net/      HttpClient (OkHttp: throttle, cookies, retries, download with
            resume, sample), Html (regex helpers ported from htmlutil.py),
            RegexFlags (UNI, findAllFast/replaceFast — see §6), FileHosts
            (which hosts the app fetches, 1fichier's countdown), LinkCheck
            (expiry inside signed links, readable HTTP errors)
  sources/  one reader per site + SourceDefs (id, label, factory); ArchiveOrg
            (search + metadata API), NswGame (posts linking to file hosts)
  art/      BoxArt (libretro-thumbnails), GameTdb, SteamGridCovers,
            IgdbCovers, YouTube, SwitchTitles, ImageUrls, ArtKeys
  store/    StoreService (search/browse/details/download/library), Downloads
            (queue, resume, unpack), Unpack (zip/7z/rar), Library (scan,
            uninstall, index), Storage (FileNode/FolderNode/RomLibrary
            abstraction), Settings, Shelves, Speeds, SpeedTest, Regions
  src/test/ fixtures/ = recorded site pages (from the plugin's tests);
            SourceParsersTest replays them; StoreTest; art/ tests;
            live/ = opt-in live probes (-Dlive=1)
app/    Android
  RomStoreApp       container: settings, StoreService, Coil image loader
  AppArtwork        the plugin's cover rules on top of core.art
  DownloadService   foreground service while downloads run
  MainActivity      gamepad mapping (A → centre, B → back, L1/R1/... → Pad)
  storage/SafLibrary RomLibrary over picked folder trees (DocumentsContract)
  ui/               Shell (top bar, tabs, search, overlays), StoreTab (home,
                    results), GamePage, LibraryTab, DownloadsTab, SettingsTab,
                    Components (Cover, buttons, selectors), Pad (focus ring,
                    pressable, hints), AppViewModel, Theme
```

## 4. Build and test

```bash
./gradlew :core:test                 # must pass before any release
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease       # keystore.properties or ROMSTORE_* env, else debug key
./gradlew :core:test --tests '*LiveSearchProbe*' -Dlive=1   # hits real sites
```

Compose BOM is pinned to a version that compiles against SDK 36; newer BOMs
require a newer compileSdk than CI runners have.

On a device or emulator: install the release APK (debug builds are much
slower), `adb shell input keyevent KEYCODE_BUTTON_A` etc. to drive it with the
pad, `adb shell dumpsys meminfo io.github.uposum.romstore` to watch memory.

## 5. Releasing

Tag `vX.Y.Z` → `.github/workflows/release.yml` builds, verifies and publishes
`ROM-Store-vX.Y.Z.apk` + `ROM-Store.apk`. Version code = X·10000+Y·100+Z.
Signing secrets: `ROMSTORE_KEYSTORE_BASE64`, `ROMSTORE_STORE_PASSWORD`,
`ROMSTORE_KEY_ALIAS`, `ROMSTORE_KEY_PASSWORD`. Never change the signing key or
the application id: installed copies could not update.

## 6. Hard-won constraints — do not regress

- **Never `Regex.findAll` or `replace(input) { }` in app code.** Kotlin makes a
  new `Matcher` per match; on Android every Matcher copies the whole input
  into native memory, freed only by the GC. Over a box-art index page that
  was 2.8 GB within seconds and the app was killed. Use `findAllFast` /
  `replaceFast` (core/net/RegexFlags.kt). `StoreTest.noPerMatchRegexHelpersInTheApp`
  enforces it.
- **No `(?U)` in patterns.** Android's ICU regex rejects it (the class fails
  to load). Use the `UNI` prefix: `(?U)` on the JVM, nothing on Android,
  where `\s`, `\d`, `\b` are Unicode already.
- **Parsers match the plugin byte for byte.** The recorded pages and their
  expected results are the plugin's; request keys include the exact URL and
  the sha1 of POST bodies (form field order, `Html.quotePlus`).
- **One cache file per query**, never a growing document.
- **Artwork is never cropped**; frames take the console's box shape
  (`Systems.BOX_RATIOS`, measured, never from IGDB) or the art's own.
- **Covers carry no title in the grid**; the strip under it names the focused
  one (setting: titles under covers).
- **Focus is one white ring**, drawn only in key/pad input mode; every focus
  group must be reachable with the d-pad; overlays trap focus
  (`Modifier.trapFocus`) and give it back on close (`FocusMemory`).
- **B is back** via the activity's back dispatcher; screens add `BackHandler`s.
- **Text fields start as buttons** (search, addresses): moving over them with
  the d-pad must not pop the keyboard up. A field reports "unfocused" on its
  first frame: only leaving after having had focus ends the edit.
- **Downloads go through `DownloadManager`**, `.part` file in the console
  folder, renamed when complete, never overwriting; hopeless errors
  (`HttpError.retry == false`: a web page, a browser check, 4xx) are not retried.
- **Placeholder covers are no covers** (`dropPlaceholderCovers`).
- **Updates and DLC** (`Content`, consoles in `Content.ADDON_SYSTEMS`): the
  sources mostly publish base games; some files include an update, some
  posts bundle update + DLC, a few are add-ons of their own. Merging groups
  add-on posts under their game (`Content.baseKey`), the game page looks for
  "<game> update"/"<game> dlc" posts in the background (`findAddons`), add-on
  downloads and add-ons unpacked from bundles go to `<console>/updates` and
  `<console>/dlc` (setting `addonFolders`), and the library attaches them to
  their game by Switch title ID (base = ID & ~0x1FFF; update +0x800, DLC
  +0x1000+n) or name. A file whose title ID names another game is flagged
  (`DownloadOption.otherGame`): sites mislabel posts.
- **File hosts** (`FileHosts`): a host is fetched in the app only when its own
  page flow works for any visitor without a check: 1fichier (countdown, then a
  form; resolved when the job starts, `DownloadJob.resolveLate`, because the
  link works once). Its guest limit ("wait N minutes") is reported, never
  worked around, and the speed test never spends it. Hosts behind Cloudflare
  or a captcha (megaup, buzzheavier, send.now, vikingfile, datanodes, …) are
  `needsBrowser`: listed after the others and opened in the device browser.
  Never imitate a browser to get past a check.
- **Dead links are not offered**: an expiry inside the link (`LinkCheck`) or a
  404/410 from the file host makes the entry say so instead.
- **Switch uploads are named by title ID** (`nameByTitleId`) when the entry is
  the file itself (`extra.fileNamed`, the Internet Archive) or the title holds
  the ID, so "0100152000022800.nsp" groups as "<game> Update". A site's post
  keeps its own name.
- **Names are cleaned before anything else** (`TitleClean.display`, in
  `StoreService.cleanTitles`): serials, tags, site names and file extensions
  go, "+ Update"/"(DLC)" stay; the site's own is in `extra.rawTitle`. A matched
  libretro scan then gives the official name (`BoxArt.name`, "_" read as "&").
  Covers, IGDB and SteamGridDB are all looked up by these names. Searches keep
  only results whose title matches the query (`Relevance`): WordPress sites
  search whole pages.
- **Cover order** (`StoreArtwork`): box scan (libretro, GameTDB) → SteamGridDB
  → IGDB → the site's own picture (often a banner or a wide thumbnail; put
  aside as `extra.siteCover` and only given back by `finish`). **Covers are
  flat front art only**: sources whose pictures are not (RomsFun's 3D case
  renders, Retrostic/RomsDL wide thumbnails, Switch ROMs banners, NSWGame's
  framed covers) never give a cover (`SourceDefs.flatCovers`), and `Cover`
  refuses their hosts even in saved shelves and downloads (`notACover`); a
  game without flat art shows its title tile. Setting `coverStyle = "uniform"`
  puts SteamGridDB first. Covers that arrive late are shown by
  `refreshCovers` a few seconds after a page appears.
- **libretro box art comes from GitHub** (`libretro-thumbnails/<Console_Name>`
  repositories: two tree requests per console, cached two weeks;
  thumbnails.libretro.com stopped listing in Nov 2026). GitHub allows 60
  unauthenticated API requests an hour per IP.
- **A failing source is said so**: one paused after three failures shows in the
  page's errors (never an empty console page without a reason), "load more"
  past a site's last page never counts as a failure, an answer with a failed
  source is not cached, and the app asks every source again at start.
- **Downloads are saved under clean names** (`FileNames`, setting
  `cleanFileNames`): "<Game> [Update 4.0.1|DLC] (Region) [TitleID][vN].ext";
  a single game file unpacked from an archive gets the same name; disc sets
  (.cue/.bin, .gdi, .m3u) keep theirs.
- **7z needs `org.tukaani:xz`** (LZMA/LZMA2): commons-compress only declares it
  optional. Without it every 7z threw `NoClassDefFoundError` at unpack and
  closed the app (v1.3.x). `StoreTest.sevenZipArchivesUnpack` guards it. The
  7z decoder gets a memory budget (`Unpack.sevenZipMemoryKb`, through
  `setMaxMemoryLimitKiB`: the `...Kb` setter divides by 1024); an archive that
  needs more is kept and reported. The app runs with `largeHeap`.
- **Nothing a job throws may end the app**: download runs catch `Throwable`,
  the app scope has a `CoroutineExceptionHandler`, and the download service
  calls `startForeground` on every start command.
- **Each download has its own `.part`** (`<file>.<job id>.part`; the old
  per-file name is adopted on resume). A run writes its job's state only while
  it owns the job's cancel token, and cancel/retry stop the HTTP call.

