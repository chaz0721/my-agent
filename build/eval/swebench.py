#!/usr/bin/env python3
"""SWE-bench Lite subset evaluation of pai-next: same model, two harness configurations (full vs bare).

Subcommands (run through build/eval/run.ps1, which loads the API key from .env into the process environment):

  sample   stratified random sample of the Lite test split -> build/eval/instances.txt
  run      for each (instance, group): one `pai --print` run inside that instance's SWE-bench image (repo at
           /testbed, the image's conda env; JRE and jar mounted read-only), then `git diff HEAD`
           -> <work>/runs/<group>/<instance>/{result.json,patch.diff,pai.log,...}, PAI_HOME <work>/home/<group>/<id>
           (pai no longer runs on the host: see docs/reports/M8-report.md, incident section)
  preds    write <work>/preds/<group>.jsonl from the per-run results
  evaluate official harness (swebench 4.1.0) in a Linux container with the host Docker socket mounted
  report   per-instance table, medians and distributions as Markdown (+ summary.json)

The prompt template and run parameters below are fixed before the first run and are not tuned on results.
Nothing here reads, prints or logs the API key: the run container gets it with `docker run -e NAME` (value taken
from this process's environment); every other child process gets an environment without it.
"""
import argparse
import collections
import json
import os
import random
import re
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

DATASET = "princeton-nlp/SWE-bench_Lite"
SPLIT = "test"
DEFAULT_SEED = 20261016
DEFAULT_N = 30
MODEL = "deepseek-chat"
PROVIDER = "deepseek"
MAX_TURNS = 40
TIMEOUT_S = 20 * 60
HARNESS_IMAGE = "pai-swebench-harness:4.1.0"
GROUPS = {
    "full": [],
    "bare": ["--no-ext", "tasks,verify,subagent,skills"],
}
PROMPT_TEMPLATE = (
    "修改仓库代码使以下 issue 解决；不要修改或新增测试文件；完成后用一句话总结并停止。\n\n"
    "<issue>\n{problem_statement}\n</issue>\n"
)
# Hugging Face dataset revision every run and the sample are pinned to (the commit cached for the M8 run).
DATASET_REVISION = "6ec7bb89b9342f664a54a6e0a6ea6501d3437cc2"
# pai exit statuses (CliOptions.USAGE); 1 is an uncaught error. Before the M8 review fix (jar 43b59c2) a usage error
# exited 2 like MAX_TURNS; that jar never hit one in the 60 runs.
EXIT_REASONS = {0: "COMPLETED", 1: "CRASH", 2: "MAX_TURNS", 3: "ABORTED", 4: "PROMPT_TOO_LONG", 5: "MAX_OUTPUT_TOKENS",
                6: "PROVIDER_ERROR", 7: "HOOK_ERROR", 64: "USAGE_ERROR"}
TEST_PATH = re.compile(r"(^|/)(tests?|testing)/|(^|/)test_[^/]*\.py$|_tests?\.py$|(^|/)conftest\.py$")
SECRET_ENV = ("PAI_OPENAI_COMPAT_API_KEY", "PAI_OPENAI_COMPAT_BASE_URL", "DEEPSEEK_API_KEY", "PAI_ANTHROPIC_API_KEY")


def clean_env():
    """Environment for git/docker: without any key."""
    return {k: v for k, v in os.environ.items() if k not in SECRET_ENV}


def load_instances():
    from datasets import load_dataset
    return {row["instance_id"]: row for row in load_dataset(DATASET, split=SPLIT, revision=DATASET_REVISION)}


def sha256(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def image_digest(image):
    """Repo digest of a local image (an image, not a container: nothing here inspects a run container)."""
    r = subprocess.run(["docker", "image", "inspect", "--format", "{{index .RepoDigests 0}}", image], env=clean_env(),
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return r.stdout.decode("utf-8", errors="replace").strip() or None


def read_list(path):
    return [line.strip() for line in Path(path).read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")]


# ---------------------------------------------------------------- sample

def allocate(counts, n):
    """Proportional allocation by largest remainder; ties broken by repo name."""
    total = sum(counts.values())
    quotas = {r: c * n / total for r, c in counts.items()}
    alloc = {r: int(q) for r, q in quotas.items()}
    rest = sorted(counts, key=lambda r: (-(quotas[r] - alloc[r]), r))
    for r in rest[: n - sum(alloc.values())]:
        alloc[r] += 1
    return alloc


def cmd_sample(args):
    data = load_instances()
    by_repo = collections.defaultdict(list)
    for iid, row in data.items():
        by_repo[row["repo"]].append(iid)
    alloc = allocate({r: len(v) for r, v in by_repo.items()}, args.n)
    rng = random.Random(args.seed)
    chosen = []
    for repo in sorted(by_repo):
        chosen += sorted(rng.sample(sorted(by_repo[repo]), alloc[repo]))
    lines = [f"# {DATASET} {SPLIT}: {args.n} instances, stratified by repo (largest remainder), seed {args.seed}",
             "# allocation: " + ", ".join(f"{r}={alloc[r]}" for r in sorted(alloc) if alloc[r])] + chosen
    Path(args.out).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(chosen)} instances to {args.out}")
    for line in lines[:2]:
        print(line)


# ---------------------------------------------------------------- run

def run(cmd, cwd=None, check=True, **kw):
    r = subprocess.run(cmd, cwd=cwd, check=False, env=clean_env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       **kw)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd[-3:])} exited {r.returncode}: "
                           f"{r.stderr.decode('utf-8', errors='replace').strip()[:500]}")
    return r


