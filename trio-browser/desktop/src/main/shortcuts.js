'use strict';
/**
 * Keyboard shortcuts, handled in before-input-event of every web contents of a browser window so
 * they work whether the page, the address bar or an agent window has focus.
 */

const isMac = process.platform === 'darwin';

/**
 * @param {Electron.Input} input
 * @returns {string | null} command name
 */
function match(input) {
  if (input.type !== 'keyDown') return null;
  const mod = isMac ? input.meta : input.control;
  const key = input.key.length === 1 ? input.key.toLowerCase() : input.key;
  const code = input.code;
  if (mod && input.shift && !input.alt) {
    if (key === 't') return 'reopenTab';
    if (key === 'n') return 'newPrivateWindow';
    if (key === 'r') return 'hardReload';
    if (key === 'b') return 'toggleBookmarksBar';
    if (key === 'tab' || key === 'Tab') return 'prevTab';
    if (code === 'Digit1') return 'agent:claude';
    if (code === 'Digit2') return 'agent:gemini';
    if (code === 'Digit3') return 'agent:chatgpt';
    if (key === 'a') return 'askAll';
    if (key === 'y') return 'analyzeHistory';
    if (code === 'Space') return 'toggleDock';
    if (key === 'g') return 'findPrevious';
    return null;
  }
  if (mod && !input.shift && !input.alt) {
    switch (key) {
      case 't': return 'newTab';
      case 'n': return 'newWindow';
      case 'w': return 'closeTab';
      case 'l': return 'focusAddress';
      case 'k': return 'focusSearch';
      case 'r': return 'reload';
      case 'f': return 'find';
      case 'g': return 'findNext';
      case 'd': return 'bookmark';
      case 'h': return isMac ? null : 'history';
      case 'y': return isMac ? 'history' : null;
      case 'j': return 'downloads';
      case 'p': return 'print';
      case '=':
      case '+': return 'zoomIn';
      case '-': return 'zoomOut';
      case '0': return 'zoomReset';
      case ',': return 'settings';
      case '[': return isMac ? 'back' : null;
      case ']': return isMac ? 'forward' : null;
      case 'Tab': return 'nextTab';
      case 'PageDown': return 'nextTab';
      case 'PageUp': return 'prevTab';
      default:
        if (/^[1-9]$/.test(key)) return `tab:${key}`;
        return null;
    }
  }
  if (input.alt && !mod && !input.shift) {
    if (key === 'ArrowLeft') return isMac ? null : 'back';
    if (key === 'ArrowRight') return isMac ? null : 'forward';
    if (key === 'd') return isMac ? null : 'focusAddress';
    if (key === 'Home') return 'home';
  }
  if (!mod && !input.alt && !input.meta) {
    if (key === 'F5') return input.shift ? 'hardReload' : 'reload';
    if (key === 'F6') return 'focusAddress';
    if (key === 'F3') return input.shift ? 'findPrevious' : 'findNext';
    if (key === 'F11') return 'fullscreen';
    if (key === 'F12') return 'devtools';
  }
  if (isMac && input.meta && input.alt && key === 'i') return 'devtools';
  if (!isMac && input.control && input.shift && key === 'i') return 'devtools';
  return null;
}

/**
 * Runs a command in a browser window. Returns false when the command does not apply (so the key
 * reaches the page).
 * @param {import('./browser-window').BrowserWin} w
 * @param {string} command
 */
function run(w, command) {
  const app = w.app;
  const tab = w.active;
  if (command.startsWith('tab:')) {
    w.selectTabNumber(Number(command.slice(4)));
    return true;
  }
  if (command.startsWith('agent:')) {
    if (w.private) return false;
    app.agents.toggle(/** @type {any} */ (command.slice(6)), w);
    return true;
  }
  switch (command) {
    case 'newTab': w.addTab('trio://newtab/'); w.focusAddressBar(); return true;
    case 'newWindow': app.windows.create({}); return true;
    case 'newPrivateWindow': app.windows.create({ private: true }); return true;
    case 'closeTab': if (tab) w.closeTab(tab.id); return true;
    case 'reopenTab': app.windows.reopenClosed(w); return true;
    case 'focusAddress': w.focusAddressBar(); return true;
    case 'focusSearch': w.focusAddressBar('?'); return true;
    case 'reload': w.reload(); return true;
    case 'hardReload': w.reload({ hard: true }); return true;
    case 'back': w.back(); return true;
    case 'forward': w.forward(); return true;
    case 'home': w.openUrl('trio://newtab/'); return true;
    case 'nextTab': w.cycleTab(1); return true;
    case 'prevTab': w.cycleTab(-1); return true;
    case 'find': w.openFind(); return true;
    case 'findNext': if (!w.findState) return false; w.find({ action: 'next' }); return true;
    case 'findPrevious': if (!w.findState) return false; w.find({ action: 'previous' }); return true;
    case 'bookmark': w.toggleBookmark(); return true;
    case 'toggleBookmarksBar': app.settings.set('general.showBookmarksBar', !app.settings.get().general.showBookmarksBar); return true;
    case 'history': w.openUrl('trio://history/', { newTab: true }); return true;
    case 'downloads': w.openUrl('trio://downloads/', { newTab: true }); return true;
    case 'settings': w.openUrl('trio://settings/', { newTab: true }); return true;
    case 'print': tab?.wc.print(); return true;
    case 'zoomIn': tab?.zoomBy(1); return true;
    case 'zoomOut': tab?.zoomBy(-1); return true;
    case 'zoomReset': tab?.zoomBy(0); return true;
    case 'fullscreen': w.win.setFullScreen(!w.win.isFullScreen()); return true;
    case 'devtools': tab?.wc.toggleDevTools(); return true;
    case 'askAll': if (w.private) return false; w.focusAddressBar('@all ', false); return true;
    case 'analyzeHistory': if (w.private) return false; app.agents.openHistoryAnalysis(undefined, w); return true;
    case 'toggleDock': if (w.private) return false; app.agents.setDockVisible(!app.agents.dockVisible()); return true;
    default: return false;
  }
}

module.exports = { match, run };
