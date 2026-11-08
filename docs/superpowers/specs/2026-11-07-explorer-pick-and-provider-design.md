# 파일 탐색기 — 다른 앱의 파일 선택과 시스템 선택기 연동 설계

- 날짜: 2026-11-07
- 상태: 설계 승인됨(방식 A), 사양 검토 대기
- 기반: `docs/superpowers/specs/2026-10-23-explorer-design.md`(이하 "본 사양 §N"). 이 문서는 그 위에 더하는
  기능만 다루며, 여기서 바꾸지 않는 규칙(쓰기 규칙, 이름 규칙, 휴지통, 제한 경로, 계층)은 본 사양을 따른다.

## 0. 목표

**사용자가 정한 것**
- 크롬처럼 다른 앱에서 파일을 불러올 때 이 앱이 선택지로 뜬다.
- 시스템 "파일" 선택기에도 이 앱이 나온다.
- 범위는 **열기 + 저장**: 다른 앱이 이 앱을 통해 파일을 고르고, 다른 이름으로 저장하고, 폴더를 고르고,
  만들고, 고치고, 지울 수 있다.
- 구현 방식 A: 앱의 파일 계층(`fs/`) 위에 직접 구현해서 앱 안과 같은 안전 장치(임시 파일 후 원자적 이름
  바꾸기, 이름 검사, 휴지통)를 다른 앱의 요청에도 적용한다.
- 다른 앱이 지우는 파일도 휴지통으로 간다(휴지통을 쓸 수 없으면 영구 삭제).

**성공 기준**
- 크롬에서 `<input type="file">`을 누르면 앱 선택 창에 "파일 탐색기"가 있고, 거기서 고른 파일(들)을 크롬이
  이름·크기와 함께 받는다. 카카오톡·메일 첨부처럼 `ACTION_GET_CONTENT`를 쓰는 앱도 같다.
- 시스템 선택기(`ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT_TREE`) 옆면에
  "파일 탐색기" 항목이 있고, 그 안에 즐겨찾기 폴더와 드라이브, 최근 항목, 검색이 동작한다.
- 다른 앱이 이 앱을 통해 파일을 덮어쓰다 실패하거나 종료돼도 기존 내용이 그대로 남는다(본 사양 §4.3의
  불변식을 다른 앱의 덮어쓰기 저장에도 적용).
- 다른 앱이 지운 파일은 앱의 휴지통에서 복원할 수 있다.
- 휴지통 폴더, 임시 파일(`*.xpart`), `Android/data`·`Android/obb`는 보이지도 쓰이지도 않고, 문서 ID를
  조작해 볼륨 밖으로 나갈 수 없다.

**넣지 않는 것**: `ACTION_PICK`, 다른 앱이 보내는 파일을 받아 저장하기(`ACTION_SEND` 수신), 선택기 안의
복사·이동 최적화(`copyDocument`/`moveDocument`/`removeDocument` — 시스템이 스트림 복사로 대신함), 가상 문서·
형식 변환, 웹 링크, 문서 메타데이터, `findDocumentPath`, 이 앱 안에서 다른 앱의 DocumentsProvider 탐색.

## 1. 구성

새 패키지 두 개를 더한다. 본 사양 §1.2의 계층을 지킨다.

```
pick/      PickResult(돌려줄 Intent 만들기), PickActivity
state/     PickRequest(요청 해석, MIME 일치 — 순수, state/가 pick/을 참조하지 않도록 여기에 둠)
provider/  DocumentIds(문서 ID ↔ 경로, 검증), DocumentRows(커서 행), SafeSave(덮어쓰기 저장 핵심),
           ExplorerDocumentsProvider(시스템 연결부), ProviderDeps(의존성 찾기), SearchArgs(선택기의 검색 인자)
fs/        OpenSaves(진행 중인 덮어쓰기 저장의 목록 — 앱과 공급자가 함께 씀, 순수 Kotlin)
```

- `provider/`는 `fs/`, `platform/`, `data/`, `util/`, `ops/OperationJournal`만 쓴다. `state/`·`ui/`·`preview/`·`pick/`을
  쓰지 않는다(LayeringTest에 규칙 추가).
- `state/PickRequest`는 순수 Kotlin이고, `pick/PickResult`는 Intent 만들기만 Android이며, `PickActivity`는 기존
  셸을 선택 모드로 띄운다.
- `DocumentIds`, `PickRequest`의 MIME 일치, `SafeSave`의 핵심은 JVM 단위 테스트가 가능하게 Android 의존 없이
  만든다.

### 1.1 매니페스트
- `PickActivity`: `exported="true"`, `launchMode` 기본값, `excludeFromRecents="true"`, `taskAffinity=""`(호출한 앱의
  작업 안에서 열리고 앱의 주 작업과 섞이지 않음), `windowSoftInputMode="adjustResize"`, `configChanges` 없음.
  인텐트 필터: action `android.intent.action.GET_CONTENT`, category `DEFAULT`·`OPENABLE`, data `mimeType="*/*"`.
  (필터에 `OPENABLE`이 있어야 `OPENABLE`을 붙인 요청과도 맞는다. 붙이지 않은 요청과도 맞는다.)
