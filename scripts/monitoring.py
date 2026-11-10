#!/usr/bin/env python3
"""Manage project-local Prometheus/Grafana without Docker or system-wide services."""

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "data" / "monitoring"
SERVICES = {"prometheus": (9091, "/-/ready"), "grafana": (3000, "/api/health")}


def validate_target(target):
    if not re.fullmatch(r"(?:[A-Za-z0-9_.-]+|\[[0-9a-fA-F:]+\]):[0-9]+", target):
        raise ValueError("应用地址必须是 host:port，例如 127.0.0.1:8080")
    if not 1 <= int(target.rsplit(":", 1)[1]) <= 65535:
        raise ValueError("应用端口必须在 1 到 65535 之间")
    return target


def prometheus_config(target, rules):
    return f"""global:
  scrape_interval: 10s
  evaluation_interval: 10s
rule_files:
  - {json.dumps(str(rules))}
scrape_configs:
  - job_name: fluxagent
    metrics_path: /metrics
    static_configs:
      - targets: [{json.dumps(validate_target(target))}]
        labels:
          service: fluxagent
  - job_name: prometheus
    static_configs:
      - targets: ["127.0.0.1:9091"]
"""


def process_identity(pid):
    result = subprocess.run(["ps", "-p", str(pid), "-o", "stat=", "-o", "lstart=", "-o", "command="],
                            capture_output=True, text=True, timeout=3)
    value = result.stdout.strip()
    # Process state (running/sleeping) changes frequently and is not part of its identity.
    return value.split(None, 1)[1] if result.returncode == 0 and value and not value.startswith("Z") else None


def read_state(path):
    if not path.exists():
        return None
    try:
        value = json.loads(path.read_text())
        if type(value["pid"]) is not int or value["pid"] <= 1 or not isinstance(value["identity"], str) or not value["identity"]:
            raise ValueError("invalid process identity")
        return value
    except (ValueError, KeyError, TypeError) as error:
        raise RuntimeError(f"状态文件无效：{path}；请检查文件后重试") from error


def is_managed(path):
    state = read_state(path)
    if state is None:
        return False
    identity = process_identity(state["pid"])
    if identity is None:
        return False
    if identity != state["identity"]:
        raise RuntimeError(f"进程身份与状态文件不符：{path}；不会操作该进程")
    return True


def require_free_port(port):
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", port))
        except OSError as error:
            raise RuntimeError(f"端口 {port} 已被占用；请检查已有服务，本脚本不会终止无关进程") from error


def stop_service(name, state_path):
    state = read_state(state_path)
    if state is None:
        return
    if is_managed(state_path):
        os.kill(state["pid"], signal.SIGTERM)
        deadline = time.monotonic() + 10
        while process_identity(state["pid"]) is not None:
            if time.monotonic() >= deadline:
                raise RuntimeError(f"{name} 未在 10 秒内退出；保留状态文件，请查看日志")
            time.sleep(0.1)
    state_path.unlink(missing_ok=True)
    print(f"{name} 已停止")


def fetch(url):
    # Local health checks should not go through a user's HTTP proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=3) as response:
        return response.read()


def binary(name):
    found = shutil.which(name)
    if found:
        return Path(found).resolve()
    bundled = RUNTIME / "vendor" / name / "bin" / name
    if bundled.is_file() and os.access(bundled, os.X_OK):
        return bundled.resolve()
    raise RuntimeError(f"未安装 {name}。macOS 可运行 brew install prometheus grafana；其他系统请安装官方二进制并加入 PATH")


def grafana_home(executable):
    override = os.environ.get("GRAFANA_HOME")
    candidates = ([Path(override)] if override else []) + [
        executable.parent.parent / "share" / "grafana",
        executable.parent.parent,
        Path("/usr/share/grafana"),
    ]
    for candidate in candidates:
        if (candidate / "conf" / "defaults.ini").is_file():
            return candidate
    raise RuntimeError("未找到 Grafana 静态资源目录；请设置 GRAFANA_HOME（包含 conf/defaults.ini）")


def write_configs(target):
    config = RUNTIME / "prometheus.yml"
    desired = prometheus_config(target, ROOT / "config" / "prometheus-alerts.yml")
    if is_managed(RUNTIME / "prometheus.json") and config.read_text() != desired:
        raise RuntimeError("应用采集地址已变化，请先 stop 再 start")
    config.write_text(desired)
    password_file = RUNTIME / "grafana-admin-password"
    if not password_file.exists():
        fd = os.open(password_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "w") as output:
            output.write(secrets.token_urlsafe(32))
    (RUNTIME / "grafana.ini").write_text(f"""[paths]
data = {RUNTIME / 'grafana'}
logs = {RUNTIME / 'grafana-logs'}
plugins = {RUNTIME / 'grafana-plugins'}
provisioning = {ROOT / 'config/grafana/provisioning'}
[server]
http_addr = 127.0.0.1
http_port = 3000
[security]
admin_user = admin
admin_password = $__file{{{password_file}}}
[auth.anonymous]
enabled = true
org_role = Viewer
[users]
allow_sign_up = false
[analytics]
reporting_enabled = false
check_for_updates = false
check_for_plugin_updates = false
[plugins]
preinstall_disabled = true
""")
    return config


