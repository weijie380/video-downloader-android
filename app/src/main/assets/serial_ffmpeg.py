"""Run expensive FFmpeg postprocessors one at a time across download processes."""
import fcntl
from functools import wraps

from yt_dlp.postprocessor.common import PostProcessor
from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor
from yt_dlp.utils import Popen


class SerialFFmpegPP(PostProcessor):
    def __init__(self, downloader=None, lock_path=None):
        super().__init__(downloader)
        if not lock_path:
            raise ValueError('Missing FFmpeg lock path')
        original = FFmpegPostProcessor.real_run_ffmpeg

        @wraps(original)
        def serialized(processor, *args, **kwargs):
            # Kernel lock disappears when the owning yt-dlp process exits or is killed.
            with open(lock_path, 'a') as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    processor._downloader.to_screen('[Merger] 等待音视频合并…')
                    fcntl.flock(lock, fcntl.LOCK_EX)
                # 子进程继承锁：取消 yt-dlp 时，仍在退出的 FFmpeg 不会与下一条重叠。
                original_run = Popen.run
                descriptor = vars(Popen)['run']

                def inherited_run(*args, **kwargs):
                    kwargs['pass_fds'] = tuple(set(kwargs.get('pass_fds', ())) | {lock.fileno()})
                    return original_run(*args, **kwargs)

                Popen.run = staticmethod(inherited_run)
                try:
                    return original(processor, *args, **kwargs)
                finally:
                    Popen.run = descriptor
                    fcntl.flock(lock, fcntl.LOCK_UN)

        FFmpegPostProcessor.real_run_ffmpeg = serialized

    def run(self, info):
        return [], info
