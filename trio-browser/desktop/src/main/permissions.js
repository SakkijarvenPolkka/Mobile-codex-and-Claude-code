'use strict';
/**
 * Site permissions. Remembered decisions live in settings.permissions; everything else is asked
 * through the browser window's permission bar. A few harmless permissions are always granted;
 * device APIs (USB, serial, HID, …) are always denied.
 */

const ALWAYS_ALLOW = new Set(['clipboard-sanitized-write', 'fullscreen', 'pointerLock', 'keyboardLock', 'speaker-selection']);
/** Other-app link schemes that may be opened after confirmation. */
const EXTERNAL_SCHEMES = new Set(['mailto', 'tel', 'sms', 'webcal', 'magnet', 'zoommtg', 'msteams', 'slack', 'spotify', 'steam', 'tg', 'whatsapp', 'kakaotalk', 'facetime', 'callto', 'skype']);

/**
 * Maps an Electron permission request to the kind shown to the user, or null when it is never
 * granted.
 * @param {string} permission
 * @param {any} [details]
 * @returns {import('../types/contract').PermissionKind | null}
 */
function permissionKind(permission, details) {
  switch (permission) {
    case 'media': {
      // Requests carry mediaTypes (array); permission checks carry mediaType (one string).
      const types = (details && details.mediaTypes) || (details && details.mediaType ? [details.mediaType] : []);
      const video = types.includes('video');
      const audio = types.includes('audio');
      if (video && audio) return 'camera+microphone';
      if (video) return 'camera';
      if (audio) return 'microphone';
      return null;
    }
    case 'geolocation':
      return 'geolocation';
    case 'notifications':
      return 'notifications';
    case 'clipboard-read':
      return 'clipboard-read';
    case 'display-capture':
      return 'screen';
    case 'midi':
    case 'midiSysex':
      return 'midi';
    default:
      return null;
  }
}

/** @param {string} url */
function originOf(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.origin : null;
  } catch {
    return null;
  }
}

/**
 * @param {import('./settings').SettingsStore} settings
 * @param {string} origin
 * @param {import('../types/contract').PermissionKind} kind
 * @returns {'allow' | 'deny' | undefined}
 */
function storedDecision(settings, origin, kind) {
  const entry = settings.get().permissions[origin];
  if (!entry) return undefined;
  if (kind === 'camera+microphone') {
    if (entry['camera+microphone']) return entry['camera+microphone'];
    if (entry.camera === 'allow' && entry.microphone === 'allow') return 'allow';
    if (entry.camera === 'deny' || entry.microphone === 'deny') return 'deny';
    return undefined;
  }
  return entry[kind];
}

/**
 * @param {Electron.Session} ses
 * @param {{
 *   settings: import('./settings').SettingsStore,
 *   prompt: (wc: Electron.WebContents, origin: string, kind: import('../types/contract').PermissionKind) => Promise<'allow' | 'deny'>,
 *   confirmExternal: (wc: Electron.WebContents, externalUrl: string, origin: string) => Promise<boolean>,
 * }} deps
 */
function installPermissionHandlers(ses, deps) {
  ses.setPermissionRequestHandler((wc, permission, callback, details) => {
    if (ALWAYS_ALLOW.has(permission)) return callback(true);
    if (permission === 'openExternal') {
      // Links to other apps (mailto:, tel:, …): only well-known schemes, and only after the user agrees.
      const external = /** @type {any} */ (details).externalURL || '';
      const scheme = external.split(':')[0].toLowerCase();
      if (!EXTERNAL_SCHEMES.has(scheme)) return callback(false);
      deps.confirmExternal(wc, external, originOf(details.requestingUrl || wc.getURL()) || '').then(callback, () => callback(false));
      return;
    }
    const kind = permissionKind(permission, details);
    const origin = originOf(details.requestingUrl || wc.getURL());
    if (!kind || !origin) return callback(false);
    const stored = storedDecision(deps.settings, origin, kind);
    if (stored) return callback(stored === 'allow');
    deps.prompt(wc, origin, kind).then((d) => callback(d === 'allow'), () => callback(false));
  });
  ses.setPermissionCheckHandler((_wc, permission, requestingOrigin, details) => {
    if (ALWAYS_ALLOW.has(permission)) return true;
    const kind = permissionKind(permission, details);
    const origin = originOf(requestingOrigin);
    if (!kind || !origin) return false;
    return storedDecision(deps.settings, origin, kind) === 'allow';
  });
  ses.setDevicePermissionHandler(() => false);
}

module.exports = { installPermissionHandlers, permissionKind, storedDecision, originOf, ALWAYS_ALLOW, EXTERNAL_SCHEMES };
