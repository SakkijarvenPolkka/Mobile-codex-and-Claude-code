# 파일 탐색기 (`:explorer`) — Windows 11 스타일 Android 파일 관리자 설계

- 날짜: 2026-10-23
- 상태: 설계 승인 대기(사양 검토 단계)
- 범위: 이 저장소에 새 Android 앱 모듈 `:explorer` 추가. 기존 Audacity 포팅(`:app`, `:engine`,
  `:editor`, `native/`)과는 코드·빌드 의존이 없는 별개의 앱.

## 0. 목표

**사용자가 정한 것**
- 별도의 파일 관리자 앱
- Windows 11 파일 탐색기의 모습과 동작(Fluent 디자인, 명령 모음, 탭, 홈, 라이트·다크)
- 전체 저장소 접근(`MANAGE_EXTERNAL_STORAGE`)
- v1 범위: 기본 탐색(주소 표시줄, 뒤로/앞으로/위로, 보기 전환, 정렬) + 파일 작업 + 내장 미리보기 +
  탭·홈·즐겨찾기 + 검색·ZIP
- 구현 방식 A: Compose Foundation 위에 직접 만든 Fluent 컴포넌트(Material 3의 모습을 쓰지 않음)
- 길게 누르기 = 선택(선택 모드 진입), 이미 선택된 항목을 길게 누르거나 행의 ⋯ = 컨텍스트 메뉴
- 파일 확장자 기본 표시(설정에서 끌 수 있음)
- 라이선스 MIT
- APK 설치 지원(`REQUEST_INSTALL_PACKAGES`)

**가정(사용자 확인 완료)**
- 휴대폰 세로가 기본, 태블릿·가로(너비 ≥ 840dp)에서 탐색 창 고정 + 미리보기 창
- Kotlin + Jetpack Compose, minSdk 30, targetSdk 36, 한국어·영어 UI
- APK 직접 배포(Play 스토어 정책은 고려하지 않음)
- 새 서드파티 라이브러리 없음(androidx·kotlinx는 기존 버전 카탈로그 사용)

**성공 기준**
- 한국어 사용자가 Windows 11 탐색기를 쓰듯 휴대폰의 내부 저장소·SD 카드·USB 파일을 찾아보고,
  복사·이동·삭제·이름 바꾸기·압축하고, 이미지·텍스트·오디오·동영상·PDF를 앱 안에서 볼 수 있다.
- 어떤 실패(취소, 공간 부족, 강제 종료, SD 카드 제거, 권한 회수)에서도 사용자의 원본 파일이
  사라지거나 잘린 파일이 완전한 파일처럼 남지 않는다(§4.3의 불변식).
- 한국어 Windows에서 만든 ZIP의 파일 이름, CP949 텍스트가 깨지지 않는다.
- 단계마다 `./gradlew -Paudacity.buildNative=false :explorer:testDebugUnitTest :explorer:assembleDebug`가 통과한다.

**v1에 넣지 않는 것**: 클라우드·네트워크 드라이브(SMB/FTP), 루트 권한, 파일 내용 검색, 암호 ZIP,
7z/RAR, 백그라운드 음악 재생, 실행 취소(Ctrl+Z), 드래그 앤 드롭, 여러 창, 위젯, 이름 바꾸기·이동의
되돌리기 스낵바, 암호 PDF 열기, Material You 이외의 테마 사용자 지정.

## 1. 아키텍처

### 1.1 모듈과 빌드
- `settings.gradle.kts`에 `include(":explorer")`.
- 패키지·`namespace`·`applicationId`: `io.github.sakkijarvenpolkka.explorer`. 앱 이름 "파일 탐색기"
  (영어 "Files"). Windows·Microsoft 이름, 로고, 아이콘 파일을 쓰지 않는다(생김새만 따름).
- `:app`의 `android {}`·`compileOptions`·`kotlin {}`·`testOptions { unitTests.isIncludeAndroidResources = true }`
  구성을 따른다. `minSdk = 30`. 릴리스는 `:app`처럼 R8 + 디버그 키 서명.
- 버전 카탈로그에 BOM 관리 항목 `androidx-compose-foundation`을 추가(androidx, 서드파티 아님).
  그 밖의 의존: core-ktx, lifecycle(runtime/viewmodel compose), activity-compose, compose ui/ui-graphics/
  foundation, coroutines, serialization-json. 아이콘은 직접 그리므로 material-icons는 쓰지 않는다.
  테스트: junit, robolectric, androidx-test core/ext, coroutines-test, compose ui-test-junit4.
- `app/src/test/resources/robolectric.properties`와 같이 `sdk=35`.
- 모든 소스 파일 머리말: `SPDX-License-Identifier: MIT`. `explorer/LICENSE`(MIT 전문).
- `:explorer`는 `:engine` 등에 의존하지 않는다. `-Paudacity.buildNative=false`를 주면 다른 모듈의
  네이티브 구성도 건너뛴다.

### 1.2 패키지 계층
위 계층이 아래 계층만 사용한다.

```
ui/          셸(탭 줄·주소 표시줄·명령 모음·탐색 창·내용·상태 표시줄), 홈, 대화상자, 단축키
ui/fluent/   FluentTheme 토큰(색·모서리·간격·글꼴·높이) + 컴포넌트
preview/     이미지·텍스트·오디오/동영상·PDF 뷰어
state/       ExplorerViewModel(창구), TabsStore, ListingLoader, ClipboardHolder, 명령 활성화 규칙
ops/         OperationRunner(프로세스 싱글톤), FileOpsService(포그라운드 서비스), 저널
data/        SettingsStore: 세션(탭), 즐겨찾기, 최근 항목, 보기 설정, 전역 설정
platform/    PermissionGate, VolumeRegistry, MimeResolver, Thumbnails, OpenWith(FileProvider),
             MediaScan, ApkInstall
fs/          순수 Kotlin(Android 의존 없음): FsBackend, FileEntry, Walker, Listing, NameRules,
             NameValidator, uniqueName, WriteProtocol, FileOps, RecycleBin, Search, Zip, TextSniffer,
             FsError
util/        UiText(:app에서 복사), 크기·날짜 형식
```

- `fs/`는 `java.nio.file.Path`만 다루며 Android API를 쓰지 않는다. MIME과 볼륨 루트는 `platform/`에서
  주입한다. 그래서 `fs/`는 JVM 단위 테스트로 검증한다. 별도 `kotlin("jvm")` 모듈로 떼어 내는 것은
  선택 사항이며 v1에서는 하지 않는다.
- 오류는 `sealed interface FsError`(AccessDenied, NotFound, AlreadyExists, NoSpace, NameInvalid,
  TooLargeForVolume, CrossesIntoSelf, ReadOnly, IoFailure, VolumeRemoved, Restricted, Corrupt, Unsupported)로
  전달하고, 문자열 변환은 `ui/`에서 `UiText`로만 한다.
- 디스패처, 시계(`TimeSource`/`Clock`), ID 생성기는 모두 주입한다.

## 2. 화면 구성

### 2.1 휴대폰 세로

