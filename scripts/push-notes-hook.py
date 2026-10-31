#!/usr/bin/env python3
"""Claude Code's PreToolUse hook for Bash: run notes-gate.py --pending before a `git push`.

Reads the hook's JSON on stdin. Exits 0 for anything that is not a push, or a push from a repository
without scripts/notes-gate.py; 1, a warning, for a push whose checkout cannot be found; otherwise the
gate's own code, so 2 (notes owed) blocks the push and 1 (the gate itself broke) only warns.
"""

import json
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path

PUNCTUATION = ";&|()<>\n"
# Words that can stand before a command without being the command.
PREFIXES = {"then", "do", "else", "elif", "!", "{", "time", "command", "exec"}
# git's global options that take their value as the next word rather than after `=`.
VALUED = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--super-prefix", "--config-env"}
ASSIGNMENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
# A heredoc body is data, so a line in it that happens to start with `git push` must not count.
HEREDOC = re.compile(r"<<-?[ \t]*(['\"]?)(\w+)\1([^\n]*)\n.*?^\t*\2[ \t]*$", re.MULTILINE | re.DOTALL)


def words(command: str) -> list[str]:
    lexer = shlex.shlex(HEREDOC.sub(r"\3", command), posix=True, punctuation_chars=PUNCTUATION)
    lexer.whitespace = " \t\r"
    lexer.whitespace_split = True
    lexer.commenters = ""
    return list(lexer)


def separator(word: str) -> bool:
    return bool(word) and set(word) <= set(PUNCTUATION) and not set(word) & set("<>")


def segments(tokens: list[str]) -> list[list[str]]:
    result: list[list[str]] = [[]]
    for word in tokens:
        if separator(word):
            result.append([])
        else:
            result[-1].append(word)
    return [s for s in result if s]


def expand(word: str) -> str:
    return os.path.expanduser(os.path.expandvars(word))


def git_options(segment: list[str]) -> list[str] | None:
    """The global options of a `git … push` segment, or None when the segment is anything else."""
    i = 0
    while i < len(segment) and (segment[i] in PREFIXES or ASSIGNMENT.match(segment[i])):
        i += 1
    if i == len(segment) or os.path.basename(segment[i]) != "git":
        return None
    options: list[str] = []
    i += 1
    while i < len(segment) and segment[i].startswith("-"):
        takes_value = segment[i] in VALUED
        options += [expand(word) for word in segment[i : i + 1 + takes_value]]
        i += 1 + takes_value
    return options if i < len(segment) and segment[i] == "push" else None


def push_target(command: str, cwd: str) -> tuple[str, list[str]] | None:
    """Where the first `git push` in a shell command runs, and the git options it carries."""
    try:
        tokens = words(command)
    except ValueError:
        return None
    for segment in segments(tokens):
        if segment[0] == "cd" and len(segment) <= 2:
            target = segment[1] if len(segment) == 2 else str(Path.home())
            if target != "-":
                cwd = os.path.normpath(os.path.join(cwd, expand(target)))
            continue
        options = git_options(segment)
        if options is not None:
            return cwd, options
    return None


def main() -> int:
    hook = json.load(sys.stdin)
    target = push_target(hook.get("tool_input", {}).get("command", ""), hook.get("cwd") or os.getcwd())
    if target is None:
        return 0
    cwd, options = target
    try:
        toplevel = subprocess.run(
            ["git", *options, "rev-parse", "--show-toplevel"], cwd=cwd, capture_output=True, text=True
        )
    except OSError:
        toplevel = None
    if toplevel is None or toplevel.returncode:
        print(f"notes gate skipped: cannot find the checkout this push runs in ({cwd}, {options})", file=sys.stderr)
        return 1
    root = Path(toplevel.stdout.strip())
    gate = root / "scripts" / "notes-gate.py"
    if not gate.is_file():
        return 0
    subprocess.run(["git", "-C", str(root), "fetch", "-q", "origin", "main"], capture_output=True)
    return subprocess.run([sys.executable, str(gate), "--pending"]).returncode


if __name__ == "__main__":
    sys.exit(main())
