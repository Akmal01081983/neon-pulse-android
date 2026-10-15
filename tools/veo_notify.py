#!/usr/bin/env python3
# Текст оповещения владельцу о запуске видео Veo. Аргументы: <ключи через запятую> <статус job> <ссылка на запуск>
import json, os, sys, datetime
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUIET = '--quiet' in sys.argv
made, job, run = ([a for a in sys.argv[1:] if a != '--quiet'] + ['', '', ''])[:3]
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
if QUIET:
    # skip — сегодня лимит уже выбран без ошибок (запасной запуск), сообщать нечего
    # лимит уже выбран более ранним запуском (он и отправил отчёт) — повторять нечего
    used = sum(1 for e in log if e['d'] == today and (e['ok'] or e.get('rej')))
    print('skip' if used >= int(os.environ.get('DAILY') or 2) else 'send')
    sys.exit(0)
lines = []
if made:
    lines.append('🎬 Видео техники загружены: ' + str(len(made)))
    sc = {e['k']: e.get('score') for e in log if e['d'] == today and e['ok']}
    lines += ['✅ ' + names.get(k, k) + (f' — техника {sc[k]}/10' if sc.get(k) else '') for k in made]
else:
    lines.append('🎬 Видео техники: сегодня новых нет')
for e in errs[-3:]:
    mark = '🚫 Отбракован' if e.get('rej') else '⚠️ Ошибка'
    sc = f' {e["score"]}/10' if e.get('rej') else ''
    lines.append(f'{mark}{sc} ({names.get(e["k"], e["k"])}): {e.get("err", "")[:200]}')
if any(e.get('rej') for e in errs):
    lines.append('Брак не публикуется, упражнение будет снято заново.')
if job and job != 'success':
    lines.append(f'❌ Запуск завершился со статусом: {job}')
lines.append(f'Готово {done} из {len(prompts)}, осталось {left} (~{(left + 1) // 2} дн.)')
lines.append(run)
print('\n'.join(lines))