```
┌──────────────────────────────────┐
│ [📁 다운로드 ×] [🖼 DCIM ×]  [+]  │ 탭 줄
├──────────────────────────────────┤
│ ☰  ←  →  ↑  │ 내 PC › 내부 저장소 › Download ▾│ 주소 표시줄
├──────────────────────────────────┤
│ [+새로 만들기] ✂ ⧉ 📋 ✏ 🗑 │ ⇅정렬 ▦보기 ⋯ │ 명령 모음
├──────────────────────────────────┤
│ 이름 ▴                크기   수정한 날짜 │ 열 머리글(자세히 보기)
│ 📁 Music                     어제     │
│ 📄 report.pdf      1.2 MB  10/21     │
│ 🖼 photo.jpg ✓     3.4 MB  10/20     │
├──────────────────────────────────┤
│ 항목 24개 · 1개 선택됨 (3.4 MB)  ≡ ▦ │ 상태 표시줄
└──────────────────────────────────┘
```

- **탭 줄**: 가로 스크롤, [+] 새 탭(홈), 탭의 × 닫기, 길게 누르면 탭 메뉴(닫기, 다른 탭 닫기, 복제).
  마지막 탭을 닫으면 홈 탭 하나가 남는다.
- **주소 표시줄**: 경로 조각(브레드크럼)을 탭하면 그 폴더로, 조각 옆 ▾는 하위 폴더 목록, 빈 곳을
  탭하면 직접 입력(실제 경로, Enter로 이동). 첫 조각은 "내 PC" 또는 볼륨 이름(예: "내부 저장소",
  "SD 카드"), `/storage/emulated/0` 같은 실제 경로는 입력 모드에서만 보인다.
- **명령 모음**: 새로 만들기(폴더, 텍스트 문서), 잘라내기, 복사, 붙여넣기, 이름 바꾸기, 공유, 삭제,
  정렬, 보기, ⋯(모두 선택, 선택 안 함, 선택 영역 반전, 영구 삭제, 압축, 압축 풀기, 즐겨찾기에 고정,
  속성, 새로 고침, 숨긴 항목 표시, 파일 확장자 표시, 넓은 화면에서는 미리보기 창). 활성화 여부는 §3.6의 순수 함수로 정한다.
  좁은 화면에서는 들어가지 않는 명령이 ⋯로 넘어간다.
- **상태 표시줄**: 항목 수, 선택 수. 선택이 파일만일 때만 크기 합계를 보여 준다(폴더가 섞이면
  "N개 항목 선택됨"). 오른쪽에 자세히/큰 아이콘 빠른 전환. 진행 중인 작업이 있으면 진행률 칩.
- **탐색 창**(☰, 휴대폰은 서랍): 홈, 즐겨찾기(고정한 폴더), 내 PC(볼륨 목록, 사용할 수 없는 볼륨은
  흐리게), 휴지통.

### 2.2 넓은 화면(≥ 840dp)
탐색 창이 왼쪽에 고정되고, 오른쪽에 미리보기 창(명령 모음 ⋯ ▸ 미리보기 창, Alt+P)이 붙는다.
미리보기 창은 선택된 파일 1개를 §7의 뷰어로 작게 보여 준다.

### 2.3 보기·정렬
- 보기: 자세히(이름·수정한 날짜·유형·크기 열), 목록, 타일, 큰 아이콘(썸네일 격자). 폴더마다
  기억(§3.5), 처음 보는 폴더는 직전 기본값.
- 정렬: 이름·수정한 날짜·유형·크기, 오름/내림차순, 폴더 항상 먼저. 열 머리글을 탭해도 정렬된다.
- 이름 정렬은 `NameComparator`: NFC 정규화, 대소문자 무시, 숫자 부분은 수치로 비교(Windows
  `StrCmpLogicalW`와 비슷하게 `2.txt` < `10.txt`), 같으면 원래 문자열로 결정. 큰 폴더는 정렬 키를
  미리 계산.

### 2.4 홈
- 빠른 액세스: 즐겨찾기 + 기본 폴더(다운로드, DCIM, 사진(Pictures), 문서, 음악, 동영상) — 기본 폴더는
  있는 것만.
- 드라이브: 볼륨마다 사용량 막대(`StatFs` 전체/여유), "N GB 중 M GB 사용 가능".
- 최근 항목: 이 앱에서 연 파일 최근 20개(없어진 파일은 표시할 때 빼고 저장소에서도 정리).
- 휴지통 크기(비어 있지 않을 때).

### 2.5 터치 동작

| Windows | 이 앱 |
|---|---|
| 더블클릭으로 열기 | 탭(폴더는 이동, 파일은 §7) |
| 클릭으로 선택 / Ctrl+클릭 | 길게 누르기로 선택 모드 진입, 이후 탭으로 선택 추가·해제 |
| 우클릭 메뉴 | 이미 선택된 항목을 길게 누르기, 또는 행의 ⋯ |
| 드래그 범위 선택 | 선택 모드에서 체크 열을 따라 끌기(앵커부터 연속 선택) |
| Backspace / Alt+← | 시스템 뒤로(§3.4) |

**컨텍스트 메뉴**(Fluent 메뉴, 선택 대상 기준): 열기, 연결 프로그램…, 잘라내기, 복사, 이름 바꾸기,
공유, 압축(ZIP), 압축 풀기(.zip일 때), 즐겨찾기에 고정(폴더일 때), 속성, 삭제.

**속성 대화상자**: 이름, 유형, 위치, 크기(폴더는 하위 항목을 세며 갱신, 파일·폴더 수), 수정한 날짜,
숨김 여부. 여러 개 선택 시 합계.

**하드웨어 키보드**: Ctrl+C/X/V/A, Delete, Shift+Delete, F2, F5/Ctrl+R, Ctrl+L(주소 입력), Ctrl+T/W,
Ctrl+Tab, Alt+←/→/↑, Enter, Backspace, Alt+P. 단축키 처리는 한 곳(`ShortcutDispatcher`)에서 하고,
텍스트 입력란(주소, 이름 바꾸기, 검색)에 포커스가 있으면 Backspace·Ctrl+A/C/V/X는 텍스트 편집에 쓴다.

### 2.6 Fluent 스타일
- 배경: Mica 느낌의 단색 레이어(라이트 `#F3F3F3`/카드 `#FBFBFB`, 다크 `#202020`/카드 `#2B2B2B`),
  블러 없음.
- 모서리 반경: 컨트롤 4dp, 메뉴·대화상자·카드 8dp. 얇은 테두리(1dp, 반투명)와 은은한 그림자.
- 강조색: 기본 `#0067C0`(라이트)/`#4CC2FF`(다크), 설정에서 "시스템 강조색 사용"(Android 12+ 동적 색).
- 선택된 행: 강조색 10% 배경 + 왼쪽 3dp 강조 막대 + 체크. 누를 때는 리플 대신 배경색 변화.
- 글꼴: 시스템 기본 산세리프(한글 Noto Sans CJK), 크기 체계는 Fluent type ramp(Caption 12,
  Body 14, Subtitle 20, Title 28).
- 아이콘: Fluent 아이콘 모양을 참고해 직접 그린 `ImageVector`(폴더, 열린 폴더, 일반 파일, 이미지,
  동영상, 오디오, 텍스트, PDF, ZIP, APK, 코드, 볼륨, SD, USB, 휴지통, 명령 아이콘들).
- 컴포넌트: FluentButton(기본/강조/서브틀), CommandBar, MenuFlyout, ContentDialog, ListRow,
  TabStrip, Breadcrumb, TextBox, ProgressBar, ToggleSwitch, InfoBar(경고 배너), NavigationPane.
