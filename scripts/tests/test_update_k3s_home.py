"""Exercise real commits/pushes against disposable repositories, without secrets."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "update-k3s-home.sh"
GIT = shutil.which("git")
FILES = {
    "api-deployment.yaml": "backend",
    "worker-deployment.yaml": "backend",
    "frontend-deployment.yaml": "frontend",
    "ai-service-deployment.yaml": "ai-service",
    "seeder-job.yaml": "backend",
}


class GitOpsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="eventpulse-gitops-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.remote = self.root / "remote.git"
        self.editor = self.root / "editor"
        self.git("init", "--bare", "--initial-branch=main", str(self.remote))
        self.git("clone", str(self.remote), str(self.editor))
        self.git("-C", str(self.editor), "config", "user.name", "GitOps test")
        self.git("-C", str(self.editor), "config", "user.email", "test@example.invalid")
        manifests = self.editor / "apps/eventpulse"
        manifests.mkdir(parents=True)
        for filename, component in FILES.items():
            is_seeder = filename == "seeder-job.yaml"
            header = (
                "apiVersion: batch/v1\nkind: Job\nmetadata:\n"
                "  name: eventpulse-seeder\n  annotations:\n"
                "    argocd.argoproj.io/sync-wave: '0'\n"
                "spec:\n  activeDeadlineSeconds: 600\n"
                if is_seeder else "apiVersion: apps/v1\nkind: Deployment\nspec:\n"
            )
            init_containers = (
                "      restartPolicy: Never\n      initContainers:\n"
                "        - name: wait-for-postgres\n"
                "          image: postgres:18-alpine\n"
                if is_seeder else ""
            )
            (manifests / filename).write_text(
                header + "  template:\n"
                "    spec:\n      enableServiceLinks: false\n"
                + init_containers + "      containers:\n"
                f"        - name: {component}\n"
                f"          image: ghcr.io/kaiwenyao/eventpulse-{component}:1111111\n"
            )
        (self.editor / "unrelated.txt").write_text("Keep this file.\n")
        self.publish_fixture()

    def git(self, *args):
        return subprocess.run(
            [GIT, *args], check=True, capture_output=True, text=True
        ).stdout.strip()

    def publish_fixture(self):
        self.git("-C", str(self.editor), "add", ".")
        self.git("-C", str(self.editor), "commit", "-m", "Test fixture")
        self.git("-C", str(self.editor), "push", "origin", "main")

    def head(self):
        return self.git("--git-dir", str(self.remote), "rev-parse", "main")

    def contents(self, filename):
        return self.git(
            "--git-dir", str(self.remote), "show", f"main:apps/eventpulse/{filename}"
        )

    def run_update(self, component="backend", expected=0, **overrides):
        env = {
            **os.environ,
            "BRANCH_NAME": "main",
            "CHANGE_ID": "",
            "FULL_IMAGE": f"ghcr.io/kaiwenyao/eventpulse-{component}:abcdef0",
            "GITOPS_USER": "fake-user",
            "GITOPS_TOKEN": "fake-token-not-a-secret",
            "GITOPS_REPO_URL": str(self.remote),
            "TMPDIR": str(self.root),
            **overrides,
        }
        result = subprocess.run(
            ["sh", str(SCRIPT), component], env=env, capture_output=True,
            text=True, timeout=45,
        )
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        self.assertNotIn(env["GITOPS_TOKEN"], result.stdout + result.stderr)
        self.assertEqual(list(self.root.glob("eventpulse-gitops.*")), [])
        return result

    def test_each_component_updates_only_its_manifests_and_is_idempotent(self):
        for component in ("backend", "frontend", "ai-service"):
            with self.subTest(component=component):
                before = self.head()
                originals = {name: self.contents(name) for name in FILES}
                self.run_update(component)
                files = self.git(
                    "--git-dir", str(self.remote), "diff", "--name-only", before, "main"
                ).splitlines()
                expected = {
                    f"apps/eventpulse/{name}" for name, owner in FILES.items()
                    if owner == component
                }
                self.assertEqual(set(files), expected)
                self.assertEqual(
                    self.git("--git-dir", str(self.remote), "rev-list", "--count", f"{before}..main"),
                    "1",
                )
                for filename, owner in FILES.items():
                    body = originals[filename]
                    if owner == component:
                        body = body.replace(f"eventpulse-{component}:1111111",
                                            f"eventpulse-{component}:abcdef0")
                    self.assertEqual(self.contents(filename), body)
                updated = self.head()
                self.run_update(component)
                self.assertEqual(self.head(), updated)
        self.assertIn("eventpulse-backend:abcdef0", self.contents("seeder-job.yaml"))

    def test_backend_update_repairs_stale_seeder_when_deployments_are_current(self):
        for filename in ("api-deployment.yaml", "worker-deployment.yaml"):
            path = self.editor / "apps/eventpulse" / filename
            path.write_text(path.read_text().replace("1111111", "abcdef0"))
        self.publish_fixture()
        before = self.head()
        original_seeder = self.contents("seeder-job.yaml")
        self.run_update()
        self.assertEqual(
            self.git("--git-dir", str(self.remote), "diff", "--name-only", before, "main"),
            "apps/eventpulse/seeder-job.yaml",
        )
        self.assertEqual(self.contents("seeder-job.yaml"),
                         original_seeder.replace("1111111", "abcdef0"))
        for filename in ("api-deployment.yaml", "worker-deployment.yaml", "seeder-job.yaml"):
            self.assertIn("eventpulse-backend:abcdef0", self.contents(filename))

    def test_no_change_does_not_add_final_newline(self):
        path = self.editor / "apps/eventpulse/frontend-deployment.yaml"
        path.write_text(path.read_text().replace("1111111", "abcdef0").rstrip("\n"))
        self.publish_fixture()
        before = self.head()
        self.run_update("frontend")
        self.assertEqual(self.head(), before)

    def test_pull_requests_and_other_branches_do_not_write(self):
        before = self.head()
        self.run_update(BRANCH_NAME="feature/test", GITOPS_REPO_URL="/does-not-exist")
        self.run_update(CHANGE_ID="123", GITOPS_REPO_URL="/does-not-exist")
        self.assertEqual(self.head(), before)

    def test_invalid_image_is_rejected(self):
        for image in ("ghcr.io/kaiwenyao/eventpulse-frontend:abcdef0",
                      "ghcr.io/kaiwenyao/eventpulse-backend:latest"):
            with self.subTest(image=image):
                before = self.head()
                self.run_update(expected=1, FULL_IMAGE=image)
                self.assertEqual(self.head(), before)

    def test_missing_or_ambiguous_manifest_never_pushes_partial_backend_update(self):
        for filename in ("worker-deployment.yaml", "seeder-job.yaml"):
            path = self.editor / "apps/eventpulse" / filename
            original = path.read_text()
            for content in (None, original.replace("eventpulse-backend", "other-backend"),
                            original + "          image: ghcr.io/kaiwenyao/eventpulse-backend:2222222\n"):
                with self.subTest(filename=filename, content=content):
                    if content is None:
                        path.unlink()
                    else:
                        path.write_text(content)
                    self.publish_fixture()
                    before = self.head()
                    self.run_update(expected=1)
                    self.assertEqual(self.head(), before)
            path.write_text(original)
            self.publish_fixture()

    def test_rejected_push_retries_on_latest_main_and_preserves_other_changes(self):
        # Inject another writer immediately before the helper's first push.
        # This guarantees a real non-fast-forward rejection, without timing races.
        path = self.editor / "apps/eventpulse/frontend-deployment.yaml"
        path.write_text(path.read_text().replace("1111111", "7654321"))
        seeder = self.editor / "apps/eventpulse/seeder-job.yaml"
        seeder.write_text(seeder.read_text().replace("1111111", "7654321")
                          .replace("activeDeadlineSeconds: 600", "activeDeadlineSeconds: 900"))
        (self.editor / "unrelated.txt").write_text("Changed by another pipeline.\n")
        self.git("-C", str(self.editor), "add", ".")
        self.git("-C", str(self.editor), "commit", "-m", "Concurrent frontend update")
        wrapper_dir = self.root / "bin"
        wrapper_dir.mkdir()
        wrapper = wrapper_dir / "git"
        marker = self.root / "race-injected"
        wrapper.write_text(
            f"#!{sys.executable}\n"
            "from pathlib import Path\nimport subprocess, sys\n"
            f"marker = Path({str(marker)!r})\n"
            "if 'push' in sys.argv and not marker.exists():\n"
            "    marker.touch()\n"
            f"    subprocess.run([{GIT!r}, '-C', {str(self.editor)!r}, 'push', 'origin', 'main'], check=True)\n"
            f"sys.exit(subprocess.call([{GIT!r}, *sys.argv[1:]]))\n"
        )
        wrapper.chmod(0o700)
        result = self.run_update(PATH=f"{wrapper_dir}{os.pathsep}{os.environ['PATH']}")
        self.assertIn("attempt 1/5", result.stderr)
        self.assertIn("eventpulse-frontend:7654321", self.contents("frontend-deployment.yaml"))
        self.assertIn("eventpulse-backend:abcdef0", self.contents("api-deployment.yaml"))
        self.assertIn("eventpulse-backend:abcdef0", self.contents("worker-deployment.yaml"))
        self.assertIn("eventpulse-backend:abcdef0", self.contents("seeder-job.yaml"))
        self.assertIn("activeDeadlineSeconds: 900", self.contents("seeder-job.yaml"))
        self.assertEqual(self.git("--git-dir", str(self.remote), "show", "main:unrelated.txt"),
                         "Changed by another pipeline.")

    def test_persistent_push_failure_fails_build_after_five_attempts(self):
        hook = self.remote / "hooks/pre-receive"
        hook.write_text("#!/bin/sh\nexit 1\n")
        hook.chmod(0o700)
        before = self.head()
        result = self.run_update(expected=1)
        self.assertIn("after 5 attempts", result.stderr)
        self.assertEqual(self.head(), before)


if __name__ == "__main__":
    unittest.main()
