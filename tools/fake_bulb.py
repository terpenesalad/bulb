#!/usr/bin/env python3
"""
A simulated Tuya RGB bulb for testing Halo without real hardware.

It speaks the real Tuya LAN protocol (3.3, 3.4 or 3.5) using tinytuya's own
framing helpers, so the app is tested against an independent implementation.

    pip install tinytuya
    python3 tools/fake_bulb.py --version 3.3 --port 6668 --schema v2

Options:
  --schema v2   modern bulbs (DPs 20-26)   --schema v1   older bulbs (DPs 1-7)
  --device22    behave like "device22" firmware (rejects plain DP queries)
  --broadcast   also send UDP discovery broadcasts (port 6667)
"""
import argparse
import hashlib
import hmac
import json
import os
import socket
import struct
import threading
import time

from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.message_helper import (TuyaMessage, pack_message,
                                           parse_header, unpack_message)
from tinytuya.core import header as H
from tinytuya.core import command_types as CT

DEV_ID = "bf0123456789abcdefgh01"  # 22 chars, like many Tuya bulbs
LOCAL_KEY = b"0123456789abcdef"
UDP_KEY = hashlib.md5(b"yGAdlopoPVldABfn").digest()


def initial_dps(schema):
    if schema == "v1":
        return {"1": True, "2": "white", "3": 200, "4": 128,
                "5": "ff00000000ffff", "6": "", "7": 0}
    return {"20": True, "21": "white", "22": 800, "23": 500,
            "24": "000003e803e8", "25": "", "26": 0}


class Session(threading.Thread):
    def __init__(self, conn, addr, args, state):
        super().__init__(daemon=True)
        self.conn, self.addr, self.args, self.state = conn, addr, args, state
        self.version = args.version
        self.key = LOCAL_KEY  # becomes the session key for 3.4/3.5
        self.seq = 100
        self.local_nonce = None
        self.remote_nonce = os.urandom(16)
        self.lock = threading.Lock()

    # ---------- framing ----------
    def send(self, cmd, payload, header=True):
        """Device -> client: always carries a 4 byte return code."""
        ver = self.version
        if ver == "3.3":
            body = AESCipher(self.key).encrypt(payload, False) if payload else b""
            if header and payload:
                body = b"3.3" + H.PROTOCOL_3x_HEADER + body
            msg = TuyaMessage(self.seq, cmd, 0, b"\x00\x00\x00\x00" + body, 0, True, H.PREFIX_55AA_VALUE, False)
            data = pack_message(msg)
        elif ver == "3.4":
            raw = (ver.encode() + H.PROTOCOL_3x_HEADER + payload) if (header and payload) else payload
            body = AESCipher(self.key).encrypt(raw, False) if raw else b""
            msg = TuyaMessage(self.seq, cmd, 0, b"\x00\x00\x00\x00" + body, 0, True, H.PREFIX_55AA_VALUE, False)
            data = pack_message(msg, hmac_key=self.key)
        else:  # 3.5
            raw = (ver.encode() + H.PROTOCOL_3x_HEADER + payload) if (header and payload) else payload
            msg = TuyaMessage(self.seq, cmd, 0, raw, 0, True, H.PREFIX_6699_VALUE, True)
            data = pack_message(msg, hmac_key=self.key)
        self.seq += 1
        with self.lock:
            self.conn.sendall(data)

    def recv_frame(self, buf):
        while True:
            try:
                hdr = parse_header(buf)
                if len(buf) >= hdr.total_length:
                    frame, rest = buf[:hdr.total_length], buf[hdr.total_length:]
                    return frame, hdr, rest
            except Exception:
                pass
            chunk = self.conn.recv(4096)
            if not chunk:
                raise ConnectionError("closed")
            buf += chunk

    def decode(self, frame, hdr):
        """Client -> device: no return code."""
        if self.version == "3.3":
            m = unpack_message(frame, header=hdr, no_retcode=True)
            assert m.crc_good, "bad CRC from client"
            p = m.payload
            if p.startswith(b"3.3"):
                p = p[15:]
            return m.cmd, (AESCipher(self.key).decrypt(p, False, decode_text=False) if p else b"")
        if self.version == "3.4":
            m = unpack_message(frame, header=hdr, hmac_key=self.key, no_retcode=True)
            assert m.crc_good, "bad HMAC from client"
            p = AESCipher(self.key).decrypt(m.payload, False, decode_text=False) if m.payload else b""
        else:
            m = unpack_message(frame, header=hdr, hmac_key=self.key, no_retcode=True)
            assert m.crc_good, "bad GCM tag from client"
            p = m.payload
        if p.startswith(self.version.encode()):
            p = p[15:]
        return m.cmd, p

    # ---------- behaviour ----------
    def status_json(self, cmd_style):
        dps = dict(self.state["dps"])
        if self.version in ("3.4", "3.5") and cmd_style == "push":
            return json.dumps({"protocol": 4, "t": int(time.time()), "data": {"dps": dps}}).encode()
        return json.dumps({"devId": DEV_ID, "dps": dps, "t": int(time.time())}).encode()

    def handle(self, cmd, payload):
        log = self.state["log"]
        if cmd == CT.SESS_KEY_NEG_START:
            self.local_nonce = payload[:16]
            resp = self.remote_nonce + hmac.new(LOCAL_KEY, self.local_nonce, hashlib.sha256).digest()
            self.send(CT.SESS_KEY_NEG_RESP, resp, header=False)
            return
        if cmd == CT.SESS_KEY_NEG_FINISH:
            expect = hmac.new(LOCAL_KEY, self.remote_nonce, hashlib.sha256).digest()
            assert payload[:32] == expect, "client failed session HMAC"
            x = bytes(a ^ b for a, b in zip(self.local_nonce, self.remote_nonce))
            c = AESCipher(LOCAL_KEY)
            if self.version == "3.4":
                self.key = c.encrypt(x, False, pad=False)
            else:
                self.key = c.encrypt(x, use_base64=False, pad=False, iv=self.local_nonce[:12])[12:28]
            log.append("session-ok")
            return
        if cmd == CT.HEART_BEAT:
            self.send(CT.HEART_BEAT, b"", header=False)
            return
        if cmd in (CT.DP_QUERY, CT.DP_QUERY_NEW) or (cmd == CT.CONTROL_NEW and self.version == "3.3"):
            body = json.loads(payload.decode() or "{}")
            if cmd == CT.DP_QUERY and self.args.device22:
                self.send(cmd, b"json obj data unvalid", header=False)
                return
            if cmd == CT.CONTROL_NEW and "dps" in body and all(v is None for v in body["dps"].values()):
                # device22 style status query
                self.send(CT.CONTROL_NEW, self.status_json("query"), header=False)
                return
            if cmd != CT.CONTROL_NEW:
                self.send(cmd, self.status_json("query"), header=False)
                return
        if cmd in (CT.CONTROL, CT.CONTROL_NEW):
            body = json.loads(payload.decode())
            dps = body.get("dps") or body.get("data", {}).get("dps", {})
            self.state["dps"].update(dps)
            log.append({"set": dps})
            self.send(cmd, b"", header=False)  # ack
            push = {k: self.state["dps"][k] for k in dps}
            if self.version in ("3.4", "3.5"):
                p = json.dumps({"protocol": 4, "t": int(time.time()), "data": {"dps": push}}).encode()
            else:
                p = json.dumps({"devId": DEV_ID, "dps": push, "t": int(time.time())}).encode()
            self.send(CT.STATUS, p, header=True)
            return
        log.append({"unknown": cmd})

    def run(self):
        buf = b""
        try:
            while True:
                frame, hdr, buf = self.recv_frame(buf)
                cmd, payload = self.decode(frame, hdr)
                self.handle(cmd, payload)
        except Exception as e:  # noqa
            if not isinstance(e, ConnectionError):
                self.state["log"].append({"error": repr(e)})
        finally:
            self.conn.close()


