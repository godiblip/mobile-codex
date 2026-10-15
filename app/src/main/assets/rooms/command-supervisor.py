#!/usr/bin/env python3
"""Bound the lifetime of an explicitly approved Android shell command and its jobs."""
import os
import signal
import subprocess
import sys

def main():
    command = sys.stdin.buffer.readline(32001).decode('utf-8')
    import json
    command = json.loads(command)['command']
    child = subprocess.Popen(['/system/bin/sh', '-c', command], start_new_session=True, stdin=subprocess.DEVNULL)
    def stop(*_):
        try:
            os.killpg(child.pid, signal.SIGTERM)
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        return child.wait(timeout=60)
    except subprocess.TimeoutExpired:
        return 124
    finally:
        stop()
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        child.wait()

if __name__ == '__main__':
    sys.exit(main())
