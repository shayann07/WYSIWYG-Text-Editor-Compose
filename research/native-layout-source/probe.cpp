#include "include/core/SkCanvas.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkSurface.h"
#include "include/ports/SkTypeface_win.h"
#include "modules/skparagraph/include/ParagraphBuilder.h"
#include "modules/skparagraph/include/FontCollection.h"
#include "modules/skparagraph/include/ParagraphCache.h"
#include "modules/skunicode/include/SkUnicode_icu.h"
#include <algorithm>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <vector>
using namespace skia::textlayout;
uint64_t digest = 1469598103934665603ULL;
template <class T> void hashValue(const T& v) {
    auto p = reinterpret_cast<const unsigned char*>(&v);
    for (size_t i=0;i<sizeof(T);++i) digest=(digest^p[i])*1099511628211ULL;
}
uint64_t geometry(Paragraph& p) {
    digest=1469598103934665603ULL;
    hashValue(p.getHeight()); hashValue(p.getMinIntrinsicWidth()); hashValue(p.getMaxIntrinsicWidth());
    hashValue(p.unresolvedGlyphs());
    std::vector<LineMetrics> lines; p.getLineMetrics(lines);
    for (auto& m: lines) {
        hashValue(m.fStartIndex); hashValue(m.fEndIndex); hashValue(m.fEndExcludingWhitespaces);
        hashValue(m.fEndIncludingNewline); hashValue(m.fHardBreak); hashValue(m.fAscent);
        hashValue(m.fDescent); hashValue(m.fHeight); hashValue(m.fWidth); hashValue(m.fLeft);
        hashValue(m.fBaseline); hashValue(m.fLineNumber);
    }
    for (float y: {0.f, p.getHeight()/2, std::max(0.f,p.getHeight()-64)}) {
        for (int x=0;x<=800;x+=20) {
            auto pos=p.getGlyphPositionAtCoordinate(float(x),y);
            hashValue(pos.position); hashValue(pos.affinity);
        }
        auto surface=SkSurfaces::Raster(SkImageInfo::MakeN32Premul(800,64));
        surface->getCanvas()->clear(SK_ColorTRANSPARENT);
        p.paint(surface->getCanvas(),0,-y);
        SkPixmap pixels;
        if (!surface->peekPixels(&pixels)) throw std::runtime_error("raster unavailable");
        auto bytes=static_cast<const uint8_t*>(pixels.addr());
        for(size_t i=0;i<pixels.computeByteSize();++i) hashValue(bytes[i]);
    }
    return digest;
}
int main(int argc,char**argv) {
    auto fonts=sk_make_sp<FontCollection>();
    fonts->setDefaultFontManager(SkFontMgr_New_DirectWrite());
    fonts->getParagraphCache()->turnOn(false);
    auto unicode=SkUnicodes::ICU::Make();
    TextStyle ts; ts.setFontFamilies({SkString("Consolas")}); ts.setFontSize(16);
    ts.setColor(SK_ColorBLACK);
    ParagraphStyle style; style.setTextStyle(ts); style.setTextDirection(TextDirection::kLtr);
    std::vector<std::string> fixtures={"a(\xD7\x90)","office affinity AV fi ffi",u8"العربية (abc) لا سلام",u8"אבג (abc) [123]",u8"á\n👨‍👩‍👧‍👦 🏳️‍🌈",u8"क्षि हिन्दी",u8"a\t b\n\n끝",u8"\u2067אבג\u2069 abc"};
    for(int a=1;a<argc;++a) { int groups=std::stoi(argv[a]); std::string s="a"; for(int i=0;i<groups;++i)s+=u8"(א)"; fixtures.push_back(s); }
    std::cout<<"{\"fixtures\":[";
    bool first=true;
    for(size_t i=0;i<fixtures.size();++i) {
        std::vector<double> times; uint64_t expected=0;
        for(int trial=0;trial<4;++trial) {
            auto start=std::chrono::steady_clock::now();
            auto builder=ParagraphBuilder::make(style,fonts,unicode);
            builder->addText(fixtures[i].c_str());
            auto p=builder->Build(); p->layout(800);
            auto stop=std::chrono::steady_clock::now();
            if(trial>0) times.push_back(std::chrono::duration<double,std::milli>(stop-start).count());
            auto h=geometry(*p); if(trial==0)expected=h;else if(h!=expected)throw std::runtime_error("unstable geometry");
        }
        std::sort(times.begin(),times.end());
        if(!first) std::cout<<","; first=false;
        std::cout<<"{\"fixture\":"<<i<<",\"utf8Bytes\":"<<fixtures[i].size()<<",\"medianMs\":"<<times[1]<<",\"geometryHash\":\""<<expected<<"\"}";
        std::cerr<<"Completed fixture "<<i<<" bytes="<<fixtures[i].size()<<"\n";
    }
    std::cout<<"],\"limits\":\"Native Skia release libraries, cache disabled, Consolas, fixed width 800. Three measured trials after one warmup. Hashes cover line metrics, sampled hit tests and three raster slices, not full conformance.\"}\n";
}