def broadcast_loop(args, stop):
    info = json.dumps({"ip": args.advertise_ip, "gwId": DEV_ID, "active": 2, "ability": 0,
                       "mode": 0, "encrypt": True, "productKey": "keyfakebulb000",
                       "version": args.version}).encode()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    while not stop.is_set():
        if args.version == "3.5":
            msg = TuyaMessage(0, CT.UDP_NEW, 0, info, 0, True, H.PREFIX_6699_VALUE, True)
            pkt = pack_message(msg, hmac_key=UDP_KEY)
        else:
            body = b"\x00\x00\x00\x00" + AESCipher(UDP_KEY).encrypt(info, False)
            pkt = pack_message(TuyaMessage(0, CT.UDP_NEW, 0, body, 0, True, H.PREFIX_55AA_VALUE, False))
        for dest in args.broadcast_to.split(","):
            try:
                s.sendto(pkt, (dest, 6667))
            except OSError:
                pass
        stop.wait(1.0)


def serve(args, state, ready=None):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(5)
    if ready:
        ready.set()
    while True:
        conn, addr = srv.accept()
        Session(conn, addr, args, state).start()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="3.3", choices=["3.3", "3.4", "3.5"])
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=6668)
    ap.add_argument("--schema", default="v2", choices=["v1", "v2"])
    ap.add_argument("--device22", action="store_true")
    ap.add_argument("--broadcast", action="store_true")
    ap.add_argument("--broadcast-to", default="255.255.255.255")
    ap.add_argument("--advertise-ip", default="127.0.0.1")
    ap.add_argument("--log", default="", help="write a JSON log of commands here on Ctrl-C/exit")
    args = ap.parse_args()
    state = {"dps": initial_dps(args.schema), "log": []}
    stop = threading.Event()
    if args.broadcast:
        threading.Thread(target=broadcast_loop, args=(args, stop), daemon=True).start()
    print(f"fake bulb {DEV_ID} key={LOCAL_KEY.decode()} v{args.version} schema={args.schema} on {args.host}:{args.port}", flush=True)
    t = threading.Thread(target=serve, args=(args, state), daemon=True)
    t.start()
    try:
        while True:
            time.sleep(1)
            if args.log:
                with open(args.log, "w") as f:
                    json.dump(state, f)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
