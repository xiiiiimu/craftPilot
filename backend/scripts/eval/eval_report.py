#!/usr/bin/env python3
"""Summarise a CraftPilot evaluation run into a Markdown report.

Reads the files written by EvalRunnerTest / OpinionExtractorEvalTest (eval-meta.json, eval-runs.csv,
eval-turns.csv, eval-opinions.csv) from one output directory and writes report.md next to them.

    python scripts/eval/eval_report.py target/eval/latest
    python scripts/eval/eval_report.py target/eval/after --baseline target/eval/before -o docs/evaluation/x.md

Standard library only. Percentiles use linear interpolation between closest ranks (numpy's default).
"""
import argparse
import csv
import json
import statistics
import sys
from collections import Counter, OrderedDict, defaultdict
from pathlib import Path

RECOMMENDATIONS = ["PRODUCE", "REVISE", "TRIAL", "HOLD"]
SKIPPED_BUDGET = "SKIPPED_BUDGET"
POLARITIES = ["POSITIVE", "NEGATIVE", "NEUTRAL"]


# --------------------------------------------------------------------------- loading

def read_csv(path):
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def load(directory):
    directory = Path(directory)
    meta_path = directory / "eval-meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
    all_runs = read_csv(directory / "eval-runs.csv")
    return {
        "dir": directory,
        "meta": meta,
        # Runs skipped by the cost cap never started: keep them out of every metric, report them separately.
        "runs": [r for r in all_runs if r["status"] != SKIPPED_BUDGET],
        "skipped": [r for r in all_runs if r["status"] == SKIPPED_BUDGET],
        "turns": read_csv(directory / "eval-turns.csv"),
        "opinions": read_csv(directory / "eval-opinions.csv"),
    }


# --------------------------------------------------------------------------- helpers

