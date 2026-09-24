#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GitLab 提交记录提取（独立版，不依赖 AgentFlow）
================================================

从内部 GitLab 提取「我」在指定时间窗内的逐条提交，输出可直接用于日报/周报的素材。

只依赖 Python 标准库（3.8+），复制到任何机器都能跑。

用法：
    python gitlab_commits.py --range today          # 今天
    python gitlab_commits.py --range yesterday      # 昨天
    python gitlab_commits.py --range week           # 本周（周一至今天）
    python gitlab_commits.py --range lastweek       # 上周
    python gitlab_commits.py --range 近4天
    python gitlab_commits.py --range 2026-09-21:2026-09-24
    python gitlab_commits.py --range today --json   # 结构化输出（给程序用）

配置（按优先级）：
    1) 命令行 --url / --token / --authors
    2) 环境变量 GITLAB_URL / GITLAB_TOKEN / GITLAB_AUTHORS
    3) 首个找到的配置文件（KEY=VALUE 格式）：
       ./.env、脚本同级/.env、上级目录 .env、~/.gitlab-commits.env

踩过的坑（已内置处理，改动请保留）：
    * 事件接口的 after 是「严格晚于」，多退一天再按边界精确过滤，否则起始日整天丢失；
    * 提交明细用 /repository/commits?all=true 覆盖未合并的功能分支，按 sha 去重；
    * 剔除 Merge/Revert 同步噪音，否则周报里全是「Merge branch ...」；
    * 本机 git 的作者名/邮箱常与 GitLab 档案不一致，需用 GITLAB_AUTHORS 补别名；
    * 不能累加事件的 commit_count 当贡献数（分支同步会带入整支他人提交）。