def start_service(name, command, environment):
    state_path = RUNTIME / f"{name}.json"
    if is_managed(state_path):
        print(f"{name} 已运行")
        return False
    port, health = SERVICES[name]
    require_free_port(port)
    log_path = RUNTIME / f"{name}.log"
    with log_path.open("ab") as log:
        process = subprocess.Popen(command, cwd=ROOT, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.monotonic() + 45
        while True:
            if process.poll() is not None:
                raise RuntimeError(f"{name} 启动退出，日志：{log_path}")
            try:
                fetch(f"http://127.0.0.1:{port}{health}")
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"{name} 启动超时，日志：{log_path}")
                time.sleep(0.3)
        identity = process_identity(process.pid)
        if identity is None:
            raise RuntimeError(f"{name} 就绪后进程已退出，日志：{log_path}")
        state_path.write_text(json.dumps({"pid": process.pid, "identity": identity}))
        print(f"{name} 已启动，PID {process.pid}")
        return True
    except BaseException:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
        raise


def start(target):
    prometheus = binary("prometheus")
    grafana = binary("grafana")
    home = grafana_home(grafana)
    for name, (port, _) in SERVICES.items():
        if not is_managed(RUNTIME / f"{name}.json"):
            require_free_port(port)
    config = write_configs(target)
    environment = os.environ.copy()
    environment.update(PROMETHEUS_URL="http://127.0.0.1:9091",
                       FLUXAGENT_DASHBOARD_PATH=str(ROOT / "config/grafana/dashboards"))
    commands = {
        "prometheus": [str(prometheus), f"--config.file={config}", "--web.listen-address=127.0.0.1:9091",
                       f"--storage.tsdb.path={RUNTIME / 'prometheus'}", "--storage.tsdb.retention.time=15d"],
        "grafana": [str(grafana), "server", f"--homepath={home}", f"--config={RUNTIME / 'grafana.ini'}"],
    }
    started = []
    try:
        for name, command in commands.items():
            if start_service(name, command, environment):
                started.append(name)
    except BaseException:
        for name in reversed(started):
            stop_service(name, RUNTIME / f"{name}.json")
        raise
    print("Prometheus: http://localhost:9091/graph")
    print("Grafana:    http://localhost:3000/d/fluxagent-overview/fluxagent")
    print("Grafana 本地允许只读查看；管理员密码保存在 data/monitoring/grafana-admin-password")


def status(check=False):
    healthy = True
    for name, (port, health) in SERVICES.items():
        try:
            managed = is_managed(RUNTIME / f"{name}.json")
            fetch(f"http://127.0.0.1:{port}{health}")
            print(f"{name}: 页面健康，{'由当前项目管理' if managed else '外部进程'}")
        except (OSError, RuntimeError) as error:
            healthy = False
            print(f"{name}: 不可用 ({error})")
    if check:
        try:
            targets = json.loads(fetch("http://127.0.0.1:9091/api/v1/targets"))["data"]["activeTargets"]
            apps = [item for item in targets if item["labels"].get("service") == "fluxagent"]
            if not apps or any(item["health"] != "up" for item in apps):
                raise RuntimeError("应用采集尚未成功；确认 Java 已启动、采集地址正确，并等待一个 10 秒采集周期")
            print("FluxAgent 指标采集: up")
            dashboards = json.loads(fetch("http://127.0.0.1:3000/api/search?query=FluxAgent"))
            if not any(item.get("uid") == "fluxagent-overview" for item in dashboards):
                raise RuntimeError("Grafana 看板未加载，请检查 provisioning 日志")
            print("FluxAgent Grafana 看板: 已加载")
            query = json.loads(fetch("http://127.0.0.1:3000/api/datasources/proxy/uid/fluxagent-prometheus/"
                                     "api/v1/query?query=up%7Bservice%3D%22fluxagent%22%7D"))
            if query.get("status") != "success" or not any(
                    item["value"][1] == "1" for item in query["data"]["result"]):
                raise RuntimeError("Grafana 数据源无法查询到健康的 FluxAgent 实例")
            print("Grafana → Prometheus → FluxAgent: 查询成功")
        except (OSError, ValueError, KeyError, RuntimeError) as error:
            healthy = False
            print(f"链路检查失败: {error}")
    return 0 if healthy else 1


def main():
    def interrupted(signum, _frame):
        # Raise through start_service/start so newly launched children are reaped.
        raise SystemExit(128 + signum)

    for signum in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop", "status", "check"])
    parser.add_argument("--app-target", default="127.0.0.1:8080", type=validate_target)
    args = parser.parse_args()
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if args.action in ("status", "check"):
        return status(args.action == "check")
    with (RUNTIME / "manager.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("另一个监控管理操作正在执行，请稍后重试")
        if args.action == "start":
            start(args.app_target)
        else:
            for name in reversed(SERVICES):
                stop_service(name, RUNTIME / f"{name}.json")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError) as error:
        print(f"错误：{error}", file=sys.stderr)
        sys.exit(1)
