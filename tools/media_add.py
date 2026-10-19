#!/usr/bin/env python3
# Обработка видео техники, сделанного владельцем в приложении Gemini (бесплатный лимит Veo).
#   media_add.py next [N]                   — следующие N упражнений без видео (по умолчанию 2) с промптами
#   media_add.py prep <ключ> <файл.mp4>     — обработать: без звука, значок Veo закрыт, 960×540 → media/<ключ>.mp4;
#                                             лист кадров для проверки техники → /tmp/<ключ>_sheet.jpg
#   media_add.py publish <ключ1,ключ2>      — коммит роликов в main, затем ссылки jsDelivr в index.json
# Проверку техники делает Claude по листу кадров (описание техники и ошибки — в media/veo_prompts.json).
import json, os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEDIA = os.path.join(ROOT, 'media')
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'


def prompts():
    return {p['k']: p for p in json.load(open(os.path.join(MEDIA, 'veo_prompts.json'), encoding='utf-8'))}


def index():
    return json.load(open(os.path.join(MEDIA, 'index.json'), encoding='utf-8'))


def probe(path):
    out = subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                                   'stream=width,height:format=duration', '-of', 'json', path])
    j = json.loads(out)
    return j['streams'][0]['width'], j['streams'][0]['height'], float(j['format']['duration'])


def cmd_next(n=2):
    idx, ps = index(), json.load(open(os.path.join(MEDIA, 'veo_prompts.json'), encoding='utf-8'))
    todo = [p for p in ps if p['k'] not in idx]
    print(json.dumps({'left': len(todo), 'done': len(ps) - len(todo), 'total': len(ps),
                      'next': [{'k': p['k'], 'n': p['n'], 'p': p['p']} for p in todo[:n]]}, ensure_ascii=False, indent=1))


def cmd_prep(key, src, logo=True):
    ps = prompts()
    if key not in ps:
        sys.exit(f'Нет упражнения «{key}» в veo_prompts.json')
    w, h, dur = probe(src)
    if w < h:
        sys.exit(f'Вертикальное видео {w}×{h} — нужно горизонтальное 16:9')
    vf = []
    if logo:
        # Значок Veo в правом нижнем углу роликов из приложения Gemini — размываем (фон однотонный)
        bw, bh = int(w * 0.13), int(h * 0.11)
        vf.append(f'delogo=x={w - bw - int(w * 0.012)}:y={h - bh - int(h * 0.015)}:w={bw}:h={bh}')
    vf.append('scale=960:540:force_original_aspect_ratio=decrease,pad=960:540:(ow-iw)/2:(oh-ih)/2:color=0x2b2b2b')
    out = os.path.join(MEDIA, key + '.mp4')
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', src, '-an', '-t', '10', '-vf', ','.join(vf), '-c:v', 'libx264',
                    '-preset', 'slow', '-crf', '26', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], check=True)
    # Лист кадров: 8 кадров по всей длине + номер секунды — для проверки техники
    fps = 8 / max(min(dur, 10), 1)
    sheet = f'/tmp/{key}_sheet.jpg'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', out, '-vf',
                    f"fps={fps:.3f},scale=480:-1,drawtext=fontfile={FONT}:text='%{{pts\\:hms}}':x=8:y=8:fontsize=18:fontcolor=white,tile=4x2",
                    '-frames:v', '1', sheet], check=True)
    p = ps[key]
    print(json.dumps({'k': key, 'n': p['n'], 'src': f'{w}x{h}', 'sec': round(dur, 1), 'out': out, 'sheet': sheet,
                      'tech': p.get('tech'), 'errors': p.get('errors')}, ensure_ascii=False, indent=1))


def git(*a):
    return subprocess.run(['git', '-C', ROOT, *a], check=True, capture_output=True, text=True).stdout.strip()


def cmd_publish(keys):
    keys = [k for k in keys.split(',') if k]
    for k in keys:
        if not os.path.exists(os.path.join(MEDIA, k + '.mp4')):
            sys.exit(f'Нет media/{k}.mp4 — сначала prep')
    git('checkout', '-q', 'main')
    git('pull', '-q', '--ff-only', 'origin', 'main')
    git('add', *[f'media/{k}.mp4' for k in keys])
    git('commit', '-q', '-m', f'media: {",".join(keys)} (Gemini, проверено)')
    git('push', '-q', 'origin', 'main')
    sha = git('rev-parse', 'HEAD')
    subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'veo_index.py'), sha, ','.join(keys)], check=True)
    git('add', 'media/index.json')
    git('commit', '-q', '-m', f'media: ссылки {",".join(keys)}')
    git('push', '-q', 'origin', 'main')
    print('Опубликовано:', ', '.join(keys))


if __name__ == '__main__':
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__ or 'см. заголовок файла')
    if a[0] == 'next':
        cmd_next(int(a[1]) if len(a) > 1 else 2)
    elif a[0] == 'prep':
        cmd_prep(a[1], a[2], '--no-logo' not in a)
    elif a[0] == 'publish':
        cmd_publish(a[1])
    else:
        sys.exit('Команды: next | prep | publish')