- `ExplorerDocumentsProvider`: `authorities="${applicationId}.documents"`, `exported="true"`,
  `grantUriPermissions="true"`, `permission="android.permission.MANAGE_DOCUMENTS"`(시스템 선택기만 직접 연결 가능),
  인텐트 필터 action `android.content.action.DOCUMENTS_PROVIDER`.

## 2. 선택 모드 (`ACTION_GET_CONTENT`)

### 2.1 요청 해석
`PickRequest(mimeTypes: List<String>, allowMultiple: Boolean)`
- `mimeTypes`: `EXTRA_MIME_TYPES`가 있으면 그 목록, 없으면 `intent.type`, 둘 다 없으면 `*/*`. 소문자, 매개변수 제거
  (`baseMimeType`).
- `allowMultiple`: `EXTRA_ALLOW_MULTIPLE`(기본 false). `EXTRA_LOCAL_ONLY`는 무시(모두 로컬).
- 일치 규칙 `accepts(fileMime: String?)`: 목록에 `*/*`가 있으면 모두 참. `image/*`처럼 `*`로 끝나면 최상위 형식이
  같을 때 참. 그 밖에는 정확히 같을 때 참. MIME을 모르는 파일(`null`)은 `*/*`일 때만 참. 폴더는 항상 들어갈 수
  있다(선택 대상은 아님).

### 2.2 화면
기존 셸(`ExplorerShell` + `ExplorerViewModel`)을 `ExplorerDeps.pick: PickRequest?`로 선택 모드로 연다.
- **위치·세션**: 탭 하나, 탭 줄 없음. 앱의 세션(탭)을 읽지도 저장하지도 않는다. 시작 위치는 마지막으로 고른 파일의
  폴더(`SettingsStore`의 새 키 `pick_last`, 위치 태그 형식), 없거나 사용할 수 없으면 홈.
- **상단**: 제목 "파일 선택"(여러 개면 "파일 선택(여러 개)"), 오른쪽에 취소. 주소 표시줄·검색 상자는 그대로.
- **명령**: 열기, 모두 선택·선택 안 함·선택 영역 반전(여러 개일 때만), 새로 고침, 속성, 정렬, 보기, 옵션만 남긴다.
  새로 만들기, 잘라내기, 복사, 붙여넣기, 이름 바꾸기, 공유, 삭제, 영구 삭제, 압축, 압축 풀기, 복원, 휴지통 비우기,
  즐겨찾기 고정은 없다. 탐색 창의 휴지통 항목도 숨긴다. 미리보기 창과 내장 뷰어는 쓰지 않는다.
- **탭**: 폴더는 들어간다. 맞는 파일은 한 개 모드면 즉시 돌려주고, 여러 개 모드면 선택을 켜고 끈다(길게 누르지
  않아도 탭만으로). 맞지 않는 파일은 흐리게(텍스트 비활성 색) 보이고 탭해도 아무 일 없으며 접근성 상태는
  "사용할 수 없음"이다.
- **여러 개 모드 하단 막대**: "열기 (N)"(강조 버튼, N ≥ 1일 때만 활성)와 "취소".
- **뒤로**: 본 사양 §3.4의 순서. 더 처리할 것이 없으면 취소로 끝난다.
- **권한**: 모든 파일 관리 권한이 없으면 권한 화면을 먼저 보여 주고, 돌아왔을 때 다시 확인한다.

### 2.3 결과
- 돌려주는 주소: 이미 있는 FileProvider(`${applicationId}.files`)의 `content://` 주소. 읽기 권한만
  (`FLAG_GRANT_READ_URI_PERMISSION`). 받는 앱은 `OpenableColumns`로 이름·크기를 읽는다.
- 한 개: `setDataAndType(uri, mime ?: "application/octet-stream")`. 여러 개: `ClipData`에 모든 주소, `data`는 비우고,
  `type`은 공통 MIME(본 사양 §7의 공유와 같은 규칙). `data`가 있으면 `ClipData`를 읽지 않는 앱(크롬)이 있다.
- `RESULT_OK`로 끝내고, 고른 파일의 폴더를 `pick_last`에 저장하고, 고른 파일을 최근 항목에 넣는다.
- 고르는 순간 파일이 사라졌으면(경합) `error_not_found`를 보이고 목록을 새로 읽는다(돌려주지 않음).
- 취소·뒤로: `RESULT_CANCELED`.
- 한 번에 돌려주는 개수는 500개까지. 넘으면 "한 번에 최대 500개까지 고를 수 있습니다"를 보이고 돌려주지 않는다
  (바인더 크기 한도).

## 3. 시스템 선택기 (`ExplorerDocumentsProvider`)

### 3.1 루트
- 루트 하나: `ROOT_ID = "explorer"`, 제목 = 앱 이름(ko "파일 탐색기"), 아이콘 = 앱 아이콘, 문서 ID `"root"`.
  플래그 `FLAG_SUPPORTS_CREATE | FLAG_SUPPORTS_RECENTS | FLAG_SUPPORTS_SEARCH | FLAG_SUPPORTS_IS_CHILD |
  FLAG_LOCAL_ONLY`. 남은 공간 = 기본 볼륨의 여유 공간.
