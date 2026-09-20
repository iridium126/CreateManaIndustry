"""Run actual compute shaders in a hidden GLFW context using the cached LWJGL DLL.

Usage: python scripts/validate-block-emitters.py --gradle-cache <GRADLE_USER_HOME>
No Python packages or downloads required. Windows; OpenGL 4.5 required.
"""
import argparse
import ctypes as C
from pathlib import Path
import re
import statistics
import struct
import subprocess
import os
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / 'src/main/resources/assets/createmanaindustry/shaders/particles'
parser = argparse.ArgumentParser()
parser.add_argument('--gradle-cache', required=True, type=Path)
args = parser.parse_args()
jar = next((args.gradle_cache / 'caches/modules-2/files-2.1/org.lwjgl/lwjgl-glfw/3.3.3').rglob('*-natives-windows.jar'))
scratch = ROOT / 'build/block-emitter-validation'
scratch.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(jar) as z:
    dll = next(n for n in z.namelist() if n.endswith('/glfw.dll') or n == 'glfw.dll')
    dll_path = scratch / 'glfw.dll'
    dll_path.write_bytes(z.read(dll))
glfw = C.CDLL(str(dll_path))
glfw.glfwInit.restype = C.c_int
assert glfw.glfwInit(), 'GLFW init failed'
glfw.glfwWindowHint(0x00020004, 0)  # invisible
glfw.glfwWindowHint(0x00022002, 4)
glfw.glfwWindowHint(0x00022003, 5)
glfw.glfwCreateWindow.argtypes = [C.c_int, C.c_int, C.c_char_p, C.c_void_p, C.c_void_p]
glfw.glfwCreateWindow.restype = C.c_void_p
window = glfw.glfwCreateWindow(32, 32, b'CMI compute validation', None, None)
assert window, 'OpenGL 4.5 context unavailable'
glfw.glfwMakeContextCurrent.argtypes = [C.c_void_p]
glfw.glfwMakeContextCurrent(window)
glfw.glfwGetProcAddress.argtypes = [C.c_char_p]
glfw.glfwGetProcAddress.restype = C.c_void_p


def gl(name, result, *types):
    address = glfw.glfwGetProcAddress(name.encode())
    assert address, name
    return C.CFUNCTYPE(result, *types)(address)


U, I, F, P, S = C.c_uint, C.c_int, C.c_float, C.c_void_p, C.c_ssize_t
get_string = gl('glGetString', C.c_char_p, U)
print('GPU:', get_string(0x1F01).decode(), flush=True)
create_shader = gl('glCreateShader', U, U)
shader_source = gl('glShaderSource', None, U, I, C.POINTER(C.c_char_p), P)
compile_shader = gl('glCompileShader', None, U)
shader_iv = gl('glGetShaderiv', None, U, U, C.POINTER(I))
shader_log = gl('glGetShaderInfoLog', None, U, I, P, P)
create_program = gl('glCreateProgram', U)
attach = gl('glAttachShader', None, U, U)
link = gl('glLinkProgram', None, U)
program_iv = gl('glGetProgramiv', None, U, U, C.POINTER(I))
program_log = gl('glGetProgramInfoLog', None, U, I, P, P)
use = gl('glUseProgram', None, U)
location = gl('glGetUniformLocation', I, U, C.c_char_p)
uniform_u = gl('glUniform1ui', None, I, U)
uniform_f = gl('glUniform1f', None, I, F)
uniform_3 = gl('glUniform3f', None, I, F, F, F)
uniform_4 = gl('glUniform4fv', None, I, I, P)
gen_buffers = gl('glGenBuffers', None, I, C.POINTER(U))
bind = gl('glBindBuffer', None, U, U)
bind_base = gl('glBindBufferBase', None, U, U, U)
buffer_data = gl('glBufferData', None, U, S, P, U)
sub_data = gl('glBufferSubData', None, U, S, S, P)
read_data = gl('glGetBufferSubData', None, U, S, S, P)
dispatch = gl('glDispatchCompute', None, U, U, U)
barrier = gl('glMemoryBarrier', None, U)
finish = gl('glFinish', None)
error = gl('glGetError', U)
gen_queries = gl('glGenQueries', None, I, C.POINTER(U))
begin_query = gl('glBeginQuery', None, U, U)
end_query = gl('glEndQuery', None, U)
query_result = gl('glGetQueryObjectui64v', None, U, U, C.POINTER(C.c_ulonglong))


