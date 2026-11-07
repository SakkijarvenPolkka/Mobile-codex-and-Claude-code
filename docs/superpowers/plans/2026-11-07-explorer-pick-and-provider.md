# 다른 앱의 파일 선택과 시스템 선택기 연동 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 크롬 등의 `ACTION_GET_CONTENT`에 이 앱이 선택지로 뜨고, 시스템 "파일" 선택기에 "파일 탐색기" 루트(열기·저장·만들기·이름 바꾸기·휴지통 삭제)가 생긴다.

**Architecture:** 기존 셸을 `ExplorerDeps.pick`으로 여는 선택 모드 + `PickActivity`가 결과를 FileProvider 주소로 돌려준다. `provider/ExplorerDocumentsProvider`는 `fs/`(Listing, FileOps, RecycleBin, 이름 규칙)와 순수 `DocumentIds`·`SafeSave` 위에 얇게 올린다.

**Tech Stack:** 기존과 같음(Kotlin 2.3.21, AGP 8.13.2, Compose BOM 2025.12.01, coroutines 1.10.2, Robolectric 4.16). 새 라이브러리 없음.

**Spec:** `docs/superpowers/specs/2026-11-07-explorer-pick-and-provider-design.md`(이하 "사양 §N"). 그 기반인 `docs/superpowers/specs/2026-10-23-explorer-design.md`는 "본 사양 §N".

## Global Constraints

- `docs/superpowers/plans/2026-10-23-explorer.md`의 Global Constraints가 모두 그대로 적용된다(모듈·패키지·SDK·MIT 머리말·문자열 두 언어·`-Paudacity.buildNative=false`·커밋 꼬리말·CancellationException 규칙 등).
- 공급자 authority `${applicationId}.documents`, 루트 ID `"explorer"`, 가상 루트 문서 ID `"root"`, 실제 문서 ID `"<uuid>:<rel>"` — **첫 번째 `:`에서만** 나눈다(파일 이름에 `:`가 있을 수 있음). 기본 볼륨 uuid는 `primary`.
- 상한: 선택기 검색 200개 또는 5,000ms, 선택 모드 결과 500개(`PickRequest.MAX_RESULTS`).
- 계층: `provider/`는 `state/`·`ui/`·`preview/`·`pick/`을 import하지 않는다. `state/`는 `pick/`·`provider/`를 import하지 않는다. `PickRequest`는 순수 Kotlin으로 `state/`에 둔다(사양 §1의 `pick/` 표기를 이렇게 고친다).
- 문자열(키 = ko / en, 이미 있는 키는 다시 만들지 않는다 — `cancel`, `volume_internal`, `app_name`, `error_not_found`는 있음): `pick_title` = `파일 선택` / `Choose a file`; `pick_title_multiple` = `파일 선택(여러 개)` / `Choose files`; `pick_open_count` = `열기 (%d)` / `Open (%d)`; `pick_too_many` = `한 번에 최대 500개까지 고를 수 있습니다` / `You can choose up to 500 files at a time`.
- 통과 기준(각 작업 끝): `./gradlew -Paudacity.buildNative=false :explorer:testDebugUnitTest :explorer:assembleDebug` → BUILD SUCCESSFUL. 마지막 작업은 `:explorer:assembleRelease :explorer:lintDebug`도(린트 오류 0).
- 이 계획은 최종 검토 수정이 브랜치에 들어간 뒤 시작한다. 그때 `NameValidator`가 이미 내부 이름(`HiddenNames.isInternal`)을 거부하면 Task 1의 해당 단계는 건너뛴다.

## Review Focus

1. **이름에 `:`·한글·`%`·`#`·공백이 든 파일**: 문서 ID 왕복이 깨지지 않는다 → Task 1 `colonAndUnicodeNamesRoundTrip`.
2. **다른 앱이 이미 있는 이름으로 "다른 이름으로 저장"**: 덮어쓰지 않고 `이름 (2).확장자`가 생긴다 → Task 5 `createExistingNameKeepsBoth`.
3. **저장 중 그 앱이 죽음**: 원본이 그대로 남고 임시 파일도 남지 않는다 → Task 5 `abortKeepsOriginalAndLeavesNoTemp`, `writeClosedWithErrorKeepsOriginal`.
4. **확장자가 대문자이거나 MIME을 모르는 파일**(`IMG_0001.HEIC`, `notes.xyz`): `image/*` 요청에서 HEIC는 고를 수 있고, 모르는 형식은 `*/*`에서만 고를 수 있다 → Task 1 `upperCaseExtensionStillMatches`, `unknownMimeOnlyForAnyType`.
5. **선택기가 열린 채 SD 카드를 뺌**: 그 볼륨의 문서 ID는 `FileNotFoundException`이고 공급자가 죽지 않는다 → Task 4 `ejectedVolumeDocumentsAreNotFound`.

