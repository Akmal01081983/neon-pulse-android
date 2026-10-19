#!/usr/bin/env python3
# Ежедневная генерация демо-видео техники через Veo (Gemini API).
# Берёт следующие упражнения из media/veo_prompts.json, которых ещё нет в media/index.json,
# делает не больше DAILY видео за сутки (UTC), кладёт в media/<ключ>.mp4.
# Ссылки в index.json дописывает отдельный шаг процесса (нужен SHA коммита с роликом).
import json, os, subprocess, sys, time, urllib.request, urllib.error, datetime, base64

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEDIA = os.path.join(ROOT, 'media')
KEY = os.environ['GEMINI_API_KEY']
MODEL = os.environ.get('VEO_MODEL') or 'veo-3.1-generate-preview'
DAILY = int(os.environ.get('DAILY') or 2)          # лимит видео в сутки
REDO = [k.strip() for k in (os.environ.get('REDO') or '').split(',') if k.strip()]  # переснять (плохие ролики)
BASE = 'https://generativelanguage.googleapis.com/v1beta'
STATE = os.path.join(MEDIA, 'veo_state.json')
# Проверка техники: модель смотрит ролик до публикации. Первая доступная из списка.
CHECK_MODELS = [m for m in [os.environ.get('VEO_CHECK_MODEL'), 'gemini-3.8-flash', 'gemini-2.5-flash'] if m]
MIN_SCORE = int(os.environ.get('VEO_MIN_SCORE') or 8)   # проходной балл из 10
MAX_REJECT = 3   # после стольких отказов упражнение уходит в конец очереди


def load(p, d):
    try:
        return json.load(open(p, encoding='utf-8'))
    except FileNotFoundError:
        return d


def api(url, body=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None,
                                 headers={'x-goog-api-key': KEY, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def start(prompt):
    # Сначала полный набор параметров; при отказе 400 — минимальный (часть моделей/ключей не принимает лишние поля)
    variants = [{'aspectRatio': '16:9', 'durationSeconds': 8, 'resolution': '720p', 'personGeneration': 'allow_all'},
                {'aspectRatio': '16:9'}]
    for i, prm in enumerate(variants):
        try:
            return api(f'{BASE}/models/{MODEL}:predictLongRunning', {'instances': [{'prompt': prompt}], 'parameters': prm})
        except urllib.error.HTTPError as e:
            if e.code != 400 or i == len(variants) - 1:
                raise
            print('  400 на полных параметрах, пробую минимальные:', e.read().decode(errors='ignore')[:200])


def generate(prompt, raw):
    name = start(prompt)['name']
    for _ in range(90):                               # до 15 минут ожидания
        time.sleep(10)
        st = api(f'{BASE}/{name}')
        if st.get('done'):
            break
    else:
        raise RuntimeError('Veo не ответил за 15 минут')
    if 'error' in st:
        raise RuntimeError(st['error'].get('message', 'ошибка Veo'))
    smp = (st.get('response', {}).get('generateVideoResponse', {}).get('generatedSamples') or [])
    if not smp:
        raise RuntimeError('ролик заблокирован фильтром Veo (деньги не списываются)')
    req = urllib.request.Request(smp[0]['video']['uri'], headers={'x-goog-api-key': KEY})
    with urllib.request.urlopen(req, timeout=300) as r, open(raw, 'wb') as f:
        f.write(r.read())


CHECK_PROMPT = '''Ты строгий тренер по технике упражнений. Посмотри 8-секундное демо-видео для фитнес-приложения.
Упражнение: {n}.
Правильная техника: {tech}
Типичные ошибки (их быть не должно): {errors}

Проверь по пунктам:
1. Это именно это упражнение и правильный снаряд/тренажёр (не похожий, не выдуманный).
2. Движение реально выполняется с полной амплитудой из описания (не застывшая поза, не полдвижения).
3. Нет ни одной типичной ошибки, техника безопасна.
4. В кадре ровно одна фигура-манекен без лица, всё тело видно целиком (не обрезаны стопы/голова), нет текста и логотипов.
5. Нет артефактов: лишние конечности, предметы сквозь тело, снаряд меняет форму.

Ответь только JSON: {{"score": 0-10, "ok": true/false, "issues": ["кратко по-русски, что не так"]}}.
ok=true только если все 5 пунктов выполнены. Сомневаешься — ok=false.'''


def check(path, p):
    # Возвращает (прошло, балл, замечания). Ошибка API — не прошло (лучше переснять, чем показать брак).
    vid = base64.b64encode(open(path, 'rb').read()).decode()
    text = CHECK_PROMPT.format(n=p['n'], tech=p.get('tech', ''), errors='; '.join(p.get('errors', [])) or '—')
    last = ''
    for model in CHECK_MODELS:
        for meta in ({'videoMetadata': {'fps': 4}}, {}):
            part = {'inline_data': {'mime_type': 'video/mp4', 'data': vid}, **meta}
            try:
                r = api(f'{BASE}/models/{model}:generateContent', {
                    'contents': [{'parts': [part, {'text': text}]}],
                    'generationConfig': {'responseMimeType': 'application/json', 'temperature': 0}})
                out = ''.join(x.get('text', '') for x in r['candidates'][0]['content']['parts'])
                j = json.loads(out[out.find('{'):out.rfind('}') + 1])
                score = int(j.get('score', 0))
                return bool(j.get('ok')) and score >= MIN_SCORE, score, [str(i) for i in j.get('issues', [])][:4]
            except urllib.error.HTTPError as e:
                last = f'{model}: HTTP {e.code}'
                if e.code == 404:
                    break          # модели нет — следующая
            except Exception as e:
                last = f'{model}: {str(e)[:100]}'
    return False, 0, [f'проверка недоступна ({last})']


def finish(raw, out):
    # Как у готовых роликов: 960×540, без звука, быстрый старт в браузере
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', raw, '-an', '-vf', 'scale=960:540', '-c:v', 'libx264',
                    '-preset', 'slow', '-crf', '26', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], check=True)


