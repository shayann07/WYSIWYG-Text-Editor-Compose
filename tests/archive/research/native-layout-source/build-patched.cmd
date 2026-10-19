@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
cl /nologo /std:c++17 /O2 /EHsc /MT /utf-8 /DNDEBUG /I skia-release probe.cpp OneLineShaper-patched.cpp /link /OUT:probe-patched.exe /LIBPATH:skia-release\out\Release-windows-x64 skparagraph.lib skshaper.lib skunicode_icu.lib skunicode_core.lib skia.lib harfbuzz.lib icu.lib skcms.lib libjpeg.lib libjpeg12.lib libjpeg16.lib libpng.lib libwebp.lib libwebp_sse41.lib wuffs.lib zlib.lib user32.lib gdi32.lib ole32.lib oleaut32.lib uuid.lib dwrite.lib windowscodecs.lib advapi32.lib fontsub.lib


