# Audacity for Android (비공식 포팅)

[Audacity](https://www.audacityteam.org) 3.7.9(리눅스 안정판)를 Android로 포팅한 프로젝트입니다.
Audacity의 **실제 코어 라이브러리**(편집 엔진, 효과, 가져오기/내보내기, `.aup3` 프로젝트, 오디오 I/O)를
Android NDK로 크로스 컴파일하고, 데스크톱 전용인 wxWidgets GUI 대신 Jetpack Compose로 만든 터치 UI를
얹었습니다. Audacity 4가 같은 라이브러리 위에 Qt UI를 얹는 것과 같은 구조입니다.

> **비공식 포팅입니다.** 이 프로젝트는 Muse Group 및 Audacity 팀과 관련이 없으며, 그들의 승인을 받지
> 않았습니다. Audacity®는 Muse Group의 등록 상표입니다. 앱 이름은 "Audacity Port"입니다.

English summary: an unofficial Android port of Audacity 3.7.9. The real Audacity core libraries are
cross-compiled with the NDK and driven by a C++ bridge; the UI is new (Kotlin/Jetpack Compose). See
[Architecture](#구조-architecture) and [Building](#빌드-building).

## 기능

데스크톱 Audacity 3.7.9의 라이브러리를 그대로 사용하므로 처리 결과가 데스크톱과 같습니다.

- **프로젝트**: `.aup3` 새로 만들기/열기/저장/다른 이름으로 저장/사본 저장/압축, 자동 저장과
  비정상 종료 후 복구, 프로젝트 관리 화면, 메타데이터(태그) 편집, 실행 취소/다시 실행과 기록 창
- **편집**: 잘라내기/복사/붙여넣기/삭제, 분할 잘라내기/삭제, 무음 처리, 다듬기, 복제, 분할/새 트랙으로 분할,
  결합, 무음에서 분리, 클립 이동(타임 시프트)·이름 변경, 라벨 트랙(추가/편집/가져오기/내보내기 —
  텍스트·SRT·WebVTT), 제로 크로싱 선택
- **트랙**: 모노/스테레오/라벨 트랙 추가, 게인/팬/뮤트/솔로, 이름 변경, 이동·정렬·정렬 맞춤,
  스테레오 만들기/분리/채널 교환, 샘플레이트·샘플 포맷 변경, 리샘플, 믹스 및 렌더
- **효과**: 3.7.9 내장 효과·생성기·분석기 36종(증폭, 노멀라이즈, 라우드니스, 컴프레서, 리미터,
  이퀄라이저(필터 커브/그래픽 EQ), 노이즈 감소(2단계), 리버브, 에코, 피치/템포/속도 변경, 페이드,
  톤/노이즈/처프/DTMF 생성 등)과 번들 Nyquist 플러그인, 프리셋, 미리 듣기, 마지막 효과 반복,
  스펙트럼 플롯, 대비 분석
- **가져오기**: WAV/AIFF 등(libsndfile), MP3, Ogg Vorbis, FLAC, Opus, WavPack, 레거시 `.aup`,
  그리고 Android MediaCodec을 통한 AAC/M4A/MP4/3GP/AMR/MKV/WebM
- **내보내기**: WAV, AIFF 등, MP3(LAME), Ogg Vorbis, Opus, FLAC, WavPack, MP2, M4A(AAC, MediaCodec),
  형식별 옵션, 선택 영역/전체, 채널·샘플레이트
- **재생·녹음**: AAudio 기반(PortAudio용 AAudio 호스트 API를 새로 작성), 재생/일시정지/루프/퀵플레이,
  녹음/새 트랙에 녹음, 오버더빙과 지연 자동 보정, 입력 모니터링, 레벨 미터, 장치 선택,
  백그라운드 녹음용 포그라운드 서비스
- **화면**: Audacity 라이트/다크 테마 색상, 파형(최소/최대/RMS)과 샘플 단위 확대, 스펙트로그램,
  타임라인 눈금자와 재생 구간, 데스크톱과 같은 순서의 툴바(전송·편집·미터·시간·선택),
  3.7.9 메뉴 트리와 활성화 조건, 하드웨어 키보드 단축키, 터치 제스처(탭=커서, 드래그=선택,
  핀치=확대/축소, 길게 누르기=컨텍스트 메뉴, 클립 제목 표시줄 드래그=이동)
- **언어**: 한국어/영어 UI, Audacity 공식 한국어 번역(효과 이름, 실행 취소 기록, 오류 메시지)

### 포팅하지 않은 것 (현재)

VST/VST3/LV2/LADSPA/Audio Unit 플러그인, MIDI(노트 트랙), FFmpeg 가져오기, 매크로·스크립팅,
스크러빙, 예약 녹음, 펀치 앤 롤 녹음, 스펙트럼 선택 편집, 믹서 보드, 사용자 지정 채널 매핑 내보내기,
ID3 태그 기록, 여러 프로젝트 창.

## 설치

릴리스 빌드로 ABI별 APK가 만들어집니다.

| 파일 | 대상 |
|---|---|
| `app-arm64-v8a-release.apk` | 대부분의 Android 휴대폰·태블릿 (64비트 ARM) |
| `app-x86_64-release.apk` | 에뮬레이터, 일부 크롬북 |

Android 9(API 28) 이상이 필요합니다. 기기에서 "출처를 알 수 없는 앱 설치"를 허용한 뒤 APK를 엽니다.
APK는 디버그 키로 서명되어 있으므로 배포용으로는 별도 키로 다시 서명하세요.

## 구조 (Architecture)

```
Compose UI (:app, :editor) ──► AudacityEngine (:engine, Kotlin coroutines)
        ▲                               │ JSON commands over JNI (libaudacity-jni.so)
        │ snapshot / progress / dialog  ▼
        └──────────── events ── libaudacity-bridge.so  (native/bridge, C++)
                                        │  one "engine" thread = Audacity's main thread
                                        ▼
            Audacity 3.7.9 libraries: lib-wave-track, lib-effects, lib-builtin-effects,
            lib-import-export + mod-*, lib-project-file-io (SQLite), lib-audio-io, ... (~72 .so)
                                        │
                     PortAudio + AAudio host API (native/portaudio-android)
```

- `native/audacity/` — 원본 Audacity 3.7.9 소스의 일부(수정 없이 가져온 뒤, 꼭 필요한 변경만 별도
  커밋으로 적용; 목록은 `native/audacity/ANDROID_CHANGES.md`)
- `native/CMakeLists.txt`, `native/cmake/` — Android/리눅스 호스트용 CMake 슈퍼빌드(wxBase 3.2.8,
  libsndfile, Ogg/Vorbis, FLAC, Opus, mpg123, LAME, WavPack, PortAudio 등 의존성 포함)
- `native/bridge/` — 데스크톱 `src/`의 비GUI 부분을 대체하는 C++ 브리지. 프로토콜 명세:
  [`native/bridge/API.md`](native/bridge/API.md), 구조: [`native/bridge/MODULES.md`](native/bridge/MODULES.md)
- `native/portaudio-android/` — PortAudio용 AAudio 호스트 API(+ 호스트 테스트용 가상 장치)
- `native/jni/` — JNI 연결
- `engine/`, `editor/`, `app/` — Kotlin 모듈(엔진 클라이언트, 파형 편집기, 앱 화면)

## 빌드 (Building)

필요한 것: JDK 17 이상, Android SDK(platform 36), NDK 28.2.13676358, CMake 3.31.6(SDK 패키지),
첫 빌드 때 의존성 다운로드를 위한 네트워크.

```sh
# 릴리스 APK (arm64-v8a + x86_64, ABI별 APK)
./gradlew :app:assembleRelease
# 한 ABI만
./gradlew :app:assembleRelease -Paudacity.abis=arm64-v8a
# 네이티브 엔진 없이 UI만 (가짜 엔진으로 동작)
./gradlew :app:assembleDebug -Paudacity.buildNative=false
```

네이티브 엔진 첫 빌드는 4코어 기준 ABI당 약 7~8분 걸립니다. 자세한 내용은
[`native/BUILDING.md`](native/BUILDING.md)를 참고하세요.

### 테스트

```sh
native/scripts/build-host.sh --test        # 리눅스 호스트에서 네이티브 테스트(ctest)
./gradlew -Paudacity.buildNative=false :engine:testDebugUnitTest :editor:testDebugUnitTest :app:testDebugUnitTest
# 실제 엔진(호스트 빌드)으로 Kotlin → JNI → 브리지 → Audacity 전체 경로 테스트
./gradlew -Paudacity.buildNative=false \
  -Paudacity.hostJniLibrary=$PWD/native/build-host/lib/libaudacity-jni.so \
  :engine:testDebugUnitTest --tests '*NativeHostIntegrationTest'
```

## 현재 상태

- 리눅스 호스트에서 네이티브 테스트 34개와 Kotlin 테스트 184개가 통과합니다. 호스트 테스트는 실제
  Audacity 라이브러리로 편집, 모든 내장 효과, 형식별 내보내기→가져오기 왕복, 재생·녹음·오버더빙을
  확인합니다. 재생·녹음은 AAudio를 흉내 내는 가상 장치를 사용합니다.
- **실기기·에뮬레이터 테스트는 아직 하지 않았습니다**(개발 환경에 에뮬레이터가 없었음). 특히 AAudio
  재생·녹음, MediaCodec 코덱, Android에서의 엔진 초기화는 기기에서 확인이 필요합니다.
  문제가 있으면 앱의 *도움말 ▸ 진단 ▸ 로그 보기* 화면 내용을 함께 알려 주세요.

## 라이선스

Audacity는 GNU GPL(대부분 GPLv2 이상, 전체로는 GPLv3)로 배포되며, 이 포팅도 같은 조건을 따릅니다
([`native/audacity/LICENSE.txt`](native/audacity/LICENSE.txt)). 포함된 서드파티 구성요소(wxWidgets,
libsndfile, libogg/libvorbis, FLAC, Opus, mpg123, LAME, WavPack, TwoLAME, libsoxr, SoundTouch, SBSMS,
SQLite, PortAudio, Nyquist, nlohmann/json, RapidJSON, expat, pffft, portsmf)는 각자의 라이선스를 따르며,
앱의 *정보(About)* 화면에 목록이 있습니다.