def image_of(iid):
    return "swebench/sweb.eval.x86_64." + iid.replace("__", "_1776_") + ":latest"


def container_name(group, iid):
    return f"pai-{group}-{iid}".replace("__", "-")[:120]


# Runs inside the instance container (bash -l: the image's profile activates the `testbed` conda env).
# The diff is taken against HEAD of /testbed, the state the official harness applies the patch to.
# The prompt goes in as one argument, -p "$(cat file)" (--print-file was removed from the cli after jar 43b59c2; the
# M8 runs used --print-file /out/prompt.txt with the same text). /out/started tells a launch failure from a timeout.
IN_CONTAINER = """#!/bin/bash
date +%s > /out/started
cd /testbed
# the work tree must be clean before pai starts, otherwise the diff would contain foreign changes
git status --porcelain > /out/git_status_before 2>&1
if [ -s /out/git_status_before ]; then echo 98 > /out/exit_code; exit 98; fi
timeout {timeout} /opt/jre/bin/java -jar /opt/pai/pai-cli.jar --provider {provider} --model {model} \\
  --cwd /testbed --approval yolo --max-turns {max_turns} -p "$(cat /out/prompt.txt)" --quiet {extra} \\
  < /dev/null > /out/final.txt 2> /out/pai.log
code=$?
echo "$code" > /out/exit_code
git add -A > /out/git.log 2>&1
git diff --cached --binary HEAD > /out/patch.diff 2>> /out/git.log
exit "$code"
"""

def session_metrics(home):
    """Turns, tool calls and usage from every session file below PAI_HOME (main session and sub-agent side chains)."""
    m = {"turns": 0, "tool_calls": 0, "tools": collections.Counter(), "input": 0, "cache_read": 0, "output": 0,
         "sub_turns": 0, "sub_input": 0, "sub_output": 0, "files": 0}
    for f in sorted((home / "sessions").rglob("*.jsonl")):
        side = "subagents" in f.parts
        m["files"] += 1
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                e = json.loads(line)
            except ValueError:
                continue
            msg = e.get("message") if e.get("type") == "message" else None
            if not msg or msg.get("type") != "assistant":
                continue
            u = msg.get("usage") or {}
            calls = [b for b in msg.get("content", []) if b.get("type") == "tool_call"]
            if side:
                m["sub_turns"] += 1
                m["sub_input"] += u.get("input", 0) + u.get("cacheRead", 0)
                m["sub_output"] += u.get("output", 0)
            else:
                m["turns"] += 1
                m["tool_calls"] += len(calls)
                m["tools"].update(b.get("name", "?") for b in calls)
            m["input"] += u.get("input", 0) + u.get("cacheRead", 0)
            m["cache_read"] += u.get("cacheRead", 0)
            m["output"] += u.get("output", 0)
    m["tools"] = dict(m["tools"])
    return m


# A lower bound: commands that reach the network some other way (a script file, an alias, ...) are not seen.
FETCH = re.compile(r"\bpip3? download\b|\bpip3? install\b[^|;&\n]*(==|-U\b|--upgrade)|\b(curl|wget)\s[^|;&\n]*https?://"
                   r"|\bgit (fetch|pull|clone)\b|\bpip3? index\b|\burllib\b|\burlopen\b|\brequests\.get\(")


def fetch_calls(home):
    """bash calls that fetch packages or code from the network (a released version may already contain the fix)."""
    n = 0
    for f in sorted((home / "sessions").rglob("*.jsonl")):
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                msg = json.loads(line).get("message") or {}
            except ValueError:
                continue
            if msg.get("type") == "assistant":
                n += sum(1 for b in msg.get("content", []) if b.get("type") == "tool_call" and b.get("name") == "bash"
                         and FETCH.search(str((b.get("arguments") or {}).get("command", ""))))
    return n


