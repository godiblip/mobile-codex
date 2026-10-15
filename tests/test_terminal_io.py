import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import unittest

file = Path(__file__).parents[1] / 'app/src/main/assets/rooms/terminal_io.py'
spec = importlib.util.spec_from_file_location('terminal_io', file)
terminal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(terminal)


class TerminalProtocolTests(unittest.TestCase):
    def test_background_query_receives_reply_instead_of_hanging_login(self):
        for ending in ['\x07', '\x1b\\']:
            query = '\x1b]11;?' + ending
            for split in range(len(query) + 1):
                replies = []
                parser = terminal.TerminalProtocol(replies.append)
                text = parser.feed(query[:split]) + parser.feed(query[split:] + 'Login URL')
                self.assertEqual(text, 'Login URL')
                self.assertEqual(replies, [('\x1b]11;rgb:0000/0000/0000' + ending).encode()])

    def test_colors_cursor_and_device_queries_are_answered_without_exposing_controls(self):
        replies = []
        parser = terminal.TerminalProtocol(replies.append)
        self.assertEqual(parser.feed('Before\x1b]10;?;?\x07\x1b[6n\x1b[c\x1b[18tAfter'), 'BeforeAfter')
        self.assertEqual(replies, [b'\x1b]10;rgb:ffff/ffff/ffff\x07', b'\x1b]11;rgb:0000/0000/0000\x07',
                                  b'\x1b[1;1R', b'\x1b[?1;2c', b'\x1b[8;24;80t'])

    def test_chunked_sequences_keep_plain_urls_and_korean_and_ignore_clipboard_requests(self):
        replies = []
        parser = terminal.TerminalProtocol(replies.append)
        chunks = ['\x1b[3', '1m로그인\x1b[0m https://claude.ai/auth\n', '\x1b]52;c;secret', '\x07다음']
        self.assertEqual(''.join(parser.feed(chunk) for chunk in chunks), '로그인 https://claude.ai/auth\n다음')
        self.assertEqual(replies, [])

    def test_incomplete_control_sequence_is_bounded(self):
        parser = terminal.TerminalProtocol(lambda _: None)
        self.assertEqual(parser.feed('\x1b]' + 'a' * 9000), '')
        self.assertEqual(parser.pending, '')
        self.assertEqual(parser.feed('Ready'), 'Ready')

    @unittest.skipUnless(os.name == 'posix', 'Actual PTY and controlling terminal require POSIX')
    def test_real_cli_waits_for_color_response_and_accepts_code_with_raw_enter(self):
        child = r'''
import os,tty,select
tty.setraw(0)
os.write(1,b'\x1b]11;?\x07')
assert select.select([0],[],[],3)[0], 'Missing terminal color reply'
reply=os.read(0,200)
assert reply==b'\x1b]11;rgb:0000/0000/0000\x07',repr(reply)
os.write(1,b'Paste code: ')
value=b''
while not value.endswith(b'\r'):
 assert select.select([0],[],[],3)[0], 'Missing Enter'
 value+=os.read(0,1)
assert value==b'fixture-code\r',repr(value)
os.write(1,b'AUTH_INPUT_ACCEPTED\n')
'''
        process = subprocess.Popen([sys.executable, str(file), sys.executable, '-u', '-c', child],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertEqual(process.stdout.read(len(b'Paste code: ')), b'Paste code: ')
            process.stdin.write(b'fixture-code\n'); process.stdin.flush()
            self.assertEqual(process.wait(timeout=7), 0)
            text = process.stdout.read()
            self.assertIn(b'AUTH_INPUT_ACCEPTED', text)
            self.assertNotIn(b'fixture-code', text)
        finally:
            if process.poll() is None:
                process.kill(); process.wait()
            for pipe in [process.stdin, process.stdout, process.stderr]:
                pipe.close()


if __name__ == '__main__':
    unittest.main()
