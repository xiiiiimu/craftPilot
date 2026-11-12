"""Fail on unsafe tracked paths, public PRD metadata or missing/failed test reports."""

import argparse
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCAL_DIRS = {
    "target", "build", "out", "pgdata", "redis-data", "minio-data",
    "appendonlydir", ".minio.sys", ".idea", ".vscode", ".settings",
    ".cache", "__pycache__", ".pytest_cache", ".ci-output", ".testcontainers",
    "test-results", "surefire-reports", "failsafe-reports",
}
LOCAL_FILES = {
    "pg_version", "postmaster.pid", "credentials.json", "credentials.yml", "credentials.yaml",
    "secrets.json", "secrets.yml", "secrets.yaml", "token.txt", ".netrc", ".npmrc",
    ".testcontainers.properties", ".classpath", ".project", "thumbs.db", ".ds_store", "settings.xml",
}
LOCAL_SUFFIXES = {
    ".class", ".log", ".tmp", ".swp", ".pem", ".key", ".pfx", ".p12",
    ".jks", ".keystore", ".crt", ".cer", ".rdb", ".aof", ".iml",
}
SECRET_PATTERNS = (
    re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"),
    re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16})"),
)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def forbidden(path):
    parts = PurePosixPath(path.casefold()).parts
    name = parts[-1]
    return (
        parts[0] == "data"
        or any(part in LOCAL_DIRS for part in parts[:-1])
        or name in LOCAL_FILES
        or ((name == ".env" or name.startswith(".env.")) and name != ".env.example")
        or name.startswith(("id_rsa", "id_ed25519"))
        or PurePosixPath(name).suffix in LOCAL_SUFFIXES
    )


def inspect_office(blob, protected):
    with zipfile.ZipFile(io.BytesIO(blob)) as doc:
        for member in doc.namelist():
            data = doc.read(member)
            if any(value in data for value in protected) or any(p.search(data) for p in SECRET_PATTERNS):
                return False
            if member == "docProps/core.xml":
                root = ET.fromstring(data)
                if any((node.text or "").strip() for node in root
                       if node.tag.rsplit("}", 1)[-1] in {"creator", "lastModifiedBy"}):
                    return False
    return True


def tracked_files():
    paths = git("ls-files", "-z").decode("utf-8").rstrip("\0").split("\0")
    paths = [path for path in paths if path]
    if not paths:
        raise SystemExit("Repository gate requires a populated index.")
    failures = []
    local_values = []
    env = ROOT / ".env"
    if env.is_file():
        for line in env.read_text(encoding="utf-8-sig").splitlines():
            key, _, value = line.partition("=")
            value = value.strip().strip("'\"")
            if len(value) >= 12 and any(marker in key.upper() for marker in ("PASSWORD", "SECRET", "TOKEN")):
                local_values.append(value.encode())
    for path in paths:
        if forbidden(path):
            failures.append(f"local/private path: {path}")
            continue
        if path.endswith(".docx") or local_values:
            data = git("show", f":{path}")
            if any(value in data for value in local_values):
                failures.append(f"matches a local secret: {path}")
            if path.endswith(".docx") and not inspect_office(data, local_values):
                failures.append(f"Office package failed metadata/credential check: {path}")
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"Repository path and Office metadata check passed ({len(paths)} index paths).")


def test_reports():
    reports = sorted((ROOT / "backend").glob("**/target/surefire-reports/TEST-*.xml"))
    reports += sorted((ROOT / "backend").glob("**/target/failsafe-reports/TEST-*.xml"))
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for report in reports:
        suite = ET.parse(report).getroot()
        for field in totals:
            totals[field] += int(suite.get(field, "0"))
    summary = " ".join(f"{field}={value}" for field, value in totals.items())
    print(summary)
    if len(reports) == 0 or totals["tests"] < 7 or any(totals[k] for k in ("failures", "errors", "skipped")):
        raise SystemExit("Build/test gate failed: missing reports, reduced test count, failures, or skipped tests.")


def compose_config():
    services = json.load(sys.stdin)["services"]
    pin = re.compile(r"@sha256:[a-f0-9]{64}$")
    if set(services) != {"postgres", "redis", "minio"}:
        raise SystemExit("Unexpected Compose service inventory.")
    for name, service in services.items():
        if service.get("restart") != "no" or not service.get("healthcheck"):
            raise SystemExit(f"Invalid restart or healthcheck policy: {name}")
        if any(port.get("host_ip") != "127.0.0.1" for port in service.get("ports", [])):
            raise SystemExit(f"Non-loopback published port: {name}")
        if name == "minio" and service.get("build"):
            dockerfile = (ROOT / "infra/minio/Dockerfile").read_text(encoding="utf-8")
            bases = re.findall(r"^FROM\s+(\S+)", dockerfile, re.MULTILINE | re.IGNORECASE)
            if len(bases) < 2 or not all(pin.search(base) for base in bases):
                raise SystemExit("Unpinned MinIO build base.")
            if not any(base.startswith("cgr.dev/chainguard/minio@sha256:") for base in bases):
                raise SystemExit("MinIO base is outside the reviewed publisher.")
        elif not pin.search(service.get("image", "")):
            raise SystemExit(f"Image digest missing: {name}")
    print("Compose image pins, loopback ports and restart policies passed.")


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("mode", choices=("tracked", "tests", "compose"))
args = parser.parse_args()
try:
    {"tracked": tracked_files, "tests": test_reports, "compose": compose_config}[args.mode]()
except (OSError, subprocess.CalledProcessError, zipfile.BadZipFile, ET.ParseError) as error:
    print(f"Gate could not complete ({type(error).__name__}).", file=sys.stderr)
    sys.exit(1)
