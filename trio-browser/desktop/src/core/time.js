'use strict';
/**
 * Local-time helpers that take an IANA time-zone name, so results do not depend on the
 * machine's zone. The Kotlin port uses java.time with the same zone names.
 */

/** @type {Map<string, Intl.DateTimeFormat>} */
const formatters = new Map();

/** @param {string} timeZone */
function formatter(timeZone) {
  let f = formatters.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      weekday: 'short',
    });
    formatters.set(timeZone, f);
  }
  return f;
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/**
 * @typedef {{ year: number, month: number, day: number, hour: number, minute: number, second: number, weekday: number }} LocalParts
 * weekday: 0 = Monday … 6 = Sunday
 */

/**
 * @param {number} time epoch milliseconds
 * @param {string} timeZone
 * @returns {LocalParts}
 */
function localParts(time, timeZone) {
  /** @type {Record<string, string>} */
  const p = {};
  for (const part of formatter(timeZone).formatToParts(new Date(time))) p[part.type] = part.value;
  return {
    year: Number(p.year),
    month: Number(p.month),
    day: Number(p.day),
    hour: Number(p.hour) % 24,
    minute: Number(p.minute),
    second: Number(p.second),
    weekday: WEEKDAYS.indexOf(p.weekday),
  };
}

/** Offset of local time from UTC at the given instant, in milliseconds. */
function offsetAt(/** @type {number} */ time, /** @type {string} */ timeZone) {
  const p = localParts(time, timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return asUtc - (time - (((time % 1000) + 1000) % 1000));
}

/**
 * Epoch milliseconds of local midnight of the day that contains `time`.
 * @param {number} time
 * @param {string} timeZone
 */
function startOfLocalDay(time, timeZone) {
  const p = localParts(time, timeZone);
  const midnightUtc = Date.UTC(p.year, p.month - 1, p.day);
  const guess = midnightUtc - offsetAt(time, timeZone);
  return midnightUtc - offsetAt(guess, timeZone);
}

/** @param {number} n @param {number} width */
function pad(n, width = 2) {
  return String(n).padStart(width, '0');
}

/** "2026-11-26" @param {number} time @param {string} timeZone */
function localDate(time, timeZone) {
  const p = localParts(time, timeZone);
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}

/** "14:05" @param {number} time @param {string} timeZone */
function localTime(time, timeZone) {
  const p = localParts(time, timeZone);
  return `${pad(p.hour)}:${pad(p.minute)}`;
}

module.exports = { localParts, startOfLocalDay, localDate, localTime, pad };