- **권한이 없으면 루트를 보내지 않는다**(빈 커서). 권한 상태가 바뀌면(앱이 다시 열릴 때 확인) 루트 목록 변경을
  알린다(`DocumentsContract.buildRootsUri`).
- (최종 검토) 앱 프로세스가 시작될 때도 루트 목록 변경을 알린다(IO 스레드에서, 설정을 읽지 않고). 권한을 회수하면
  시스템이 앱을 알림 없이 종료하고, 선택기는 읽어 둔 루트를 그대로 쓴다. 선택기의 다음 호출이 프로세스를 다시
  시작하면 이 알림으로 루트를 다시 읽어 "파일 탐색기"를 뺀다.
- (최종 검토) 권한이 없으면 `queryRoots` 밖의 모든 진입점(`queryDocument`, `queryChildDocuments`, `getDocumentType`,
  `openDocument`, `openDocumentThumbnail`, `queryRecentDocuments`, 두 `querySearchDocuments`, `createDocument`,
  `renameDocument`, `deleteDocument`)이 루트 목록 변경을 알리고 `FileNotFoundException`으로 거부한다.
  `isChildDocument`는 거짓이다. 그동안은 영구 권한을 가진 앱도 그 파일을 열 수 없다.
- 볼륨 연결·해제, 즐겨찾기 변경 때도 루트와 `root`의 하위 목록 변경을 알린다. (최종 검토) 볼륨 연결·해제 때는 모든
  문서의 변경(`content://<authority>/document`)도 알린다. 루트는 하나뿐이라 드라이브가 빠져도 사라지지 않으므로,
  꺼낸 드라이브의 폴더를 보여 주던 선택기는 이 알림으로 그 폴더를 다시 읽는다(불러오기 오류가 됨).

### 3.2 문서 ID
- `"root"`: 가상 폴더. 하위 = **즐겨찾기 폴더**(저장 순서, 마운트된 볼륨에 있고 폴더로 존재하는 것만, 표시 이름 =
  즐겨찾기 라벨, 볼륨 루트인 즐겨찾기는 드라이브와 겹치므로 뺌) 다음 **드라이브**(마운트된 볼륨, 표시 이름 =
  볼륨 이름 — 기본 볼륨은 "내부 저장소").
- 실제 파일·폴더: `"<uuid>:<rel>"`. `uuid`는 볼륨 키(기본 볼륨 `primary`), `rel`은 볼륨 루트 기준 `/` 구분 상대
  경로(루트 자체는 빈 문자열).
- **해석 규칙** `DocumentIds.resolve(id)`(실패는 모두 `FileNotFoundException`):
  1. `uuid`가 지금 마운트된 볼륨이어야 한다.
  2. `rel`에 NUL, 빈 조각, `.`, `..`가 없어야 한다(앞의 `/`도 거부).
  3. `root.resolve(rel)`의 실제 경로(`toRealPath`)가 같은 볼륨 루트 아래여야 한다(심볼릭 링크로 밖에 나가는 것 거부).
  4. 어느 조각도 내부 이름(`HiddenNames.isInternal`: `.Recycle.Bin`, `*.xpart`)이 아니어야 한다.
  5. 제한 경로(`Android/data`, `Android/obb`와 그 아래)가 아니어야 한다. 선택기에서는 APK 설치 권한이 있어도 obb를
     예외로 두지 않는다(다른 앱의 데이터를 다른 앱에 내주지 않기 위해).
- `DocumentIds.idOf(path)`는 위 규칙을 만족하는 경로만 ID로 만든다.

### 3.3 행(커서)
- 열: `DOCUMENT_ID`, `MIME_TYPE`(폴더 `MIME_TYPE_DIR`, 파일은 MimeResolver, 모르면 `application/octet-stream`),
  `DISPLAY_NAME`(실제 이름, 가상 항목은 라벨), `LAST_MODIFIED`, `SIZE`(파일만), `FLAGS`.
- 플래그(쓰기 가능한 볼륨일 때만 쓰기 계열):
  - 폴더: `FLAG_DIR_SUPPORTS_CREATE`, `FLAG_SUPPORTS_DELETE`, `FLAG_SUPPORTS_RENAME`.
  - 파일: `FLAG_SUPPORTS_WRITE`, `FLAG_SUPPORTS_DELETE`, `FLAG_SUPPORTS_RENAME`, 이미지·동영상은
    `FLAG_SUPPORTS_THUMBNAIL`.
  - 볼륨 루트(USB 드라이브 포함): 만들기만(지우기·이름 바꾸기 없음) + `FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE`.
  - (최종 검토) 모든 볼륨의 맨 위에 있는 `Download`와 `Android` 폴더(대소문자 무시):
    `FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE`. 그 아래 폴더(`Android/media`, `Download/x` 등)는 막지 않는다. 플랫폼의 저장소
    공급자와 같은 규칙은 아니다(예: 플랫폼은 USB 드라이브의 루트를 고르게 둔다). 모든 볼륨에 같은 규칙을 두어
    플랫폼보다 엄격하다.
  - 가상 `root`: `FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE`만.
  - 읽기 전용 볼륨(MOUNTED_READ_ONLY): 쓰기 계열 플래그 없음.
