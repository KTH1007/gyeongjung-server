#!/usr/bin/env python3
"""파일 락을 잡은 채로 명령을 실행한다. 이미 다른 곳이 락을 잡고 있으면 기다리지 않고 11로 끝난다.

사용법: lock-run.py <락 파일> <명령> [인자...]
- 락은 운영체제가 관리하는 파일 락(flock)이라 프로세스가 죽으면 자동으로 풀린다. 낡은 락이 남지 않는다.
- 락을 연 파일 설명자를 명령이 물려받는다. 이 도우미가 죽어도 명령이 돌고 있는 동안은 락이 유지된다(배포가 겹치지 않게).
- 명령의 출력은 도우미가 받아서 전달한다. 받는 쪽(SSH 연결)이 끊겨도 명령이 SIGPIPE로 죽지 않게 한다.
- 락 파일에는 락을 잡은 프로세스의 PID를 적는다.
종료 코드: 11 다른 곳이 락을 잡고 있음, 127 명령을 실행하지 못함, 그 외에는 명령의 종료 코드(신호로 죽으면 128+신호)
"""
import fcntl
import os
import subprocess
import sys


def silence_stdout() -> None:
    # 받는 쪽이 끊긴 뒤에도 종료할 때 stdout을 비우다가 오류(종료 코드 120)가 나지 않게 /dev/null로 돌린다.
    os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())


def forward(child: subprocess.Popen) -> None:
    connected = True
    for chunk in iter(lambda: child.stdout.read1(4096), b""):
        if not connected:
            continue
        try:
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
        except (BrokenPipeError, OSError):
            connected = False
            silence_stdout()


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
        child = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            pass_fds=(fd,),
        )
    except OSError as error:
        print(f"명령을 실행하지 못했다: {error}", file=sys.stderr)
        return 127
    forward(child)
    code = child.wait()
    return 128 - code if code < 0 else code


if __name__ == "__main__":
    sys.exit(main())