---

## 파일 구조

```
explorer/src/main/java/io/github/sakkijarvenpolkka/explorer/
  state/PickRequest.kt          요청 해석·MIME 일치(순수)
  state/PickMode.kt             VM이 위임하는 선택 모드 동작(탭·고르기·취소·명령 제한)
  pick/PickActivity.kt          선택 모드 셸을 띄우고 결과를 돌려줌
  pick/PickResult.kt            결과 Intent 만들기
  ui/PickBars.kt                PickTitleBar, PickBottomBar
  provider/DocumentIds.kt       문서 ID ↔ 경로(순수, 검증)
  provider/DocumentRows.kt      커서 열·행·플래그
  provider/SafeSave.kt          덮어쓰기 저장 핵심(순수)
  provider/ProviderDeps.kt      의존성 찾기(테스트에서 교체)
  provider/DocumentsNotifier.kt 변경 알림
  provider/ExplorerDocumentsProvider.kt
explorer/src/test/java/.../state/PickRequestTest.kt, state/PickModeTest.kt, pick/PickActivityTest.kt,
  provider/DocumentIdsTest.kt, provider/SafeSaveTest.kt, provider/ProviderReadTest.kt, provider/ProviderWriteTest.kt
```

---

### Task 1: 순수 기반 — 문서 ID, 선택 요청, 내부 이름 거부, 계층 규칙

**Files:**
- Create: `provider/DocumentIds.kt`, `state/PickRequest.kt`
- Modify: `fs/Names.kt`(내부 이름 거부, 최종 검토 수정에 없을 때만), `src/test/.../LayeringTest.kt`
- Test: `provider/DocumentIdsTest.kt`, `state/PickRequestTest.kt`, `fs/NamesTest.kt`, `LayeringTest.kt`

**Interfaces:**
- Consumes: `fs.FsBackend`(`realPath`, `attributes`), `fs.VolumeRoot(uuid, root, traits, removable)`, `fs.HiddenNames.isInternal`, `fs.isRestrictedAppDataPath(volumeRoot, path, obbAllowed)`, `util.baseMimeType`.
- Produces:
  ```kotlin
  // provider/DocumentIds.kt
  const val ROOT_DOC_ID = "root"
  class DocumentIds(private val fs: FsBackend, private val mounted: () -> List<VolumeRoot>) {
    fun resolve(id: String): Path            // java.io.FileNotFoundException on every refusal (사양 §3.2 규칙 1–5)
    fun idOf(path: Path): String?            // null when the path is not exposable
    fun volumeOf(path: Path): VolumeRoot?    // longest root prefix
  }
  // state/PickRequest.kt
  data class PickRequest(val mimeTypes: List<String>, val allowMultiple: Boolean) {
    fun accepts(mime: String?): Boolean
    companion object {
      const val MAX_RESULTS = 500
      fun of(type: String?, extraMimeTypes: Array<String>?, allowMultiple: Boolean): PickRequest
    }
  }
  // fs/Names.kt (if not already there)
  data object Reserved : NameProblem   // NameValidator.validate refuses HiddenNames.isInternal(name)
  ```