- 하위 목록: `Listing.list`(숨긴 항목 포함 — 선택기가 자체 설정으로 거름), 내부 이름·제한 경로·특수 파일(`OTHER`)·
  심볼릭 링크 제외(링크 대상이 볼륨 밖일 수 있어 선택기에는 내놓지 않음; 앱 안에서는 본 사양대로 보임). 정렬은 선택기가 한다. 커서에 하위 목록 알림 주소를 건다.

### 3.4 읽기
- `openDocument(id, "r")`: 파일을 읽기 전용으로 연다. 폴더는 거부.
- `openDocumentThumbnail`: 이미지·동영상만. 앱의 썸네일 포트(`ThumbnailUtils`)로 요청 크기에 맞춰 만들고 JPEG로
  파이프에 써서 돌려준다(임시 파일 없음). 실패하면 `FileNotFoundException`(선택기가 기본 아이콘을 씀).
  (최종 검토) 요청 크기는 `sizeHint`의 긴 변이다. 0 이하면 포트를 부르지 않고 거부하고, 2048픽셀
  (`MAX_THUMBNAIL_PX`)을 넘으면 2048로 줄인다(바인더 스레드에서 사진 전체를 디코딩하지 않게).
- `queryRecentDocuments`: 앱의 최근 항목(최대 20개) 중 지금 해석되는 파일만, 최근 순.
- `querySearchDocuments`: 앱의 이름 검색(본 사양 §6.1의 일치 규칙)을 마운트된 모든 볼륨에 대해 돌린다. 숨긴 항목·
  내부 이름·제한 경로 제외. 200개 또는 5초에서 멈추고 그때까지의 결과를 돌려준다.
  (최종 검토) 선택기가 주는 검색 인자(`querySearchDocuments(rootId, projection, queryArgs)`; 인자 3개짜리는 이름만
  넘겨 이것을 부름)를 따르고, 거르는 것은 200개에 세기 전에 한다:
  - 이름은 `QUERY_ARG_DISPLAY_NAME`. 비어 있으면 아무것도 찾지 않는다.
  - `QUERY_ARG_EXCLUDE_MEDIA`(선택기의 "최근" 화면이 미디어 저장소의 결과와 합칠 때 줌): 미디어 저장소가 색인하는
    볼륨(FUSE로 제공되는 볼륨)에서 사진·동영상·음악·문서 형식의 파일을 뺀다. 문서 형식은 `text/*`와
    `MediaFile.isDocumentMimeType`(Android 15)의 목록. 색인되지 않는 볼륨(그대로 마운트된 USB)에서는 남긴다.
  - `QUERY_ARG_MIME_TYPES`: 그중 한 형식의 파일만(폴더 제외). 빈 목록은 아무 형식도 요구하지 않는다.
  - `QUERY_ARG_FILE_SIZE_OVER`: 그보다 큰 파일만. `QUERY_ARG_LAST_MODIFIED_AFTER`: 그 뒤(밀리초)에 수정한 것만.
  - 커서의 `ContentResolver.EXTRA_HONORED_ARGS`에 받은 인자 가운데 따른 것을 적는다.
  최근 항목은 거르지 않는다(앱에서 연 파일이므로, 선택기의 "최근" 화면에 미디어 공급자의 같은 파일과 함께 나올 수
  있다).
- `isChildDocument(parent, doc)`: 둘 다 해석되고 같은 볼륨이며 `doc` 경로가 `parent` 경로 아래면 참. `root`는 아무
  문서의 부모도 아니다(폴더 선택 대상이 아니므로). 권한이 없으면 거짓(§3.1).

