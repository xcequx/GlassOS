# VITURE SDK — vendoring

GlassOS buduje się **bez** proprietary SDK (stub JNI). Head tracking, jasność, 2D/3D i przyciemnienie soczewek działają dopiero po wrzuceniu oficjalnego VITURE Glasses SDK.

1. Weź dostęp: https://www.viture.com/developer
2. Skopiuj pliki:

```
glasses/src/main/jniLibs/arm64-v8a/libglasses.so
glasses/src/main/jniLibs/arm64-v8a/libcarina_vio.so      # jeśli jest w paczce
glasses/src/main/jniLibs/arm64-v8a/libcloud_protocol.so  # jeśli jest w paczce
glasses/src/main/cpp/include/*.h
```

Katalogi `jniLibs/` i `cpp/include/` są w `.gitignore` — binariów VITURE nie commitujemy.

Po wrzuceniu CMake sam przełączy się z `glasses_bridge_stub.cpp` na prawdziwy `glasses_bridge.cpp`.