- [ ] **Step 1: 테스트 작성**
  - `DocumentIdsTest`(JUnit, TemporaryFolder 볼륨 두 개 `primary`, `1A2B-3C4D`): `roundTripsFilesAndFolders`(`idOf(root/a/b.txt) == "primary:a/b.txt"`, `resolve` 왕복); `volumeRootHasEmptyRel`(`"primary:"`); `colonAndUnicodeNamesRoundTrip`(`a:b.txt`, `한글 #1 %.txt` → `"primary:a:b.txt"` 등 왕복); `refusesTraversalAndJunk`(`"primary:../x"`, `"primary:a/./b"`, `"primary:a//b"`, `"primary:/a"`, `"primary:a\u0000"`, `"nope"`, `""` → FileNotFoundException); `refusesUnmountedVolume`(`"9999-0000:x"`); `refusesSymlinkOutOfVolume`(볼륨 안의 링크가 다른 볼륨·임시 폴더 밖을 가리키면 거부, 안을 가리키면 허용); `refusesRestrictedEvenObb`(`Android/data/x`, `Android/obb/x`); `refusesInternalSegments`(`.Recycle.Bin/x`, `a/.b.op.xpart`); `idOfReturnsNullForUnexposable`(위 경로들에 `idOf == null`).
  - `PickRequestTest`: `extraMimeTypesWin`(`of("*/*", arrayOf("image/png","application/pdf"), false).mimeTypes == ["image/png","application/pdf"]`); `typeUsedWithoutExtra`; `defaultsToAny`(`of(null, null, false).mimeTypes == ["*/*"]`); `wildcardFamily`(`image/*` accepts `image/heic`, refuses `video/mp4`); `exactMatch`; `normalizesCaseAndParameters`(`"Image/PNG; q=1"` accepts `image/png`); `upperCaseExtensionStillMatches`(`MimeResolver().mimeOf("IMG_0001.HEIC")`가 `image/*`에 맞음); `unknownMimeOnlyForAnyType`(`accepts(null)`은 `*/*`에서만 참).
  - `NamesTest.reservedNamesRefused`: `"backup.xpart"`, `".Recycle.Bin"`, `".recycle.bin"` → `NameCheck.Bad(NameProblem.Reserved)`(이미 다른 이름으로 들어갔으면 그 이름을 쓴다).
  - `LayeringTest`: `providerUsesNoUpperLayer`(`provider/` 소스에 `...explorer.state`, `.ui`, `.preview`, `.pick`이 없음); `stateUsesNoPickOrProvider`.
- [ ] **Step 2:** `./gradlew -Paudacity.buildNative=false :explorer:testDebugUnitTest --tests '*DocumentIdsTest' --tests '*PickRequestTest' --tests '*NamesTest' --tests '*LayeringTest'` → FAIL(미구현).
- [ ] **Step 3: 구현.** `resolve`: 첫 `:`로 나누고 사양 §3.2의 규칙 1–5를 차례로 적용(실제 경로 비교는 `fs.realPath` 결과가 볼륨 루트의 `realPath` 아래인지). 이름 문제의 화면 문구는 `name_invalid`를 재사용(새 문자열 없음).
- [ ] **Step 4:** 같은 명령 → PASS, 이어서 통과 기준 → BUILD SUCCESSFUL.
- [ ] **Step 5: 커밋** `Add document ids, pick requests and reserved-name refusal`

### Task 2: 선택 모드 — 상태와 화면

**Files:**
- Create: `state/PickMode.kt`, `ui/PickBars.kt`
- Modify: `state/ExplorerUiState.kt`(`ExplorerDeps.pick`, `ExplorerUiState.pick`), `state/ExplorerViewModel.kt`(위임만), `data/SettingsStore.kt`(`pickLast`), `ui/ExplorerShell.kt`, `ui/FileList.kt`(`selectable`), `ui/NavContent.kt`(휴지통 숨김), `ui/Tags.kt`, strings
- Test: `state/PickModeTest.kt`(Robolectric, 실제 VM + 임시 볼륨), `ui/PickShellTest.kt`

**Interfaces:**
- Consumes: Task 1 `PickRequest`.
- Produces:
  ```kotlin
  // ExplorerDeps
  val pick: PickRequest? = null
  // ExplorerUiState
  val pick: PickUi? = null
  data class PickUi(val request: PickRequest, val chosen: Int, val outcome: PickOutcome?)
  sealed interface PickOutcome { data class Chosen(val paths: List<Path>) : PickOutcome; data object Cancelled : PickOutcome }
  // ExplorerViewModel (delegating to PickMode)
  fun choose()            // the selection (multiple mode) or nothing
  fun cancelPick()
  // SettingsStore
  var pickLast: String?   // key "pick_last", a location tag of VolumeLocationCodec
  // ui/FileList.kt
  selectable: (FileEntry) -> Boolean = { true }   // a FILE row that is not selectable is dimmed, ignores taps, semantics disabled()
  // ui/PickBars.kt
  @Composable fun PickTitleBar(multiple: Boolean, onCancel: () -> Unit)
  @Composable fun PickBottomBar(count: Int, onOpen: () -> Unit, onCancel: () -> Unit)
  ```
  선택 모드 명령 = `commandsFor(...) ∩ {OPEN, SELECT_ALL, SELECT_NONE, INVERT, REFRESH, PROPERTIES, OPTIONS}`, 한 개 모드에서는 `SELECT_ALL`·`INVERT`도 뺀다. 탭 관련 동작(`NewTab`, `Duplicate`, Ctrl+T)·미리보기 창·내장 뷰어는 선택 모드에서 쓰지 않는다.
