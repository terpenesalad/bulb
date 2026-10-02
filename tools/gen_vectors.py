#!/usr/bin/env python3
"""Generates protocol test vectors from tinytuya (an independent implementation)."""
import hashlib, hmac, json
from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.message_helper import TuyaMessage, pack_message
from tinytuya.core import header as H
from tinytuya.core import command_types as CT
from tinytuya import BulbDevice

KEY = b"0123456789abcdef"
SESSION = b"fedcba9876543210"
IV = bytes(range(12))
payload = b'{"protocol":5,"t":1700000000,"data":{"dps":{"20":true}}}'
p33 = b'{"devId":"abc","uid":"abc","t":"1700000000","dps":{"20":true}}'

def client(ver, seq, cmd, pl, key):
    if ver == "3.3":
        body = AESCipher(key).encrypt(pl, False)
        if cmd not in H.NO_PROTOCOL_HEADER_CMDS:
            body = b"3.3" + H.PROTOCOL_3x_HEADER + body
        return pack_message(TuyaMessage(seq, cmd, 0, body, 0, True, H.PREFIX_55AA_VALUE, False))
    raw = (ver.encode() + H.PROTOCOL_3x_HEADER + pl) if cmd not in H.NO_PROTOCOL_HEADER_CMDS else pl
    if ver == "3.4":
        return pack_message(TuyaMessage(seq, cmd, 0, AESCipher(key).encrypt(raw, False), 0, True, H.PREFIX_55AA_VALUE, False), hmac_key=key)
    return pack_message(TuyaMessage(seq, cmd, None, raw, 0, True, H.PREFIX_6699_VALUE, IV), hmac_key=key)

def device(ver, seq, cmd, pl, key, header):
    if ver == "3.3":
        body = AESCipher(key).encrypt(pl, False)
        if header: body = b"3.3" + H.PROTOCOL_3x_HEADER + body
        return pack_message(TuyaMessage(seq, cmd, 0, b"\0\0\0\0" + body, 0, True, H.PREFIX_55AA_VALUE, False))
    raw = (ver.encode() + H.PROTOCOL_3x_HEADER + pl) if header else pl
    if ver == "3.4":
        return pack_message(TuyaMessage(seq, cmd, 0, b"\0\0\0\0" + AESCipher(key).encrypt(raw, False), 0, True, H.PREFIX_55AA_VALUE, False), hmac_key=key)
    return pack_message(TuyaMessage(seq, cmd, 0, raw, 0, True, H.PREFIX_6699_VALUE, IV), hmac_key=key)

status = b'{"devId":"abc","dps":{"20":true,"22":500},"t":1700000000}'
out = {
  "c33": client("3.3", 5, CT.CONTROL, p33, KEY).hex(),
  "q33": client("3.3", 6, CT.DP_QUERY, b'{"gwId":"abc"}', KEY).hex(),
  "c34": client("3.4", 7, CT.CONTROL_NEW, payload, SESSION).hex(),
  "q34": client("3.4", 8, CT.DP_QUERY_NEW, b'{}', SESSION).hex(),
  "c35": client("3.5", 9, CT.CONTROL_NEW, payload, SESSION).hex(),
  "d33q": device("3.3", 1, CT.DP_QUERY, status, KEY, False).hex(),
  "d33s": device("3.3", 2, CT.STATUS, status, KEY, True).hex(),
  "d34s": device("3.4", 3, CT.STATUS, status, SESSION, True).hex(),
  "d35s": device("3.5", 4, CT.STATUS, status, SESSION, True).hex(),
  "d35q": device("3.5", 5, CT.DP_QUERY_NEW, status, SESSION, False).hex(),
  "v1col": BulbDevice.rgb_to_hexvalue(255, 0, 0, "rgb8"),
  "v2col": BulbDevice.rgb_to_hexvalue(0, 0, 255, "hsv16"),
}
udp_info = json.dumps({"ip":"192.168.1.50","gwId":"abc","active":2,"version":"3.3","productKey":"pk"}).encode()
udpkey = hashlib.md5(b"yGAdlopoPVldABfn").digest()
out["udp33"] = pack_message(TuyaMessage(0, CT.UDP_NEW, 0, b"\0\0\0\0" + AESCipher(udpkey).encrypt(udp_info, False), 0, True, H.PREFIX_55AA_VALUE, False)).hex()
out["udp35"] = pack_message(TuyaMessage(0, CT.UDP_NEW, None, udp_info.replace(b'3.3', b'3.5'), 0, True, H.PREFIX_6699_VALUE, IV), hmac_key=udpkey).hex()
# cloud signature, written out from the published algorithm
cid, sec, t = "abcd1234", "secretsecret", "1700000000000"
s2s = "GET\n" + hashlib.sha256(b"").hexdigest() + "\n\n" + "/v1.0/token?grant_type=1"
out["sign"] = hmac.new(sec.encode(), (cid + t + s2s).encode(), hashlib.sha256).hexdigest().upper()
for k, v in out.items():
    print(f'    const val {k} = "{v}"')
