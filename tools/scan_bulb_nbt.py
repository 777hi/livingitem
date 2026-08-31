"""扫描 MC 存档 region 文件，导出所有容器内活物品组件（服务端真值）。"""
import struct, zlib, gzip, sys, io, os, glob

class NBT:
    def __init__(self, data):
        self.d = data
        self.i = 0
    def u1(self):
        v = self.d[self.i]; self.i += 1; return v
    def s2(self):
        v = struct.unpack_from('>h', self.d, self.i)[0]; self.i += 2; return v
    def s4(self):
        v = struct.unpack_from('>i', self.d, self.i)[0]; self.i += 4; return v
    def s8(self):
        v = struct.unpack_from('>q', self.d, self.i)[0]; self.i += 8; return v
    def f4(self):
        v = struct.unpack_from('>f', self.d, self.i)[0]; self.i += 4; return v
    def f8(self):
        v = struct.unpack_from('>d', self.d, self.i)[0]; self.i += 8; return v
    def string(self):
        n = struct.unpack_from('>H', self.d, self.i)[0]; self.i += 2
        v = self.d[self.i:self.i+n].decode('utf-8', 'replace'); self.i += n; return v
    def payload(self, t):
        if t == 1: return self.u1()
        if t == 2: return self.s2()
        if t == 3: return self.s4()
        if t == 4: return self.s8()
        if t == 5: return self.f4()
        if t == 6: return self.f8()
        if t == 7:
            n = self.s4(); v = self.d[self.i:self.i+n]; self.i += n; return v
        if t == 8: return self.string()
        if t == 9:
            it = self.u1(); n = self.s4()
            return [self.payload(it) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                et = self.u1()
                if et == 0: break
                name = self.string()
                out[name] = self.payload(et)
            return out
        if t == 11:
            n = self.s4()
            v = [struct.unpack_from('>i', self.d, self.i+4*k)[0] for k in range(n)]
            self.i += 4*n; return v
        if t == 12:
            n = self.s4()
            v = [struct.unpack_from('>q', self.d, self.i+8*k)[0] for k in range(n)]
            self.i += 8*n; return v
        raise ValueError(f'bad tag {t} at {self.i}')
    def parse(self):
        t = self.u1()
        assert t == 10, 'root must be compound'
        self.string()
        return self.payload(10)

def read_region(path):
    with open(path, 'rb') as f:
        blob = f.read()
    if len(blob) < 8192: return
    for idx in range(1024):
        off = struct.unpack_from('>I', b'\x00' + blob[idx*4:idx*4+3])[0]
        cnt = blob[idx*4+3]
        if off == 0 or cnt == 0: continue
        start = off * 4096
        if start + 5 > len(blob): continue
        length = struct.unpack_from('>I', blob, start)[0]
        comp = blob[start+4]
        raw = blob[start+5:start+4+length]
        try:
            if comp == 1: data = gzip.decompress(raw)
            elif comp == 2: data = zlib.decompress(raw)
            elif comp == 3: data = raw
            else: continue
            yield NBT(data).parse()
        except Exception:
            continue

LIVING_PREFIX = 'living_item:'

def walk(root):
    found = []
    for be in root.get('block_entities', []):
        items = be.get('Items')
        if not items: continue
        pos = (be.get('x'), be.get('y'), be.get('z'))
        for it in items:
            comps = it.get('components') or {}
            living = {k: v for k, v in comps.items() if k.startswith(LIVING_PREFIX)}
            if living:
                found.append((be.get('id'), pos, it.get('id'), it.get('count'), living))
    return found

def main(region_dir):
    for path in sorted(glob.glob(os.path.join(region_dir, '*.mca'))):
        name = os.path.basename(path)
        for root in read_region(path):
            try:
                for be_id, pos, item_id, count, living in walk(root):
                    q = ''
                    for k, v in living.items():
                        if 'bulb' in k:
                            q = f'  q={v.get("charge_milli_fe")}'
                    extra = {k.split(':', 1)[1]: v for k, v in living.items() if 'bulb' not in k and k != 'living_item:is_living'}
                    print(f'{name}  {be_id} @{pos}  {item_id} x{count}{q}' + (f'  extra={extra}' if extra else ''))
            except Exception as e:
                print(f'{name}  [chunk parse error: {e}]', file=sys.stderr)

if __name__ == '__main__':
    main(sys.argv[1])
