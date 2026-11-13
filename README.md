# 차담

차 안 대화를 동승자 폰에 실시간 자막으로 띄우고, 동승자가 폰에 쓴 글은 차 스피커로 읽어 주어, 듣거나 말하기 어려운 탑승자도 대화에 함께하게 하는 Pleos 차량 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전(2026) 출품작입니다.

## 구성

| 폴더 | 내용 |
|---|---|
| `car-pleos/` | 차 앱 (Pleos Connect, Kotlin). 차 마이크로 말을 받아 폰으로 보내고, 폰에서 온 음성을 차 스피커로 재생합니다. 기어·주행 상태로 정차를 확인할 때만 세션을 엽니다. |
| `phone/` | 동승자 폰 앱 (Android, Kotlin). 차 화면의 QR을 찍어 연결하고, 차에서 온 소리를 폰 안의 음성 인식으로 자막을 띄웁니다. 쓴 글은 폰에서 음성으로 바꿔 차로 보냅니다. |
| `tools/` | 에뮬레이터 시연용 포트 전달기(`forward.mjs`, Node 코어 모듈만 사용) |
| `apk/` | 서명한 release APK 2개 |

클라우드 서버는 없습니다. 차와 폰이 같은 네트워크에서 직접 연결하고, 음성 인식은 동승자 폰 안에서만 합니다. 음성과 자막은 저장하지 않습니다.

## APK

| 파일 | 설치할 곳 |
|---|---|
| `apk/차담_차담_release.apk` | Pleos Connect Emulator (차 앱) |
| `apk/차담_차담폰_release.apk` | 동승자 Android 폰, Android 13 이상 |

## 테스트 환경

- macOS, Pleos Connect Emulator 2.1.0
- 동승자 폰: Galaxy Note20 (Android 13). 한국어 기기 안 음성 인식이 되는 폰이어야 자막이 나옵니다.
- Mac과 폰은 같은 Wi-Fi에 연결합니다.

## 실행 방법 (에뮬레이터 시연)

에뮬레이터 안의 차 앱은 Mac 밖에서 바로 접속할 수 없어서, Mac이 포트만 넘겨 줍니다. 실차에서는 폰이 차 앱(포트 47000)에 바로 연결하도록 설계했습니다.

1. **에뮬레이터를 켭니다.** 실제 목소리를 넣으려면 마이크를 쓰는 AVD를 `-allow-host-audio`로 켜고, 에뮬레이터 창의 ⋯ → Microphone → "Enable Host Microphone Access"를 켭니다.
2. **차 앱을 설치합니다.**
   ```bash
   adb -s emulator-5554 install -r apk/차담_차담_release.apk
   ```
3. **포트를 넘깁니다.** (Node.js 필요)
   ```bash
   adb -s emulator-5554 forward tcp:47001 tcp:47000
   node tools/forward.mjs 0.0.0.0 47100 47001
   ```
   에뮬레이터를 다시 켜면 `adb forward`를 다시 실행해야 합니다.
4. **차 앱을 엽니다.** 처음에는 마이크 권한을 허용합니다. 기어가 P이면 QR과 "세션 연결중"이 바로 뜹니다.
5. **연결 주소를 맞춥니다.** "끄기" → "연결 설정"에서 QR 주소에 Mac의 Wi-Fi IP, 포트에 `47100`을 넣고 저장한 뒤 "세션 열기"를 누릅니다.
6. **폰 앱을 설치하고 QR을 찍습니다.** 대화명을 정하고 "동의하고 연결"을 누르면 연결됩니다.
7. 차 쪽에서 말하면 폰에 자막이 뜨고, 폰에 쓴 글은 차 스피커로 읽힙니다. 폰 위쪽 스피커 버튼을 끄면 글이 다른 폰에만 갑니다.

## 주요 동작

- 앱을 열면 정차(기어 P)를 확인한 뒤 세션이 자동으로 열립니다. 주행 중에는 세션을 열지 않고 새 폰도 받지 않습니다. 이미 연결된 폰은 주행 중에도 이어집니다.
- 폰은 4대까지 연결됩니다. 한 사람이 쓴 글은 다른 폰에도 대화명과 함께 뜹니다.
- 내비 안내 중에는 글 읽기를 기다리고 차 마이크 소리도 보내지 않습니다. 통화 중에는 읽지 않습니다.
- 끄면 QR이 사라지고 연결이 끊기며 폰의 자막이 지워집니다.
- 자막은 틀릴 수 있어 긴급·안전 안내의 유일한 수단으로 쓰지 않습니다.

## 소스로 빌드하기

JDK 17과 Android SDK(compileSdk 36)가 필요합니다. `local.properties`에 `sdk.dir`를 넣거나 `ANDROID_HOME`을 설정합니다.

```bash
cd car-pleos && ./gradlew assembleDebug testDebugUnitTest
cd phone && ./gradlew assembleDebug testDebugUnitTest
```

차 앱은 Pleos Playground 저장소(`nexus-playground.pleos.ai`)에서 Vehicle SDK를 받습니다.

## 사용한 라이브러리

| 이름 | 버전 | 라이선스 | 쓰는 곳 |
|---|---|---|---|
| ZXing core | 3.5.4 | Apache-2.0 | 차 QR 만들기, 폰 QR 읽기 |
| Pleos Playground Vehicle SDK | 2.3.2 | Pleos Playground 제공 | 차 기어 상태 |
| Noto Sans KR Bold (글자 윤곽만) | — | SIL OFL 1.1 | 앱 아이콘 "차담" 글자 |