- 접근성: 모든 컴포넌트에 `semantics` 역할(Button, Tab, Checkbox), 선택·토글 상태,
  선택 모드의 `stateDescription`, 아이콘의 한국어·영어 `contentDescription`, 터치 영역 최소 48dp.
- `enableEdgeToEdge()`: 탭 줄·주소 표시줄은 상태 표시줄·디스플레이 컷아웃 인셋, 하단 상태 표시줄은
  내비게이션 바 인셋, 입력란과 대화상자는 `imePadding()`, 범위 선택용 체크 열은 왼쪽 시스템 제스처
  인셋만큼 띄운다. 화면 방향은 고정하지 않는다.

### 2.7 이름 표시
모든 화면(행, 브레드크럼, 대화상자, 알림, 상태 표시줄)은 `DisplayName.of(raw)`를 거친다.
C0/C1 제어 문자(줄바꿈 포함)는 U+2400 블록의 보이는 기호로 바꾸고, 글자 순서를 바꾸거나 줄을 끊는
문자(U+200E, U+200F, U+061C, U+202A–U+202E, U+2066–U+2069, U+2028, U+2029)는 `·`로 바꾸며, 문장 안에
넣을 때는 FSI…PDI(U+2068/U+2069)로 감싼다. 한 줄 표시, 가운데 줄임(확장자가 항상 보임). 작업은 항상 원래
`Path`를 쓰며 표시 문자열에서 경로를 다시 만들지 않는다.

크기는 1024 단위, Windows 표기(바이트/KB/MB/GB), 유효 숫자 3자리. 날짜는 한국어
`yyyy-MM-dd a h:mm`, 영어는 시스템 짧은 형식. `Formatter.formatFileSize`는 쓰지 않는다(1000 단위).

## 3. 상태와 데이터 흐름

### 3.1 위치(Location)
`sealed interface Location { Home; Folder(path: Path); RecycleBin; SearchResults(root: Path, query: String) }`

- `Folder.path`는 항상 `toRealPath()`로 정규화한 경로. `/sdcard`, `/storage/self/primary`, 대소문자만
  다른 입력이 모두 같은 위치가 된다. 정규화 결과가 `VolumeRegistry`의 마운트된 루트 아래가 아니면
  "저장소 밖의 경로는 열 수 없습니다".
- "위로"는 볼륨 루트에서 홈으로 간다(홈은 ③에서 생기며, 그 전에는 비활성화).

### 3.2 구성 요소
- `ExplorerViewModel`: 화면이 쓰는 창구. 아래 부품을 묶어 `StateFlow<ExplorerUiState>`를 만들 뿐
  상태를 직접 갖지 않는다.
- `TabsStore`: 순수 리듀서 `(TabsModel, TabAction) -> TabsModel`. 안정적인 `TabId`, 활성 탭,
  탭마다 `location`, 뒤로·앞으로 스택(각 최대 50), `SelectionState`, `viewOverride`.
  처음부터 여러 탭 구조(①a에서는 탭 UI만 숨김).
- `SelectionState(items: LinkedHashSet<Path>(목록 순서), anchor: Path?, focus: Path?)`. 선택이 비어 있지
  않으면 선택 모드. 이동하면 선택 해제. 목록이 바뀌면 존재하는 항목만 남김. 작업이
  끝나면(`Done.created`) 만들어진 항목이 새 선택이 된다.
- `ListingLoader`: `TabId`마다 `StateFlow<ListingState>`. 새 이동이 오면 이전 작업 취소.
- `ClipboardHolder`: `Clipboard(paths, mode = COPY | CUT)`(경로는 잡을 때 정규화). 붙여넣을 때 경로를
  다시 확인하고 없는 항목은 건너뛴다. CUT 붙여넣기는 **실제로 옮긴 항목만** 클립보드에서 뺀다.
- `OperationRunner`(§5): 작업 상태는 여기 있고 ViewModel은 관찰만 한다.

### 3.3 목록
`ListingState = Loading | Loaded(rows, refreshing) | Streaming(rows, done, capped, skippedDirs) | Error(FsError)`,
`Row(entry: FileEntry, extra: BinInfo(originalPath?, deletedAt) | SearchInfo(parentDir) | null)`.

- `FileEntry(path, name, kind = FILE | DIR | SYMLINK | OTHER, size, modified, mime, hidden)`.
  항목마다 `Files.readAttributes(BasicFileAttributes, NOFOLLOW_LINKS)` 한 번. 정렬은 메인 스레드 밖에서.
- 숨김: 이름이 `.`으로 시작하면 숨김(설정 "숨긴 항목 표시", 기본 끔). `.Recycle.Bin`과 `*.xpart`는
  설정과 관계없이 항상 숨기며 선택·붙여넣기 대상·검색에서도 뺀다.
- 새로 고침: 앱이 돌아올 때(활성 탭만 다시 읽고 나머지는 "오래됨" 표시), 그 폴더와 관련된 작업이
  끝났을 때, ⋯ ▸ 새로 고침과 F5. 새로 고침은 `Loaded(refreshing = true)`로 내용을 유지한 채 바꿔서
  깜박임과 스크롤 위치 변화가 없다. 끌어서 새로 고침, FileObserver는 v1에 없다.
- 탭마다 `LazyListState`/`LazyGridState`를 `TabId`로 보관, 기록 항목에 첫 표시 항목 위치와 포커스
  이름을 담아(메모리만) 뒤로 가면 스크롤이 복원된다.

### 3.4 뒤로 가기
`ExplorerUiState.backTarget`: 열린 메뉴·대화상자 → 선택 → 열린 서랍 → 활성 탭의 뒤로 스택 → `null`.
셸은 `BackHandler(enabled = backTarget != null)`만 쓴다(`onBackPressed`/`KEYCODE_BACK` 사용 안 함).
`null`이면 비활성화되어 예측형 뒤로 가기로 홈 화면에 간다. Alt+←와 (입력란 포커스가 없을 때)
Backspace도 같은 동작을 부른다.

### 3.5 저장(SettingsStore)
`UiPrefs` 패턴: `SettingsStore(context: Context?)`, `null`이면 메모리만(테스트). 키를 나눠 저장한다.

- `session`: `PersistedSession(v = 1, tabs: List<PersistedTab(id, location, back, forward)>, active)`.
  위치는 태그 문자열 `folder:<volumeUuid>:<relPath>`, `home`, `bin`. `SearchResults`는 검색 루트 폴더로
  저장하고 복원할 때 다시 실행하지 않는다. 선택·목록·클립보드는 저장하지 않는다. 이동과 탭 변경 때
  저장(선택 변경 때는 저장하지 않음).
- `favorites`: `(volumeUuid, relPath, label)` 목록, 순서 유지.
- `recents`: 최근 연 파일 최대 20개.
- `views`: 폴더별 보기·정렬, 약 500개 LRU.
- `global`: 숨긴 항목 표시(끔), 확장자 표시(켬), 시스템 강조색(끔), 테마(시스템/라이트/다크).
- `Json { ignoreUnknownKeys = true }`, 탭마다 따로 디코딩해서 하나가 깨져도 나머지는 복원.
- 볼륨이 지금 없으면 그 탭·즐겨찾기를 홈으로 바꾸지 않고 "사용할 수 없음"으로 보여 준다.
- `SavedStateHandle`은 쓰지 않는다(SharedPreferences가 유일한 원천).