def resolve(path):
    return re.sub(r'^\s*#pragma cmi_include (\S+)\s*$',
                  lambda m: resolve(SHADERS / m[1]), path.read_text(), flags=re.M)


# Same bindings/layout as ParticlePrograms; only constants used by these kernels.
prelude = '''#version 450 core
#define BIND_POOL_READ 0
#define BIND_POOL_WRITE 1
#define BIND_COUNTER 3
#define BIND_EMITCMD 4
#define BIND_EMITTER 5
#define BIND_MEMBERMAP 24
#define VEC4_PER_PARTICLE 4u
#define VEC4_PER_EMITTER 20u
#define MODEL_ABOVE_FEET 1.0
'''


def program(file):
    shader = create_shader(0x91B9)
    source = C.c_char_p((prelude + resolve(SHADERS / file)).encode())
    shader_source(shader, 1, C.byref(source), None)
    compile_shader(shader)
    ok = I()
    shader_iv(shader, 0x8B81, C.byref(ok))
    log = C.create_string_buffer(16384)
    shader_log(shader, len(log), None, log)
    assert ok.value, file + ': ' + log.value.decode()
    p = create_program()
    attach(p, shader)
    link(p)
    program_iv(p, 0x8B82, C.byref(ok))
    program_log(p, len(log), None, log)
    assert ok.value, log.value.decode()
    print('Compiled:', file, flush=True)
    return p


program('emit.comp')  # regression: extracted shared classic spawn must still compile
p = program('block_emit.comp')
use(p)
SSBO = 0x90D2


def buffer(binding, data=None, size=0):
    b = U()
    gen_buffers(1, C.byref(b))
    bind(SSBO, b.value)
    buffer_data(SSBO, len(data) if data else size, data, 0x88E8)
    bind_base(SSBO, binding, b.value)
    return b.value


def upload(b, data, offset=0):
    bind(SSBO, b)
    sub_data(SSBO, offset, len(data), data)


def read(b, size, offset=0):
    result = C.create_string_buffer(size)
    bind(SSBO, b)
    read_data(SSBO, offset, size, result)
    return result.raw


def ui(name, value):
    uniform_u(location(p, name.encode()), value)


def uf(name, value):
    uniform_f(location(p, name.encode()), value)


capacity = 1_000_000
pool = buffer(1, size=(capacity + 1) * 64)
counter = buffer(3, bytes(16))
headers = [0.0] * 80
headers[3] = .46
headers[4:7] = [4, .04, .1]
headers[20:24] = [3.2, 4.26667, .135, .135]
headers[68] = 6
headers[76:79] = [0, 0, 1]
buffer(5, struct.pack('80f', *headers))
table = buffer(4, size=262140 * 32)
ui('uCapacity', capacity)
uf('uDtScale', .01)
uf('uRangeSquared', 120 * 120)
uf('uSeed', 1234)
planes = (F * 24)(*([0, 0, 0, 1] * 6))
uniform_4(location(p, b'uFrustum'), 6, planes)
uniform_3(location(p, b'uCamPos'), 0, 0, 0)


