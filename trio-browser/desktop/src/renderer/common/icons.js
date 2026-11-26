// Trio line icons, drawn on a 20 × 20 grid (1.6 px stroke, round caps and joins).

const SPARK = 'M10 3l1.6 4.4L16 9l-4.4 1.6L10 15l-1.6-4.4L4 9l4.4-1.6z';
const STAR = '10 3 12.1 7.3 16.8 7.9 13.4 11.2 14.2 15.9 10 13.7 5.8 15.9 6.6 11.2 3.2 7.9 7.9 7.3';
const PIN = 'M7.5 3.5h5M8.5 3.5v5L6 11h8l-2.5-2.5v-5M10 11v5.5';
const SPEAKER = 'M3.5 8h3L10.5 4.5v11L6.5 12h-3z';
const BUBBLE = 'M4 5.5A1.5 1.5 0 0 1 5.5 4h9A1.5 1.5 0 0 1 16 5.5v6a1.5 1.5 0 0 1-1.5 1.5H9.5L6 16v-3h-.5A1.5 1.5 0 0 1 4 11.5z';

/** @type {Record<string, string>} inner SVG markup per icon */
const ICONS = {
  back: '<path d="M16 10H4M9 5l-5 5 5 5"/>',
  forward: '<path d="M4 10h12M11 5l5 5-5 5"/>',
  reload: '<path d="M16 10a6 6 0 1 1-1.8-4.3"/><path d="M16 3.5V7h-3.5"/>',
  stop: '<path d="M5.5 5.5l9 9M14.5 5.5l-9 9"/>',
  home: '<path d="M3.5 9.5 10 4l6.5 5.5"/><path d="M5.5 8v8h9V8"/>',
  plus: '<path d="M10 4.5v11M4.5 10h11"/>',
  close: '<path d="M6 6l8 8M14 6l-8 8"/>',
  search: '<circle cx="8.8" cy="8.8" r="5"/><path d="m12.6 12.6 3.9 3.9"/>',
  globe: '<circle cx="10" cy="10" r="7"/><path d="M3 10h14"/><path d="M10 3c1.9 2 2.9 4.4 2.9 7s-1 5-2.9 7c-1.9-2-2.9-4.4-2.9-7s1-5 2.9-7z"/>',
  lock: '<rect x="4.5" y="9" width="11" height="8" rx="2"/><path d="M7 9V7a3 3 0 0 1 6 0v2"/>',
  'lock-open': '<rect x="4.5" y="9" width="11" height="8" rx="2"/><path d="M7 9V7a3 3 0 0 1 5.7-1.3"/>',
  info: '<circle cx="10" cy="10" r="7"/><path d="M10 9.2v4.8M10 6.3v.1"/>',
  warning: '<path d="M10 3.5 17.2 16H2.8z"/><path d="M10 8.3v3.6M10 14.1v.1"/>',
  star: `<polygon points="${STAR}"/>`,
  'star-filled': `<polygon points="${STAR}" fill="currentColor"/>`,
  bookmark: '<path d="M6 3.5h8v13l-4-3-4 3z"/>',
  history: '<path d="M3.6 10a6.4 6.4 0 1 0 1.9-4.5"/><path d="M3.5 3.8v3.3h3.3"/><path d="M10 6.6V10l2.5 1.6"/>',
  download: '<path d="M10 3.5v9M6 8.5l4 4 4-4M4 16.2h12"/>',
  settings: '<path d="M3.5 6.5h7M15.5 6.5h1M3.5 13.5h1M8.5 13.5h8"/><circle cx="13" cy="6.5" r="2.2"/><circle cx="6.5" cy="13.5" r="2.2"/>',
  menu: '<path d="M4 6h12M4 10h12M4 14h12"/>',
  more: '<circle cx="10" cy="5" r="1.1" fill="currentColor" stroke="none"/><circle cx="10" cy="10" r="1.1" fill="currentColor" stroke="none"/><circle cx="10" cy="15" r="1.1" fill="currentColor" stroke="none"/>',
  external: '<path d="M11.5 4H16v4.5M16 4l-7 7"/><path d="M14 11.5V16H4V6h4.5"/>',
  pin: `<path d="${PIN}"/>`,
  'pin-filled': `<path d="M8.5 3.5v5L6 11h8l-2.5-2.5v-5z" fill="currentColor"/><path d="${PIN}"/>`,
  minimize: '<path d="M5 10h10"/>',
  'chevron-up': '<path d="M5.5 12.5 10 8l4.5 4.5"/>',
  'chevron-down': '<path d="M5.5 8 10 12.5 14.5 8"/>',
  'chevron-left': '<path d="M12.5 5.5 8 10l4.5 4.5"/>',
  'chevron-right': '<path d="M8 5.5 12.5 10 8 14.5"/>',
  volume: `<path d="${SPEAKER}"/><path d="M13.5 7.5a3.5 3.5 0 0 1 0 5M15.5 5.5a6.4 6.4 0 0 1 0 9"/>`,
  'volume-muted': `<path d="${SPEAKER}"/><path d="M13.5 8l4 4M17.5 8l-4 4"/>`,
  sparkles: `<path d="${SPARK}"/><path d="M15.5 13l.6 1.5 1.5.6-1.5.6-.6 1.5-.6-1.5-1.5-.6 1.5-.6z"/>`,
  chat: `<path d="${BUBBLE}"/>`,
  'new-chat': `<path d="${BUBBLE}"/><path d="M10 6.3v4.4M7.8 8.5h4.4"/>`,
  folder: '<path d="M3.5 6A1.5 1.5 0 0 1 5 4.5h3.2L10 6.3h5A1.5 1.5 0 0 1 16.5 7.8V14a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 14z"/>',
  file: '<path d="M5.5 3.5h5.5l3.5 3.5v9.5h-9z"/><path d="M11 3.5V7h3.5"/>',
  trash: '<path d="M4 6h12M8 6V4.3h4V6"/><path d="M5.8 6l.8 10h6.8l.8-10"/>',
  edit: '<path d="M13.3 4.2l2.5 2.5L8 14.5 4.5 15.5l1-3.5z"/>',
  check: '<path d="M4.5 10.5 8 14l7.5-8"/>',
  copy: '<rect x="7" y="7" width="9" height="9" rx="1.6"/><path d="M4 13V5.6A1.6 1.6 0 0 1 5.6 4H13"/>',
  private: '<path d="M2.8 9.3h14.4M5.7 9.3 7.3 4h5.4l1.6 5.3"/><circle cx="6.8" cy="13.3" r="2.3"/><circle cx="13.2" cy="13.3" r="2.3"/><path d="M9.1 13.1h1.8"/>',
  dock: '<rect x="6.5" y="2.8" width="7" height="14.4" rx="3.5"/><circle cx="10" cy="7" r="1" fill="currentColor" stroke="none"/><circle cx="10" cy="10" r="1" fill="currentColor" stroke="none"/><circle cx="10" cy="13" r="1" fill="currentColor" stroke="none"/>',
  grip: '<g fill="currentColor" stroke="none"><circle cx="7.5" cy="6" r="1.1"/><circle cx="12.5" cy="6" r="1.1"/><circle cx="7.5" cy="10" r="1.1"/><circle cx="12.5" cy="10" r="1.1"/><circle cx="7.5" cy="14" r="1.1"/><circle cx="12.5" cy="14" r="1.1"/></g>',
  shield: '<path d="M10 3 16 5.4v4.4c0 3.6-2.5 6.2-6 7.2-3.5-1-6-3.6-6-7.2V5.4z"/>',
  translate: '<path d="M3.5 5h7.5M7.2 3.5V5M9.3 5c-.6 3.2-2.6 5.6-5.3 7M5.6 8c1 1.6 2.4 2.9 4 3.6"/><path d="M10.8 16.5l2.9-7 2.9 7M11.9 14h3.6"/>',
  list: '<path d="M7.5 6h8.5M7.5 10h8.5M7.5 14h8.5"/><circle cx="4.4" cy="6" r=".9" fill="currentColor" stroke="none"/><circle cx="4.4" cy="10" r=".9" fill="currentColor" stroke="none"/><circle cx="4.4" cy="14" r=".9" fill="currentColor" stroke="none"/>',
  clock: '<circle cx="10" cy="10" r="7"/><path d="M10 6.3V10l2.8 1.8"/>',
  eye: '<path d="M2.5 10S5.3 4.8 10 4.8 17.5 10 17.5 10 14.7 15.2 10 15.2 2.5 10 2.5 10z"/><circle cx="10" cy="10" r="2.4"/>',
  pause: '<path d="M7.3 5v10M12.7 5v10"/>',
  play: '<path d="M6.5 4.5v11l9-5.5z"/>',
  'agents-off': `<path d="${SPARK}"/><path d="M3.5 3.5l13 13"/>`,
};

const NS = 'http://www.w3.org/2000/svg';

/**
 * @param {string} name
 * @param {number} [size]
 * @returns {SVGSVGElement}
 */
export function icon(name, size = 20) {
  const markup = ICONS[name];
  if (markup === undefined) throw new Error(`Unknown icon: ${name}`);
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 20 20');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.6');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  svg.innerHTML = markup;
  return svg;
}

export const ICON_NAMES = Object.keys(ICONS);
