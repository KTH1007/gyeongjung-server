#!/usr/bin/env python3
"""시험용 가짜 디스코드 웹훅 수신기. 받은 요청의 경로와 본문을 한 줄씩 파일에 남긴다."""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG_PATH = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 18099


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8")
        with open(LOG_PATH, "a", encoding="utf-8") as log:
            log.write(json.dumps({"path": self.path, "body": body}, ensure_ascii=False) + "\n")
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


# 컨테이너가 호스트 게이트웨이로 접근하므로 모든 인터페이스에서 받는다. 시험이 도는 동안에만 뜬다.
HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
