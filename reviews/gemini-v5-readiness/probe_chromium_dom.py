# Real Headless Chromium DOM, Composing-Node Preservation & HTML5 Parser Probe (Area 2 & Area 4)
import json
from selenium import webdriver
from selenium.webdriver.chrome.options import Options

JS_PROBE = r"""
const results = {};
document.body.innerHTML = '<div id="editor" contenteditable="true"><p id="p1"><span id="s1">Hello </span><span id="comp-span">world</span></p></div>';

// 1. Composing DOM Text-node identity & Selection survival under mark corrections (plan-v4.md §6.4)
const compSpan = document.getElementById('comp-span');
const compTextNode = compSpan.firstChild;
const sel = window.getSelection();
const range = document.createRange();
range.setStart(compTextNode, 2); // 'wo|rld'
range.setEnd(compTextNode, 5);   // 'wo[rld]'
sel.removeAllRanges();
sel.addRange(range);

const mutations = [];
const obs = new MutationObserver(list => {
  for (const m of list) {
    mutations.push({type: m.type, targetTag: m.target.nodeName, added: m.addedNodes.length, removed: m.removedNodes.length});
  }
});
obs.observe(document.getElementById('p1'), {childList: true, subtree: true, characterData: true, attributes: true});

// Case 1A: Mutating a sibling outside the composing Text node (#s1)
const s1 = document.getElementById('s1');
s1.style.fontWeight = 'bold';
const afterSiblingSameNode = compSpan.firstChild.isSameNode(compTextNode);
const afterSiblingSelPreserved = (
  sel.anchorNode === compTextNode && sel.anchorOffset === 2 &&
  sel.focusNode === compTextNode && sel.focusOffset === 5
);

// Case 1B: Splitting the composing Text node itself at offset 2 ('wo' vs 'rld') to wrap 'wo' in <strong>
// even though 'wo' [0, 2) is outside the marked range [2, 5)!
const tailNode = compTextNode.splitText(2);
const wrapper = document.createElement('strong');
compSpan.insertBefore(wrapper, tailNode);
wrapper.appendChild(compTextNode);

const originalNodeStillHoldsSelection = (sel.anchorNode === compTextNode || sel.focusNode === compTextNode);
const tailNodeHoldsSelection = (sel.anchorNode === tailNode && sel.focusNode === tailNode);
const selectionAfterSplit = {
  anchorNodeIsOriginal: sel.anchorNode === compTextNode,
  anchorNodeIsNewTail: sel.anchorNode === tailNode,
  anchorOffset: sel.anchorOffset,
  focusOffset: sel.focusOffset,
  originalTextNodeNewValue: compTextNode.nodeValue,
  newTailTextNodeValue: tailNode.nodeValue
};

// Case 1C: Replacing parent span innerHTML (naive ProseMirror mark redraw) destroys node identity and collapses selection
const p2 = document.createElement('p');
p2.innerHTML = '<span id="comp2">nihao</span>';
document.getElementById('editor').appendChild(p2);
const comp2Text = document.getElementById('comp2').firstChild;
const r2 = document.createRange();
r2.setStart(comp2Text, 0);
r2.setEnd(comp2Text, 5);
sel.removeAllRanges();
sel.addRange(r2);
document.getElementById('comp2').innerHTML = '<b>ni</b>hao';
const afterInnerHtmlSameNode = (document.getElementById('comp2').firstChild?.firstChild === comp2Text);
const afterInnerHtmlSelectionSurvived = (sel.anchorNode === comp2Text);

results.composingDomPreservation = {
  siblingMarkUpdatePreservesNodeAndSelection: afterSiblingSameNode && afterSiblingSelPreserved,
  splittingComposingTextNodeMovesSelectionToNewNode: {
    originalNodeStillHoldsSelection,
    tailNodeHoldsSelection,
    selectionAfterSplit
  },
  naiveInnerHtmlReplacementDestroysComposingNodeAndSelection: {
    sameNode: afterInnerHtmlSameNode,
    selectionSurvived: afterInnerHtmlSelectionSurvived
  }
};

// 2. Real browser HTML5 DOMParser behavior on partial-open clipboard fragments & split graphemes (§2.6, §5.3, §6.5)
const parser = new DOMParser();

// 2A: Partially open list items copied without enclosing <ul>/<ol>
const partialListDoc = parser.parseFromString('<!--StartFragment--><li>First item tail</li><li>Second item</li><!--EndFragment-->', 'text/html');
const orphanLiNodes = [...partialListDoc.body.children].map(el => ({
  tag: el.tagName,
  parentTag: el.parentElement.tagName,
  text: el.textContent
}));

// 2B: Bare table cells <td>A</td><td>B</td> without enclosing <table><tr> (HTML5 foster-parenting / tag-dropping rule!)
const bareTdDoc = parser.parseFromString('<!--StartFragment--><td>Cell A</td><td>Cell B</td><!--EndFragment-->', 'text/html');

// 2C: Combining mark and Emoji ZWJ sequence split across DOM <span> boundaries
const clusterContainer = document.createElement('div');
clusterContainer.innerHTML = (
  '<p id="comb-split"><span id="c-base" style="font-weight:bold">e</span><span id="c-mark" style="font-style:italic;color:red">\u0301</span></p>' +
  '<p id="comb-whole"><span id="c-merged" style="font-weight:bold">e\u0301</span></p>' +
  '<p id="zwj-split"><span id="z-w" style="font-weight:bold">\uD83D\uDC69</span><span id="z-j">\u200D</span><span id="z-l" style="font-style:italic">\uD83D\uDCBB</span></p>' +
  '<p id="zwj-whole"><span id="z-merged" style="font-weight:bold">\uD83D\uDC69\u200D\uD83D\uDCBB</span></p>'
);
document.body.appendChild(clusterContainer);

const combSplitWidth = document.getElementById('c-base').getBoundingClientRect().width + document.getElementById('c-mark').getBoundingClientRect().width;
const combWholeWidth = document.getElementById('c-merged').getBoundingClientRect().width;
const zwjSplitWidth = (
  document.getElementById('z-w').getBoundingClientRect().width +
  document.getElementById('z-j').getBoundingClientRect().width +
  document.getElementById('z-l').getBoundingClientRect().width
);
const zwjWholeWidth = document.getElementById('z-merged').getBoundingClientRect().width;

results.browserHtmlAndGraphemeDomSemantics = {
  orphanLiElementsInBody: orphanLiNodes,
  bareTdDroppedByHtml5Parser: {
    parsedChildElementCount: bareTdDoc.body.children.length,
    survivingTextContent: bareTdDoc.body.textContent
  },
  graphemeSplitAcrossDomSpans: {
    combiningSplitTotalWidthPx: Number(combSplitWidth.toFixed(2)),
    combiningWholeWidthPx: Number(combWholeWidth.toFixed(2)),
    zwjEmojiSplitTotalWidthPx: Number(zwjSplitWidth.toFixed(2)),
    zwjEmojiWholeWidthPx: Number(zwjWholeWidth.toFixed(2)),
    zwjLigatureBrokenBySpanBoundary: zwjSplitWidth > zwjWholeWidth * 1.25
  }
};

return results;
"""

def main():
    opts = Options()
    opts.add_argument("--headless=new")
    opts.add_argument("--no-sandbox")
    opts.add_argument("--disable-dev-shm-usage")
    opts.add_argument("--disable-gpu")
    driver = webdriver.Chrome(options=opts)
    try:
        driver.get("data:text/html;charset=utf-8,<!DOCTYPE html><html><body></body></html>")
        res = driver.execute_script(JS_PROBE)
        res["browser"] = {
            "name": driver.capabilities.get("browserName"),
            "version": driver.capabilities.get("browserVersion"),
            "userAgent": driver.execute_script("return navigator.userAgent;")
        }
        print(json.dumps(res, indent=2))
    finally:
        driver.quit()

if __name__ == "__main__":
    main()
