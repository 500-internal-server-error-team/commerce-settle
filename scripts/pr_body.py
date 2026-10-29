"""검증 목록을 검사한 뒤 gh로 PR 본문을 게시한다."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


def without_comments(body):
    token = re.compile(
        r"(?P<fence>^ {0,3}(?P<marker>`{3,}|~{3,})[^\n]*$)"
        r"|(?P<code>(?<!`)(?P<ticks>`+)(?!`)[^\n]*?(?<!`)(?P=ticks)(?!`))"
        r"|(?P<comment><!--.*?(?:-->|\Z))",
        flags=re.M | re.S,
    )
    result = []
    cursor = 0
    while match := token.search(body, cursor):
        result.append(body[cursor:match.start()])
        end = match.end()
        if match.group("fence") is not None:
            marker = match.group("marker")
            closing = re.compile(rf"^ {{0,3}}{re.escape(marker[0])}{{{len(marker)},}}[ \t]*$", re.M)
            close = closing.search(body, end)
            end = close.end() if close else len(body)
        if match.group("comment") is None:
            result.append(body[match.start():end])
        cursor = end
    result.append(body[cursor:])
    return "".join(result)


def validate(body):
    lines = without_comments(body.replace("\r\n", "\n").replace("\r", "\n")).splitlines()
    headings = []
    fence = None
    for number, line in enumerate(lines):
        marker = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line)
        if marker:
            token, suffix = marker.groups()
            if fence is None:
                fence = (token[0], len(token))
            elif token[0] == fence[0] and len(token) >= fence[1] and not suffix.strip():
                fence = None
            continue
        if fence is None and re.match(r"^#{1,2}\s+", line):
            headings.append((number, line))
    starts = [i for i, (_, title) in enumerate(headings) if title.strip() == "## 검증"]
    if len(starts) != 1:
        raise ValueError("'## 검증' 제목은 정확히 한 번 필요합니다.")
    start = starts[0]
    begin = headings[start][0] + 1
    end = headings[start + 1][0] if start + 1 < len(headings) else len(lines)
    content = lines[begin:end]
    while content and not content[0].strip():
        content.pop(0)
    while content and not content[-1].strip():
        content.pop()
    if not content or any(not re.fullmatch(r"- \[[ xX]\] \S.*", line) for line in content):
        raise ValueError(
            "검증은 빈 줄 없는 단일 체크리스트로 작성하세요. "
            "결과와 미실행 이유를 각 항목에 쓰고 별도 결과 목록을 중복 추가하지 마세요."
        )


def check_account(account, body_file):
    validate(body_file.read_text(encoding="utf-8"))
    if not account.strip():
        raise ValueError("게시할 GitHub 계정을 명시하세요.")
    login = subprocess.run(
        ["gh", "api", "user", "--jq", ".login"], check=True, capture_output=True, text=True
    ).stdout.strip()
    if login != account:
        raise ValueError(f"현재 GitHub 계정 {login}이 지정한 계정 {account}과 다릅니다.")


def publish(repo, number, account, body_file, title=None):
    if number < 1 or not repo.strip():
        raise ValueError("게시할 저장소·PR 번호를 명시하세요.")
    check_account(account, body_file)
    command = ["gh", "pr", "edit", str(number), "--repo", repo, "--body-file", str(body_file)]
    if title is not None:
        command.extend(["--title", title])
    subprocess.run(command, check=True)


def create(repo, base, head, title, account, body_file):
    if any(not value.strip() for value in [repo, base, head, title]):
        raise ValueError("생성할 저장소·base·head·제목을 명시하세요.")
    check_account(account, body_file)
    subprocess.run(
        ["gh", "pr", "create", "--repo", repo, "--base", base, "--head", head,
         "--title", title, "--body-file", str(body_file)],
        check=True,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check")
    source = check.add_mutually_exclusive_group(required=True)
    source.add_argument("--body-file", type=Path)
    source.add_argument("--event-file", type=Path)
    post = commands.add_parser("publish")
    post.add_argument("--body-file", type=Path, required=True)
    post.add_argument("--repo", required=True)
    post.add_argument("--pr", type=int, required=True)
    post.add_argument("--account", required=True)
    post.add_argument("--title")
    new = commands.add_parser("create")
    new.add_argument("--body-file", type=Path, required=True)
    new.add_argument("--repo", required=True)
    new.add_argument("--base", required=True)
    new.add_argument("--head", required=True)
    new.add_argument("--title", required=True)
    new.add_argument("--account", required=True)
    args = parser.parse_args()
    try:
        if args.command == "publish":
            publish(args.repo, args.pr, args.account, args.body_file, args.title)
        elif args.command == "create":
            create(args.repo, args.base, args.head, args.title, args.account, args.body_file)
        elif args.event_file:
            event = json.loads(args.event_file.read_text(encoding="utf-8"))
            validate(event["pull_request"]["body"] or "")
        else:
            validate(args.body_file.read_text(encoding="utf-8"))
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print("PASS PR verification list: one compact checklist")
    return 0


if __name__ == "__main__":
    sys.exit(main())
