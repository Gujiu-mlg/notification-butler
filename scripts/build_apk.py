#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""手工构建 APK —— 不需要 Android Studio，不需要 Gradle。

依赖：
    JDK 8+         提供 javac / keytool
    Android SDK    只要 build-tools 和 platforms/android-34/android.jar
                   sdkmanager "build-tools;34.0.0" "platforms;android-34"

SDK 路径查找顺序：
    1. --sdk <路径>
    2. 环境变量 ANDROID_SDK_ROOT / ANDROID_HOME
    3. 项目根目录 local.properties 里的 sdk.dir=...
    4. 常见默认位置

用法：
    python3 scripts/build_apk.py
    python3 scripts/build_apk.py --sdk ~/Android/Sdk
    python3 scripts/build_apk.py --name MyApp --keystore release.jks

产物：build/<name>.apk
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app")
BUILD = os.path.join(ROOT, "build")
MIN_SDK, TARGET_SDK = "26", "34"


def find_sdk(explicit=None):
    c = []
    if explicit:
        c.append(explicit)
    for k in ("ANDROID_SDK_ROOT", "ANDROID_HOME", "ANDROID_SDK"):
        if os.environ.get(k):
            c.append(os.environ[k])
    lp = os.path.join(ROOT, "local.properties")
    if os.path.exists(lp):
        with open(lp, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = re.match(r"\s*sdk\.dir\s*=\s*(.+)", line)
                if m:
                    c.append(m.group(1).strip().replace("\\\\", "\\"))
    h = os.path.expanduser("~")
    c += [os.path.join(h, "Android", "Sdk"),
          os.path.join(h, "Library", "Android", "sdk"),
          os.path.join(h, "AppData", "Local", "Android", "Sdk"),
          r"C:\Android\Sdk", r"D:\Android\Sdk",
          "/usr/lib/android-sdk", "/opt/android-sdk"]
    for p in c:
        if p and os.path.isdir(p):
            return os.path.abspath(p)
    return None


def tool(sdk, name):
    bt = os.path.join(sdk, "build-tools")
    if not os.path.isdir(bt):
        return None
    for v in sorted(os.listdir(bt), reverse=True):
        for ext in (".exe", ".bat", ""):
            p = os.path.join(bt, v, name + ext)
            if os.path.exists(p):
                return p
    return None


def ajar_of(sdk):
    pf = os.path.join(sdk, "platforms")
    if not os.path.isdir(pf):
        return None
    for p in sorted(os.listdir(pf), reverse=True):
        j = os.path.join(pf, p, "android.jar")
        if os.path.exists(j):
            return j
    return None


def run(cmd, label):
    print("\n>>> %s" % label)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=1800, errors="replace")
    except FileNotFoundError:
        print("  x 找不到命令 %s（PATH 里有吗？）" % cmd[0])
        return False
    for line in ((r.stdout or "") + (r.stderr or "")).strip(
    ).splitlines()[:15]:
        print("    %s" % line[:115])
    if r.returncode != 0:
        print("  x 失败（退出码 %d）" % r.returncode)
        return False
    return True


