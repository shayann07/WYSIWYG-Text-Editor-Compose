"""Probe B-1: Soft-wrap boundary offset conflation and VK_END caret truncation.

Demonstrates:
- On soft-wrapped lines, the wrap boundary offset k belongs to line L (end of line)
  AND line L+1 (start of line).
- Compose/Skia getLineForOffset(k) maps boundary k to line L+1.
- Because EditorSurface.stops(g) builds exactly one Stop per offset using getLineForOffset,
  line L has NO stop at offset k.
- Consequently, pressing VK_END on line L places the caret at k - 1 (before the last character on unspaced/CJK text).
"""
import pathlib
import sys

# Independent model verifying the mathematical property of the stops() algorithm in DesktopEditor.kt:485-516
def test_soft_wrap_line_stops():
    # Simulate a wrapped paragraph with 3 lines:
    # Line 0: offsets 0..50 (51 characters, e.g. CJK chars)
    # Line 1: offsets 51..100 (50 characters)
    # Line 2: offsets 101..120
    # In Compose/Skia Paragraph:
    # getLineForOffset(0..50) = 0
    # getLineForOffset(51) = 1 (boundary 51 is start of line 1)
    # getLineForOffset(52..100) = 1
    # getLineForOffset(101) = 2
    
    def compose_get_line_for_offset(offset):
        if offset < 51:
            return 0
        elif offset < 101:
            return 1
        else:
            return 2

    # Prototype stops(g) logic from DesktopEditor.kt:485-493:
    # Graphemes.stops(text).map { offset -> Stop(offset, line = getLineForOffset(offset)) }
    all_offsets = list(range(121)) # each CJK char is 1 grapheme stop
    stops = []
    for off in all_offsets:
        line = compose_get_line_for_offset(off)
        stops.append((off, line))

    # Prototype lineEdge(start = false) [VK_END] logic from DesktopEditor.kt:509-514:
    # val currentLine = stops.firstOrNull { it.offset == current }?.line ?: 0
    # val target = stops.filter { it.line == currentLine }.maxByOrNull { it.x } (or max offset on line)
    
    # User is on Line 0 (e.g. at offset 10) and presses END:
    current_line = 0
    line0_stops = [off for off, line in stops if line == current_line]
    end_caret_offset = max(line0_stops) if line0_stops else -1

    # Total characters visible on Line 0: 51 chars (indices 0..50)
    # True visual line end after 51st char would be offset 51 (with Upstream affinity).
    # Actual caret offset after pressing END:
    print(f"LINE_0_VISIBLE_CHARS=51 (indices 0..50)")
    print(f"LINE_0_WRAP_BOUNDARY_OFFSET=51")
    print(f"PROTOTYPE_LINE_0_MAX_STOP_OFFSET={end_caret_offset}")
    print(f"MISSING_LAST_CHAR_ON_LINE_0={end_caret_offset == 50}")
    print(f"DEFECT_CONFIRMED={end_caret_offset < 51}")

def main():
    test_soft_wrap_line_stops()
    return 0

if __name__ == "__main__":
    sys.exit(main())
