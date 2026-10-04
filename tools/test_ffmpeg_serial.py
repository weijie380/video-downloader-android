"""验证 FFmpeg 锁的跨进程序列化和进程退出后的释放，不访问任何平台。"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
CHILD = r'''
import importlib.util, json, os, sys, time
sys.path.insert(0, sys.argv[1])
from yt_dlp import YoutubeDL
from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor
from yt_dlp.utils import Popen
trace, lock, duration = sys.argv[3], sys.argv[4], float(sys.argv[5])
def fake(self, *args, **kwargs):
    with open(trace, 'a') as f: f.write(json.dumps(['start', os.getpid(), time.monotonic()])+'\n')
    child = "import json,os,sys,time;f=open(sys.argv[1],'a');f.write(json.dumps(['start',int(sys.argv[2]),time.monotonic()])+'\\n');f.flush();time.sleep(float(sys.argv[3]));f.write(json.dumps(['end',int(sys.argv[2]),time.monotonic()])+'\\n');f.close()"
    Popen.run([sys.executable, '-c', child, trace+'.child', str(os.getpid()), str(duration)])
    with open(trace, 'a') as f: f.write(json.dumps(['end', os.getpid(), time.monotonic()])+'\n')
FFmpegPostProcessor.real_run_ffmpeg = fake
spec = importlib.util.spec_from_file_location('serial_ffmpeg', sys.argv[2])
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
ydl = YoutubeDL({'quiet': True, 'no_warnings': True})
module.SerialFFmpegPP(ydl, lock_path=lock)
FFmpegPostProcessor(ydl).real_run_ffmpeg([], [])
'''


def main():
    with tempfile.TemporaryDirectory(prefix='videodl-ffmpeg-') as folder:
        base = Path(folder)
        trace, lock = base / 'trace.jsonl', base / 'ffmpeg.lock'
        def start(duration):
            return subprocess.Popen([sys.executable, '-c', CHILD,
                str(ROOT / 'app/src/main/res/raw/ytdlp'), str(ROOT / 'app/src/main/assets/serial_ffmpeg.py'),
                str(trace), str(lock), str(duration)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def entries():
            return [json.loads(line) for line in trace.read_text().splitlines()] if trace.exists() else []
        def wait_start():
            deadline = time.monotonic() + 5
            while not entries():
                assert time.monotonic() < deadline, '子进程未开始'
                time.sleep(.02)
        a = start(.5); b = start(.5)
        try:
            for p in (a, b):
                _, err = p.communicate(timeout=10)
                assert p.returncode == 0, err.decode()
            records = entries()
            assert [r[0] for r in records] == ['start','end','start','end'], records
            assert records[0][1] == records[1][1] and records[2][1] == records[3][1]
            print('PASS: 两个进程同时请求 FFmpeg，执行区间不重叠')
            trace.unlink()
            child_trace = Path(str(trace)+'.child')
            child_trace.unlink(missing_ok=True)
            a = start(.7); wait_start()
            deadline = time.monotonic()+5
            while not child_trace.exists() or not child_trace.read_text().strip():
                assert time.monotonic()<deadline
                time.sleep(.02)
            a.terminate()
            b = start(.1); _, err = b.communicate(timeout=5)
            a.communicate(timeout=5)
            child_records = [json.loads(line) for line in child_trace.read_text().splitlines()]
            first_child_end = next(r[2] for r in child_records if r[0]=='end' and r[1]==a.pid)
            assert next(r[2] for r in entries() if r[0]=='start' and r[1]==b.pid) >= first_child_end
            assert b.returncode == 0, err.decode()
            assert entries()[-1][0] == 'end'
            print('PASS: 取消持锁进程后，继承锁的子进程结束才开始下一条，没有重叠或遗留锁')
        finally:
            for p in (a, b):
                if p.poll() is None: p.kill(); p.communicate(timeout=5)


if __name__ == '__main__': main()
