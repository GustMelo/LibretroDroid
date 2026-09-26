#!/usr/bin/env python3
"""Package built artifacts; never includes source working trees or build intermediates."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
out = root / 'build/distribution'
out.mkdir(parents=True, exist_ok=True)
version = next(line.split('=', 1)[1] for line in (root / 'gradle.properties').read_text().splitlines() if line.startswith('version='))
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
files = {}
for name, directory in {'maven': 'build/maven', 'cores-android': '.cache/cores/android', 'cores-ios': '.cache/cores/ios', 'angle-ios': '.cache/angle', 'native-ios': 'build/native-xcframework'}.items():
    source = root / directory
    if not source.is_dir() or not any(source.iterdir()):
        raise SystemExit(f'Missing build output: {source}')
    target = out / f'{name}.zip'
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob('*')):
            if path.is_file() and not path.name.startswith('.'):
                archive.write(path, path.relative_to(source))
    files[target.name] = hashlib.sha256(target.read_bytes()).hexdigest()
manifest = {'version': version, 'commit': commit, 'upstream': '8835c3098514390a271e36983957f7bb5f40abf1', 'sha256': files}
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
