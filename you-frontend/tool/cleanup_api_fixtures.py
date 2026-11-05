"""Clean only users recorded by api_live_test in the local Compose stack."""
import json
import re
import subprocess
from pathlib import Path

manifest = Path('build/api-live-users.json')
if not manifest.exists():
    raise SystemExit('No live-test fixtures to clean')

ids = json.loads(manifest.read_text())
if not isinstance(ids, list) or not all(
    isinstance(value, str) and re.fullmatch(r'[0-9a-f-]{36}', value)
    for value in ids
):
    raise SystemExit('Invalid live-test user manifest')


def sql(query):
    return subprocess.check_output([
        'docker', 'compose', '-f', '../compose.yaml', 'exec', '-T',
        'mysql', 'mysql', '-N', '-B', '-uyou', '-pyou', 'you', '-e', query,
    ], stderr=subprocess.DEVNULL).decode().strip()


for uid in ids:
    owner = "UUID_TO_BIN('" + uid + "')"
    username = sql('SELECT username FROM app_user WHERE id=' + owner)
    if not username:
        continue
    if not re.fullmatch(r'flutter_it_[0-9]+', username):
        raise SystemExit('Refusing to clean a non-test user')
    sql('DELETE FROM app_user WHERE id=' + owner + " AND username='" + username + "'")

manifest.unlink()
print('Cleaned live-test users:', len(ids))