- [ ] **Step 1: 테스트 작성**
  - `PickModeTest`: `sessionIsNeitherReadNorWritten`(앱 세션에 탭 2개가 저장된 상태에서 선택 모드 VM을 만들고 이동해도 `settings`의 세션이 그대로, 선택 모드는 탭 1개); `startsAtLastPickFolderElseHome`(`pickLast`가 있는 폴더 → 그 폴더, 없거나 사라짐 → Home); `singleTapOnMatchingFileChooses`(`image/*`, `a.jpg` 탭 → `outcome == Chosen([a.jpg])`, `pickLast`가 그 폴더); `tapOnFolderNavigates`; `tapOnNonMatchingFileDoesNothing`(`doc.pdf` 탭 → outcome null, 위치 그대로); `multipleTapsToggleAndChooseReturnsListingOrder`(여러 개 모드에서 `b.jpg`, `a.jpg` 탭 → `chosen == 2`, `choose()` → `Chosen([a.jpg, b.jpg])`(목록 순서)); `chooseVanishedFileShowsNotFound`(고르기 직전에 지운 파일 → `error_not_found`, outcome null, 목록 다시 읽음); `tooManyRefused`(501개 선택 → `pick_too_many`, outcome null); `commandsAreReadOnly`(파일 1개 선택 시 commands ⊆ 선택 모드 집합, `DELETE`·`RENAME`·`PASTE` 없음); `backWithNothingToUndoCancels`(`backTarget == null` 상태의 `systemBack()` → `Cancelled`); `chosenFileBecomesRecent`.
  - `PickShellTest`(Compose): `titleBarReplacesTabStrip`(탭 줄 태그 없음, `pick_title` 표시, 취소 → `Cancelled`); `nonMatchingRowsAreDisabled`(그 행의 semantics에 Disabled); `bottomBarCountsAndOpens`(여러 개 모드에서 두 개 탭 → `열기 (2)` 활성, 누르면 `Chosen`); `binHiddenFromNavPane`.
- [ ] **Step 2:** `--tests '*PickModeTest' --tests '*PickShellTest'` → FAIL.
- [ ] **Step 3: 구현.** VM은 `pick != null`일 때 `loadSession`·`saveSession`을 건너뛰고(초기 위치 = `codec.decode(pickLast)`가 `Ok(Folder)`이고 볼륨이 마운트돼 있으면 그 폴더, 아니면 Home), `onTap`/`onLongPress`/`command(OPEN)`/`systemBack`을 `PickMode`로 넘긴다. 고르기 전 존재 확인은 IO에서 `fs.exists`. `ExplorerViewModel.kt`는 지금 줄 수 + 25줄 이내로 유지하고 나머지는 `PickMode.kt`에 둔다.
- [ ] **Step 4:** PASS, 통과 기준.
- [ ] **Step 5: 커밋** `Add a pick mode to the explorer shell`

### Task 3: PickActivity와 결과

**Files:**
- Create: `pick/PickActivity.kt`, `pick/PickResult.kt`
- Modify: `AndroidManifest.xml`(사양 §1.1의 `PickActivity` 항목), `platform/OpenWith.kt`(`commonType`을 `internal`로 열어 재사용)
- Test: `pick/PickActivityTest.kt`, `pick/PickResultTest.kt`

**Interfaces:**
- Consumes: Task 2 `ExplorerDeps.pick`, `PickUi.outcome`, `ExplorerViewModel.cancelPick`; `OpenWith.uriFor(path)`, `MimeResolver.mimeOf(name)`.
- Produces:
  ```kotlin
  fun pickResultIntent(paths: List<Path>, uriFor: (Path) -> Uri, typeOf: (Path) -> String?): Intent
  // single: setDataAndType(uri, type ?: "application/octet-stream"); multiple: ClipData of all uris, no data, type = commonType
  // always FLAG_GRANT_READ_URI_PERMISSION, never WRITE
  class PickActivity : ComponentActivity()   // MainActivity's permission gate + ExplorerShell with deps.pick; finishes on outcome
  ```
