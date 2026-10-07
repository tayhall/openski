"""PlatformIO pre-build script: defines OPENSKI_BUILD_ID as "<short git hash>[-dirty] <date>".

The firmware prints it at boot and serves it in the Wi-Fi JSON, so the build on a board can be identified.
Falls back to "unknown" if git is unavailable.
"""
import datetime
import subprocess

Import("env")  # noqa: F821  (provided by PlatformIO)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=env["PROJECT_DIR"], stderr=subprocess.DEVNULL).decode().strip()  # noqa: F821


try:
    build_id = git("rev-parse", "--short", "HEAD")
    if git("status", "--porcelain", "--untracked-files=no"):
        build_id += "-dirty"
    build_id += " " + datetime.date.today().isoformat()
except Exception:
    build_id = "unknown"

env.Append(CPPDEFINES=[("OPENSKI_BUILD_ID", env.StringifyMacro(build_id))])  # noqa: F821
print("OpenSki build id:", build_id)