"""

import argparse
import json
import os
import re
import ssl
import sys
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta

MAX_WINDOW_DAYS = 186
DETAIL_MAX_CHARS = 150
API_TIMEOUT = 30

# ---------------------------------------------------------------- 配置


def _read_env_file(path):
    out = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip().strip('"').strip("'")
    except Exception:
        pass
    return out


def load_config(args):
    """返回 (url, token, aliases, verify_ssl)；缺失项为空串并交由调用方提示"""
    url = (args.url or os.environ.get("GITLAB_URL") or "").strip()
    token = (args.token or os.environ.get("GITLAB_TOKEN") or "").strip()
    authors = (args.authors or os.environ.get("GITLAB_AUTHORS") or "").strip()

    if not url or not token:
        here = os.path.dirname(os.path.abspath(__file__))
        candidates = [
            os.path.join(os.getcwd(), ".env"),
            os.path.join(here, ".env"),
            os.path.join(here, "..", ".env"),
            os.path.join(here, "..", "..", ".env"),
            os.path.join(os.path.expanduser("~"), ".gitlab-commits.env"),
        ]
        for p in candidates:
            if not os.path.isfile(p):
                continue
            env = _read_env_file(p)
            url = url or env.get("GITLAB_URL", "")
            token = token or env.get("GITLAB_TOKEN", "")
            authors = authors or env.get("GITLAB_AUTHORS", "")
            if url and token:
                break

    return url.rstrip("/"), token, authors, (not args.insecure)


# ---------------------------------------------------------------- HTTP


def api(base, token, path, verify=True, timeout=API_TIMEOUT):
    req = urllib.request.Request(base + path)
    req.add_header("PRIVATE-TOKEN", token)
    ctx = None if verify else ssl._create_unverified_context()
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
        return json.loads(resp.read().decode("utf-8"))


# ---------------------------------------------------------------- 时间窗


def parse_range(text, today=None):
    """自然语言/日期 → (since, until, label)。识别不到时返回 None"""
    today = today or date.today()
    if not text:
        return None
    c = text.strip()

    m = re.search(r"(\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}|\d{1,2}月\d{1,2}[日号]?)\s*[至到~～—–]\s*"
                  r"(\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}|\d{1,2}月\d{1,2}[日号]?)", c)
    if m:
        a, b = _parse_date(m.group(1), today), _parse_date(m.group(2), today)
        if a and b and a <= b:
            return _window(a, b)
    # ISO 区间写法 2026-09-21:2026-09-24
    iso = re.match(r"^(\d{4}-\d{2}-\d{2})\s*[:~]\s*(\d{4}-\d{2}-\d{2})$", c)
    if iso:
        a, b = date.fromisoformat(iso.group(1)), date.fromisoformat(iso.group(2))
        return _window(a, b) if a <= b else None

    if "上周" in c:
        mon = today - timedelta(days=today.weekday()) - timedelta(days=7)
        return _window(mon, mon + timedelta(days=6), "上周")
    m = re.search(r"[近过去]\s*(\d+)\s*周", c)
    if m:
        n = min(int(m.group(1)), 26)
        return _window(today - timedelta(days=n * 7 - 1), today, "近%d周" % n)
    m = re.search(r"[近过去]\s*(\d+)\s*天", c)
    if m:
        n = min(int(m.group(1)), MAX_WINDOW_DAYS)
        return _window(today - timedelta(days=n - 1), today, "近%d天" % n)
    if any(k in c for k in ("最近一周", "近一周", "过去一周")):
        return _window(today - timedelta(days=6), today, "最近一周")
    if any(k in c for k in ("本周", "这周", "这一周", "本星期", "这星期")):
        mon = today - timedelta(days=today.weekday())
        return _window(mon, today, "本周")
    if "昨天" in c or "昨日" in c:
        return _window(today - timedelta(days=1), today - timedelta(days=1), "昨天")
    if "今天" in c or "今日" in c:
        return _window(today, today, "今天")
    single = _parse_date(c, today)
    if single:
        return _window(single, single)
    return None


def _window(a, b, label=None):
    if b < a:
        a, b = b, a
    if b > a + timedelta(days=MAX_WINDOW_DAYS):
        b = a + timedelta(days=MAX_WINDOW_DAYS)
        label = None
    zh = "%d月%d日" % (a.month, a.day) if a == b else "%d月%d日–%d月%d日" % (a.month, a.day, b.month, b.day)
    scope = ("%s（%s）" % (label, zh)) if label else zh
    return a, b, scope


def _parse_date(s, today):
    m = re.search(r"(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})", s)
    if m:
        try:
            return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        except ValueError:
            return None
    m = re.search(r"(\d{1,2})月(\d{1,2})[日号]?", s)
    if m:
        try:
            d = date(today.year, int(m.group(1)), int(m.group(2)))
            return d - timedelta(days=365) if d > today + timedelta(days=7) else d
        except ValueError:
            return None
    return None


def _to_iso(d, end_of_day=False):
    """本地时区某天的 00:00（或次日 00:00）→ 带时区的 ISO8601"""
    base = datetime(d.year, d.month, d.day) + (timedelta(days=1) if end_of_day else timedelta(0))
    return base.astimezone().isoformat(timespec="seconds")


# ---------------------------------------------------------------- 身份匹配


def build_identity(base, token, verify, aliases):
    me = api(base, token, "/api/v4/user", verify)
    emails = set()
    for v in (me.get("email"), me.get("commit_email")):
        if v:
            emails.add(v.lower())
    try:
        for e in api(base, token, "/api/v4/user/emails", verify):
            if e.get("email"):
                emails.add(e["email"].lower())
    except Exception:
        pass  # 部分实例无此权限，档案邮箱仍生效
    names = {me["name"].lower()} if me.get("name") else set()
    als = {a.strip().lower() for a in aliases.split(",") if a.strip()}
    return me.get("name", "我"), names, emails, als


def _matches(commit, names, emails, aliases):
    author = (commit.get("author_name") or "").lower()
    email = (commit.get("author_email") or "").lower()
    return (author and (author in names or author in aliases)) or \
           (email and (email in emails or email in aliases))


def is_sync_noise(title):
    t = (title or "").strip().lower()
    return t.startswith("merge ") or t.startswith("revert ") or t.startswith("merge,")


def detail_of(title, message):
    """提交正文压成一行（剥项目符号、以；连接、截断）"""
    if not message or not message.strip():
        return ""
    body = message.strip()
    t = (title or "").strip()
    if t and body.startswith(t):
        body = body[len(t):]
    else:
        nl = body.find("\n")
        body = body[nl + 1:] if nl > 0 else ""
    parts = []
    for line in body.split("\n"):
        s = line.strip().lstrip("-*").strip()
        if s:
            parts.append(s)
    text = "；".join(parts)
    return text[:DETAIL_MAX_CHARS] + ("…" if len(text) > DETAIL_MAX_CHARS else "")


# ---------------------------------------------------------------- 取数


def fetch_commits(base, token, verify, since, until, identity, limit_per_project=50, projects=None):
    """返回 [{project, commits: [{time,title,detail,sha}]}]，按项目分组"""
    my_name, names, emails, aliases = identity
    since_iso, until_iso = _to_iso(since), _to_iso(until, end_of_day=True)
    since_dt = datetime.fromisoformat(since_iso)
    until_dt = datetime.fromisoformat(until_iso)
    me = api(base, token, "/api/v4/user", verify)

    pids = []
    if projects:
        for kw in projects:
            for p in api(base, token, "/api/v4/projects?search=%s&per_page=5&simple=true"
                         % urllib.parse.quote(kw), verify):
                pids.append((p["id"], p.get("name_with_namespace") or p.get("name") or str(p["id"])))
    else:
        # 候选项目来自推送事件：after 严格晚于该日，多退一天再精确过滤
        seen = {}
        for page in range(1, 11):
            events = api(base, token,
                         "/api/v4/users/%d/events?action=pushed&after=%s&per_page=100&sort=desc&page=%d"
                         % (me["id"], (since - timedelta(days=1)).isoformat(), page), verify)
            if not isinstance(events, list) or not events:
                break
            for e in events:
                t = _parse_time(e.get("created_at"))
                pid = e.get("project_id") or 0
                if t and pid > 0 and since_dt <= t < until_dt:
                    seen.setdefault(pid, e.get("project_id"))
            if len(events) < 100:
                break
        pids = [(pid, None) for pid in seen]

    out = []
    for pid, pname in pids:
        if not pname:
            try:
                p = api(base, token, "/api/v4/projects/%d?simple=true" % pid, verify)
                pname = p.get("name") or p.get("name_with_namespace") or ("项目#%d" % pid)
            except Exception:
                pname = "项目#%d" % pid
        commits, seen_sha = [], set()
        for page in range(1, 11):
            try:
                batch = api(base, token,
                            "/api/v4/projects/%d/repository/commits?since=%s&until=%s&all=true&per_page=100&page=%d"
                            % (pid, urllib.parse.quote(since_iso), urllib.parse.quote(until_iso), page), verify)
            except Exception:
                break  # 仓库不可访问（已删/无权限）
            if not isinstance(batch, list) or not batch:
                break
            for c in batch:
                sha, title = c.get("id", ""), c.get("title", "")
                if not sha or sha in seen_sha or is_sync_noise(title) or not _matches(c, names, emails, aliases):
                    continue
                seen_sha.add(sha)
                t = _parse_time(c.get("created_at"))
                commits.append({
                    "time": t.strftime("%H:%M") if t else "",
                    "datetime": t.strftime("%Y-%m-%d %H:%M") if t else "",
                    "title": title,
                    "detail": detail_of(title, c.get("message", "")),
                    "sha": sha[:8],
                })
            if len(batch) < 100:
                break
        commits.sort(key=lambda x: x["datetime"])
        if commits:
            out.append({"project": pname, "commits": commits[:limit_per_project], "total": len(commits)})

    out.sort(key=lambda x: -x["total"])
    return my_name, out


def _parse_time(s):
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone()
    except ValueError:
        return None


# ---------------------------------------------------------------- 输出


def render_markdown(my_name, scope, groups):
    if not groups:
        return "「%s」%s没有提交记录" % (my_name, scope)
    total = sum(g["total"] for g in groups)
    lines = ["%s %s共提交 %d 次，涉及 %d 个项目：" % (my_name, scope, total, len(groups)), ""]
    for g in groups:
        lines.append("【%s】%d 个提交" % (g["project"], g["total"]))
        for c in g["commits"]:
            line = "- %s %s" % (c["time"], c["title"])
            if c["detail"]:
                line += " ｜ 详情：" + c["detail"]
            lines.append(line)
        lines.append("")
    return "\n".join(lines).rstrip()


def main():
    ap = argparse.ArgumentParser(description="提取 GitLab 提交记录（日报/周报素材）")
    ap.add_argument("--range", dest="range_", default="today",
                    help="时间窗：today/yesterday/week/lastweek/近N天/近N周/2026-09-01:2026-09-24（默认 today）")
    ap.add_argument("--json", action="store_true", help="输出 JSON（结构化，供程序消费）")
    ap.add_argument("--url", help="GitLab 地址，如 http://gitlab.example.com")
    ap.add_argument("--token", help="Personal Access Token（scope: read_api）")
    ap.add_argument("--authors", help="提交作者别名（逗号分隔，git 用户名/邮箱，与档案不一致时补）")
    ap.add_argument("--projects", help="只查这些项目（逗号分隔关键词；默认按推送事件自动发现）")
    ap.add_argument("--limit", type=int, default=50, help="单项目提交条数上限（默认 50）")
    ap.add_argument("--insecure", action="store_true", help="跳过 HTTPS 证书校验（自签名证书时用）")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    base, token, authors, verify = load_config(args)
    if not base or not token:
        print("缺少 GitLab 配置：请设置环境变量 GITLAB_URL / GITLAB_TOKEN，"
              "或在 .env / ~/.gitlab-commits.env 中配置（参考 README）", file=sys.stderr)
        sys.exit(2)

    window = parse_range(args.range_)
    if not window:
        print("无法识别时间窗：%s（示例：today / week / 近4天 / 2026-09-01:2026-09-24）" % args.range_,
              file=sys.stderr)
        sys.exit(2)
    since, until, scope = window

    try:
        identity = build_identity(base, token, verify, authors)
        projects = [s.strip() for s in args.projects.split(",")] if args.projects else None
        my_name, groups = fetch_commits(base, token, verify, since, until, identity,
                                        limit_per_project=args.limit, projects=projects)
    except urllib.error.HTTPError as ex:
        hint = "（401：token 无效或过期；403：scope 需含 read_api）" if ex.code in (401, 403) else ""
        print("GitLab 接口返回 %s %s%s" % (ex.code, ex.reason, hint), file=sys.stderr)
        sys.exit(1)
    except Exception as ex:
        print("请求 GitLab 失败：%s（检查地址是否可达）" % ex, file=sys.stderr)
        sys.exit(1)

    if args.json:
        print(json.dumps({
            "user": my_name, "scope": scope,
            "since": since.isoformat(), "until": until.isoformat(),
            "total": sum(g["total"] for g in groups), "projects": groups,
        }, ensure_ascii=False, indent=2))
    else:
        print(render_markdown(my_name, scope, groups))


if __name__ == "__main__":
    main()