### 3.6 명령 활성화
`commandsFor(locationKind, selection, clipboard, volumeTraits, restricted): Set<Command>` 순수 함수.
예: 휴지통에서는 복원·영구 삭제·휴지통 비우기만, `Android/data` 아래에서는 새로 만들기·붙여넣기·
이름 바꾸기 비활성, 검색 결과에서는 붙여넣기 비활성.

## 4. 파일 시스템 계층(`fs/`)

### 4.1 FsBackend
모든 조회·변경은 `FsBackend`를 거친다: `list`, `readAttributes(noFollow)`, `isSameFile`, `fileKey`,
`openRead`, `createNew`(CREATE_NEW), `force`, `atomicMove`, `delete`(비재귀), `createDirectory`,
`setMtime`, `availableBytes`, `realPath`. 실제 구현은 NIO, 테스트 구현은 §9의 `FaultyFs`.
`File.walk`, `copyRecursively`, `deleteRecursively`, `Files.walkFileTree`는 `fs/`에서 쓰지 않는다.

### 4.2 공통 규칙
- **링크와 특수 파일**: 모든 조회는 `NOFOLLOW_LINKS`. 심볼릭 링크 삭제는 링크만 지운다. 복사·이동·
  압축은 링크와 장치·FIFO·소켓을 "지원하지 않는 항목"으로 건너뛴다. 미리보기는 거부.
- **Walker**: 한 가지 일시 중단 순회기(디렉터리 스택, 항목마다 `ensureActive()`, 읽을 수 없는 폴더는
  건너뛰고 셈). 개수 세기, 복사 계획, 삭제, 검색이 모두 이것을 쓴다. 복사는 세기 단계에서 만든 목록을
  그대로 사용(다시 순회하지 않음).
- **대소문자**: 모든 볼륨을 대소문자 무시로 다룬다(Android 공유 저장소는 FUSE에서 대소문자 무시,
  SD·USB는 vfat/exFAT). 충돌 판단과 고유 이름 찾기는 문자열 비교가 아니라 파일 시스템으로만 한다
  (`exists(NOFOLLOW)` + `isSameFile`, `CREATE_NEW`/`createDirectory`의 `FileAlreadyExistsException`).
  이름 비교가 필요한 곳은 주입 가능한 `NameRules(caseInsensitive)`를 쓴다.
- **제한 경로**: `isRestrictedAppDataPath(volumeRoot, path)` — `<루트>/Android/data`, `<루트>/Android/obb`와
  그 아래(대소문자 무시). 목록 위에 "Android에서 제한한 폴더" 배너, 새로 만들기·붙여넣기·이름 바꾸기
  비활성, 검색에서 제외. `REQUEST_INSTALL_PACKAGES`가 허용되면 obb는 예외.

### 4.3 쓰기 규칙(WriteProtocol)
복사, 볼륨 간 이동, 바꾸기, ZIP 압축, ZIP 풀기, 복사로 하는 복원에 모두 적용한다.

1. 대상 폴더에 숨김 임시 파일 `.<이름>.<opId>.xpart`를 `CREATE_NEW`로 연다.
2. 1 MiB 버퍼로 쓴다. 끝나면 쓴 크기 == 원본 크기 확인, 수정 시각을 원본으로 설정(실패해도 항목은
   성공, 로그만).
3. **fsync 정책**: 원본을 지울 때(이동), 기존 파일을 바꿀 때, 대상이 이동식 볼륨일 때만
   `FileChannel.force(true)`. 내부 저장소로의 단순 복사는 "임시에 쓰고 이름 바꾸기"만으로 충분하다
   (프로세스가 죽어도 페이지 캐시는 남고, 잃는 경우는 전원 차단·카드 제거뿐).
4. `atomicMove(tmp, final)`로 확정. "바꾸기"에서도 기존 대상은 이 순간까지 그대로다.
5. 취소·실패하면 `withContext(NonCancellable)` 안의 파일별 `try/finally`에서 임시 파일만 지운다.
6. 이동의 원본 파일은 **그 파일이 확정된 뒤에만** 지운다.

폴더 수정 시각은 하위 항목이 끝난 뒤 후위 순서로 설정. `COPY_ATTRIBUTES`는 쓰지 않는다.

**불변식**: 어느 시점에 프로세스가 죽어도, 모든 원본 파일은 원래 자리에 온전히 있거나 완전한 최종
대상으로 존재한다. 최종 이름을 가진 잘린 파일은 없다. 남는 것은 `*.xpart`뿐이며 저널이 정리한다.

### 4.4 이름
- `uniqueName(dir, stem, ext, style = KeepBoth | CopyOf, labels)`: 라벨은 리소스에서 주입
  (한국어 `새 폴더`, `새 텍스트 문서`, ` - 복사본`; 영어 `New folder`, `New Text Document`, ` - Copy`).
  KeepBoth는 `이름 (2).ext`, CopyOf는 `이름 - 복사본.ext` → `이름 - 복사본 (2).ext`. 확장자는 마지막
  점부터(점으로 시작하는 파일은 확장자 없음). 255 UTF-8 바이트에 맞게 코드 포인트 경계에서 자른다.
  이름은 `CREATE_NEW`/`createDirectory`로 원자적으로 확보하고 실패하면 다음 번호.
- `NameValidator(volumeTraits)`: 항상 거부 — 빈 이름, `/`, NUL, `.`, `..`, C0/C1 제어 문자, 양방향 제어
  문자, 255 UTF-8 바이트 초과. 이동식(FAT 계열) 볼륨은 추가로 `\ : * ? " < > |`와 끝의 점·공백 거부,
  255 UTF-16 단위 확인, 메시지 "파일 이름에는 다음 문자를 사용할 수 없습니다: \ / : * ? " < > |".
  앞뒤 공백은 자동으로 다듬는다.

### 4.5 파일 작업(FileOps)

```kotlin
fun interface ConflictResolver { suspend fun resolve(c: Conflict): Resolution }
data class Resolution(val action: ConflictAction, val applyToAll: Boolean)
suspend fun FileOps.run(spec: OperationSpec, resolver: ConflictResolver,
                        onProgress: (OpProgress) -> Unit): OpResult
```

- `@Serializable OperationSpec(id, kind = COPY | MOVE | DELETE_TO_BIN | DELETE_PERMANENT | RESTORE |
  EMPTY_BIN | COMPRESS | EXTRACT, sources: List<String>, target: String?, options)`: 큐·저널·재시도의 단위.
- `OpProgress(totalBytes, doneBytes, totalItems, doneItems, currentName)`, 주입된 `TimeSource`로 100ms마다
  최대 한 번. 첫 단계에서 Walker로 개수·바이트를 센다.
- `OpResult = Done(created, failed: List<ItemFailure>, leftInSource) | Failed(FsError, done, total)`.
  취소는 `CancellationException`으로 전파되고 runner가 `Cancelled`로 바꾼다.
- **같은 파일 검사**(충돌을 묻기 전): `exists(dst, NOFOLLOW) && isSameFile(src, dst)`이면 COPY는 묻지 않고
  `CopyOf` 이름으로 복사(같은 폴더에 붙여넣기), MOVE는 아무것도 하지 않는다. 같은 파일에는 "바꾸기"를
  절대 제공하지 않는다.
- **자기 안으로 복사·이동**: 대상 폴더와 그 상위 폴더들의 `fileKey`(NOFOLLOW)를 각 원본 폴더와 비교해
  일치하면 `CrossesIntoSelf`로 거부. 기존 대상이 원본이거나 원본의 상위인 "바꾸기"도 거부.