def run_one(row, group, args, work):
    """One pai run in the instance's own SWE-bench container: the repo at /testbed, the image's conda env, no access
    to the host besides the read-only JRE and jar mounts and the per-run output and PAI_HOME directories."""
    iid = row["instance_id"]
    out = work / "runs" / group / iid
    result_file = out / "result.json"
    if result_file.exists() and not args.force:
        previous = json.loads(result_file.read_text(encoding="utf-8"))
        # a result is reused only for the same build; results written before jar_sha256 was recorded are kept
        if previous.get("jar_sha256", args.jar_sha256) == args.jar_sha256:
            return previous
        print(f"{group:4} {iid:40} jar changed since the stored result; running again", flush=True)
    if out.exists():
        rm_tree(out)
    out.mkdir(parents=True)
    home = work / "home" / group / iid
    if home.exists():
        rm_tree(home)
    home.mkdir(parents=True)
    (out / "prompt.txt").write_text(PROMPT_TEMPLATE.format(problem_statement=row["problem_statement"]),
                                    encoding="utf-8", newline="\n")
    (out / "run.sh").write_text(IN_CONTAINER.format(timeout=TIMEOUT_S, provider=args.provider, model=MODEL,
                                                    max_turns=MAX_TURNS, extra=" ".join(GROUPS[group])),
                                encoding="utf-8", newline="\n")
    name = container_name(group, iid)
    # -e NAME without a value: docker copies the value from this process's environment (run.ps1 loaded it from
    # .env); it never appears on a command line, in a script or in a log.
    cmd = ["docker", "run", "--rm", "--name", name, "-e", "PAI_OPENAI_COMPAT_API_KEY", "-e",
           "PAI_OPENAI_COMPAT_BASE_URL", "-e", "PAI_HOME=/pai-home", "-v", f"{Path(args.jre).resolve()}:/opt/jre:ro",
           "-v", f"{Path(args.jar).resolve()}:/opt/pai/pai-cli.jar:ro", "-v", f"{home.resolve()}:/pai-home",
           "-v", f"{out.resolve()}:/out", image_of(iid), "bash", "-l", "/out/run.sh"]
    env = os.environ if args.provider != "fake" else clean_env()
    started = time.time()
    with open(out / "docker.log", "wb") as log:
        proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, env=env)
        try:
            proc.wait(timeout=TIMEOUT_S + 120)
        except subprocess.TimeoutExpired:
            subprocess.run(["docker", "kill", name], env=clean_env(), stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)
            proc.wait()
    elapsed = time.time() - started
    code_file = out / "exit_code"
    code = int(code_file.read_text().strip()) if code_file.exists() else None
    if code is None:
        reason = "TIMEOUT" if (out / "started").exists() else "LAUNCH_FAILED"
    else:
        reason = "DIRTY_TREE" if code == 98 else "TIMEOUT" if code in (124, 137) else EXIT_REASONS.get(code, f"EXIT_{code}")
    patch_file = out / "patch.diff"
    patch = patch_file.read_text(encoding="utf-8", errors="replace") if patch_file.exists() else ""
    changed = re.findall(r"^diff --git a/(\S+) b/", patch, re.M)
    result = {"instance_id": iid, "repo": row["repo"], "group": group, "exit_code": code, "reason": reason,
              "seconds": round(elapsed, 1), "files_changed": changed,
              "test_files_changed": [p for p in changed if TEST_PATH.search(p)], "patch_bytes": len(patch),
              "jar_sha256": args.jar_sha256, "provider": args.provider, "model": MODEL, "max_turns": MAX_TURNS,
              "timeout_s": TIMEOUT_S, "group_args": GROUPS[group], "dataset": DATASET, "split": SPLIT,
              "dataset_revision": DATASET_REVISION, "image": image_of(iid), "image_digest": image_digest(image_of(iid)),
              **session_metrics(home)}
    result_file.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    return result


def cmd_run(args):
    from concurrent.futures import ThreadPoolExecutor
    work = Path(args.work)
    args.jar_sha256 = sha256(args.jar)
    data = load_instances()
    ids = args.only.split(",") if args.only else read_list(args.instances)
    groups = args.groups.split(",")
    # interleaved order: tasks queue as (i1, bare), (i1, full), (i2, bare), ... and the workers take them in turn
    tasks = [(data[i], g) for i in ids for g in groups]

    def go(task):
        row, g = task
        try:
            r = run_one(row, g, args, work)
            print(f"{g:4} {row['instance_id']:40} {r['reason']:16} {r['seconds']:7.0f}s turns={r['turns']} "
                  f"tools={r['tool_calls']} in={r['input']} out={r['output']} files={len(r['files_changed'])}",
                  flush=True)
        except Exception as e:  # one failed setup must not stop the batch; it is recorded and reported
            print(f"{g:4} {row['instance_id']:40} SETUP_ERROR {e!r}", flush=True)

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        list(pool.map(go, tasks))