def num(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def percentile(values, q):
    values = sorted(values)
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    pos = (len(values) - 1) * q
    lower = int(pos)
    upper = min(lower + 1, len(values) - 1)
    return values[lower] + (values[upper] - values[lower]) * (pos - lower)


def mean(values):
    return statistics.fmean(values) if values else None


def ratio(numerator, denominator):
    return None if not denominator else numerator / denominator


def fmt(value, digits=1, pct=False, money=False):
    if value is None:
        return "n/a"
    if pct:
        return f"{value * 100:.{digits}f}%"
    if money:
        return f"${value:.6f}"
    if isinstance(value, float) and not value.is_integer():
        return f"{value:.{digits}f}"
    return f"{int(value)}" if isinstance(value, (int, float)) else str(value)


def table(header, rows):
    out = ["| " + " | ".join(header) + " |", "| " + " | ".join("---" for _ in header) + " |"]
    for row in rows:
        out.append("| " + " | ".join(str(cell) for cell in row) + " |")
    return "\n".join(out)


# --------------------------------------------------------------------------- metrics

def quality(runs):
    research = [r for r in runs if r["kind"] == "research"]
    succeeded = [r for r in research if r["status"] == "SUCCEEDED"]
    cited = sum(num(r["cited_count"]) for r in succeeded)
    valid = sum(num(r["cited_valid_count"]) for r in succeeded)
    key_total = sum(num(r["key_evidence_total"]) for r in research)
    key_hit = sum(num(r["key_evidence_cited"]) for r in research)
    return OrderedDict([
        ("recommendation_accuracy", ratio(sum(r["recommendation_correct"] == "1" for r in research), len(research))),
        ("recommendation_acceptable", ratio(sum(r["recommendation_acceptable"] == "1" for r in research), len(research))),
        ("citation_validity", ratio(valid, cited)),
        ("key_evidence_recall", ratio(key_hit, key_total)),
        ("must_not_cite_hits", sum(num(r["must_not_cite_hits"]) for r in research)),
        ("cross_revision_citations", sum(num(r["cross_revision_citations"]) for r in research)),
        ("stability", stability(research)),
    ])


def stability(research):
    by_case = defaultdict(list)
    for r in research:
        by_case[r["case_id"]].append(r["predicted_recommendation"] or r["status"])
    shares = [Counter(v).most_common(1)[0][1] / len(v) for v in by_case.values() if len(v) > 1]
    return mean(shares)


def efficiency(runs, turns):
    tool_calls = sum(num(r["tool_calls"]) for r in runs)
    invalid = sum(num(r["failed_tool_calls"]) + num(r["duplicate_tool_calls"]) for r in runs)
    turn_model = [num(t["model_ms"]) for t in turns if t["model_ms"] not in ("", None)]
    turn_context = [num(t["context_ms"]) for t in turns if t["context_ms"] not in ("", None)]
    e2e = [num(r["e2e_ms"]) for r in runs]
    overhead = [num(r["overhead_ms"]) for r in runs]
    return OrderedDict([
        ("turns_mean", mean([num(r["turns"]) for r in runs])),
        ("turns_max", max([num(r["turns"]) for r in runs], default=None)),
        ("tool_calls_mean", mean([num(r["tool_calls"]) for r in runs])),
        ("invalid_tool_call_rate", ratio(invalid, tool_calls)),
        ("e2e_ms_p50", percentile(e2e, 0.5)),
        ("e2e_ms_p95", percentile(e2e, 0.95)),
        ("turn_model_ms_p50", percentile(turn_model, 0.5)),
        ("turn_model_ms_p95", percentile(turn_model, 0.95)),
        ("turn_context_ms_p50", percentile(turn_context, 0.5)),
        ("turn_context_ms_p95", percentile(turn_context, 0.95)),
        ("tool_ms_per_run_p50", percentile([num(r["tool_ms"]) for r in runs], 0.5)),
        ("overhead_ms_p50", percentile(overhead, 0.5)),
        ("overhead_ms_p95", percentile(overhead, 0.95)),
    ])


def cost(runs):
    costs = [num(r["cost_usd"]) for r in runs]
    used = sum(num(r["tokens_in"]) + num(r["tokens_out"]) for r in runs)
    reserved = sum(num(r["reserved_tokens"]) for r in runs)
    return OrderedDict([
        ("tokens_in_mean", mean([num(r["tokens_in"]) for r in runs])),
        ("tokens_out_mean", mean([num(r["tokens_out"]) for r in runs])),
        ("cost_usd_p50", percentile(costs, 0.5)),
        ("cost_usd_p95", percentile(costs, 0.95)),
        ("cost_usd_total", sum(costs)),
        ("reservation_accuracy", ratio(used, reserved)),
        ("estimated_usage_calls", sum(num(r["estimated_usage_calls"]) for r in runs)),
    ])


def reliability(runs):
    return OrderedDict([
        ("success_rate", ratio(sum(r["status"] == "SUCCEEDED" for r in runs), len(runs))),
        ("research_success_rate", ratio(
            sum(r["status"] == "SUCCEEDED" for r in runs if r["kind"] == "research"),
            sum(r["kind"] == "research" for r in runs))),
        ("runs_with_lease_epoch_gt_1", sum(num(r["lease_epoch"]) > 1 for r in runs)),
        ("held_reservations", sum(num(r["held_reservations"]) for r in runs)),
    ])


def safety(runs):
    adversarial = [r for r in runs if r["kind"] == "adversarial"]
    return OrderedDict([
        ("adversarial_pass_rate", ratio(sum(r["adversarial_pass"] == "1" for r in adversarial), len(adversarial))),
        ("foreign_citations", sum(num(r["foreign_citations"]) for r in runs)),
    ])


def opinion_metrics(rows):
    """Per-label precision/recall/F1 over polarity:attribute labels, macro-F1 over labels present in gold."""
    tp, fp, fn = Counter(), Counter(), Counter()
    confusion = defaultdict(Counter)
    for row in rows:
        gold = set(filter(None, row["gold"].split(";")))
        pred = set(filter(None, row["predicted"].split(";")))
        for label in gold & pred:
            tp[label] += 1
        for label in pred - gold:
            fp[label] += 1
        for label in gold - pred:
            fn[label] += 1
        pred_by_attr = defaultdict(set)
        for label in pred:
            polarity, _, attribute = label.partition(":")
            pred_by_attr[attribute].add(polarity)
        for label in gold:
            polarity, _, attribute = label.partition(":")
            predicted = pred_by_attr.get(attribute)
            if not predicted:
                confusion[polarity]["MISSING"] += 1
            elif polarity in predicted:
                confusion[polarity][polarity] += 1
            else:
                confusion[polarity][sorted(predicted)[0]] += 1
    gold_labels = sorted({label for row in rows for label in filter(None, row["gold"].split(";"))})
    all_labels = sorted(set(gold_labels) | set(fp))
    per_label = []
    f1s = []
    for label in all_labels:
        precision = ratio(tp[label], tp[label] + fp[label])
        recall = ratio(tp[label], tp[label] + fn[label])
        f1 = None
        if precision is not None and recall is not None and precision + recall > 0:
            f1 = 2 * precision * recall / (precision + recall)
        elif label in gold_labels:
            f1 = 0.0
        if label in gold_labels:
            f1s.append(f1 or 0.0)
        per_label.append((label, tp[label] + fn[label], precision, recall, f1))
    return {"macro_f1": mean(f1s), "per_label": per_label, "confusion": confusion, "n": len(rows)}


# --------------------------------------------------------------------------- rendering

LABELS = {
    "recommendation_accuracy": ("建议正确率", "pct"),
    "recommendation_acceptable": ("建议在可接受范围内的比例", "pct"),
    "citation_validity": ("引用有效率（成功的 Run）", "pct"),
    "key_evidence_recall": ("关键证据召回率", "pct"),
    "must_not_cite_hits": ("引用了禁止引用证据的次数", "int"),
    "cross_revision_citations": ("跨版本引用次数", "int"),
    "stability": ("稳定性（同一案例建议一致的比例）", "pct"),
    "turns_mean": ("每个 Run 的平均轮数", "num"),
    "turns_max": ("最多轮数", "int"),
    "tool_calls_mean": ("每个 Run 的平均工具调用数", "num"),
    "invalid_tool_call_rate": ("无效工具调用率（失败 + 同参数重复）", "pct"),
    "e2e_ms_p50": ("端到端耗时 p50 (ms)", "num"),
    "e2e_ms_p95": ("端到端耗时 p95 (ms)", "num"),
    "turn_model_ms_p50": ("单轮模型耗时 p50 (ms)", "num"),
    "turn_model_ms_p95": ("单轮模型耗时 p95 (ms)", "num"),
    "turn_context_ms_p50": ("单轮上下文构建 p50 (ms)", "num"),
    "turn_context_ms_p95": ("单轮上下文构建 p95 (ms)", "num"),
    "tool_ms_per_run_p50": ("每个 Run 的工具耗时 p50 (ms)", "num"),
    "overhead_ms_p50": ("系统自身开销 p50 (ms)", "num"),
    "overhead_ms_p95": ("系统自身开销 p95 (ms)", "num"),
    "tokens_in_mean": ("每个 Run 的输入 token（均值）", "num"),
    "tokens_out_mean": ("每个 Run 的输出 token（均值）", "num"),
    "cost_usd_p50": ("每个 Run 的花费 p50", "money"),
    "cost_usd_p95": ("每个 Run 的花费 p95", "money"),
    "cost_usd_total": ("本次总花费", "money"),
    "reservation_accuracy": ("预留准确度（实际 ÷ 预留）", "pct"),
    "estimated_usage_calls": ("用量为估算值的模型调用数", "int"),
    "success_rate": ("Run 成功率（全部）", "pct"),
    "research_success_rate": ("Run 成功率（调研案例）", "pct"),
    "runs_with_lease_epoch_gt_1": ("lease_epoch > 1 的 Run 数", "int"),
    "held_reservations": ("仍为 HELD 的预留", "int"),
    "adversarial_pass_rate": ("对抗案例通过率", "pct"),
    "foreign_citations": ("引用其他商家证据的次数（必须为 0）", "int"),
}


def render_value(key, value):
    kind = LABELS.get(key, (key, "num"))[1]
    if kind == "pct":
        return fmt(value, pct=True)
    if kind == "money":
        return fmt(value, money=True)
    if kind == "int":
        return fmt(value, digits=0)
    return fmt(value)


def metric_table(metrics, baseline=None):
    if baseline is None:
        return table(["指标", "值"], [(LABELS.get(k, (k,))[0], render_value(k, v)) for k, v in metrics.items()])
    rows = []
    for key, value in metrics.items():
        base = baseline.get(key)
        delta = "n/a"
        if isinstance(value, (int, float)) and isinstance(base, (int, float)):
            diff = value - base
            kind = LABELS.get(key, (key, "num"))[1]
            delta = f"{diff * 100:+.1f} pp" if kind == "pct" else f"{diff:+.6f}" if kind == "money" else f"{diff:+.1f}"
        rows.append((LABELS.get(key, (key,))[0], render_value(key, base), render_value(key, value), delta))
    return table(["指标", "基线", "本次", "变化"], rows)


def sections(data):
    runs, turns = data["runs"], data["turns"]
    out = OrderedDict()
    out["quality"] = quality(runs)
    out["efficiency"] = efficiency(runs, turns)
    out["cost"] = cost(runs)
    out["reliability"] = reliability(runs)
    out["safety"] = safety(runs)
    return out


def header(meta, runs, skipped=()):
    model = meta.get("model", {})
    spend = meta.get("spend", {})
    prompt = meta.get("prompt", {})
    git = meta.get("git", {})
    machine = meta.get("machine", {})
    datasets = meta.get("datasets", {})
    lines = [
        f"- 模式：{meta.get('mode', 'unknown')}",
        "- 数据集：" + "，".join(f"{k} {v}" for k, v in sorted(datasets.items())),
        f"- 提示词：{prompt.get('id', '?')} v{prompt.get('version', '?')}（{prompt.get('content_hash', '?')}）",
        f"- 模型：{model.get('name', '?')}，温度 {model.get('temperature', '?')}，单价 输入 ${model.get('inputUsdPerMtok', '?')} / "
        f"输出 ${model.get('outputUsdPerMtok', '?')} 每百万 token",
        f"- research 预算：{meta.get('researchTokenBudget', '?')} token；每个案例重复 {meta.get('repeats', '?')} 次；共 {len(runs)} 个 Run",
        f"- git：{git.get('commit', '?')}{'（工作区有未提交改动）' if git.get('dirty') else ''}",
        f"- 机器：{machine.get('os', '?')} {machine.get('arch', '')}，{machine.get('cpus', '?')} 核，"
        f"堆上限 {machine.get('maxHeapMb', '?')} MB，Java {machine.get('java', '?')}",
        f"- 时间：{meta.get('startedAt', '?')} → {meta.get('finishedAt', '?')}",
        f"- 本次总花费：{fmt(sum(num(r['cost_usd']) for r in runs), money=True)}",
    ]
    if spend:
        enforced = "生效" if spend.get("enforced") else "仅记录，桩模型不花钱"
        lines.append(f"- 花费上限：${spend.get('capUsd', '?')}（{enforced}）；单个 Run 最坏 ${spend.get('perRunCeilingUsd', '?')}，"
                     f"计划 {spend.get('plannedRuns', '?')} 个 Run，最坏合计 ${spend.get('worstCaseUsd', '?')}")
    if skipped:
        ids = "、".join(sorted({r["case_id"] for r in skipped}))
        lines.append(f"- **因花费上限跳过 {len(skipped)} 个 Run**（{ids}），不计入下面任何指标")
    return "\n".join(lines)


def confusion_table(runs):
    research = [r for r in runs if r["kind"] == "research"]
    predicted_labels = RECOMMENDATIONS + ["(无输出)"]
    counts = defaultdict(Counter)
    for r in research:
        counts[r["expected_recommendation"]][r["predicted_recommendation"] or "(无输出)"] += 1
    rows = [[f"**{exp}**"] + [counts[exp][p] for p in predicted_labels] for exp in RECOMMENDATIONS]
    return table(["标注 \\ 预测"] + predicted_labels, rows)


def scenario_table(runs):
    groups = OrderedDict()
    for r in runs:
        if r["kind"] == "research":
            groups.setdefault(r["scenario"], []).append(r)
    rows = []
    for scenario, items in groups.items():
        rows.append((scenario, len(items),
                     fmt(ratio(sum(i["recommendation_correct"] == "1" for i in items), len(items)), pct=True),
                     fmt(ratio(sum(num(i["key_evidence_cited"]) for i in items),
                               sum(num(i["key_evidence_total"]) for i in items)), pct=True)))
    return table(["场景", "Run 数", "建议正确率", "关键证据召回率"], rows)


def failure_table(runs):
    reasons = Counter((r["terminal_reason"] or "(无)") for r in runs if r["status"] != "SUCCEEDED")
    if not reasons:
        return "没有失败的 Run。"
    return table(["失败原因", "次数"], sorted(reasons.items(), key=lambda kv: -kv[1]))


def turn_token_table(turns):
    by_turn = defaultdict(list)
    for t in turns:
        if t["input_tokens"] not in ("", None):
            by_turn[int(num(t["turn_no"]))].append(num(t["input_tokens"]))
    rows = [(n, len(v), fmt(mean(v))) for n, v in sorted(by_turn.items())]
    return table(["轮次", "样本数", "平均输入 token"], rows) if rows else "无轮次数据。"


def adversarial_table(runs):
    rows = [(r["case_id"], r["scenario"], r["status"], r["terminal_reason"] or "-",
             r["predicted_recommendation"] or "-", "通过" if r["adversarial_pass"] == "1" else "**未通过**")
            for r in runs if r["kind"] == "adversarial"]
    return table(["案例", "攻击类型", "状态", "终止原因", "建议", "结果"], rows) if rows else "无对抗案例。"


def opinion_section(rows):
    if not rows:
        return "没有 eval-opinions.csv，跳过。"
    parts = []
    for title, subset in (("全部", rows), ("测试集（split=test）", [r for r in rows if r["split"] == "test"])):
        metrics = opinion_metrics(subset)
        parts.append(f"**{title}**：{metrics['n']} 条，macro-F1 = {fmt(metrics['macro_f1'], digits=3)}")
    metrics = opinion_metrics(rows)
    parts.append("")
    parts.append(table(["标签（极性:属性）", "标注数", "精确率", "召回率", "F1"],
                       [(label, support, fmt(p, pct=True), fmt(r, pct=True), fmt(f, digits=3))
                        for label, support, p, r, f in metrics["per_label"]]))
    parts.append("")
    parts.append("极性混淆矩阵（按标注标签统计；同一属性下没有预测记为 MISSING）：")
    parts.append("")
    columns = POLARITIES + ["MISSING"]
    parts.append(table(["标注 \\ 预测"] + columns,
                       [[f"**{g}**"] + [metrics["confusion"][g][c] for c in columns] for g in POLARITIES]))
    return "\n".join(parts)


def render(data, baseline=None):
    meta, runs, turns = data["meta"], data["runs"], data["turns"]
    current = sections(data)
    base = sections(baseline) if baseline else {}
    title_suffix = "（离线桩模型：质量数字只验证流程，不代表模型效果）" if meta.get("mode") == "offline-stub" else ""
    out = [f"# CraftPilot 评测报告{title_suffix}", "", header(meta, runs, data.get("skipped", ())), ""]
    if baseline:
        out += [f"对比基线：`{baseline['dir']}`（git {baseline['meta'].get('git', {}).get('commit', '?')}）", ""]
    names = OrderedDict([("quality", "1. 质量"), ("efficiency", "2. 效率"), ("cost", "3. 成本"),
                         ("reliability", "4. 可靠性"), ("safety", "5. 安全")])
    for key, title in names.items():
        out += [f"## {title}", "", metric_table(current[key], base.get(key) if baseline else None), ""]
        if key == "quality":
            out += ["建议混淆矩阵（调研案例）：", "", confusion_table(runs), "", "按场景：", "", scenario_table(runs), ""]
        if key == "cost":
            out += ["输入 token 随轮次的变化：", "", turn_token_table(turns), ""]
        if key == "reliability":
            out += [failure_table(runs), ""]
        if key == "safety":
            out += [adversarial_table(runs), ""]
    out += ["## 6. 观点提取（组件评测）", "", opinion_section(data["opinions"]), ""]
    out += ["## 说明", "",
            "- 端到端耗时 = Run 创建到终态；系统自身开销 = 端到端 − 模型 − 工具 − 上下文构建。",
            "- lease_epoch 在 HITL resume 和取消时也会 +1，大于 1 不一定是租约换手。",
            "- 限流重试次数暂未采集：网关在同一条 model_call_attempt 上重试，最终状态会覆盖 429。",
            ""]
    return "\n".join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("run_dir", help="directory containing eval-runs.csv and eval-meta.json")
    parser.add_argument("--baseline", help="earlier run directory to compare against")
    parser.add_argument("-o", "--output", help="report path (default: <run_dir>/report.md)")
    args = parser.parse_args(argv)

    data = load(args.run_dir)
    if not data["runs"]:
        print(f"no completed runs in {args.run_dir}/eval-runs.csv", file=sys.stderr)
        return 1
    baseline = load(args.baseline) if args.baseline else None
    output = Path(args.output) if args.output else Path(args.run_dir) / "report.md"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(data, baseline), encoding="utf-8")
    print(f"wrote {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