- **충돌** `Conflict(kind, source: FileEntry, existing: FileEntry)`:
  - `FILE_FILE`: 바꾸기 / 건너뛰기 / 둘 다 유지
  - `DIR_DIR`: **병합**(하위 항목마다 충돌 처리, 기존 폴더는 절대 지우지 않음, 대화상자 문구 "폴더 병합")
    / 건너뛰기
  - `FILE_DIR`, `DIR_FILE`: 건너뛰기 / 둘 다 유지
  - "모두 적용"은 작업마다·종류마다 기억.
- **이동**: 같은 볼륨인지 미리 판단하지 않는다. 최상위 원본마다(같은 파일·충돌 검사 후, 대상이 없거나
  바꾸기를 고른 경우) `atomicMove`를 시도하고, `AtomicMoveNotSupportedException`(EXDEV)이면 그 하위
  트리를 쓰기 규칙으로 복사한다. 원본 폴더는 후위 순서로 비재귀 삭제하고, 내용이 남으면(건너뜀·실패)
  남겨 두고 "원본에 N개 항목이 남았습니다". 실패 목록은 "복사됨, 원본 삭제 실패"와 "복사 안 됨"을
  구분해 다시 시도가 중복을 만들지 않게 한다.
- **이름 바꾸기**: 대소문자·NFC만 다르고 `isSameFile`이면 같은 폴더의 임시 이름을 거쳐 두 단계로
  (`a.txt` → `.~ren-<id>` → `A.txt`, 실패하면 되돌림). 그 밖에는 존재 확인 후 `atomicMove`.
  즉시 실행(큐에 넣지 않음). 진행 중인 작업이 쓰는 경로면 "진행 중인 작업이 사용하는 항목입니다".
- **새로 만들기**: 폴더(`새 폴더`), 텍스트 문서(`새 텍스트 문서.txt`). 만든 뒤 선택하고, 보이게
  스크롤하고, 이름 바꾸기를 시작한다.
- **공간 확인**: 시작 전에 복사 경로로 가는 바이트만 `availableBytes`(StatFs)와 비교(여유 64 MiB).
  권고용이며, 실행 중 `ENOSPC`는 오류 분류(§8)로 처리. 이름 바꾸기로 끝나는 이동은 확인하지 않는다.
- **FAT32 사전 검사**: 이동식 볼륨으로 복사·이동할 때 계획에서 허용되지 않는 이름과 4 GiB−1 초과
  파일을 미리 찾아 "이 N개 항목 건너뛰기"를 제안.

### 4.6 휴지통(RecycleBin)
- 위치: 볼륨마다 `<볼륨 루트>/.Recycle.Bin/`. 항목: `<uuid>/<원래 이름>` + `<uuid>.json`
  `{v, originalPath, deletedAt, size, isDir}`. `originalPath`는 `toRealPath(부모)/이름`.
- **삭제 순서**: `<uuid>/` 만들기 → `<uuid>.json`을 임시+force+원자적 이름 바꾸기로 기록 → 항목을
  `<uuid>/<이름>`으로 `atomicMove`.
- **복원**: 원래 부모가 없으면 다시 만들고, 이름 충돌은 일반 충돌 처리. 항목을 되돌린 뒤 json 삭제,
  `<uuid>/` 삭제. `originalPath`가 절대·정규 경로가 아니거나 `..`가 있거나 다른 볼륨이거나 제한 경로면
  "원래 위치 알 수 없음"으로 취급.
- **영구 삭제·비우기**: 내용 먼저, json 나중.
- **정합성 맞추기**(휴지통을 볼 때마다): 내용 없는 json은 삭제, json이 없거나 깨진 내용은 "원래 위치
  알 수 없음"(이름 + 수정 시각)으로 표시하고 볼륨 루트로 복원하거나 영구 삭제할 수 있다.
- 휴지통으로 옮길 수 없으면(EROFS, EACCES, EXDEV, 볼륨을 못 찾음, 휴지통 자체나 그 안의 항목)
  "휴지통으로 이동할 수 없습니다 — 영구 삭제할까요?"를 묻는다. 조용히 영구 삭제로 바꾸지 않는다.
- **확인**: 휴지통으로 삭제는 확인하지 않는다(Windows 기본). 영구 삭제(⋯, Shift+Delete, 휴지통 안,
  비우기, 휴지통 불가 대체)는 항상 항목 수와 "이 작업은 되돌릴 수 없습니다"로 확인.
- 휴지통 화면 열: 이름, 원래 위치, 삭제한 날짜, 크기. 명령: 복원, 영구 삭제, 휴지통 비우기.
  여러 볼륨의 휴지통을 합쳐서 보여 준다.

## 5. 작업 실행(`ops/`)

- `OperationRunner`: `ExplorerApp`(Application)이 가진 프로세스 싱글톤,
  `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`(AudacityApp과 같은 방식).
  직렬 FIFO 작업자 하나. `StateFlow<RunnerState(queue, running: RunningOp?, pendingConflict, finished)>`.
- **충돌 대화**: runner가 `ConflictResolver`를 구현해 `PendingConflict(opId, conflict,
  CompletableDeferred<Resolution>)`를 상태에 올리고, UI는 그 상태로 대화상자를 그린 뒤 `complete()`를
  부른다. 회전, 언어 변경, 다크 모드, 화면 크기 변경으로 액티비티가 다시 만들어져도 유지된다. 작업을
  취소하면 deferred도 취소되고 정리는 정상적으로 진행된다.
- **FileOpsService**: `android:foregroundServiceType="dataSync"`, 권한 `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`. 큐가 비어 있다가 작업이 들어오면(항상 화면이 보이는
  상태의 사용자 동작) `ContextCompat.startForegroundService`로 시작, 즉시 `startForeground`,
  `START_NOT_STICKY`, 큐가 비면 `stopSelf`.
  - 작업 중에는 `PARTIAL_WAKE_LOCK`(타임아웃 있음, `finally`에서 해제).
  - 진행 중 알림 하나: 진행률, 취소 동작. 충돌 대기 중이면 "확인이 필요합니다", 누르면 앱이 열린다.
  - `onTimeout(int, int)`(targetSdk 35+의 dataSync 하루 6시간 한도): 실행 중 작업을 정상 정리로 취소한 뒤
    `stopSelf`.
  - 시작이 거부되면(`ForegroundServiceStartNotAllowedException`) 프로세스 안에서 실행하고 "앱을 연 채로
    두세요"를 알린다.
  - `POST_NOTIFICATIONS`는 첫 작업 때 한 번 요청(거부해도 서비스는 동작).
- **저널**: `filesDir/ops/<opId>.json` = OperationSpec + 진행 중 임시 경로 + 확정된 개수. 시작할 때와 항목이
  확정될 때마다 임시+이름 바꾸기로 기록, 끝나면 삭제. 앱 시작 시와 각 작업 전에 기록된 임시 경로를
  지운다. 남은 저널이 있으면 "작업이 중단되었습니다: X/Y 완료 — 다시 시도"(같은 spec을 남은 항목에 대해
  다시 실행).
