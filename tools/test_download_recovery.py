"""仅用本机生成的视频和 HTTP 服务器验证 yt-dlp；不访问平台帖子。
运行：/Users/weijie/miniconda3/envs/default/bin/python tools/test_download_recovery.py
需要本机 ffmpeg / ffprobe。临时素材和服务器随测试清理。
"""
import functools
import http.server
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / 'app/src/main/res/raw/ytdlp'
POLICY = ['--socket-timeout', '45', '--retries', '5', '--fragment-retries', '5',
          '--retry-sleep', 'http:exp=1:8', '--retry-sleep', 'fragment:exp=1:8',
          '--abort-on-unavailable-fragments', '--continue', '--no-overwrites']


def main():
    ffmpeg = shutil.which('ffmpeg')
    ffprobe = shutil.which('ffprobe')
    assert ffmpeg and ffprobe, '需要 ffmpeg 和 ffprobe'
    with tempfile.TemporaryDirectory(prefix='videodl-recovery-') as folder:
        base = Path(folder)
        plugin = base / 'plugins/serial/yt_dlp_plugins/postprocessor/serial_ffmpeg.py'
        plugin.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / 'app/src/main/assets/serial_ffmpeg.py', plugin)
        plugins = ['--plugin-dirs', str(base / 'plugins'), '--use-postprocessor',
                   f'SerialFFmpeg:when=pre_process;lock_path={base / "ffmpeg.lock"}']
        subprocess.run([ffmpeg, '-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25',
                        '-f', 'lavfi', '-i', 'sine=frequency=440', '-t', '4', '-c:v', 'libx264',
                        '-preset', 'ultrafast', '-g', '25', '-c:a', 'aac', '-hls_time', '1',
                        '-hls_list_size', '0', '-hls_segment_filename', str(base / 'seg%03d.ts'),
                        str(base / 'index.m3u8')], check=True)
        counts = {}

        class Handler(http.server.SimpleHTTPRequestHandler):
            def do_GET(self):
                route, _, filename = self.path.strip('/').partition('/')
                key = self.path
                counts[key] = counts.get(key, 0) + 1
                if filename == 'seg000.ts' and (route == 'blocked' or
                                               (route == 'transient' and counts[key] <= 2)):
                    self.send_error(403 if route == 'blocked' else 503)
                    return
                self.path = '/' + filename
                super().do_GET()

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0),
            functools.partial(Handler, directory=str(base)))
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        local = f'http://127.0.0.1:{server.server_port}'
        try:
            def info(route):
                return {'id': 'offline-fixture', 'title': 'offline-fixture', 'extractor': 'generic',
                        'webpage_url': local + '/' + route + '/index.m3u8', 'formats': [
                            {'format_id': 'hls', 'url': local + '/' + route + '/index.m3u8',
                             'protocol': 'm3u8_native', 'ext': 'mp4', 'vcodec': 'h264',
                             'acodec': 'aac', 'height': 180}]}

            def run(metadata, output, extra=()):
                file = base / 'metadata.json'
                file.write_text(json.dumps(metadata))
                return subprocess.run([sys.executable, str(ENGINE), '--load-info-json', str(file),
                    *POLICY, *plugins, '--ffmpeg-location', ffmpeg, '-o', str(output), *extra],
                    capture_output=True, text=True, timeout=90)

            output = base / 'resumed.mp4'
            response = run(info('transient'), output)
            assert response.returncode == 0, response.stderr
            assert counts['/transient/seg000.ts'] == 3, counts
            assert output.stat().st_size > 10000
            duration = float(subprocess.check_output([ffprobe, '-v', 'error', '-show_entries',
                'format=duration', '-of', 'default=nw=1:nk=1', str(output)], text=True))
            assert 3.8 < duration < 4.5, duration
            subprocess.run([ffmpeg, '-v', 'error', '-i', str(output), '-f', 'null', '-'], check=True)
            print(f'PASS: 分片两次 503 后重试成功，完整解码，时长 {duration:.3f}s')

            blocked = base / 'blocked.mp4'
            response = run(info('blocked'), blocked,
                ['--fragment-retries', '1', '--retry-sleep', 'fragment:0'])
            assert response.returncode != 0
            assert '403' in response.stderr, response.stderr
            assert 'The downloaded file is empty' not in response.stderr, response.stderr
            assert not blocked.exists()
            assert not any(key.startswith('/blocked/seg001') for key in counts)
            print('PASS: 持续 403 时立即停止，不跳过所有分片，不发布空成品')

            formats = []
            for name, height, protocol in [('hls720', 720, 'm3u8_native'),
                                           ('http360', 360, 'https'), ('http720', 720, 'https')]:
                formats.append({'format_id': name, 'url': local + '/selector/index.m3u8',
                    'protocol': protocol, 'height': height, 'vcodec': 'h264', 'acodec': 'aac', 'ext': 'mp4'})
            metadata = info('selector'); metadata['formats'] = formats
            selector = 'best[protocol=https][height=720]/best[protocol=http][height=720]/hls720'
            response = run(metadata, base / 'unused.mp4', ['--simulate', '--print', '%(format_id)s', '-f', selector])
            assert response.returncode == 0, response.stderr
            assert response.stdout.strip() == 'http720', response.stdout
            metadata['formats'] = formats[:2]
            response = run(metadata, base / 'unused.mp4', ['--simulate', '--print', '%(format_id)s', '-f', selector])
            assert response.returncode == 0, response.stderr
            assert response.stdout.strip() == 'hls720', response.stdout
            assert not any(key.startswith('/selector/') for key in counts)
            print('PASS: 优先同分辨率 HTTP 格式，没有时回退 HLS，不降低画质')
        finally:
            server.shutdown(); server.server_close(); worker.join(timeout=3)


if __name__ == '__main__':
    main()