def rows(n, rate=100, x=0):
    return b''.join(struct.pack('8f', x + i % 100 * .01, (i // 100) * .001, 0,
                                rate, 0, 0, 4, 0) for i in range(n))


def run(n, offset=0):
    ui('uEmitterCount', n)
    ui('uEmitterOffset', offset)
    dispatch((n + 3) // 4, 1, 1)
    barrier(0xFFFFFFFF)
    finish()
    assert error() == 0, 'OpenGL error'
    return struct.unpack('I', read(counter, 4))[0]


for n in [1, 3, 10000, 50000]:
    upload(table, rows(n))
    upload(counter, bytes(16))
    assert run(n, n // 2) == n, f'{n} emitters: count mismatch or partial group repeated'
    sample = struct.unpack('16f', read(pool, 64))
    assert .039 <= sum(v*v for v in sample[4:7]) ** .5 <= .101
    assert 3.2 <= sample[13] <= 4.267
print('PASS: 1/3/10,000/50,000 emitters, partial groups, rotation, particle payload')

upload(table, rows(10000, x=1000))
upload(counter, bytes(16))
assert run(10000) == 0
upload(table, rows(10000))
uniform_3(location(p, b'uCamPos'), 1000, 0, 0)
assert run(10000) == 0
uniform_3(location(p, b'uCamPos'), 0, 0, 0)
assert run(10000) == 10000
print('PASS: camera exits/re-enters range, no accumulated catch-up')

upload(counter, bytes(16))
planes[3] = -100
uniform_4(location(p, b'uFrustum'), 6, planes)
assert run(10000) == 0
planes[3] = 1
uniform_4(location(p, b'uFrustum'), 6, planes)
upload(table, rows(10000, rate=0))
assert run(10000) == 0
print('PASS: frustum rejection and removed emitter slots')

upload(table, rows(3, rate=25))
upload(counter, bytes(16))
assert [run(3) for _ in range(4)] == [0, 0, 0, 3]
uf('uDtScale', 0)
assert run(3) == 3
print('PASS: fractional rates and zero emission scale')

uf('uDtScale', .25)
upload(table, rows(3, rate=1024))
upload(counter, bytes(16))
assert run(3) == 768
print('PASS: maximum rate/frame delta, multi-iteration lane loop')

uf('uDtScale', .01)
upload(table, rows(10000))
upload(counter, struct.pack('4I', capacity - 2, 0, 0, 0))
sentinel = bytes([0xAB]) * 64
upload(pool, sentinel, capacity * 64)
run(10000)
assert read(pool, 64, capacity * 64) == sentinel
assert struct.unpack('16f', read(pool, 64, (capacity - 1) * 64))[13] >= 3.2
print('PASS: full-pool bounds; no writes past capacity')

# Kernel-only timings: not a Minecraft rendering/FPS benchmark.
q = U()
gen_queries(1, C.byref(q))
for n in [10000, 50000]:
    upload(table, rows(n))
    uf('uDtScale', 1 / 60)
    ui('uEmitterCount', n)
    ui('uEmitterOffset', 0)
    samples = []
    for iteration in range(35):
        upload(counter, bytes(16))
        begin_query(0x88BF, q.value)
        dispatch((n + 3) // 4, 1, 1)
        end_query(0x88BF)
        barrier(0xFFFFFFFF)
        elapsed = C.c_ulonglong()
        query_result(q.value, 0x8866, C.byref(elapsed))
        if iteration >= 5:
            samples.append(elapsed.value / 1e6)
    print(f'{n:,} emitters: kernel GPU median={statistics.median(samples):.3f} ms, max={max(samples):.3f} ms')
assert error() == 0
glfw.glfwDestroyWindow.argtypes = [C.c_void_p]
glfw.glfwDestroyWindow(window)
glfw.glfwTerminate()

# CompileJava must run first. Exercise the actual Java upload/lifecycle code,
# not a Python reimplementation of its dirty-range or slot-allocation logic.
classpath = [ROOT / 'build/classes/java/main']
for module in ['lwjgl', 'lwjgl-glfw', 'lwjgl-opengl']:
    module_cache = args.gradle_cache / 'caches/modules-2/files-2.1/org.lwjgl' / module / '3.3.3'
    for filename in [f'{module}-3.3.3.jar', f'{module}-3.3.3-natives-windows.jar']:
        classpath.append(next(module_cache.rglob(filename)))
subprocess.run(['java', '-cp', os.pathsep.join(map(str, classpath)),
                str(ROOT / 'scripts/BlockEmitterTableValidation.java')], check=True)
