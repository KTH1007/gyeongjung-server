#!/usr/bin/env python3
"""파일 락을 잡은 채로 명령을 실행한다. 이미 다른 곳이 락을 잡고 있으면 기다리지 않고 11로 끝난다.

사용법: lock-run.py <락 파일> <명령> [인자...]
- 락은 운영체제가 관리하는 파일 락(flock)이라 프로세스가 죽으면 자동으로 풀린다. 낡은 락이 남지 않는다.
- 락을 연 파일 설명자를 명령이 물려받는다. 이 도우미가 죽어도 명령이 돌고 있는 동안은 락이 유지된다(배포가 겹치지 않게).
- 명령의 출력은 파이프가 아니라 지운 임시 파일로 받고 도우미가 읽어서 전달한다. 파이프로 받으면 받는 쪽이 사라질 때
  (SSH 연결이 끊기거나 이 도우미가 죽을 때) 명령이 다음 출력에서 SIGPIPE로 죽는다. 배포가 롤백 없이 중간에 끊기면 서버가
  절반만 바뀐 채 남으므로, 출력을 받는 쪽이 없어져도 명령은 끝까지 간다.
- 락 파일에는 락을 잡은 프로세스의 PID를 적는다.
종료 코드: 11 다른 곳이 락을 잡고 있음, 127 명령을 실행하지 못함, 그 외에는 명령의 종료 코드(신호로 죽으면 128+신호)
"""
import fcntl
import os
import subprocess
import sys
import tempfile
import time

POLL_SECONDS = 0.05


def silence_stdout() -> None:
    # 받는 쪽이 끊긴 뒤에도 종료할 때 stdout을 비우다가 오류(종료 코드 120)가 나지 않게 /dev/null로 돌린다.
    os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())


def open_output(lock_path: str):
    """명령이 쓸 임시 파일(쓰기)과 이쪽에서 읽을 설명자(읽기)를 만든다. 파일은 바로 지워서 어디에도 남지 않는다."""
    directory = os.path.dirname(os.path.abspath(lock_path))
    fd, path = tempfile.mkstemp(prefix=".deploy-output-", dir=directory)
    writer = os.fdopen(fd, "wb")
    reader = open(path, "rb")
    os.unlink(path)
    return writer, reader


def forward(child: subprocess.Popen, reader) -> None:
    connected = True
    exited = False
    while True:
        chunk = reader.read(65536)
        if chunk:
            if connected:
                try:
                    sys.stdout.buffer.write(chunk)
                    sys.stdout.buffer.flush()
                except (BrokenPipeError, OSError):
                    connected = False
                    silence_stdout()
            continue
        if exited:
            return
        # 종료를 확인한 뒤에 한 번 더 읽는다. 마지막으로 읽은 뒤에서 종료 사이에 쓴 것을 놓치지 않게.
        exited = child.poll() is not None
        if not exited:
            time.sleep(POLL_SECONDS)


def main() -> int:
    lock_path, command = sys.argv[1], sys.argv[2:]
    fd = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("다른 배포가 진행 중이다. 끝난 뒤에 다시 시도한다", file=sys.stderr)
        return 11
    os.ftruncate(fd, 0)
    os.write(fd, f"{os.getpid()}\n".encode())
    try:
        writer, reader = open_output(lock_path)
        with writer:
            child = subprocess.Popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=writer,
                stderr=subprocess.STDOUT,
                pass_fds=(fd,),
            )
    except OSError as error:
        print(f"명령을 실행하지 못했다: {error}", file=sys.stderr)
        return 127
    forward(child, reader)
    code = child.wait()
    return 128 - code if code < 0 else code


if __name__ == "__main__":
    sys.exit(main())