- **미디어 스캔**: 작업이 끝날 때마다 `MediaScannerConnection.scanFile(context, paths[], null, cb)` 한 번.
  확정된 최상위 생성 경로만(복사·압축 풀기·복원 결과, 볼륨 간 이동 결과, 새 ZIP). 삭제와 같은 볼륨 이름
  바꾸기는 스캔하지 않는다. FUSE를 거치지 않는 볼륨(§6.3의 `fuseBacked = false`)은 건너뛴다.
  `platform/MediaScan` 인터페이스 뒤에 두고 테스트에서는 아무것도 하지 않는 구현.
- 작업 UI: 시작하면 Fluent 진행률 대화상자(이름, 진행 막대, 남은 항목, 취소, "백그라운드에서 실행"),
  최소화하면 상태 표시줄 칩. 끝나면 실패가 없을 때 짧은 알림 막대, 실패가 있으면 목록 대화상자와
  "실패한 항목 다시 시도".

## 6. 검색, ZIP, 볼륨

### 6.1 검색
- `Search.find(root, query, includeSubfolders)`: Walker를 쓰는 `channelFlow`, 약 200ms 또는 200개마다
  묶어서 보낸다. `.Recycle.Bin`, `*.xpart`, 제한 경로, 특수 파일은 제외. 최대 10,000개(`capped`).
- 일치: 이름과 검색어를 NFC + `lowercase(Locale.ROOT)`로 비교, 부분 문자열 또는 `*`/`?` 패턴
  (직접 만든 매처, 사용자 입력으로 정규식을 만들지 않음).
- 주소 표시줄 오른쪽 검색 상자(휴대폰은 명령 모음의 🔍), IME 동작 또는 300ms 디바운스(조합 중에는
  실행하지 않음). 결과는 같은 탭의 `SearchResults` 위치(뒤로 가면 원래 폴더). 결과 행에 부모 폴더 표시,
  상단에 현재 순회 중인 폴더와 "N개 폴더 건너뜀".
- 결과에서 열기·복사·잘라내기·삭제·이름 바꾸기·압축 가능, 붙여넣기 불가.

### 6.2 ZIP
**압축**: 선택을 정리(상위 폴더가 선택된 항목 제거) → `<첫 항목 이름>.zip`(uniqueName)을 쓰기 규칙으로
현재 폴더(검색 결과에서는 검색 루트)에 만든다. `ZipOutputStream`(UTF-8, EFS 플래그), 출력 파일과 임시
경로는 순회에서 제외. 진행률은 복사와 같음.

**압축 풀기**("모두 압축 풀기…"):
- `java.util.zip.ZipFile(File, Charset)` 사용(중앙 디렉터리로 항목·크기를 미리 알 수 있음).
- **인코딩**: `chooseZipCharset(open: (Charset) -> List<String>)` — UTF-8로 열고, `IllegalArgumentException`/
  `ZipException`이 나거나 이름에 U+FFFD가 있으면 `x-windows-949`, `MS949`, `windows-949`, `EUC-KR` 중
  지원되는 첫 문자셋으로 다시 연다. EFS 플래그가 있는 항목은 어느 쪽이든 UTF-8로 읽힌다. 모두 실패하면
  "지원하지 않는 파일 이름 인코딩". 대화상자에 감지한 인코딩과 선택(자동 / UTF-8 / 한국어 CP949).
  풀어낸 이름은 NFC로 정규화(macOS의 NFD 한글).
