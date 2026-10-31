"""python3 -m unittest discover -s scripts"""

import importlib.util
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
