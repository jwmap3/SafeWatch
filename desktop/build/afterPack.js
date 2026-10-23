'use strict';
// After the app is put together, before it is wrapped in an installer.
//
// 1. With a castLabs EVS account (free; secrets EVS_ACCOUNT_NAME and EVS_PASSWD in the repository's settings), the app
//    is signed for Widevine's Verified Media Path, which paid services such as Netflix ask for before they will play.
//    Without one, those services may refuse to play; everything else works.
// 2. On a Mac, the app is given a plain (ad hoc) signature, without which a Mac with Apple silicon will not open it.
const { execFileSync } = require('child_process');
const path = require('path');

function run(command, args) {
  console.log('  > ' + command + ' ' + args.map((a) => (/PASSWD|passwd/.test(a) ? '***' : a)).join(' '));
  execFileSync(command, args, { stdio: 'inherit' });
}

exports.default = async function afterPack(context) {
  const out = context.appOutDir;
  const python = process.platform === 'win32' ? 'python' : 'python3';
  if (process.env.EVS_ACCOUNT_NAME && process.env.EVS_PASSWD) {
    run(python, ['-m', 'pip', 'install', '--upgrade', 'castlabs-evs']);
    run(python, ['-m', 'castlabs_evs.account', 'reauth', '-A', process.env.EVS_ACCOUNT_NAME, '-P', process.env.EVS_PASSWD]);
    run(python, ['-m', 'castlabs_evs.vmp', 'sign-pkg', out]);
  } else {
    console.log('  no EVS account set: the app is not signed for Verified Media Path');
  }
  if (context.electronPlatformName === 'darwin') {
    run('codesign', ['--force', '--deep', '--sign', '-', path.join(out, context.packager.appInfo.productFilename + '.app')]);
  }
};