- **이름 검사**(쓰기 전에 모든 항목): `ZipNames.sanitize(raw)` — `\`→`/`; NUL·C0/C1 제어 문자, 앞의 `/`,
  드라이브 접두어 `X:`, `..` 조각이 있으면 위험; `.`과 빈 조각은 버림; 조각마다 255 UTF-8 바이트.
  하나라도 위험하면 압축 풀기 전체를 거부("안전하지 않은 경로가 있어 압축을 풀 수 없습니다").
  API 34+의 `ZipPathValidator`가 생성자에서 던지는 `ZipException`도 같은 메시지로. `clearCallback()`은
  부르지 않는다.
- **기록**: 대상 폴더의 숨김 임시 폴더 `.<zip 이름>.<opId>.xpart/`에 푼다. 항목마다 `CREATE_NEW`,
  이미 있으면(중복·대소문자 충돌) 둘 다 유지하고 요약에 기록. 항목별로 쓴 바이트를 세어 선언 크기를
  넘으면 중단, CRC32와 크기 검증. 불일치·`ZipException`·`DataFormatException`은 "손상되었거나 암호로
  보호된 ZIP입니다". 시작 전 선언 크기 합계로 공간 확인, 실행 중 `ENOSPC`는 치명적 오류.
  성공하면 임시 폴더를 `uniqueName(<zip 이름>)`으로 원자적 이름 바꾸기(병합하지 않음). 실패·취소하면 임시
  폴더 삭제.
- 중첩 압축은 자동으로 풀지 않는다. 심볼릭 링크 항목은 일반 파일로 푼다. 수정 시각은
  `ZipEntry.lastModifiedTime`.
- .zip을 탭하면 "모두 압축 풀기" 대화상자(v1에서는 ZIP 안을 폴더처럼 탐색하지 않음).

### 6.3 볼륨(VolumeRegistry)
- `StateFlow<List<Volume(uuid, label, root: Path?, state, isPrimary, isRemovable, fuseBacked, traits)>>`,
  `StorageManager.getStorageVolumes()` + `registerStorageVolumeCallback`. 마운트 해제 중이면 `root = null`.
  `fuseBacked`는 루트가 `/storage` 아래인지. `traits`는 대소문자 무시(항상), FAT 계열 이름 규칙(이동식).
- 보이지 않는 공개 볼륨(일반 AOSP 계열의 USB OTG)은 `/mnt/media_rw/<uuid>`에 있다. 권한을 받기 전에
  시작된 프로세스라 EACCES가 나면 "앱을 다시 시작하면 이 드라이브를 열 수 있습니다"와 다시 시작 동작.
- 볼륨이 EJECTING/UNMOUNTED로 바뀌면(콜백 또는 `ACTION_MEDIA_EJECT`) 그 볼륨을 쓰는 작업을
  `Failed(VolumeRemoved)`로 즉시 취소하고, 그 볼륨의 미리보기·썸네일 파일 디스크립터를 닫고, 탭을
  "사용할 수 없음"으로 표시한다. vold는 약 5초 뒤 그 볼륨의 파일을 연 프로세스를 강제 종료하기 때문이다.
- 실행 중 전체 파일 접근 권한을 회수하면 시스템이 앱을 종료한다. 별도 UI 없이 저널(§5)이 처리한다.

## 7. 미리보기와 열기(`preview/`, `platform/`)

파일을 탭하면: 내장 미리보기가 되는 유형이면 전체 화면 뷰어(넓은 화면에서 미리보기 창이 켜져 있으면
창에), 아니면 "다른 앱으로 열기". 뷰어 상단 막대: 이름, 공유, 연결 프로그램…, 속성, 닫기.
열면 최근 항목에 추가. 디코딩은 인터페이스 뒤에 두어 UI 테스트에서 가짜로 바꾼다.

- **이미지**(jpg, png, webp, gif, heic/heif, bmp, avif — `ImageDecoder`가 지원하는 것): 화면 크기로 줄여
  디코딩(`setTargetSize`), 핀치 확대(최대 원본 픽셀의 2배), 두 번 탭 확대/맞춤, 가로로 넘겨 같은 폴더의
  이전/다음 이미지(현재 정렬 순서). 애니메이션 GIF/WebP는 `AnimatedImageDrawable`. 실패하면
  "이미지를 표시할 수 없습니다" + 다른 앱으로 열기.
- **텍스트**: MIME이 `text/*`이거나 확장자 허용 목록(txt, log, csv, tsv, md, json, xml, html, ini, cfg, conf,
  yaml, yml, smi, srt, vtt, kt, java, py, js, ts, c, cpp, h, sh, gradle, properties). 최대 1 MiB를 직접 읽기
  루프로 읽는다(`readNBytes`는 API 33). `TextSniffer`: BOM(EF BB BF → UTF-8, FF FE → UTF-16LE,
  FE FF → UTF-16BE) → 앞 8 KiB에 NUL이 있으면 바이너리(다른 앱으로 열기) → 엄격한 UTF-8
  (`REPORT`, 잘린 파일이면 `endOfInput = false`로 끝의 잘린 다중 바이트를 오류로 보지 않음) → CP949.
  잘린 UTF-16은 짝수 길이로 자름. 줄 단위로 메인 스레드 밖에서 나눠 `LazyColumn`, 고정폭 글꼴, 줄 번호,
  2,000자를 넘는 줄은 같은 줄 번호로 이어지는 행으로 나눔. 1 MiB를 넘으면 "처음 1 MiB만 표시합니다".
  상태 표시줄에 감지한 인코딩, ⋯에서 바꾸기(자동 / UTF-8 / 한국어 / UTF-16). 편집은 v1에 없다.
- **오디오·동영상**: 동영상은 `VideoView.setVideoURI(Uri.fromFile(file))`, 오디오는
  `MediaPlayer.setDataSource(FileDescriptor)`. Fluent 재생 컨트롤(재생/일시정지, 탐색 막대, 경과/전체 시간),
  `android.widget.MediaController`는 쓰지 않음. `AudioFocusRequest`, `ACTION_AUDIO_BECOMING_NOISY`로
  일시정지, `ON_STOP`에서 일시정지하고 위치를 저장했다가 다시 준비되면 그 위치로. 오류는
  "재생할 수 없는 파일" + 다른 앱으로 열기. 백그라운드 재생·전체 화면 전용 기능은 v1에 없다.
- **PDF**: 문서마다 `PdfRenderer` 하나, 모든 호출(닫기 포함)을 `Dispatchers.IO.limitedParallelism(1)`에서.
  페이지마다 `openPage → render → close`, 흰색으로 채운 ARGB_8888 비트맵, 너비는 화면 너비, 한 변 최대
  약 4096 px·16 MP. 바이트 기준 `LruCache`(약 3화면), 측정 전에는 자리 표시 비율. 세로 `LazyColumn`,
  핀치 확대. `SecurityException` → "암호로 보호된 PDF" + 다른 앱으로 열기, `IOException` → "손상된 파일".
  `onDispose`와 볼륨 제거 시 닫기.
- **썸네일**(큰 아이콘·타일 보기, 미리보기 창): `ThumbnailUtils.createImage/Video/AudioThumbnail(File,
  Size, CancellationSignal)`을 `Dispatchers.IO.limitedParallelism(3)`에서, 보이는 항목마다 하나
  (`LaunchedEffect(key)`), 스크롤로 사라지면 신호 취소. `LruCache<ThumbKey(path, mtime, sizePx), Bitmap>`,
  크기 = `allocationByteCount`, 최대 ≈ `maxMemory/8`. 실패는 "썸네일 없음"으로 캐시.
- **다른 앱으로 열기**: FileProvider(authority `${applicationId}.files`, `exported=false`,
  `grantUriPermissions=true`), `file_paths.xml`은 `<root-path name="storage" path="storage/"/>`와
  `<root-path name="usb" path="mnt/media_rw/"/>`만(앱의 `/data`는 노출되지 않음). 읽기 권한만 부여,
  `setDataAndType(uri, mime)`. 파일을 탭할 때는 일반 `ACTION_VIEW`("항상" 선택 가능), "연결 프로그램…"
  명령만 `createChooser`. `ActivityNotFoundException` → "이 파일을 열 수 있는 앱이 없습니다",
  `getUriForFile`의 `IllegalArgumentException` → "이 파일은 열 수 없습니다". `resolveActivity`로 미리
  확인하지 않는다(패키지 공개 범위 제한). 공유도 같은 Uri로 `ACTION_SEND`/`ACTION_SEND_MULTIPLE`.
- **APK**: `REQUEST_INSTALL_PACKAGES` 선언. 탭하면 `canRequestPackageInstalls()`가 거짓일 때
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES`로 안내하고, 허용되면 FileProvider Uri로 `ACTION_VIEW`
  (`application/vnd.android.package-archive`).

## 8. 오류 처리

- **권한**: 시작 시 `Environment.isExternalStorageManager()`가 거짓이면 전체 화면 Fluent 안내와 버튼.
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`(package: Uri) → 없으면
  `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` → 없으면 `ACTION_APPLICATION_DETAILS_SETTINGS`. 앱이
  돌아올 때 다시 확인. 권한이 없으면 다른 화면을 보여 주지 않는다. 볼륨·경로 존재 확인은 권한 통과
  뒤에만.
- **목록 오류**: 접근 거부 → "이 폴더에 접근할 수 없습니다"(제한 경로면 §4.2 배너), 없음 → 메시지와
  "위로" 버튼, 볼륨 없음 → "드라이브를 사용할 수 없습니다".
- **작업 오류 분류**:
  - 항목별(목록에 모으고 계속): AccessDenied, NotFound, 이름 불가(EINVAL/ENAMETOOLONG → "대상 드라이브에서
    허용되지 않는 이름"), EFBIG → "이 드라이브(FAT32)에는 4GB보다 큰 파일을 저장할 수 없습니다",
    지원하지 않는 항목(링크·특수 파일).
  - 작업 전체 중단: ENOSPC/EDQUOT(공간 부족, 휴지통 크기도 안내), EIO, EROFS, 대상 볼륨 제거·루트 없음.
    즉시 멈추고 현재 임시 파일 삭제, 원본은 지우지 않음, "N/M 완료" 메시지.
  - 모든 `catch`는 `CancellationException`을 다시 던진다. `Error`(OOM 등)는 항목별 실패로 취급하지 않는다.
- **이름 바꾸기 대화상자**: 파일은 확장자를 뺀 부분만 미리 선택, 폴더와 점으로 시작하는 파일은 전체 선택.
  확장자가 바뀌면 "확장자를 변경하면 파일을 사용할 수 없게 될 수도 있습니다. 변경하시겠습니까?".
  §4.4 검사를 입력하는 동안 표시.
- 예상하지 못한 예외는 runner와 로더에서 `IoFailure`로 바꿔 상태로 보여 주며 앱이 종료되지 않는다.

## 9. 테스트

- **`fs/` JVM 단위 테스트**(JUnit `TemporaryFolder`) + `FaultyFs` 장식자:
  - 지정한 두 루트 사이에서 `AtomicMoveNotSupportedException`
  - N바이트째 또는 지정 경로에서 ENOSPC/EFBIG/EINVAL/EIO
  - 대소문자 무시 이름 접기
  - AccessDenied 주입
  - 단계 k에서 "강제 종료"(정리 없이 던지고 버림) → 이후 저널 정리와 휴지통 정합성 맞추기를 디스크
    상태에 대해 실행
- **불변식 테스트**: 모든 k에서 §4.3 불변식, 저널·휴지통이 일관된 상태로 정리됨.
- **개별 시나리오**: 같은 폴더 복사(원본 바이트 유지 + `- 복사본`), 같은 폴더 잘라내기 붙여넣기(변화 없음),
  같은 폴더 복사에 바꾸기가 제공되지 않음, 대소문자만 바꾸는 이름 변경, 별칭(심볼릭 링크) 경로의 자기 안
  복사 거부, 폴더 병합(대상에만 있는 파일 유지), FILE_DIR은 건너뛰기/둘 다 유지만, 바꾸기 실패 시 원본
  대상 유지, 치명적 오류 즉시 중단, 볼륨 간 이동의 부분 실패와 다시 시도, 심볼릭 링크가 든 폴더 삭제 시
  링크 대상 보존, 휴지통 왕복·정합성·원래 위치 검증, uniqueName(한국어·영어, 255바이트 자르기),
  NameValidator, NameComparator, 크기·날짜 형식, 검색(패턴, NFC, 상한, 건너뛴 폴더 수), TextSniffer
  (CP949, UTF-16LE+BOM, 1 MiB 경계에서 잘린 한글, 바이너리).
- **ZIP 테스트 파일은 테스트 안에서 생성**: `ZipOutputStream(out, Charset.forName("MS949"))`로 EFS 없는 CP949
  이름(`가.txt`/`각.txt` 포함), EFS 있는 UTF-8, EFS 없는 NFD UTF-8, 경로 탈출·절대 경로·역슬래시·NUL 이름,
  중복·대소문자 충돌 이름, 선언 크기보다 큰 항목, CRC 불일치. 작은 암호 ZIP 하나만 저장소에 넣는다.
  압축→풀기 왕복.
- **`state/`·`ops/`**: TabsStore 리듀서(기록 상한, 선택, 뒤로 대상), 명령 활성화 함수, SettingsStore(null
  컨텍스트로 메모리 모드, 깨진 탭 하나 복원 실패), ClipboardHolder(CUT 부분 성공), OperationRunner
  `runTest`(제출 → 충돌 대기 → complete → Done, 대기 중 취소, 모두 적용 횟수). Robolectric
  `ServiceController`로 서비스 시작·중지 하나.
- **`ui/` Robolectric Compose 테스트**: 주소 표시줄 브레드크럼 이동과 직접 입력, 명령 모음 활성화,
  길게 누르기 선택과 컨텍스트 메뉴, 충돌·이름 바꾸기·삭제 확인 대화상자, `OnBackPressedDispatcher`를 통한
  뒤로 순서, 입력란 포커스 중 단축키, 접근성 의미(역할·상태·설명), FileProvider가 `/storage/XXXX-XXXX/x`는
  되고 `filesDir`는 안 됨, `values`와 `values-ko`의 키 집합이 같음.
- chmod 기반 테스트는 쓰지 않는다(루트로 실행되는 CI에서 의미 없음).
- **통과 기준**(단계마다): `./gradlew -Paudacity.buildNative=false :explorer:testDebugUnitTest :explorer:assembleDebug`,
  각 단계 끝에 한 번 `:explorer:assembleRelease`(R8·직렬화 문제 확인).
- **실기기 확인 목록**(`explorer/README.md`, 이 개발 환경에는 에뮬레이터가 없음): SD 카드 쓰기, USB OTG(권한
  받기 전·후), Android/data 배너, 화면을 끈 10분 복사, 복사 중 SD 카드 제거, 복사 중 전체 파일 접근 권한
  회수, 기록이 없을 때 예측형 뒤로 가기, 이름 바꾸기 대화상자 위 키보드, Windows에서 만든 한국어 ZIP,
  내부·SD에서 대소문자만 바꾸는 이름 변경, 같은 폴더 붙여넣기, SD의 파일 다른 앱으로 열기, 복사한 사진이
  갤러리에 보임, 볼륨 간 이동 후 수정 시각 유지, APK 설치.

## 10. 구현 단계

각 단계는 빌드·테스트가 통과한 상태로 커밋하며, 그 자체로 쓸 수 있는 앱이다.

| 단계 | 내용 |
|---|---|
| ①a 읽기 전용 탐색 | 모듈·빌드, FluentTheme와 기본 컴포넌트, 권한 화면, VolumeRegistry, 탐색 창(볼륨), 목록·4가지 보기·정렬, 주소 표시줄, 뒤로/앞으로/위로, 숨김 항목·확장자 설정, 세션 저장(탭 하나), 다른 앱으로 열기·공유·APK 설치, 속성, 단축키 기본. 시작 위치·대체 위치는 기본 볼륨 루트 |
| ①b 파일 작업 | FsBackend·쓰기 규칙·FileOps, 새로 만들기, 이름 바꾸기, 복사·잘라내기·붙여넣기, 충돌 대화상자, OperationRunner + FileOpsService + 저널 + 알림, 휴지통(탐색 창 항목, 복원·영구 삭제·비우기), 미디어 스캔 |
| ② 미리보기 | 이미지·텍스트·오디오/동영상·PDF 뷰어, 썸네일(큰 아이콘·타일), 넓은 화면 미리보기 창 |
| ③ 탭·홈·즐겨찾기 | 탭 줄 UI(TabsStore는 ①a부터 다중 탭), 홈(빠른 액세스, 드라이브 막대, 최근 항목), 즐겨찾기 고정·해제·순서, "위로"가 볼륨 루트에서 홈으로 |
| ④ 검색·ZIP | 검색(SearchResults 위치), ZIP 압축·풀기와 인코딩 선택 |

## 11. 결정 기록

| 결정 | 이유 |
|---|---|
| minSdk 30 | `MANAGE_EXTERNAL_STORAGE`가 Android 11부터. 9~10용 예전 권한 경로는 비용 대비 이득이 적음 |
| Compose Foundation 위 자체 Fluent 컴포넌트 | 앱의 차별점이 "Windows 11 탐색기처럼 보임". Material 3 컴포넌트는 리플·메뉴·대화상자 모습이 남음 |
| 볼륨별 `.Recycle.Bin` | Android에 일반 파일 휴지통이 없음. 같은 볼륨 안 이름 바꾸기라 빠름 |
| 임시 파일 + 원자적 이름 바꾸기 | 강제 종료·카드 제거 시 잘린 파일이 완전한 파일처럼 남는 것을 막음 |
| 포그라운드 서비스(dataSync) + wake lock | 앱 범위 코루틴만으로는 앱을 벗어나면 멈추거나 종료됨 |
| 모든 볼륨을 대소문자 무시로 취급 | Android 공유 저장소(FUSE)와 vfat/exFAT 모두 대소문자 무시 |
| ZIP 이름 UTF-8 → CP949 대체 | 한국어 Windows에서 만든 ZIP은 EFS 플래그 없이 CP949 |
| 길게 누르기 = 선택, 선택된 항목 길게 누르기/⋯ = 메뉴 | 사용자 결정 |
| 확장자 기본 표시 | 사용자 결정(위장 파일 식별, 모바일에서 유형 열이 자주 가려짐) |
| MIT | 사용자 결정. 탐색기는 Audacity 코드를 쓰지 않음 |
| APK 설치 지원 | 사용자 결정 |
| 새 서드파티 라이브러리 없음 | 플랫폼 API(ImageDecoder, PdfRenderer, MediaPlayer, java.util.zip)로 충분 |
