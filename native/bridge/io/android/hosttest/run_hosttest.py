#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Host test harness of native/bridge/io/android (no device needed).

Compiles the real AndroidMediaImport.cpp / AndroidAacExport.cpp for the host
against a fake NDK media layer (fake_ndk.cpp) and links them with the
host-built Audacity libraries of native/build-host (which must be built).
Compiler flags are those of native/tests/smoke/smoke.cpp, taken from
`ninja -t compdb` (read-only); the plug-in sources get -Wall -Wextra -Werror.
Then runs every mode (full, lconly, implicit, noencoder).

Usage:  native/bridge/io/android/hosttest/run_hosttest.py [--out DIR]
        (DIR defaults to a fresh temporary directory; nothing is written
        into the source tree)
"""
import argparse, glob, json, os, shlex, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
iodir = os.path.dirname(here)
native = os.path.normpath(os.path.join(iodir, '..', '..', '..'))
build_host = os.path.join(native, 'build-host')

ap = argparse.ArgumentParser()
ap.add_argument('--out', default=None)
ap.add_argument('--ninja', default='ninja')
opts = ap.parse_args()
out = opts.out or tempfile.mkdtemp(prefix='ioandroid-hosttest-')
os.makedirs(out, exist_ok=True)

compdb = subprocess.run([opts.ninja, '-C', build_host, '-t', 'compdb'],
                        check=True, capture_output=True, text=True).stdout
entry = next(e for e in json.loads(compdb)
             if e['file'].endswith('tests/smoke/smoke.cpp'))
flags, skip = [], 0
for a in shlex.split(entry['command'])[1:]:
    if skip:
        skip -= 1
        continue
    if a in ('-o', '-MT', '-MF', '-c'):
        skip = 1
        continue
    if a == '-MD' or a.startswith('-DSMOKE_'):
        continue
    if a.startswith('-I'):          # library headers: no -Werror for them
        flags += ['-isystem', a[2:]]
        continue
    flags.append(a)
compiler = shlex.split(entry['command'])[0]
flags += ['-I' + os.path.join(here, 'include'), '-I' + iodir, '-I' + here,
          '-g', '-O1', '-Wall', '-Wextra']

objs = []
for src, strict in ((os.path.join(iodir, 'AndroidMediaImport.cpp'), True),
                    (os.path.join(iodir, 'AndroidAacExport.cpp'), True),
                    (os.path.join(here, 'fake_ndk.cpp'), False),
                    (os.path.join(here, 'test_main.cpp'), False)):
    obj = os.path.join(out, os.path.basename(src) + '.o')
    cmd = [compiler] + flags + (['-Werror'] if strict else []) + ['-c', src, '-o', obj]
    if subprocess.run(cmd, cwd=entry['directory']).returncode:
        sys.exit('compile failed: ' + src)
    objs.append(obj)

lib = os.path.join(build_host, 'lib')
# The import/export modules register themselves when loaded: link them all
# (--no-as-needed) so that the plug-in order is the real one
mods = sorted(glob.glob(os.path.join(lib, 'libmod-*.so')))
libs = ['-Wl,--no-as-needed'] + mods + ['-Wl,--as-needed'] + \
   sorted(glob.glob(os.path.join(lib, 'lib-*.so'))) + [
    os.path.join(lib, n) for n in ('libsqlite.so', 'libsndfile.so',
                                   'libportaudio.so', 'libwx_baseu-3.2.so')]
exe = os.path.join(out, 'ioandroid-hosttest')
if subprocess.run([compiler, '-o', exe] + objs + ['-Wl,-rpath,' + lib] + libs +
                  ['-pthread', '-ldl']).returncode:
    sys.exit('link failed')

failed = []
for mode in ('full', 'lconly', 'implicit', 'noencoder'):
    log = os.path.join(out, 'stderr-' + mode + '.txt')
    with open(log, 'w') as err:
        rc = subprocess.run([exe, mode], stderr=err, cwd=out).returncode
    print(f'--- mode {mode}: rc {rc} (log: {log})', flush=True)
    if rc:
        failed.append(mode)
sys.exit('FAILED: ' + ' '.join(failed) if failed else 0)
