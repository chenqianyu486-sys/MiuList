# -*- coding: utf-8 -*-
"""Windows DPAPI：把电脑端的本地快照**加密到当前 Windows 用户**名下。

## 为什么需要它

镜像文件里含配对 secret —— 那把钥匙一旦明文落在磁盘上，
拿到文件的人就能解开之后所有与手机之间的通信（密钥是从 secret 派生的）。
DPAPI 用当前 Windows 用户的登录凭据做密钥，于是：

- 别的 Windows 用户读不开；
- 文件拷到别的电脑上也没用（密钥跟着用户的凭据走）；
- 文件被拷走但没有你的 Windows 账号，等于一把废钥匙。

App 那边的设备密钥是**加密落盘**在 Keystore 里的（见
`SecureAccountStorage`）—— 这里是同一套思路的桌面版。

## 不是 Windows 时怎么办

`available()` 返回 False，调用方退回明文文件并**明确告诉用户**这件事
（而不是悄悄降级）。这个启动器本来就只面向 Windows，
但代码里留一条明路，是为了能在别的系统上跑测试。
"""

import ctypes
from ctypes import wintypes

_CRYPT32 = None
_KERNEL32 = None

if hasattr(ctypes, "WinDLL"):
    try:
        _CRYPT32 = ctypes.WinDLL("crypt32.dll", use_last_error=True)
        _KERNEL32 = ctypes.WinDLL("kernel32.dll", use_last_error=True)
    except OSError:  # pragma: no cover - 非 Windows 或 DLL 缺失
        _CRYPT32 = None
        _KERNEL32 = None

CRYPTPROTECT_UI_FORBIDDEN = 0x01


class _DataBlob(ctypes.Structure):
    _fields_ = [
        ("cbData", wintypes.DWORD),
        ("pbData", ctypes.POINTER(ctypes.c_ubyte)),
    ]


def available() -> bool:
    """这个系统上能不能用 DPAPI。"""
    return _CRYPT32 is not None and _KERNEL32 is not None


def _blob(data: bytes):
    buf = ctypes.create_string_buffer(data, len(data))
    blob = _DataBlob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_ubyte)))
    return blob, buf  # buf 必须活着 —— blob 指向它


def protect(data: bytes) -> bytes:
    """加密。失败抛 OSError。"""
    if not available():
        raise OSError("这个系统上没有 DPAPI")
    if not data:
        return b""
    in_blob, _in_buf = _blob(data)
    out_blob = _DataBlob()
    ok = _CRYPT32.CryptProtectData(
        ctypes.byref(in_blob),
        None,   # 描述（弹窗标题），不给就不弹窗
        None,   # 可选熵
        None,   # 附加数据
        None,   # 密钥容器
        CRYPTPROTECT_UI_FORBIDDEN,
        ctypes.byref(out_blob),
    )
    if not ok:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(out_blob.pbData, out_blob.cbData)
    finally:
        _KERNEL32.LocalFree(ctypes.cast(out_blob.pbData, ctypes.c_void_p))


def unprotect(data: bytes) -> bytes:
    """解密。失败抛 OSError。"""
    if not available():
        raise OSError("这个系统上没有 DPAPI")
    if not data:
        return b""
    in_blob, _in_buf = _blob(data)
    out_blob = _DataBlob()
    ok = _CRYPT32.CryptUnprotectData(
        ctypes.byref(in_blob),
        None,   # 解出来的描述
        None,
        None,
        None,
        CRYPTPROTECT_UI_FORBIDDEN,
        ctypes.byref(out_blob),
    )
    if not ok:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(out_blob.pbData, out_blob.cbData)
    finally:
        _KERNEL32.LocalFree(ctypes.cast(out_blob.pbData, ctypes.c_void_p))
