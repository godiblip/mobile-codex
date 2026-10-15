"""Line-oriented PTY transport shared by setup and the app terminal.

The WebView is a transcript, not a terminal emulator. Answer terminal queries
here before removing control sequences from the text shown in the WebView.
"""
import codecs
import json
import os
import re
import selectors
import signal
import subprocess
import sys
import time


class TerminalProtocol:
    def __init__(self, reply):
        self.reply = reply
        self.pending = ''

    def feed(self, text):
        text = self.pending + text
        self.pending = ''
        output = []
        index = 0
        while index < len(text):
            char = text[index]
            if char != '\x1b':
                if char in '\n\r\t' or ord(char) >= 32:
                    output.append(char)
                index += 1
                continue
            start = index
            if index + 1 == len(text):
                self.pending = text[start:]
                break
            kind = text[index + 1]
            if kind in ']P_^':
                end = re.search(r'\x07|\x1b\\', text[index + 2:])
                if end is None:
                    # Bound incomplete/untrusted control strings, including OSC 52.
                    self.pending = text[start:] if len(text) - start <= 8192 else ''
                    break
                payload = text[index + 2:index + 2 + end.start()]
                terminator = end.group()
                if kind == ']':
                    fields = payload.split(';')
                    if fields[0] in ('10', '11', '12'):
                        for offset, value in enumerate(fields[1:]):
                            code = int(fields[0]) + offset
                            if value == '?' and code in (10, 11, 12):
                                color = '0000/0000/0000' if code == 11 else 'ffff/ffff/ffff'
                                self.reply(('\x1b]' + str(code) + ';rgb:' + color + terminator).encode())
                index += 2 + end.end()
            elif kind == '[':
                end = re.search(r'[@-~]', text[index + 2:])
                if end is None:
                    self.pending = text[start:] if len(text) - start <= 256 else ''
                    break
                command = text[index + 2:index + 3 + end.start()]
                replies = {'5n': b'\x1b[0n', '6n': b'\x1b[1;1R', '?6n': b'\x1b[?1;1R',
                           'c': b'\x1b[?1;2c', '0c': b'\x1b[?1;2c', '>c': b'\x1b[>0;0;0c',
                           '>0c': b'\x1b[>0;0;0c', '18t': b'\x1b[8;24;80t'}
                if command in replies:
                    self.reply(replies[command])
                index += 3 + end.start()
            elif kind in '()*+':
                if index + 2 == len(text):
                    self.pending = text[start:]
                    break
                index += 3
            else:
                index += 2
        return ''.join(output)


def run_terminal(argv, env, output, json_input=True, columns=80):
    import fcntl
    import pty
    import struct
    import termios
    master, slave = pty.openpty()
    settings = termios.tcgetattr(slave)
    settings[3] &= ~termios.ECHO
    termios.tcsetattr(slave, termios.TCSANOW, settings)
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, columns, 0, 0))
    env = {**env, 'TERM': 'xterm-256color', 'COLUMNS': str(columns), 'LINES': '24'}
    try:
        process = subprocess.Popen(argv, env=env, stdin=slave, stdout=slave, stderr=slave,
                                   start_new_session=True,
                                   preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0))
    except BaseException:
        os.close(master)
        raise
    finally:
        os.close(slave)
    stopping_at = None

    def stop(*_):
        nonlocal stopping_at
        if stopping_at is None:
            stopping_at = time.monotonic()
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass

    previous = signal.signal(signal.SIGTERM, stop)
    decoder = codecs.getincrementaldecoder('utf-8')('replace')

    def write(data):
        while data:
            count = os.write(master, data)
            data = data[count:]

    protocol = TerminalProtocol(write)
    def write_input(data):
        # Raw CLI key handlers recognize CR as Enter; canonical terminals map
        # CR to LF themselves. Replies to terminal queries must remain unchanged.
        write(data.replace(b'\r\n', b'\n').replace(b'\n', b'\r'))
    pending_input = b''
    output_open = True
    exited_at = None
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(master, selectors.EVENT_READ)
            selector.register(sys.stdin, selectors.EVENT_READ)
            while output_open or process.poll() is None:
                now = time.monotonic()
                if stopping_at is not None and now - stopping_at >= 2:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                if process.poll() is not None:
                    if exited_at is None:
                        exited_at = now
                    elif now - exited_at >= 0.5:
                        break
                for key, _ in selector.select(timeout=0.1):
                    if key.fileobj == master:
                        try:
                            data = os.read(master, 8192)
                        except OSError:
                            data = b''
                        if not data:
                            selector.unregister(master)
                            output_open = False
                            continue
                        text = protocol.feed(decoder.decode(data))
                        if text:
                            output(text)
                    else:
                        data = os.read(sys.stdin.fileno(), 8192)
                        if not data:
                            selector.unregister(sys.stdin)
                            stop()
                            continue
                        if not json_input:
                            write_input(data)
                            continue
                        pending_input += data
                        if len(pending_input) > 1024 * 1024:
                            raise ValueError('입력이 너무 깁니다.')
                        while b'\n' in pending_input:
                            line, pending_input = pending_input.split(b'\n', 1)
                            write_input(str(json.loads(line).get('text', '')).encode('utf-8'))
            return process.wait()
    finally:
        stop()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        os.close(master)
        signal.signal(signal.SIGTERM, previous)


if __name__ == '__main__':
    def show(text):
        sys.stdout.write(text)
        sys.stdout.flush()
    sys.exit(run_terminal(sys.argv[1:], dict(os.environ), show, json_input=False))