# ---------------------------------------------------------------- predictions / evaluation

def cmd_preds(args):
    work = Path(args.work)
    (work / "preds").mkdir(exist_ok=True)
    for g in args.groups.split(","):
        rows = []
        for i in read_list(args.instances):
            f = work / "runs" / g / i / "patch.diff"
            patch = f.read_text(encoding="utf-8") if f.exists() else ""
            rows.append({"instance_id": i, "model_name_or_path": f"pai-next-{g}", "model_patch": patch})
        p = work / "preds" / f"{g}.jsonl"
        p.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8", newline="\n")
        print(f"{p}: {len(rows)} predictions, {sum(1 for r in rows if r['model_patch'])} non-empty")


def cmd_evaluate(args):
    work = Path(args.work).resolve()
    (work / "harness").mkdir(exist_ok=True)
    (work / "hf").mkdir(exist_ok=True)
    for g in args.groups.split(","):
        preds = args.preds or f"/eval/preds/{g}.jsonl"
        ids = args.only.split(",") if args.only else read_list(args.instances)
        cmd = ["docker", "run", "--rm", "-v", "//var/run/docker.sock:/var/run/docker.sock", "-v",
               f"{work}:/eval", "-e", "HF_HOME=/eval/hf", "-w", "/eval/harness", HARNESS_IMAGE,
               "python", "-m", "swebench.harness.run_evaluation", "--dataset_name", DATASET, "--split", SPLIT,
               "--predictions_path", preds, "--max_workers", str(args.max_workers), "--run_id", args.run_id or g,
               "--instance_ids", *ids]
        print("evaluate", g, flush=True)
        r = subprocess.run(cmd, env=clean_env())
        print("harness exit", r.returncode)


# ---------------------------------------------------------------- report

def resolved_ids(work, group, run_id):
    p = work / "harness" / f"pai-next-{group}.{run_id}.json"
    if not p.exists():
        return None, {}
    rep = json.loads(p.read_text(encoding="utf-8"))
    return set(rep.get("resolved_ids", [])), rep


