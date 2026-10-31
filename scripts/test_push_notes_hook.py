"""python3 -m unittest discover -s scripts"""

import importlib.util
import io
import json
import os
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

spec = importlib.util.spec_from_file_location("push_notes_hook", Path(__file__).with_name("push-notes-hook.py"))
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)

CWD = "/work/app"


class PushTarget(unittest.TestCase):
    def test_pushes(self):
        cases = {
            "git push": (CWD, []),
            "git push origin HEAD": (CWD, []),
            "git -C ../other-app push": (CWD, ["-C", "../other-app"]),
            'git -C "/a b" push': (CWD, ["-C", "/a b"]),
            "git -c k=v push": (CWD, ["-c", "k=v"]),
            "git --no-pager push": (CWD, ["--no-pager"]),
            "git --git-dir=/x/.git push": (CWD, ["--git-dir=/x/.git"]),
            "cd ../x && git push": ("/work/x", []),
            "./gradlew test && git push -u origin HEAD": (CWD, []),
            "false || git push": (CWD, []),
            "git fetch; git push": (CWD, []),
            "echo y | git push": (CWD, []),
            "git status\ngit push": (CWD, []),
            "GIT_TRACE=1 git push": (CWD, []),
            "if true; then git push; fi": (CWD, []),
            "(git push)": (CWD, []),
            "git push 2>&1 | tail -3": (CWD, []),
        }
        for command, expected in cases.items():
            with self.subTest(command=command):
                self.assertEqual(hook.push_target(command, CWD), expected)

    @mock.patch.dict(os.environ, {"HOME": "/home/me", "CLAUDE_PROJECT_DIR": "/work/project"})
    def test_expands_home_and_variables_as_the_shell_would(self):
        cases = {
            "git -C ~/repo push": (CWD, ["-C", "/home/me/repo"]),
            'git -C "$CLAUDE_PROJECT_DIR" push': (CWD, ["-C", "/work/project"]),
            'cd "$HOME/repo" && git push': ("/home/me/repo", []),
            'cd "$CLAUDE_PROJECT_DIR" && git push': ("/work/project", []),
            "cd ${HOME} && git push": ("/home/me", []),
        }
        for command, expected in cases.items():
            with self.subTest(command=command):
                self.assertEqual(hook.push_target(command, CWD), expected)

    def test_not_pushes(self):
        for command in [
            "git status",
            "git pushx",
            "git log --grep push",
            'git commit -m "fix the hook, then git push"',
            "gh pr create --body '... Bash(git push*) ...'",
            "echo git push",
            "echo 'git push'",
            "grep -rn 'git push' scripts",
            "git commit -F - <<'EOF'\nfix: x\n\ngit push is gated now\nEOF",
            "gh pr create --body-file - <<EOF\ngit push\nEOF\n",
            'echo "unterminated',
            "",
        ]:
            with self.subTest(command=command):
                self.assertIsNone(hook.push_target(command, CWD))


class Main(unittest.TestCase):
    def run_hook(self, command: str) -> tuple[int, str]:
        stdin = io.StringIO(json.dumps({"tool_input": {"command": command}, "cwd": "/"}))
        stderr = io.StringIO()
        with mock.patch("sys.stdin", stdin), redirect_stderr(stderr):
            return hook.main(), stderr.getvalue()

    def test_warns_when_the_push_checkout_cannot_be_found(self):
        for command in ["cd /nonexistent/repo && git push", "git -C /nonexistent/repo push"]:
            with self.subTest(command=command):
                code, stderr = self.run_hook(command)
                self.assertEqual(code, 1)
                self.assertIn("notes gate skipped", stderr)

    def test_stays_silent_for_anything_but_a_push(self):
        self.assertEqual(self.run_hook("cd /nonexistent/repo && git status"), (0, ""))


if __name__ == "__main__":
    unittest.main()
