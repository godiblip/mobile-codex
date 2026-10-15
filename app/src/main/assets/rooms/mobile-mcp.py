#!/usr/bin/env python3
"""Private stdio MCP relay. The Android host owns tools, scopes and approvals."""
import json
import sys
import urllib.request
import urllib.error

def relay(config, payload):
    request = urllib.request.Request(
        'http://127.0.0.1:%d/' % int(config['port']), json.dumps(payload).encode(),
        {'Authorization': 'Bearer ' + config['token'], 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=610) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(json.load(error).get('error', 'Tool access denied')) from None

def main():
    with open(sys.argv[1], encoding='utf-8') as handle:
        config = json.load(handle)['mcp']
    for line in sys.stdin:
        if len(line) > 2 * 1024 * 1024:
            break
        request = json.loads(line)
        if 'id' not in request:
            continue
        try:
            method = request.get('method')
            if method == 'initialize':
                result = {'protocolVersion': '2024-11-05', 'capabilities': {'tools': {}},
                          'serverInfo': {'name': 'mobile', 'version': '1.0.0'}}
            elif method == 'ping':
                result = {}
            elif method == 'tools/list':
                result = relay(config, {'action': 'list'})
            elif method == 'tools/call':
                params = request.get('params', {})
                value = relay(config, {'action': 'call', 'name': params.get('name'), 'arguments': params.get('arguments', {})})
                result = {'content': [{'type': 'text', 'text': json.dumps(value, ensure_ascii=False)}]}
            else:
                raise RuntimeError('Unsupported MCP method')
            response = {'jsonrpc': '2.0', 'id': request['id'], 'result': result}
        except Exception as error:
            response = {'jsonrpc': '2.0', 'id': request['id'], 'result': {
                'isError': True, 'content': [{'type': 'text', 'text': str(error)}]}}
        print(json.dumps(response, ensure_ascii=False), flush=True)

if __name__ == '__main__':
    main()