### 3.5 쓰기
- **덮어쓰기 저장** — `openDocument(id, "w" | "wt")`: `SafeSave`로 같은 폴더의 임시 파일
  `.<이름>.<id>.xpart`를 만들어 그 파일을 쓰기 전용으로 연다. 다른 앱이 쓰고 닫으면(닫기 알림, 전용
  HandlerThread):
  - 오류 없이 닫힘 → 디스크에 강제 기록 → 원자적 이름 바꾸기로 대상 교체(수정 시각은 새로 쓴 시각) → (최종 검토)
    폴더도 디스크에 강제 기록(볼륨이 폴더의 기록을 거부하면 넘어감. 그 직후 전원이 끊겨도 파일은 이전 내용이나 새
    내용) → 변경 알림·미디어 스캔.
  - 오류와 함께 닫힘(`closeWithError`, 앱 종료, 닫지 않고 버린 파일(LEAKED), `detachFd`(DETACHED)) → 임시 파일만
    지움. 원본은 그대로.
  - 임시 경로는 앱의 작업 저널(`OperationJournal`, TempJournal)에 등록하고 닫힐 때 뺀다. 앱이 그 사이에 죽어도 다음
    시작 때 정리된다. (최종 검토) 지울 임시 파일이 없는데 그 폴더도 없으면(볼륨이 빠짐) 기록을 남겨 다음 시작 때의
    정리에 맡긴다(`OperationJournal.deleted`와 같은 판단, 앱의 `WriteProtocol.discard`도 같음).
  - (최종 검토) **남은 공간**: 닫기 상태로는 가득 찬 드라이브가 자른 쓰기와 끝난 쓰기를 구별할 수 없다. 그래서 열 때
    그 드라이브의 남은 공간이 1 MiB(`SAVE_ROOM_MARGIN`: SD 카드·USB 드라이브를 포맷하는 FAT32·exFAT의 클러스터보다
    크고, Android가 가득 찬 드라이브로 보는 기준) 미만이거나 읽을 수 없으면 아무것도 만들지 않고
    `FileNotFoundException`으로 거부하고, 닫힌 뒤 확정하기 전에도 그러면 버린다(원본 유지, 알림·스캔 없음). 그 밖에는
    정상적으로 닫힌 저장을 끝난 것으로 본다. 그 앱 자신의 쓰기가 실패했더라도 그렇다.
  - (최종 검토) **진행 중인 저장**: 저장마다 `(대상, 임시 파일)`을 `OpenSaves`에 OPEN(그 앱이 아직 쓸 수 있음)으로
    올린다. 닫기 알림 스레드(`explorer-documents`)에서 곧바로 CLOSING으로 바꾸고, 확정·버리기는 저장 전용 스레드
    (`explorer-saves`)에서 닫힌 순서대로 한다(한 파일의 두 저장은 닫힌 순서대로 자리 잡고, 긴 기록이 다른 저장의
    닫힘 표시를 늦추지 않음). 확정이나 버리기가 끝나면(실패해도) 목록에서 빼고 기다리는 호출을 깨운다.
  - (최종 검토) **저장 직후의 호출**: `openDocument`(모든 모드), `queryDocument`, `getDocumentType`,
    `openDocumentThumbnail`은 ID를 해석한 뒤 그 문서의 저장이 자리 잡기를 기다리고(`OpenSaves.settle`),
    `renameDocument`·`deleteDocument`는 그 아래의 저장까지 기다린다. CLOSING인 저장은 끝날 때까지 최대 10초, OPEN인
    저장은 CLOSING이 될 때까지 최대 2초 기다리고(계속 열려 있으면 그대로 진행), 한 호출은 모두 합쳐 10초를 넘게
    기다리지 않는다. 하위 목록·검색·최근은 기다리지 않는다(확정이 그 폴더의 변경을 알림).
- **덧붙이기·임의 위치 쓰기** — `"wa"`, `"rw"`, `"rwt"`: 원리상 원자적으로 만들 수 없으므로 대상 파일을 직접 연다.
- **만들기** — `createDocument(parent, mime, name)`:
  - 부모는 쓰기 가능한 실제 폴더여야 한다.
  - 이름은 본 사양 §4.4 `NameValidator`(대상 볼륨 규칙)로 검사하고, 내부 이름(`*.xpart`, `.Recycle.Bin`)도 거부한다.
  - 파일이고 이름에 확장자가 없으며 MIME에서 확장자를 알 수 있으면 붙인다(`application/octet-stream` 제외).
  - 이름이 겹치면 `이름 (2).확장자`(KEEP_BOTH). 폴더는 디렉터리, 파일은 빈 파일을 `CREATE_NEW`로 확보한다.
  - 새 문서 ID를 돌려준다.
- **이름 바꾸기** — `renameDocument(id, name)`: 앱의 이름 바꾸기(`FileOps.rename`, 대소문자만 바꾸는 경우 포함)를
  쓴다. 볼륨 루트는 거부. 새 ID를 돌려준다. 옛 ID의 권한은 공급자가 회수하지 않는다(Task 5 결정): 시스템이 이름을
  바꾼 앱의 권한을 새 ID에 준 뒤 옛 ID를 회수한다. 공급자가 먼저 회수하면 그 파일 하나의 권한만 가진 앱이 새 ID를
  열 수 없다.
- **지우기** — `deleteDocument(id)`: 휴지통으로 옮긴다(`RecycleBin.moveToBin`). 휴지통을 쓸 수 없으면(본 사양 §4.6
  BinUnavailable) 물어볼 화면이 없으므로 요청대로 영구 삭제한다. (최종 검토) 공급자 안에서 BinUnavailable은 휴지통
  폴더를 만들거나 그 기록을 쓰거나 항목을 옮기지 못한 경우이므로(드라이브 루트·읽기 전용 볼륨은 그 전에 거부),
  가득 찬 내부 저장소에서도 영구 삭제가 된다. 볼륨 루트는 거부. 지운 ID의 권한을 회수한다.
- (최종 검토) **쓰는 중인 항목**: `renameDocument`·`deleteDocument`는 다른 앱이 저장 중인 파일(대상이나 임시 파일)
  이나 그것을 담은 폴더(`OpenSaves.uses`), 앱에서 진행 중인 작업이 쓰는 항목(원본·대상, 그 안의 항목, 그것을 담은
  폴더: `OperationSpec.uses`)을 `FileNotFoundException`("in use")으로 거부한다. 아무것도 옮기거나 알리지 않는다.
  앱 안에서도 같다: 저장 중인 항목의 이름 바꾸기는 `err_busy`, 이동·휴지통으로 삭제·영구 삭제는 그 최상위 항목만
  Busy 실패로 두고 나머지를 계속한다. 다른 볼륨으로 옮기는(복사 후 삭제) 동안 원본의 속성(fileKey, 크기, 수정
  시각)이 바뀌면 원본을 지우지 않고 Busy·`copiedButSourceKept` 실패로 두어 두 판을 모두 남긴다.
