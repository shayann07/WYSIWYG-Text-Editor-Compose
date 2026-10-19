// Native C++20 probe reproducing the exact OneLineShaper::finish / iterateThroughFontStyles /
// addFullyResolved mechanics from research/native-layout-source/OneLineShaper-m150.cpp
// vs OneLineShaper-patched.cpp (fResolvedBlocks.clear()) and OneLineShaper-main.cpp.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <deque>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

struct TextRange {
    size_t start = 0;
    size_t end = 0;
    size_t width() const { return end > start ? end - start : 0; }
    bool empty() const { return start >= end; }
};

struct GlyphRange {
    size_t start = 0;
    size_t end = 0;
    size_t width() const { return end > start ? end - start : 0; }
};

struct SimulatedRun {
    TextRange fTextRange;
    size_t fGlyphCount;
    uint8_t fBidiLevel;
    uint32_t fFontId;
    size_t fIndex = 0;
    std::vector<uint16_t> fGlyphs;
    std::vector<uint32_t> fClusterIndexes;
    std::vector<float> fPositions;

    SimulatedRun(TextRange tr, size_t glyphs, uint8_t level, uint32_t fontId)
        : fTextRange(tr), fGlyphCount(glyphs), fBidiLevel(level), fFontId(fontId),
          fGlyphs(glyphs, 42), fClusterIndexes(glyphs + 1, 0), fPositions(glyphs + 1, 0.0f) {
        for (size_t i = 0; i <= glyphs; ++i) {
            fClusterIndexes[i] = static_cast<uint32_t>(tr.start + std::min(i, tr.width()));
            fPositions[i] = static_cast<float>(i * 8);
        }
    }
    size_t size() const { return fGlyphCount; }
};

struct RunBlock {
    std::shared_ptr<SimulatedRun> fRun;
    TextRange fText;
    GlyphRange fGlyphs;
    size_t fResolvedGlyphs = 0;

    RunBlock(std::shared_ptr<SimulatedRun> run, TextRange text, GlyphRange glyphs, size_t resolved)
        : fRun(std::move(run)), fText(text), fGlyphs(glyphs), fResolvedGlyphs(resolved) {}

    bool isFullyResolved() const {
        return fRun != nullptr && fResolvedGlyphs == fRun->size();
    }
};

struct ShapingResult {
    double medianMs = 0.0;
    double p95Ms = 0.0;
    size_t emittedRuns = 0;
    size_t sortComparisons = 0;
    size_t loopVisits = 0;
    bool rangeContinuityValid = true;
};

// Executes the exact OneLineShaper::addFullyResolved + OneLineShaper::finish loop
// across `blockCount` shaping sub-blocks (arising from BidiRegions or StyleType::kFont changes).
ShapingResult runOneLineShaperExperiment(
    size_t blockCount,
    bool patchClearResolvedBlocks,
    bool patchKeepZeroGlyphDefaultIgnorableRuns,
    bool includeDefaultIgnorableZeroGlyphBlock,
    int trials
) {
    std::vector<double> times;
    size_t lastEmittedRuns = 0;
    size_t lastSortComparisons = 0;
    size_t lastLoopVisits = 0;
    bool continuityOk = true;

    for (int t = 0; t < trials; ++t) {
        std::deque<RunBlock> fUnresolvedBlocks;
        std::vector<RunBlock> fResolvedBlocks;
        std::vector<SimulatedRun> paragraphRuns;
        paragraphRuns.reserve(blockCount);

        size_t comparisons = 0;
        size_t visits = 0;
        bool trialContinuityOk = true;

        auto t0 = std::chrono::steady_clock::now();

        for (size_t b = 0; b < blockCount; ++b) {
            TextRange blockText{b * 3, (b + 1) * 3};
            // If testing default-ignorable zero-glyph block (e.g., standalone U+200C ZWNJ or U+200B ZWSP
            // in its own style or bidi block), HarfBuzz produces 0 glyphs for non-empty fTextRange.
            size_t glyphCount = (includeDefaultIgnorableZeroGlyphBlock && b == blockCount / 2) ? 0 : 3;
            auto currentRun = std::make_shared<SimulatedRun>(blockText, glyphCount, static_cast<uint8_t>(b & 1), static_cast<uint32_t>(b & 1));

            // Exact OneLineShaper::addFullyResolved():
            // - m150 checks: if (this->fCurrentRun->size() == 0) return;
            // - main checks: if (this->fCurrentRun->fTextRange.empty()) return;
            if (patchKeepZeroGlyphDefaultIgnorableRuns) {
                if (!currentRun->fTextRange.empty()) {
                    fResolvedBlocks.emplace_back(currentRun, currentRun->fTextRange, GlyphRange{0, currentRun->size()}, currentRun->size());
                }
            } else {
                if (currentRun->size() != 0) {
                    fResolvedBlocks.emplace_back(currentRun, currentRun->fTextRange, GlyphRange{0, currentRun->size()}, currentRun->size());
                }
            }

            // Exact OneLineShaper::finish(block, ...):
            while (!fUnresolvedBlocks.empty()) {
                auto unresolved = fUnresolvedBlocks.front();
                fUnresolvedBlocks.pop_front();
                if (unresolved.fText.width() == 0) continue;
                fResolvedBlocks.emplace_back(unresolved);
            }

            // Sort all pieces by text (OneLineShaper.cpp lines 163-166)
            std::sort(fResolvedBlocks.begin(), fResolvedBlocks.end(),
                      [&comparisons](const RunBlock& a, const RunBlock& b) {
                          ++comparisons;
                          return a.fText.start < b.fText.start;
                      });

            // Go through all of them (OneLineShaper.cpp lines 169-246)
            size_t lastTextEnd = blockText.start;
            for (auto& resolvedBlock : fResolvedBlocks) {
                ++visits;
                if (resolvedBlock.fText.end <= blockText.start) {
                    continue;
                }
                auto run = resolvedBlock.fRun;
                auto text = resolvedBlock.fText;
                if (lastTextEnd != text.start) {
                    trialContinuityOk = false;
                }
                lastTextEnd = text.end;

                if (resolvedBlock.isFullyResolved()) {
                    resolvedBlock.fRun->fIndex = paragraphRuns.size();
                    paragraphRuns.emplace_back(*resolvedBlock.fRun);
                    resolvedBlock.fRun.reset();
                    continue;
                } else if (run == nullptr) {
                    continue;
                }
            }
            if (lastTextEnd != blockText.end) {
                trialContinuityOk = false;
            }
            if (patchClearResolvedBlocks) {
                fResolvedBlocks.clear();
            }
        }

        auto t1 = std::chrono::steady_clock::now();
        double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        if (t >= 2) { // 2 warmup runs
            times.push_back(ms);
        }
        lastEmittedRuns = paragraphRuns.size();
        lastSortComparisons = comparisons;
        lastLoopVisits = visits;
        continuityOk = trialContinuityOk;
    }

    std::sort(times.begin(), times.end());
    double median = times[times.size() / 2];
    size_t p95Idx = static_cast<size_t>(std::ceil(times.size() * 0.95)) - 1;
    double p95 = times[std::min(p95Idx, times.size() - 1)];
    return {median, p95, lastEmittedRuns, lastSortComparisons, lastLoopVisits, continuityOk};
}