- [ ] **Step 1: 테스트 작성**
  - `PickResultTest`(Robolectric): `singleResultHasDataTypeAndReadGrant`(authority `io.github.sakkijarvenpolkka.explorer.files`, 플래그에 READ 있고 WRITE 없음, type `image/jpeg`); `multipleResultUsesClipData`(3개 → `clipData.itemCount == 3`, `data == null`, type 공통 규칙); `unknownTypeIsOctetStream`.
  - `PickActivityTest`(Robolectric + `createEmptyComposeRule`, AppOps로 권한 허용): `intentFilterMatchesGetContent`(`queryIntentActivities(Intent(ACTION_GET_CONTENT).addCategory(CATEGORY_OPENABLE).setType("image/*"), 0)`과 `setType("*/*")` 둘 다 `PickActivity` 포함); `pickingReturnsOk`(`launchActivityForResult(GET_CONTENT, type image/*)` → 임시 볼륨의 `a.jpg` 탭 → `result.resultCode == RESULT_OK`, `result.resultData.data`가 `.files` 주소); `cancelReturnsCanceled`(취소 버튼 → `RESULT_CANCELED`); `withoutPermissionShowsPermissionScreen`; `doesNotTouchAppSession`.
- [ ] **Step 2:** FAIL. **Step 3: 구현**(`PickActivity`는 `MainActivity`의 권한 확인·테마·`enableEdgeToEdge` 구조를 따른다. 결과는 `ui.pick.outcome`을 수집해 `setResult` 후 `finish()`). **Step 4:** PASS, 통과 기준.
- [ ] **Step 5: 커밋** `Answer other apps' requests for files`

### Task 4: 공급자 — 읽기

**Files:**
- Create: `provider/ProviderDeps.kt`, `provider/DocumentRows.kt`, `provider/DocumentsNotifier.kt`, `provider/ExplorerDocumentsProvider.kt`
- Modify: `AndroidManifest.xml`(사양 §1.1의 공급자 항목), `ExplorerApp.kt`(볼륨 이벤트·즐겨찾기 변경 → 알림), `MainActivity.kt`(`onResume`에서 루트 알림)
- Test: `provider/ProviderReadTest.kt`, `provider/DocumentRowsTest.kt`

**Interfaces:**
- Consumes: Task 1 `DocumentIds`, `ROOT_DOC_ID`; `fs.Listing.list(fs, dir, ListOptions(showHidden = true), mime)`; `fs.search(...)`; `platform.Volume`, `VolumeState`; `data.SettingsStore.favorites`/`recents`, `VolumeLocationCodec`; `platform.ThumbnailPort.load(file, kind, sizePx, signal)`, `thumbKindFor`; `platform.PermissionGate.granted()`.
- Produces:
  ```kotlin
  class ProviderDeps(val fs: FsBackend, val volumes: () -> List<Volume>, val settings: SettingsStore, val fileOps: FileOps,
      val bin: RecycleBin, val journal: TempJournal, val mediaScan: MediaScan, val thumbnails: ThumbnailPort,
      val mime: MimeResolver, val granted: () -> Boolean, val internalLabel: String, val io: CoroutineDispatcher,
      val ids: () -> String) {
    companion object { @Volatile var override: ProviderDeps? = null; fun of(context: Context): ProviderDeps }   // override first, else from ExplorerApp (lazily: provider onCreate runs before Application.onCreate)
  }
  object DocumentRows {
    val ROOT_COLUMNS: Array<String>; val DOCUMENT_COLUMNS: Array<String>
    fun flagsFor(kind: EntryKind, mime: String?, writable: Boolean, volumeRoot: Boolean, blocksTree: Boolean): Int
  }
  object DocumentsNotifier { fun authority(context: Context): String; fun roots(context: Context); fun children(context: Context, docIds: Collection<String>) }
  class ExplorerDocumentsProvider : DocumentsProvider()   // queryRoots, queryDocument, queryChildDocuments, getDocumentType, isChildDocument, openDocument("r"), openDocumentThumbnail, queryRecentDocuments, querySearchDocuments
  ```
  `flagsFor` 규칙은 사양 §3.3. `blocksTree`는 볼륨 루트·기본 볼륨의 `Download`·가상 `root`. 하위 목록에서 `SYMLINK`·`OTHER`·내부 이름·제한 경로는 뺀다.