def main():
    ap = argparse.ArgumentParser(description="手工构建 APK（无需 Gradle）")
    ap.add_argument("--sdk")
    ap.add_argument("--name", default="NotifButler")
    ap.add_argument("--keystore", default=None)
    ap.add_argument("--ks-alias", default="androiddebugkey")
    ap.add_argument("--ks-pass", default="pass:android")
    ap.add_argument("--key-pass", default="pass:android")
    a = ap.parse_args()

    print("=" * 74)
    print("构建 %s" % a.name)
    print("=" * 74)

    sdk = find_sdk(a.sdk)
    if not sdk:
        print("""
  x 找不到 Android SDK

  用任一方式指定：
    1) python3 scripts/build_apk.py --sdk "C:\\\\Android\\\\Sdk"
    2) export ANDROID_SDK_ROOT=/path/to/sdk
    3) 在项目根目录建 local.properties，写：
         sdk.dir=C\\\\:\\\\\\\\Android\\\\\\\\Sdk

  只需装 SDK 的这两部分（不必装 Android Studio）：
    sdkmanager "build-tools;34.0.0" "platforms;android-34"
""")
        return 1

    aapt2, d8 = tool(sdk, "aapt2"), tool(sdk, "d8")
    zipalign, apksigner = tool(sdk, "zipalign"), tool(sdk, "apksigner")
    ajar = ajar_of(sdk)

    print("  SDK        : %s" % sdk)
    for nm, v in (("aapt2", aapt2), ("d8", d8), ("zipalign", zipalign),
                  ("apksigner", apksigner), ("android.jar", ajar)):
        print("  %-10s : %s" % (nm, v or "x 缺失"))
    if not all((aapt2, d8, zipalign, apksigner, ajar)):
        print("\n  x SDK 不完整")
        return 1

    keystore = a.keystore or os.path.join(ROOT, "debug.keystore")

    if os.path.exists(BUILD):
        shutil.rmtree(BUILD, ignore_errors=True)
    for s in ("compiled", "gen", "classes", "dex"):
        os.makedirs(os.path.join(BUILD, s), exist_ok=True)

    compiled = os.path.join(BUILD, "compiled.zip")
    if not run([aapt2, "compile", "--dir", os.path.join(APP, "res"),
                "-o", compiled], "1/9 aapt2 compile（编译资源）"):
        return 1

    unaligned = os.path.join(BUILD, "unaligned.apk")
    gen = os.path.join(BUILD, "gen")
    if not run([aapt2, "link", "-o", unaligned, "-I", ajar,
                "--manifest", os.path.join(APP, "AndroidManifest.xml"),
                "-R", compiled, "--java", gen,
                "--min-sdk-version", MIN_SDK,
                "--target-sdk-version", TARGET_SDK,
                "--version-code", "1", "--version-name", "1.0",
                "--auto-add-overlay"],
               "2/9 aapt2 link（生成骨架 + R.java）"):
        return 1

    srcs = glob.glob(os.path.join(APP, "src", "**", "*.java"), recursive=True)
    rjava = glob.glob(os.path.join(gen, "**", "R.java"), recursive=True)
    classes = os.path.join(BUILD, "classes")
    if not run(["javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
                "-bootclasspath", ajar, "-classpath", ajar,
                "-d", classes] + srcs + rjava,
               "3/9 javac（%d 个 Java 文件）" % len(srcs)):
        return 1

    cfs = glob.glob(os.path.join(classes, "**", "*.class"), recursive=True)
    dexdir = os.path.join(BUILD, "dex")
    if not run([d8, "--output", dexdir, "--lib", ajar,
                "--min-api", MIN_SDK, "--release"] + cfs,
               "4/9 d8（%d 个 class → dex）" % len(cfs)):
        return 1

    dexf = os.path.join(dexdir, "classes.dex")
    if not os.path.exists(dexf):
        print("  x 没有生成 classes.dex")
        return 1

    withdex = os.path.join(BUILD, "with-dex.apk")
    shutil.copy(unaligned, withdex)
    with zipfile.ZipFile(withdex, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dexf, "classes.dex")
    print("\n>>> 5/9 把 classes.dex 打进 APK")

    if not os.path.exists(keystore):
        print("\n>>> 6/9 生成调试签名密钥（仅本地测试用）")
        try:
            subprocess.run(["keytool", "-genkeypair", "-v",
                            "-keystore", keystore, "-alias", a.ks_alias,
                            "-keyalg", "RSA", "-keysize", "2048",
                            "-validity", "10000", "-storepass", "android",
                            "-keypass", "android",
                            "-dname", "CN=Android Debug,O=Android,C=US"],
                           capture_output=True, timeout=180, check=True)
        except Exception as e:
            print("  x keytool 失败: %s" % e)
            return 1
    else:
        print("\n>>> 6/9 签名密钥已存在")

    aligned = os.path.join(BUILD, "aligned.apk")
    if not run([zipalign, "-f", "-p", "4", withdex, aligned],
               "7/9 zipalign（4 字节对齐）"):
        return 1

    out = os.path.join(BUILD, "%s.apk" % a.name)
    if not run([apksigner, "sign", "--ks", keystore,
                "--ks-key-alias", a.ks_alias, "--ks-pass", a.ks_pass,
                "--key-pass", a.key_pass, "--out", out, aligned],
               "8/9 apksigner（签名）"):
        return 1

    run([apksigner, "verify", "--print-certs", out], "9/9 验证签名")

    print("\n" + "=" * 74)
    print("构建完成")
    print("=" * 74)
    print("  %s  (%.1f KB)" % (out, os.path.getsize(out) / 1024.0))
    print("\n  安装： adb install -r \"%s\"" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
