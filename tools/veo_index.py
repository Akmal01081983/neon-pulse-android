#!/usr/bin/env python3
# Дописывает в media/index.json ссылки jsDelivr на новые ролики, закреплённые за коммитом SHA.
# Запуск: veo_index.py <sha> <ключ1,ключ2>
import json, os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
p = os.path.join(ROOT, 'media', 'index.json')
sha, keys = sys.argv[1], [k for k in sys.argv[2].split(',') if k]
idx = json.load(open(p, encoding='utf-8'))
for k in keys:
    idx[k] = f'https://cdn.jsdelivr.net/gh/Akmal01081983/neon-pulse-android@{sha}/media/{k}.mp4'
json.dump(idx, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
open(p, 'a').write('\n')
print('В index.json:', len(idx), 'роликов')