- (최종 검토) **선택기를 통한 이동**: `moveDocument`는 없으므로(§0) 시스템 "파일" 앱 등의 이동은 복사 후
  `deleteDocument`이고, 옮긴 원본은 휴지통으로 가서 비울 때까지 자리를 차지한다.
- 쓰기 후 공통: 부모 폴더의 하위 목록 변경 알림, FUSE 볼륨이면 미디어 스캔. (최종 검토) 바뀐 항목이나 이름을
  바꾸거나 지워 없어진 옛 경로가 즐겨찾기 폴더이거나 그것을 담고 있으면 `root`의 하위 목록 변경도 알린다.
- 앱 자신의 파일 작업이 끝났을 때(`onFinished`)도 영향받은 폴더의 하위 목록 변경을 알린다(선택기가 열려 있을 때
  목록이 낡지 않게). (최종 검토) 끝나지 못한(실패·취소) 복사·이동·압축 풀기는 대상 폴더를, 압축은 대상의 부모
  폴더를 알리고, 옮기거나 지운 원본이나 만든 항목이 즐겨찾기 폴더이거나 그것을 담고 있으면 `root`도 알린다. 작업
  실행기를 거치지 않는 앱 안의 이름 바꾸기·새로 만들기도 같은 규칙으로 알린다.

### 3.6 실행 환경
- 선택기 호출은 바인더 스레드에서 온다. 막히는 I/O는 그대로 하고, suspend API는 `runBlocking(Dispatchers.IO)`로
  부른다. 화면 상태(`state/`, `ui/`)에 손대지 않는다.
- `ContentProvider.onCreate`는 `Application.onCreate`보다 먼저 불리므로, 앱의 싱글톤(볼륨, 설정, FileOps, 휴지통,
  저널, 미디어 스캔, 썸네일)은 **첫 호출 때** `ProviderDeps`로 찾는다. 테스트는 `ProviderDeps`에 가짜를 넣는다.

## 4. 오류 처리
- 선택기 쪽 실패는 모두 `FileNotFoundException(이유)`로 돌려준다(선택기가 일반 오류를 보여 줌). 프로그래밍 오류는
  숨기지 않는다.
- 쓰는 도중 볼륨이 빠지면 쓰기가 실패하고 임시 파일 삭제를 시도한다. 원본은 그대로다.
- 선택 모드의 오류는 앱 안과 같은 메시지 막대로 보인다(본 사양 §8).

## 5. 테스트
- **JVM 단위 테스트**
  - `DocumentIds`: 왕복(`idOf` ↔ `resolve`), `..`·`.`·빈 조각·NUL·앞의 `/` 거부, 심볼릭 링크로 볼륨 밖 거부,
    제한 경로 거부(obb 포함), 내부 이름 조각 거부, 마운트되지 않은 볼륨 거부, 볼륨 루트(빈 `rel`).
  - `PickRequest`: `EXTRA_MIME_TYPES`와 `type`의 우선순위, `image/*` 일치, 정확한 일치, 모르는 MIME은 `*/*`에서만.
  - `SafeSave`: 성공하면 대상이 새 내용으로 바뀌고 임시 파일 없음, 중단(abort)하면 원본 그대로·임시 파일 없음,
    대상이 없을 때(새 파일)도 동작, 저널 등록·해제.
- **Robolectric**
  - 공급자: 권한이 없으면 루트 없음, `root` 하위가 즐겨찾기 다음 드라이브, 실제 폴더 하위에서 내부 이름·제한 경로가
    빠짐, 플래그(볼륨 루트·Download의 폴더 선택 차단, 읽기 전용 볼륨), `"r"` 읽기, `"w"` 저장 성공과 오류 닫기 후
    원본 유지(Robolectric에서 닫기 알림을 못 쓰면 `SafeSave` 단위 테스트로 대신하고 그 사실을 기록), 만들기(확장자
    붙이기, `(2)` 이름, 내부 이름 거부), 이름 바꾸기(새 ID), 지우기(휴지통에 항목이 생김), 최근 항목, 검색 상한,
    `isChildDocument`.
  - 선택 모드: 인텐트 필터가 `GET_CONTENT */*`(+`OPENABLE`)에 맞음, 한 개 결과(주소·읽기 권한·형식), 여러 개 결과
    (`ClipData`), 맞지 않는 파일은 비활성, 취소·뒤로 → `RESULT_CANCELED`, 파일을 바꾸는 명령이 없음, 앱 세션이
    덮어써지지 않음, 500개 상한.
- **실기기 확인 목록**(README에 추가): 크롬 `<input type="file">`에서 앱이 뜨고 사진·PDF가 올라감, 카카오톡 파일 첨부,
  시스템 선택기 옆면의 "파일 탐색기"와 즐겨찾기·드라이브·최근·검색, 메모 앱 등의 "다른 이름으로 저장", 폴더 선택
  (`OPEN_DOCUMENT_TREE`)에서 볼륨 루트·Download 차단, 선택기에서 지운 파일이 휴지통에 있음, 저장 중 그 앱을 강제
  종료해도 원본 유지, 썸네일 표시.

