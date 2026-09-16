#!/usr/bin/env python3
"""Launch the patched NXT client against this server.

The client does not run on its own: it expects to be started by the game
launcher, which passes it the launcher parameters on the command line and then
talks to it over two named pipes. This script plays that role.

    python tools/launch_client.py
    python tools/launch_client.py --port 43594 --host 127.0.0.1

Windows only.
"""
import argparse
import ctypes
import ctypes.wintypes as w
import os
import re
import secrets
import struct
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = 950

PIPE_ACCESS_DUPLEX = 0x00000003
INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value
STILL_ACTIVE = 259
ERROR_PIPE_CONNECTED = 535

OP_CONFIG = 0x0001
OP_READY = 0x0003
OP_PROGRESS = 0x000D
OP_UI_STRINGS = 0x001C
OP_GO_AHEAD = 0x001F

CONFIG_PREFIX = bytes.fromhex('00040000000000000000')
CONFIG_MIDDLE = bytes.fromhex('0004000007')
CONFIG_SUFFIX = bytes.fromhex(
    '0002fd021c0f0008700400030000220001'
    'ffffffffffffffffffffffffffffffff'
    '00000000000000000000'
    '041000000327')
GAME_NAME = b'RuneScape'
UI_STRINGS = [b'RuneScape', b'Options', b'Quit', b'Terms and Conditions', b'Privacy Policy']


class STARTUPINFOW(ctypes.Structure):
    _fields_ = [('cb', w.DWORD), ('lpReserved', w.LPWSTR), ('lpDesktop', w.LPWSTR),
                ('lpTitle', w.LPWSTR), ('dwX', w.DWORD), ('dwY', w.DWORD),
                ('dwXSize', w.DWORD), ('dwYSize', w.DWORD), ('dwXCountChars', w.DWORD),
                ('dwYCountChars', w.DWORD), ('dwFillAttribute', w.DWORD),
                ('dwFlags', w.DWORD), ('wShowWindow', w.WORD), ('cbReserved2', w.WORD),
                ('lpReserved2', w.LPVOID), ('hStdInput', w.HANDLE),
                ('hStdOutput', w.HANDLE), ('hStdError', w.HANDLE)]


class PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [('hProcess', w.HANDLE), ('hThread', w.HANDLE),
                ('dwProcessId', w.DWORD), ('dwThreadId', w.DWORD)]


def frame(opcode, body):
    payload = struct.pack('>H', opcode) + body
    return struct.pack('<H', len(payload)) + payload


def config_frame(cache_dir, user_dir):
    body = (CONFIG_PREFIX
            + cache_dir.encode('ascii') + b'\x00'
            + user_dir.encode('ascii') + b'\x00'
            + CONFIG_MIDDLE + GAME_NAME + CONFIG_SUFFIX)
    return frame(OP_CONFIG, body)


def ui_strings_frame():
    return frame(OP_UI_STRINGS, b'\x00\x01' + b''.join(s + b'\x00' for s in UI_STRINGS))


def go_ahead_frame():
    return frame(OP_GO_AHEAD, b'\x00\x01\x01')


def read_server_port():
    path = os.path.join(ROOT, 'data', 'config', 'server.toml')
    try:
        import tomllib
        with open(path, 'rb') as fh:
            return int(tomllib.load(fh)['networking']['ports']['game'])
    except Exception:
        return 43594


def read_params(config_path):
    params = {}
    with open(config_path, encoding='utf-8', errors='replace') as fh:
        for line in fh:
            m = re.match(r'param=(\d+)=(.*)$', line.rstrip('\r\n'))
            if m:
                params[m.group(1)] = m.group(2)
    if not params:
        raise SystemExit('no param= lines in %s - run the client-patcher step first' % config_path)
    return params


def launcher_args(params, host, port):
    p = dict(params)
    for key in ('3', '36', '37', '49', '53', '54', '56', '58', '59'):
        if key in p:
            p[key] = host
    p['16'] = '.' + host
    p['35'] = 'http://' + host
    p['40'] = 'http://' + host
    for key in range(41, 49):
        p[str(key)] = str(port)
    p['25'] = '0'
    return [x for k in sorted(p, key=lambda k: k + '=') for x in (k, p[k])]


def refuse_real_install(label, path):
    lower = path.replace('\\', '/').lower()
    for marker in ('/programdata/jagex', '/appdata/local/jagex', '/appdata/roaming/jagex'):
        if marker in lower:
            raise SystemExit('refusing to launch: the %s folder %s is inside a real game install; '
                             'use a folder of its own' % (label, path))


k32 = ctypes.WinDLL('kernel32', use_last_error=True) if os.name == 'nt' else None


