#!/usr/bin/env python3
# Текст оповещения владельцу о запуске видео Veo. Аргументы: <ключи через запятую> <статус job> <ссылка на запуск>
import json, os, sys, datetime
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
made, job, run = (sys.argv[1:] + ['', '', ''])[:3]
made = [k for k in made.split(',') if k]
prompts = json.load(open(os.path.join(ROOT, 'media', 'veo_prompts.json'), encoding='utf-8'))
names = {p['k']: p['n'] for p in prompts}
try:
    idx = json.load(open(os.path.join(ROOT, 'media', 'index.json'), encoding='utf-8'))
except Exception:
    idx = {}
left = sum(1 for p in prompts if p['k'] not in idx)
done = len(prompts) - left
today = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d')
try:
    log = json.load(open(os.path.join(ROOT, 'media', 'veo_state.json'), encoding='utf-8'))['log']
except Exception:
    log = []
errs = [e for e in log if e['d'] == today and not e['ok']]
lines = []
if made:
    lines.append('🎬 Видео техники загружены: ' + str(len(made)))
    lines += ['✅ ' + names.get(k, k) for k in made]
else:
    lines.append('🎬 Видео техники: сегодня новых нет')
if errs:
    e = errs[-1]
    lines.append(f'⚠️ Ошибка ({names.get(e["k"], e["k"])}): {e.get("err", "")[:200]}')
if job and job != 'success':
    lines.append(f'❌ Запуск завершился со статусом: {job}')
lines.append(f'Готово {done} из {len(prompts)}, осталось {left} (~{(left + 1) // 2} дн.)')
lines.append(run)
print('\n'.join(lines))
