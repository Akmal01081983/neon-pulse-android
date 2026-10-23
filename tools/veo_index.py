#!/usr/bin/env python3
# Дописывает в media/index.json ссылки jsDelivr на новые ролики, закреплённые за коммитом SHA.
# Запуск: veo_index.py <sha> <ключ1,ключ2>. Петли media/loop/<ключ>.mp4 (если есть) — в media/loops.json
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
# Петли для карточек (media/loop/<ключ>.mp4) — в media/loops.json, тем же коммитом
lp = os.path.join(ROOT, 'media', 'loops.json')
loops = json.load(open(lp, encoding='utf-8')) if os.path.exists(lp) else {}
for k in keys:
    if os.path.exists(os.path.join(ROOT, 'media', 'loop', f'{k}.mp4')):
        loops[k] = f'https://cdn.jsdelivr.net/gh/Akmal01081983/neon-pulse-android@{sha}/media/loop/{k}.mp4'
json.dump(loops, open(lp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
open(lp, 'a').write('\n')
print('В loops.json:', len(loops), 'петель')