def make_pipe(name):
    k32.CreateNamedPipeW.restype = w.HANDLE
    k32.CreateNamedPipeW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, w.DWORD,
                                     w.DWORD, w.DWORD, w.DWORD, w.LPVOID]
    h = k32.CreateNamedPipeW(name, PIPE_ACCESS_DUPLEX, 0, 4, 65536, 65536, 0, None)
    if h == INVALID_HANDLE_VALUE or h is None:
        raise ctypes.WinError(ctypes.get_last_error())
    return h


def write(handle, data):
    n = w.DWORD(0)
    k32.WriteFile(w.HANDLE(handle), ctypes.create_string_buffer(data, len(data)), len(data),
                  ctypes.byref(n), None)
    k32.FlushFileBuffers(w.HANDLE(handle))


def connect(handle):
    ok = k32.ConnectNamedPipe(w.HANDLE(handle), None)
    return bool(ok) or ctypes.get_last_error() in (0, ERROR_PIPE_CONNECTED)


def serve(in_pipe, out_pipe, out_ready, stop, cache_dir, user_dir):
    if not connect(in_pipe):
        print('client did not connect to the launcher pipe')
        return
    buf = ctypes.create_string_buffer(4096)
    n = w.DWORD(0)
    configured = False
    last_progress = -1
    while not stop.is_set():
        ok = k32.ReadFile(w.HANDLE(in_pipe), buf, 4096, ctypes.byref(n), None)
        if not ok or n.value == 0:
            time.sleep(0.05)
            continue
        data = buf.raw[:n.value]
        if not configured:
            configured = True
            if out_ready.wait(10):
                write(out_pipe, config_frame(cache_dir, user_dir))
                write(out_pipe, ui_strings_frame())
            else:
                print('client never opened the launcher output pipe')
        if len(data) >= 10 and data[2:4] == struct.pack('>H', OP_PROGRESS):
            pct = int(struct.unpack('>f', data[6:10])[0])
            if pct // 10 != last_progress // 10:
                print('loading %d%%' % pct)
            last_progress = pct
        if struct.pack('>HH', OP_READY, 1) in data:
            write(out_pipe, go_ahead_frame())
            print('client loaded')


def main():
    if os.name != 'nt':
        raise SystemExit('the NXT client and its launcher protocol are Windows only')
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    default_client = os.path.join(ROOT, 'data', 'clients', str(BUILD), 'win64c', 'patched', 'rs2client.exe')
    ap.add_argument('--client', default=default_client)
    ap.add_argument('--config', help='jav_config.ws to take launcher parameters from (default: beside the client)')
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=None, help='game port (default: from data/config/server.toml)')
    ap.add_argument('--cache-dir', default=os.path.join(ROOT, 'data', 'client', 'cache'))
    ap.add_argument('--user-dir', default=os.path.join(ROOT, 'data', 'client', 'user'))
    args = ap.parse_args()

    client = os.path.abspath(args.client)
    if not os.path.isfile(client):
        raise SystemExit('no client at %s - run setup first' % client)
    config = args.config or os.path.join(os.path.dirname(client), 'jav_config.ws')
    port = args.port or read_server_port()
    cache_dir = os.path.abspath(args.cache_dir)
    user_dir = os.path.abspath(args.user_dir)
    refuse_real_install('cache', cache_dir)
    refuse_real_install('user', user_dir)
    os.makedirs(cache_dir, exist_ok=True)
    os.makedirs(user_dir, exist_ok=True)

    token = secrets.token_hex(2).upper()
    base = r'\\.\pipe\RS2LauncherConnection_%s_' % token
    in_pipe = make_pipe(base + 'i')
    out_pipe = make_pipe(base + 'o')

    stop = threading.Event()
    out_ready = threading.Event()
    threading.Thread(target=lambda: connect(out_pipe) and out_ready.set(), daemon=True).start()
    threading.Thread(target=serve, args=(in_pipe, out_pipe, out_ready, stop, cache_dir, user_dir),
                     daemon=True).start()

    argv = launcher_args(read_params(config), args.host, port) + ['launcher', token]
    cmdline = '"%s" %s' % (client, ' '.join('"%s"' % a for a in argv))
    si = STARTUPINFOW()
    si.cb = ctypes.sizeof(si)
    pi = PROCESS_INFORMATION()
    if not k32.CreateProcessW(client, ctypes.create_unicode_buffer(cmdline), None, None, True, 0x400,
                              None, os.path.dirname(client), ctypes.byref(si), ctypes.byref(pi)):
        raise ctypes.WinError(ctypes.get_last_error())
    print('client started (pid %d), connecting to %s:%d' % (pi.dwProcessId, args.host, port))

    code = w.DWORD(STILL_ACTIVE)
    try:
        while True:
            time.sleep(1)
            k32.GetExitCodeProcess(pi.hProcess, ctypes.byref(code))
            if code.value != STILL_ACTIVE:
                print('client exited (0x%08X)' % (code.value & 0xFFFFFFFF))
                break
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()


if __name__ == '__main__':
    main()