def cmd_report(args):
    work = Path(args.work)
    ids = read_list(args.instances)
    groups = args.groups.split(",")
    res = {g: {} for g in groups}
    resolved = {}
    reports = {}
    for g in groups:
        resolved[g], reports[g] = resolved_ids(work, g, args.run_prefix + g)
        for i in ids:
            f = work / "runs" / g / i / "result.json"
            if f.exists():
                res[g][i] = json.loads(f.read_text(encoding="utf-8"))
                res[g][i]["fetch"] = fetch_calls(work / "home" / g / i)
    out = []
    out.append("| # | instance | repo | " + " | ".join(
        f"{g} resolved | {g} turns | {g} tool calls | {g} tokens in/out | {g} s | {g} end | {g} fetch"
        for g in groups) + " |")
    out.append("|" + "---|" * (3 + 7 * len(groups)))
    for n, i in enumerate(ids, 1):
        cells = [str(n), f"`{i}`", next((res[g][i]["repo"] for g in groups if i in res[g]), "?")]
        for g in groups:
            r = res[g].get(i)
            if not r:
                cells += ["-"] * 7
                continue
            ok = "?" if resolved[g] is None else ("**yes**" if i in resolved[g] else "no")
            cells += [ok, str(r["turns"]), str(r["tool_calls"]), f"{r['input'] / 1000:.0f}k/{r['output'] / 1000:.1f}k",
                      f"{r['seconds']:.0f}", r["reason"], str(r["fetch"] or "")]
        out.append("| " + " | ".join(cells) + " |")
    summary = {}
    out.append("")
    out.append("| group | resolved | rate | median turns | median tool calls | median tokens in | median tokens out "
               "| median s | total tokens in | total tokens out | empty patch | runs touching test files |")
    out.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for g in groups:
        rs = list(res[g].values())
        if not rs:
            continue
        med = lambda k: statistics.median(r[k] for r in rs)
        nres = len(resolved[g]) if resolved[g] is not None else None
        s = {"runs": len(rs), "resolved": nres, "median_turns": med("turns"), "median_tool_calls": med("tool_calls"),
             "median_input": med("input"), "median_output": med("output"), "median_seconds": med("seconds"),
             "total_input": sum(r["input"] for r in rs), "total_cache_read": sum(r["cache_read"] for r in rs),
             "total_output": sum(r["output"] for r in rs),
             "empty_patch": sum(1 for r in rs if not r["files_changed"]),
             "touched_tests": sum(1 for r in rs if r["test_files_changed"]),
             "reasons": dict(collections.Counter(r["reason"] for r in rs)),
             "tools": dict(sum((collections.Counter(r["tools"]) for r in rs), collections.Counter())),
             "sub_turns": sum(r["sub_turns"] for r in rs),
             "fetch_runs": sum(1 for r in rs if r["fetch"]),
             "harness": {k: reports[g].get(k) for k in ("submitted_instances", "completed_instances",
                                                         "resolved_instances", "unresolved_instances",
                                                         "empty_patch_instances", "error_instances")}}
        summary[g] = s
        rate = f"{nres}/{len(ids)} ({100 * nres / len(ids):.1f}%)" if nres is not None else "?"
        out.append(f"| {g} | {nres} | {rate} | {s['median_turns']} | {s['median_tool_calls']} | "
                   f"{s['median_input']:.0f} | {s['median_output']:.0f} | {s['median_seconds']:.0f} | "
                   f"{s['total_input']} | {s['total_output']} | {s['empty_patch']} | {s['touched_tests']} |")
    if all(resolved[g] is not None for g in groups) and len(groups) == 2:
        a, b = groups
        only_a = sorted(set(ids) & resolved[a] - resolved[b])
        only_b = sorted(set(ids) & resolved[b] - resolved[a])
        summary["discordant"] = {f"only_{a}": only_a, f"only_{b}": only_b}
        out.append("")
        out.append(f"discordant pairs: only {a} = {len(only_a)} {only_a}; only {b} = {len(only_b)} {only_b}")
        clean = [i for i in ids if not any(res[g].get(i, {}).get("fetch") for g in groups)]
        summary["without_fetch"] = {"instances": len(clean),
                                    **{g: len(set(clean) & resolved[g]) for g in groups}}
        out.append(f"instances where no run fetched packages/code: {len(clean)}; resolved there: "
                   + ", ".join(f"{g} {len(set(clean) & resolved[g])}/{len(clean)}" for g in groups))
    out.append("")
    for g in groups:
        if g in summary:
            out.append(f"{g} end reasons: {summary[g]['reasons']}; tool calls by name: {summary[g]['tools']}; "
                       f"runs fetching packages/code: {summary[g]['fetch_runs']}; sub-agent turns: "
                       f"{summary[g]['sub_turns']}")
    text = "\n".join(out) + "\n"
    Path(work / "report.md").write_text(text, encoding="utf-8")
    Path(work / "summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print(text)


def main():
    here = Path(__file__).resolve().parent
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--work", default=r"D:\PaiCLI\tmp\eval")
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--instances", default=str(here / "instances.txt"))
    common.add_argument("--groups", default="bare,full")
    common.add_argument("--only", help="comma-separated instance ids instead of the list")
    sub = p.add_subparsers(dest="cmd", required=True)
    add = lambda name: sub.add_parser(name, parents=[common])
    s = add("sample")
    s.add_argument("--seed", type=int, default=DEFAULT_SEED)
    s.add_argument("--n", type=int, default=DEFAULT_N)
    s.add_argument("--out", default=str(here / "instances.txt"))
    r = add("run")
    r.add_argument("--jar", required=True, help=r"fixed copy of the evaluated build, e.g. D:\PaiCLI\tmp\eval\pai-cli-<commit>.jar")
    r.add_argument("--workers", type=int, default=2)
    r.add_argument("--force", action="store_true", help="rerun instances that already have a result")
    r.add_argument("--jre", default=r"D:\PaiCLI\tmp\eval\jre", help="Linux x64 JRE 21, mounted read-only")
    r.add_argument("--provider", default=PROVIDER, help="'fake' for a plumbing smoke test without key or model")
    add("preds")
    e = add("evaluate")
    e.add_argument("--max-workers", type=int, default=2)
    e.add_argument("--run-id")
    e.add_argument("--preds", help="predictions path inside the harness container (default /eval/preds/<group>.jsonl)")
    rp = add("report")
    rp.add_argument("--run-prefix", default="", help="harness run id = prefix + group")
    args = p.parse_args()
    {"sample": cmd_sample, "run": cmd_run, "preds": cmd_preds, "evaluate": cmd_evaluate,
     "report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