def main():
    prompts = json.load(open(os.path.join(MEDIA, 'veo_prompts.json'), encoding='utf-8'))
    index = load(os.path.join(MEDIA, 'index.json'), {})
    state = load(STATE, {'log': []})
    today = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d')
    # Лимит тратят ролики, которые Veo сделал (опубликованные и отбракованные). Ошибки API лимит не тратят.
    used = sum(1 for e in state['log'] if e['d'] == today and (e['ok'] or e.get('rej')))
    left = max(0, DAILY - used)
    rej = {}
    for e in state['log']:
        if e.get('rej'):
            rej[e['k']] = rej.get(e['k'], 0) + 1
    queue = [p for p in prompts if p['k'] not in index and p['k'] not in REDO]
    queue.sort(key=lambda p: rej.get(p['k'], 0) >= MAX_REJECT)   # часто бракуемые — в конец
    todo = [p for p in prompts if p['k'] in REDO] + queue
    print(f'Осталось снять: {sum(1 for p in prompts if p["k"] not in index)} · сегодня можно ещё: {left}')
    made = []
    for p in todo[:left]:
        out, raw = os.path.join(MEDIA, p['k'] + '.mp4'), f'/tmp/veo_{p["k"]}.mp4'
        print(f'▶ {p["k"]} — {p["n"]}')
        try:
            generate(p['p'], raw)
            tmp = f'/tmp/veo_{p["k"]}_fin.mp4'
            finish(raw, tmp)
            ok, score, issues = check(tmp, p)
            if ok:
                os.replace(tmp, out)
                made.append(p['k'])
                state['log'].append({'d': today, 'k': p['k'], 'ok': True, 'score': score})
                print(f'  ✓ готово, техника {score}/10')
            else:
                state['log'].append({'d': today, 'k': p['k'], 'ok': False, 'rej': True, 'score': score,
                                     'err': 'Брак техники: ' + '; '.join(issues)[:250]})
                print(f'  ✗ отбракован ({score}/10): {issues}')
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors='ignore')[:300]
            try:
                msg = json.loads(msg)['error']['message'][:300]   # текст ошибки Google — в журнал
            except Exception:
                pass
            state['log'].append({'d': today, 'k': p['k'], 'ok': False, 'err': f'HTTP {e.code}: {msg}'})
            print(f'  ✗ HTTP {e.code}: {msg}')
            if e.code in (429, 403, 402, 400):   # лимит/баланс/ключ/модель — дальше пробовать бессмысленно
                break
        except Exception as e:
            state['log'].append({'d': today, 'k': p['k'], 'ok': False, 'err': str(e)[:200]})
            print(f'  ✗ {e}')
    state['log'] = state['log'][-200:]
    json.dump(state, open(STATE, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    open(os.environ.get('GITHUB_OUTPUT', '/dev/null'), 'a').write(f'made={",".join(made)}\n')


if __name__ == '__main__':
    main()