- [ ] **Step 1: 테스트 작성**
  - `DocumentRowsTest`(JVM): 폴더/파일/이미지/볼륨 루트/읽기 전용/`blocksTree` 조합별 플래그(사양 §3.3 그대로).
  - `ProviderReadTest`(Robolectric `Robolectric.buildContentProvider(ExplorerDocumentsProvider::class.java).create(ProviderInfo().apply { authority = "io.github.sakkijarvenpolkka.explorer.documents" })`, `ProviderDeps.override`에 임시 볼륨 두 개와 가짜 썸네일): `noRootsWithoutPermission`(granted=false → 행 0); `oneRootWithFlags`(ROOT_ID `explorer`, 문서 `root`, `FLAG_SUPPORTS_CREATE|RECENTS|SEARCH|IS_CHILD|LOCAL_ONLY`); `rootListsFavoritesThenDrives`(즐겨찾기 `Download`(라벨) 다음 `내부 저장소`, `SD` — 볼륨 루트인 즐겨찾기는 빠짐, 없는 폴더의 즐겨찾기도 빠짐); `childrenHideInternalRestrictedLinksAndSpecials`; `childRowColumns`(이름·크기·MIME·수정 시각); `downloadAndVolumeRootsBlockTree`; `readOnlyVolumeHasNoWriteFlags`; `openForReadReturnsContent`; `openFolderIsNotFound`; `thumbnailForImageOnly`(가짜 포트가 비트맵을 주면 `AssetFileDescriptor` 길이 > 0, 텍스트 파일은 FileNotFound); `recentsAreExistingAppRecents`(사라진 최근 항목 빠짐, 최근 순); `searchFindsAndCapsAt200`(250개 일치 → 200, 점으로 시작하는 숨긴 항목·`.Recycle.Bin` 안·`Android/data` 안의 일치는 결과에 없음); `isChildDocumentChecksPaths`(같은 볼륨 하위 참, 형제·다른 볼륨·`root` 부모 거짓); `ejectedVolumeDocumentsAreNotFound`(볼륨 목록에서 SD를 빼면 그 ID의 `queryDocument`·`openDocument`가 FileNotFound, 크래시 없음); `childrenCursorHasNotificationUri`.
- [ ] **Step 2:** FAIL. **Step 3: 구현**(검색은 `runBlocking(deps.io) { withTimeoutOrNull(5_000) { ... } }`, 200개에서 중단. 썸네일은 `ParcelFileDescriptor.createReliablePipe()`에 JPEG 85%를 백그라운드 스레드로 씀). **Step 4:** PASS, 통과 기준.
- [ ] **Step 5: 커밋** `Show the explorer in the system file picker`

### Task 5: 공급자 — 쓰기

**Files:**
- Create: `provider/SafeSave.kt`
- Modify: `fs/FsBackend.kt`(`fun sync(path: Path)`), test `fs/FaultyFs.kt`(같은 메서드, 단계로 셈), `provider/ExplorerDocumentsProvider.kt`, `ExplorerApp.kt`(`onFinished` → 영향받은 폴더 알림)
- Test: `provider/SafeSaveTest.kt`, `provider/ProviderWriteTest.kt`

**Interfaces:**
- Consumes: Task 4 `ProviderDeps`, `DocumentsNotifier`, `DocumentIds`; `fs.tempNameFor(name, opId)`, `fs.NameValidator`, `fs.claimUniqueName`/`UniqueStyle.KEEP_BOTH`, `FileOps.rename(path, name)`, `RecycleBin.moveToBin(path)`, `fs.deleteTree`, `MediaScan.scan(paths)`.
- Produces:
  ```kotlin
  // fs/FsBackend.kt
  fun sync(path: Path)   // NioFsBackend: FileChannel.open(path, WRITE).use { it.force(true) }
  // provider/SafeSave.kt
  class SafeSave(private val fs: FsBackend, private val journal: TempJournal) {
    fun begin(target: Path, id: String): Pending   // temp = target.resolveSibling(tempNameFor(target.name, id)), created with fs.createNew, journal.add
  }
  class Pending(val target: Path, val temp: Path) { fun commit(); fun abort() }   // commit: sync → atomicMove(temp, target) → journal.remove; abort: delete temp (ignore NotFound) → journal.remove
  // ExplorerDocumentsProvider adds: openDocument("w"|"wt" → SafeSave via ParcelFileDescriptor.open(temp, …, handler, onClose); "wa"|"rw"|"rwt" → direct),
  //   createDocument, renameDocument, deleteDocument
  ```
