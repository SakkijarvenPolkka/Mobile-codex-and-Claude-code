'use strict';
/** Profile and application paths. TRIO_PROFILE_DIR overrides the profile (used by tests). */
const path = require('node:path');
const { app } = require('electron');

function configureProfile() {
  const dir = process.env.TRIO_PROFILE_DIR;
  if (dir) app.setPath('userData', path.resolve(dir));
}

const profileFile = (/** @type {string} */ name) => path.join(app.getPath('userData'), name);
const rendererDir = () => path.join(__dirname, '..', 'renderer');
const preloadFile = () => path.join(__dirname, '..', 'preload', 'trio.js');

module.exports = { configureProfile, profileFile, rendererDir, preloadFile };