## 6. 구현 단계
최종 검토의 수정이 끝난 뒤 시작한다.

| 단계 | 내용 |
|---|---|
| P1 | `DocumentIds`, `PickRequest`(순수) + 내부 이름 거부를 `NameValidator`에 넣기(최종 검토 지적과 공유) |
| P2 | 선택 모드: `PickActivity`, 매니페스트, VM·셸의 선택 모드, `PickResult`, `pick_last` |
| P3 | 공급자 읽기: 루트, 문서·하위 목록, `"r"`, 썸네일, 최근, 검색, 변경 알림, `ProviderDeps` |
| P4 | 공급자 쓰기: `SafeSave`, `"w"`/`"wt"`, `"wa"`/`"rw"`, 만들기, 이름 바꾸기, 휴지통 지우기, 미디어 스캔 |
| P5 | README 실기기 확인 목록, 릴리스 APK |

## 7. 결정 기록

| 결정 | 이유 |
|---|---|
| 방식 A(앱의 `fs/` 위에 직접) | 다른 앱의 저장·삭제에도 임시 파일 후 교체, 이름 검사, 휴지통을 그대로 적용 |
| 열기 + 저장 | 사용자 결정 |
| 다른 앱의 삭제도 휴지통으로 | 사용자 결정. 휴지통이 안 되는 볼륨은 물어볼 화면이 없어 영구 삭제(최종 검토에서 범위를 바로잡음 — 아래) |
| 루트 하나("파일 탐색기") + 즐겨찾기·드라이브 | 시스템 선택기에 이미 있는 "내부 저장소" 항목을 볼륨마다 되풀이하지 않고, 이 앱만의 즐겨찾기를 보여 줌 |
| 볼륨 루트·Download의 폴더 선택 차단 | Android 11 이후 플랫폼과 같은 제한. 다른 앱이 이 앱을 우회로 삼아 저장소 전체 권한을 얻지 못하게(최종 검토에서 범위를 바꿈 — 아래) |
| 선택기에서 obb 예외 없음 | 다른 앱의 데이터를 제3의 앱에 내주지 않기 위해 |
| `GET_CONTENT` 결과는 FileProvider 주소(읽기 전용) | 이미 있는 공급자, 받는 앱이 이름·크기를 읽을 수 있음, 쓰기 권한을 줄 이유가 없음 |
| 여러 개 결과는 `ClipData`만(`data` 없음) | 크롬은 `data`가 없을 때만 `ClipData`를 읽어, `data`가 있으면 `<input type="file" multiple>`에 첫 파일만 올린다. AOSP DocumentsUI도 여러 개면 `ClipData`만 둔다 |
| `"wa"`/`"rw"`는 직접 열기 | 덧붙이기와 임의 위치 쓰기는 임시 파일 교체로 표현할 수 없음 |
| 선택 결과 500개 상한 | 바인더 거래 크기 한도(최종 검토의 공유 지적과 같은 원인) |
| (최종 검토) 덮어쓰기 저장은 드라이브의 남은 공간이 1 MiB 미만이거나 알 수 없으면 열 때 거부하고, 닫힌 뒤에도 그러면 버림 | 닫기 상태로는 가득 찬 드라이브가 자른 쓰기와 끝난 쓰기를 구별할 수 없다. 대가: 1 MiB 미만이 남은 드라이브에서는 실제로 끝난 저장도 버려지고(그 앱은 저장했다고 여김), 그 앱 자신의 쓰기가 실패한 뒤 정상적으로 닫은 저장은 대상을 바꾼다 |
| (최종 검토) 진행 중인 저장 목록(`fs/OpenSaves`): 문서를 다루는 호출은 그 저장이 자리 잡기를 기다리고(CLOSING 최대 10초, OPEN 최대 2초), 확정·버리기는 전용 스레드에서 닫힌 순서대로 | 저장을 닫은 직후의 다시 열기·덧붙이기·이름 바꾸기가 옛 내용을 보거나 확정 전의 임시 파일을 잃게 하지 않으려고. 긴 기록이 다른 저장의 닫힘 표시를 늦추지 않게 스레드를 나눔. 대가: 아직 쓰는 중인 파일에 대한 호출은 2초까지 기다린다 |
| (최종 검토) 다른 앱이 저장 중인 파일과 그것을 담은 폴더는 이름 바꾸기·이동·삭제를 거부(공급자 "in use", 앱의 이름 바꾸기 `err_busy`, 이동·삭제의 그 항목은 Busy). 선택기의 이름 바꾸기·지우기는 앱에서 진행 중인 작업이 쓰는 항목도 거부 | 확정할 곳이 사라지거나 작업이 다루던 항목을 잃지 않게(앱 안의 규칙과 같게). 대가: 저장이나 작업이 끝날 때까지 그런 이름 바꾸기·삭제는 실패한다 |
| (최종 검토) 지울 임시 파일이 없는데 그 폴더도 없으면 저널 기록을 남김(`SafeSave.abort`, `WriteProtocol.discard`) | 볼륨이 빠진 동안의 "없음"은 지워졌다는 뜻이 아니다(저널의 정리와 같은 판단). 대가: 돌아오지 않는 폴더의 기록이 남을 수 있다 |
| (최종 검토) 다른 볼륨으로 옮기는 동안 원본의 속성(fileKey, 크기, 수정 시각)이 바뀌면 원본을 지우지 않음(Busy, `copiedButSourceKept`) | 복사하는 사이 다른 앱이 저장한 새 내용을 지우지 않게. 대가: 두 판이 모두 남아 사용자가 정리한다 |
| (최종 검토) 확정한 뒤 폴더도 디스크에 강제 기록 | 이름 바꾸기 직후 전원이 끊겨도 파일이 이전 내용이나 새 내용이게. 폴더의 기록을 거부하는 볼륨에서는 넘어간다 |
| (최종 검토) 검색은 선택기의 인자(`EXCLUDE_MEDIA`는 FUSE 볼륨에서만, `MIME_TYPES`, `FILE_SIZE_OVER`, `LAST_MODIFIED_AFTER`)를 따르고, 최근 항목은 거르지 않음 | 선택기의 "최근" 화면 검색에서 미디어 공급자의 결과와 겹치지 않고 칩이 동작하게. 최근 항목은 앱에서 연 파일이라 그대로 둔다. 대가: 앱에서 연 파일이 "최근" 화면에 두 번 나올 수 있다 |
| (최종 검토) 즐겨찾기 폴더이거나 그것을 담은 항목이 바뀌면 `root`도, 끝나지 못한 작업은 그 대상도, 앱 안의 이름 바꾸기·새로 만들기도, 볼륨 연결·해제 때는 모든 문서도 알림 | 열려 있는 선택기의 목록이 낡지 않게. 루트가 하나라 드라이브가 빠져도 선택기는 자리를 옮기지 않는다 |
| (최종 검토) 권한이 없으면 `queryRoots` 밖의 모든 진입점이 거부하고 루트 변경을 알리며, 프로세스가 시작될 때도 루트 변경을 알림 | §3.1의 "앱이 다시 열릴 때 확인"과 다르다. 권한을 회수하면 시스템이 앱을 알림 없이 종료하고, 선택기는 읽어 둔 루트를 시스템의 캐시에 두고 쓰기 때문이다. 대가: 권한이 없는 동안에는 영구 권한을 가진 앱도 이 앱이 내준 파일을 열 수 없다 |
| (최종 검토) 썸네일 크기는 2048픽셀까지, 0 이하는 거부 | 바인더 스레드에서 큰 사진을 통째로 디코딩해 메모리를 다 쓰지 않게 |
| (최종 검토) 폴더 선택 차단: 가상 `root`, 모든 볼륨 루트(USB 포함), 모든 볼륨 맨 위의 `Download`·`Android`(대소문자 무시) | 위의 "플랫폼과 같은 제한"은 정확하지 않았다(플랫폼은 USB 드라이브의 루트를 고르게 두고, 버전마다 규칙이 다름). 모든 볼륨에 같은 규칙을 둔다. 대가: USB 드라이브에서는 플랫폼보다 엄격하다 |
| (최종 검토) 선택기의 삭제는 휴지통으로 옮기지 못하면 이유와 관계없이 영구 삭제(유지) | 사용자 결정(§0)과 §3.5 그대로다. 물어볼 화면이 없고, 시스템 선택기가 지우기 전에 확인을 받으며, 플랫폼의 저장소 공급자도 늘 영구 삭제한다. README만 바로잡음. 대가: 가득 찬 내부 저장소에서는 선택기의 삭제가 영구 삭제다 |
| (최종 검토) `moveDocument` 없음(§0 유지) | 시스템 "파일" 앱 등의 이동은 복사 후 삭제이고, 옮긴 원본은 휴지통으로 간다(README에 적음). 대가: 그런 이동은 휴지통을 비울 때까지 공간을 차지한다 |
| (최종 검토) 선택 모드의 결과는 메인 스레드 밖에서 만들고, 돌려줄 수 없는 파일이면 "이 파일은 열 수 없습니다"를 보이고 `RESULT_CANCELED`로 끝냄 | 앱이 멈추거나 종료되지 않게. 대가: 사용자가 처음부터 다시 고른다 |
| (최종 검토) 선택 모드는 앱의 작업 안내를 보이지 않고, 끝난 작업이 바꾼 목록만 다시 읽음. 여러 개를 고를 때 지금 목록에 있는 링크 대상은 선택에 더함. 다시 만들어진 화면(회전 등)은 돌아올 때 목록을 다시 읽음 | 다른 앱 위에 앱의 작업 결과를 띄우지 않으면서 목록이 낡지 않게, 링크를 누를 때 고른 것을 잃지 않게 |
| (최종 검토) 이번에 하지 않는 것: 작업 저널을 추가할 때마다 디스크에 기록하기, 진행 중인 작업이 쓰는 파일의 쓰기 열기 거부, `"rwt"`를 `SafeSave`로 처리하기, 최근 항목에서 미디어 빼기, 분류에서 미룬 항목(C12, M1–M9) | 최종 검토의 판단. 이동 중 원본이 바뀌는 경합은 원본 속성 비교가 막는다 |
