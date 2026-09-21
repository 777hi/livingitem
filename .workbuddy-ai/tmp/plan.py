import zipfile, glob, os, hashlib, io, collections
from PIL import Image

ROOT = r'G:\777hi\mc\mymods\livingitem-template-1.21.1'
MOD = os.path.join(ROOT, r'src\main\resources\assets\living_item\textures')
JAR = r'C:\Users\AI-777hi\.gradle\caches\fabric-loom\1.21.1\minecraft-client.jar'

z = zipfile.ZipFile(JAR)
van_raw, van_px = {}, {}
for n in z.namelist():
    if n.startswith('assets/minecraft/textures/') and n.endswith('.png'):
        b = z.read(n)
        key = n[len('assets/minecraft/textures/'):]
        van_raw.setdefault(hashlib.md5(b).hexdigest(), []).append(key)
        van_px.setdefault(hashlib.md5(Image.open(io.BytesIO(b)).convert('RGBA').tobytes()).hexdigest(), []).append(key)

plan, solid = [], []
for f in sorted(glob.glob(os.path.join(MOD, '**', '*.png'), recursive=True)):
    rel = os.path.relpath(f, MOD).replace('\\', '/')
    raw = open(f, 'rb').read()
    im = Image.open(io.BytesIO(raw)).convert('RGBA')
    if len(set(im.getdata())) == 1:
        solid.append(rel); continue
    rh, ph = hashlib.md5(raw).hexdigest(), hashlib.md5(im.tobytes()).hexdigest()
    if rh in van_raw:
        plan.append((rel, van_raw[rh][0], 'byte'))
    elif ph in van_px:
        plan.append((rel, van_px[ph][0], 'pixel'))

print('=== 拟转换（%d 张）===' % len(plan))
for rel, vk, kind in plan:
    print('  %-38s -> minecraft:%-32s [%s]' % (rel, vk[:-4], kind))
print('\n=== 排除的纯色图（%d 张，撞原版纯色纹理属巧合）===' % len(solid))
print(' ', solid)

ref = collections.defaultdict(list)
for p in glob.glob(os.path.join(ROOT, 'src', '**', '*.*'), recursive=True):
    if os.path.splitext(p)[1] not in ('.json', '.java', '.cfg', '.toml', '.mcmeta', '.txt'):
        continue
    txt = open(p, encoding='utf-8', errors='ignore').read()
    for rel, vk, _ in plan:
        stem = rel[:-4]
        if ('living_item:' + stem) in txt or ('living_item:textures/' + rel) in txt:
            ref[rel].append(os.path.relpath(p, ROOT).replace('\\', '/'))

print('\n=== 引用点 ===')
ext = collections.Counter()
for rel, files in ref.items():
    for p in files:
        ext[os.path.splitext(p)[1]] += 1
print('  引用文件类型分布:', dict(ext))
print('  有引用 %d 张 / 无引用 %d 张' % (len(ref), len([r for r, _, _ in plan if r not in ref])))

nonjson = [(rel, p) for rel, files in ref.items() for p in files if not p.endswith('.json')]
print('\n--- ⚠️ 非 JSON 引用（改 JSON 无效）---')
for rel, p in nonjson:
    print('  %-38s <- %s' % (rel, p))
if not nonjson:
    print('  （无）')

print('\n--- 无引用的副本 ---')
print(' ', [r for r, _, _ in plan if r not in ref] or '（无）')

print('\n--- 引用次数前 8 ---')
for rel, files in sorted(ref.items(), key=lambda kv: -len(kv[1]))[:8]:
    print('  %-38s %d 处' % (rel, len(files)))

print('\n--- 目标映射（用于改写）---')
for rel, vk, kind in sorted(plan):
    if rel in ref:
        print('  "living_item:%s"  ->  "minecraft:%s"' % (rel[:-4], vk[:-4]))
