import importlib.util
import json
import os
from pathlib import Path
import socket
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("monitoring", Path(__file__).parents[1] / "monitoring.py")
monitoring = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(monitoring)


class MonitoringTest(unittest.TestCase):

    def test_check_verifies_queries_through_grafana_datasource(self):
        for connected in (True, False):
            def response(url):
                if "/api/v1/targets" in url:
                    payload = {"data": {"activeTargets": [{"labels": {"service": "fluxagent"}, "health": "up"}]}}
                elif "/api/search" in url:
                    payload = [{"uid": "fluxagent-overview"}]
                elif "/api/datasources/proxy/" in url:
                    payload = {"status": "success", "data": {"result": [{"value": [0, "1"]}] if connected else []}}
                else:
                    payload = {}
                return json.dumps(payload).encode()
            with self.subTest(connected=connected), patch.object(monitoring, "is_managed", return_value=True), patch.object(monitoring, "fetch", side_effect=response):
                self.assertEqual(monitoring.status(check=True), 0 if connected else 1)

    def test_port_can_be_reused_after_server_actively_closes_connection(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            with socket.create_connection(("127.0.0.1", port)) as client:
                accepted, _ = listener.accept()
                accepted.close()
                self.assertEqual(client.recv(1), b"")
        monitoring.require_free_port(port)

    def test_termination_during_startup_reaps_child(self):
        # The manager receives a real SIGTERM in an isolated subprocess.
        script = """
import os, signal, sys, time
from pathlib import Path
from unittest.mock import patch
import monitoring
monitoring.RUNTIME = Path(sys.argv[1])
original_popen = monitoring.subprocess.Popen
def tracked(*args, **kwargs):
    child = original_popen(*args, **kwargs)
    (monitoring.RUNTIME / "child.pid").write_text(str(child.pid))
    return child
def start(_):
    monitoring.start_service("prometheus", [sys.executable, "-c", "import time; time.sleep(30)"], os.environ.copy())
def interrupted_fetch(_):
    os.kill(os.getpid(), signal.SIGTERM)
    time.sleep(1)
sys.argv = ["monitoring.py", "start"]
with patch.object(monitoring, "start", side_effect=start), patch.object(monitoring, "require_free_port"), patch.object(monitoring, "fetch", side_effect=interrupted_fetch), patch.object(monitoring.subprocess, "Popen", side_effect=tracked):
    monitoring.main()
"""
        with tempfile.TemporaryDirectory() as directory:
            environment = os.environ.copy()
            environment["PYTHONPATH"] = str(Path(__file__).parents[1])
            manager = subprocess.run([sys.executable, "-c", script, directory], env=environment, timeout=10,
                                     capture_output=True, text=True)
            child = int((Path(directory) / "child.pid").read_text())
            try:
                self.assertEqual(manager.returncode, 128 + signal.SIGTERM, manager.stderr)
                self.assertIsNone(monitoring.process_identity(child), "startup child survived SIGTERM")
            finally:
                if monitoring.process_identity(child) is not None:
                    os.kill(child, signal.SIGTERM)

    def test_target_validation_rejects_urls_and_configuration_injection(self):
        for target in ["127.0.0.1:8080", "app.internal:8080", "[::1]:8080"]:
            self.assertEqual(monitoring.validate_target(target), target)
        for target in ["http://localhost:8080", "localhost:0", "localhost:65536", "localhost:8080\njobs:", "user@localhost:8080", "localhost:8080/path"]:
            with self.subTest(target=target), self.assertRaises(ValueError):
                monitoring.validate_target(target)

    def test_local_config_scrapes_host_application_and_has_alert_rules(self):
        config = monitoring.prometheus_config("127.0.0.1:8080", Path("/project/flux agent/config/prometheus-alerts.yml"))
        self.assertIn('"127.0.0.1:8080"', config)
        self.assertIn("/metrics", config)
        self.assertIn("prometheus-alerts.yml", config)
        self.assertNotIn("echomind-java:8080", config)

    def test_occupied_port_is_reported_without_terminating_listener(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            with self.assertRaisesRegex(RuntimeError, str(port)):
                monitoring.require_free_port(port)
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                pass

    def test_stale_pid_cannot_stop_unrelated_process(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "prometheus.json"
            state.write_text(json.dumps({"pid": 43210, "identity": "previous process"}))
            with patch.object(monitoring, "process_identity", return_value="different process"), patch.object(monitoring.os, "kill") as kill:
                with self.assertRaisesRegex(RuntimeError, "进程身份"):
                    monitoring.stop_service("prometheus", state)
                kill.assert_not_called()
                self.assertTrue(state.exists())

    def test_malformed_or_dead_state_is_safe(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "prometheus.json"
            state.write_text("invalid")
            with self.assertRaisesRegex(RuntimeError, "状态文件"):
                monitoring.read_state(state)
            state.write_text(json.dumps({"pid": 43210, "identity": "gone"}))
            with patch.object(monitoring, "process_identity", return_value=None):
                monitoring.stop_service("prometheus", state)
                self.assertFalse(state.exists())

    def test_managed_process_can_be_identified_and_stopped(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "test.json"
            process = subprocess.Popen([sys.executable, "-c", "import time; print('ready', flush=True); time.sleep(30)"],
                                       stdout=subprocess.PIPE, text=True, start_new_session=True)
            try:
                self.assertEqual(process.stdout.readline().strip(), "ready")
                state.write_text(json.dumps({"pid": process.pid, "identity": monitoring.process_identity(process.pid)}))
                self.assertTrue(monitoring.is_managed(state))
                monitoring.stop_service("test", state)
                self.assertIsNotNone(process.poll())
                self.assertFalse(state.exists())
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=3)
                process.stdout.close()


if __name__ == "__main__":
    unittest.main()