- [ ] **Step 1: 테스트 작성**
  - `SafeSaveTest`(JVM, FaultyFs): `commitReplacesTarget`(기존 `"old"` → 임시에 `"new"` 쓰고 commit → 대상 `"new"`, `.xpart` 없음, 저널 add/remove 짝); `abortKeepsOriginalAndLeavesNoTemp`; `worksForNewTarget`(대상이 없을 때 commit → 생김); `commitSyncsBeforeMove`(FaultyFs 기록: sync 다음 atomicMove); `crashBeforeMoveLeavesOriginal`(crashAtStep을 atomicMove에 → 원본 그대로, 임시는 저널에 남음).
  - `ProviderWriteTest`(Robolectric): `writeTruncateGoesThroughTempAndCommits`(`openDocument(id,"wt")`에 쓰고 닫기 → 내용 교체, 하위 목록 변경 알림, 미디어 스캔 호출); `writeClosedWithErrorKeepsOriginal`(`closeWithError` → 원본 그대로 — Robolectric이 닫기 알림을 부르지 않으면 이 테스트는 `@Ignore` 대신 지우고 SafeSaveTest가 그 경로를 맡는다고 보고서에 적는다); `appendWritesInPlace`(`"wa"`); `createFileAddsExtensionAndKeepsBoth`(`createDocument(parent,"text/plain","memo")` → `memo.txt`, 다시 → `memo (2).txt`); `createExistingNameKeepsBoth`(`a.pdf`가 있을 때 `"a.pdf"` → `a (2).pdf`, 기존 파일 내용 그대로); `createFolder`; `createRefusesBadAndReservedNames`(`"a/b"`, `"x.xpart"`, `".Recycle.Bin"` → FileNotFound); `createOnReadOnlyVolumeRefused`; `renameReturnsNewIdAndCaseOnlyWorks`(`a.txt`→`A.txt`, FaultyFs caseInsensitive); `renameVolumeRootRefused`; `deleteGoesToRecycleBin`(지운 뒤 `bin.list`에 항목, 원래 위치 기록); `deleteFallsBackToPermanentWhenBinUnavailable`; `deleteVolumeRootRefused`.
- [ ] **Step 2:** FAIL. **Step 3: 구현.** 닫기 알림은 전용 `HandlerThread("explorer-documents")`. 쓰기 후 공통 처리(부모 하위 목록 알림, FUSE 볼륨이면 미디어 스캔, 지우기·이름 바꾸기 후 `revokeDocumentPermission(옛 ID)`)는 한 함수로. `ExplorerApp.onFinished`는 끝난 작업의 원본·생성 경로의 부모 폴더 ID들로 `DocumentsNotifier.children`.
- [ ] **Step 4:** PASS, 통과 기준.
- [ ] **Step 5: 커밋** `Let other apps save, create, rename and delete through the picker`

### Task 6: README와 릴리스 APK

**Files:**
- Modify: `explorer/README.md`(기능 목록에 두 기능, 실기기 확인 목록에 사양 §5의 항목)

- [ ] **Step 1:** README에 기능 설명(한국어)과 실기기 확인 목록 항목(사양 §5 "실기기 확인 목록" 전체)을 체크박스로 더한다.
- [ ] **Step 2:** `./gradlew -Paudacity.buildNative=false :explorer:testDebugUnitTest :explorer:assembleDebug :explorer:assembleRelease :explorer:lintDebug` → BUILD SUCCESSFUL, 린트 오류 0.
- [ ] **Step 3:** `apksigner verify`, `aapt2 dump badging`(새 activity·provider는 badging에 안 나오므로 `aapt2 dump xmltree --file AndroidManifest.xml`로 `PickActivity` 인텐트 필터와 공급자 `MANAGE_DOCUMENTS` 권한 확인). APK를 `dist/explorer-1.1.0-release.apk`로 복사(`versionCode = 2`, `versionName = "1.1.0"`로 올림).
- [ ] **Step 4: 커밋** `Document picking for other apps and the system picker`