int main() {
    std::vector<size_t> blockCounts = {64, 256, 1024, 4096, 8192, 16384};
    std::cout << "{\n  \"oneLineShaperBookkeepingScaling\": [\n";
    for (size_t i = 0; i < blockCounts.size(); ++i) {
        size_t m = blockCounts[i];
        int trials = (m <= 4096) ? 9 : 5;
        auto unpatched = runOneLineShaperExperiment(m, false, true, false, trials);
        auto patched = runOneLineShaperExperiment(m, true, true, false, trials);
        double speedup = patched.medianMs > 0 ? (unpatched.medianMs / patched.medianMs) : 0.0;
        std::cout << "    {\"shapingBlocks\":" << m
                  << ",\"utf16EquivalentLength\":" << (m * 3)
                  << ",\"unpatchedM150\":{\"medianMs\":" << std::fixed << std::setprecision(4) << unpatched.medianMs
                  << ",\"p95Ms\":" << unpatched.p95Ms
                  << ",\"sortComparisons\":" << unpatched.sortComparisons
                  << ",\"loopVisits\":" << unpatched.loopVisits << "}"
                  << ",\"patchedClearResolvedBlocks\":{\"medianMs\":" << patched.medianMs
                  << ",\"p95Ms\":" << patched.p95Ms
                  << ",\"sortComparisons\":" << patched.sortComparisons
                  << ",\"loopVisits\":" << patched.loopVisits << "}"
                  << ",\"bookkeepingSpeedupRatio\":" << std::setprecision(1) << speedup << "}"
                  << (i + 1 < blockCounts.size() ? "," : "") << "\n";
    }
    std::cout << "  ],\n";

    // Test the default-ignorable zero-glyph run defect in OneLineShaper-m150.cpp vs OneLineShaper-main.cpp
    auto m150DefaultIgnorable = runOneLineShaperExperiment(16, true, false, true, 4);
    auto mainDefaultIgnorable = runOneLineShaperExperiment(16, true, true, true, 4);
    std::cout << "  \"defaultIgnorableZeroGlyphBlockTest\": {\n"
              << "    \"m150Unpatched\":{\"emittedRuns\":" << m150DefaultIgnorable.emittedRuns
              << ",\"expectedRuns\":16,\"rangeContinuityValid\":" << (m150DefaultIgnorable.rangeContinuityValid ? "true" : "false") << "},\n"
              << "    \"mainPatched\":{\"emittedRuns\":" << mainDefaultIgnorable.emittedRuns
              << ",\"expectedRuns\":16,\"rangeContinuityValid\":" << (mainDefaultIgnorable.rangeContinuityValid ? "true" : "false") << "}\n"
              << "  }\n}\n";
    return 0;
}
