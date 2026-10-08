# VITURE SDK — vendoring

GlassOS buduje się **bez** proprietary SDK (stub JNI). Head tracking, jasność, 2D/3D i
przyciemnienie soczewek działają dopiero z oficjalnym VITURE Glasses SDK.

## Najprościej: wrzuć paczkę do `sdk/`

1. Weź dostęp: https://www.viture.com/developer
2. Rozpakuj `VITURE_XR_Glasses_SDK_for_Android` do katalogu `sdk/` w repo:

```
sdk/VITURE_XR_Glasses_SDK_for_Android/
    android/arm64-v8a/*.so
    include/*.h
```

3. Buduj normalnie. Zadanie `:glasses:syncVitureSdk` samo kopiuje `.so` do
   `glasses/src/main/jniLibs/` i nagłówki do `glasses/src/main/cpp/include/`, a moduł
   `glasses` sam włącza kompilację natywnego mostka, gdy widzi SDK **i** NDK.

W logu builda widać, co zdecydował:

```
GlassOS glasses: native bridge ON (SDK=true, NDK=true)
```

Wymuszenie: `-Pglassos.native=true` (błąd, gdy brak NDK) albo `-Pglassos.native=false`
(świadomy build bez trackingu).

## Czego potrzebuje mostek natywny

| | wersja |
|---|---|
| NDK | `30.0.14904198` |
| CMake | `4.1.2` |

Instalacja do lokalnego SDK:

```powershell
.android-sdk\cmdline-tools\latest\bin\sdkmanager.bat --sdk_root=.android-sdk "ndk;30.0.14904198" "cmake;4.1.2"
```

Katalogi `jniLibs/`, `cpp/include/` i `sdk/` są w `.gitignore` — binariów VITURE nie
commitujemy.

## Skąd wiadomo, że SDK faktycznie działa na telefonie

Hub (`http://IP:30100`) ma w lewej kolumnie pozycję **SDK VITURE** — telefon zgłasza tam
wersję `libglasses.so` z działającej aplikacji. „brak natywnego mostka" = APK zbudowany
bez SDK albo bez NDK.
