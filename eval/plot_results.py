#!/usr/bin/env python3
"""Plot PathShala evaluation results (CSV produced by `./gradlew :core:run`).
Palette: validated categorical slots (CVD-checked); identity also carried by markers/labels."""
import csv, os, collections
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

R = os.path.join(os.path.dirname(__file__), "results")
FIG = os.path.join(os.path.dirname(__file__), "figures"); os.makedirs(FIG, exist_ok=True)
C = {"graph_cat": "#2a78d6", "indep_cat": "#eb6834", "graph_random": "#1baf7a", "fixed_form": "#eda100"}
M = {"graph_cat": "o", "indep_cat": "s", "graph_random": "^", "fixed_form": "D"}
NAMES = {"graph_cat": "Graph-CAT (PathShala)", "indep_cat": "CAT, no prerequisite graph", "graph_random": "Graph model, random questions", "fixed_form": "Fixed placement test"}
PACK = {"jee_mechanics": "JEE Mechanics", "python_ds": "Python for DS", "gate_cheme": "GATE Chemical"}
plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True, "grid.color": "#e6e6e3", "grid.linewidth": 0.8, "axes.edgecolor": "#888"})

# ---------------- Figure 1: diagnostic accuracy vs questions
fig, axes = plt.subplots(1, 3, figsize=(12, 3.8), sharey=True)
summary = []
for ax, pid in zip(axes, PACK):
    rows = list(csv.DictReader(open(os.path.join(R, f"diagnostic_{pid}.csv"))))
    by = collections.defaultdict(list)
    for r in rows: by[r["strategy"]].append((int(r["questions"]), float(r["accuracy"]), float(r["brier"])))
    for s in ["fixed_form", "graph_random", "indep_cat", "graph_cat"]:
        pts = sorted(by[s]); xs = [p[0] for p in pts]; ys = [100 * p[1] for p in pts]
        ax.plot(xs, ys, color=C[s], lw=2, marker=M[s], ms=4, markevery=2, label=NAMES[s], zorder=3 if s == "graph_cat" else 2)
        summary.append((pid, s, dict((p[0], p) for p in pts)))
    ax.set_title(PACK[pid]); ax.set_xlabel("Questions asked"); ax.set_xlim(0, 20); ax.set_ylim(50, 95); ax.set_xticks(range(0, 21, 4))
axes[0].set_ylabel("Skills classified correctly (%)")
axes[0].legend(loc="lower right", fontsize=8, frameon=False)
fig.suptitle("E1 · Diagnostic efficiency on 500 model-mismatched synthetic learners per pack", fontsize=11)
fig.tight_layout(); fig.savefig(os.path.join(FIG, "fig1_diagnostic_efficiency.png"), dpi=200); plt.close(fig)

# questions needed for Graph-CAT to match the fixed test's 12-question accuracy
lines = ["| Pack | Fixed test @12 q | Graph-CAT prior only (0 q) | Graph-CAT @6 q | Graph-CAT @12 q | Q for Graph-CAT to match fixed@12 | Brier @12 (Graph-CAT / fixed) |", "|---|---|---|---|---|---|---|"]
for pid in PACK:
    d = {s: m for (p, s, m) in summary if p == pid}
    target = d["fixed_form"][12][1]
    q = next(k for k in range(21) if d["graph_cat"][k][1] >= target)
    lines.append(f"| {PACK[pid]} | {100*target:.1f}% | {100*d['graph_cat'][0][1]:.1f}% | {100*d['graph_cat'][6][1]:.1f}% | {100*d['graph_cat'][12][1]:.1f}% | {q} ({100*(1-q/12):.0f}% fewer) | {d['graph_cat'][12][2]:.3f} / {d['fixed_form'][12][2]:.3f} |")

# ---------------- Figure 2: planning readiness
rows = list(csv.DictReader(open(os.path.join(R, "planning.csv"))))
PC = {"linear_syllabus": "#eda100", "diagnostic_linear": "#eb6834", "pathshala": "#2a78d6"}
PN = {"linear_syllabus": "Linear syllabus", "diagnostic_linear": "Diagnose, then linear (no reviews)", "pathshala": "PathShala adaptive planner"}
fig, axes = plt.subplots(1, 2, figsize=(11, 3.8), sharey=True)
tbl = ["| Pack | Budget | Linear syllabus | Diagnose + linear | PathShala | Gain vs linear | Re-studied known material (linear → PathShala) |", "|---|---|---|---|---|---|---|"]
for ax, hw in zip(axes, ["5.0", "10.0"]):
    sub = [r for r in rows if r["hours_per_week"] == hw]
    x = range(len(PACK)); w = 0.26
    for i, pol in enumerate(["linear_syllabus", "diagnostic_linear", "pathshala"]):
        vals = [100 * float(next(r for r in sub if r["pack"] == pid and r["policy"] == pol)["readiness"]) for pid in PACK]
        bars = ax.bar([xi + (i - 1) * (w + 0.02) for xi in x], vals, w, color=PC[pol], label=PN[pol])
        for b, v in zip(bars, vals): ax.text(b.get_x() + b.get_width() / 2, v + 1, f"{v:.0f}", ha="center", fontsize=8, color="#333")
    ax.set_xticks(list(x)); ax.set_xticklabels(PACK.values()); ax.grid(axis="x", visible=False); ax.set_title(f"{hw.split('.')[0]} h/week, 8-week deadline")
    for pid in PACK:
        g = {r["policy"]: r for r in sub if r["pack"] == pid}
        lin, dl, ps = (float(g[k]["readiness"]) for k in ["linear_syllabus", "diagnostic_linear", "pathshala"])
        tbl.append(f"| {PACK[pid]} | {hw.split('.')[0]} h/wk | {100*lin:.1f}% | {100*dl:.1f}% | {100*ps:.1f}% | {100*(ps/lin-1):+.0f}% | {100*float(g['linear_syllabus']['wasted_share']):.0f}% → {100*float(g['pathshala']['wasted_share']):.0f}% |")
axes[0].set_ylabel("Exam-day readiness (%)\n(weighted retained mastery)"); axes[0].set_ylim(0, 80)
axes[0].legend(loc="upper left", fontsize=8, frameon=False)
fig.suptitle("E3 · Planning on 200 synthetic learners per setting (same learners, same study time for every policy)", fontsize=11)
fig.tight_layout(); fig.savefig(os.path.join(FIG, "fig2_planning_readiness.png"), dpi=200); plt.close(fig)

stops = "".join(open(os.path.join(R, f"stop_{p}.txt")).read() for p in PACK)
with open(os.path.join(os.path.dirname(__file__), "RESULTS.md"), "w") as f:
    f.write("# Evaluation results (auto-generated by eval/plot_results.py)\n\n## E1 Diagnostic efficiency\n\n" + "\n".join(lines) +
            "\n\n![E1](figures/fig1_diagnostic_efficiency.png)\n\n## E2 Self-stopping Graph-CAT (max 12 questions)\n\n```\n" + stops +
            "```\n\n## E3 Planning\n\n" + "\n".join(tbl) + "\n\n![E3](figures/fig2_planning_readiness.png)\n")
print("\n".join(lines)); print(stops); print("\n".join(tbl))
