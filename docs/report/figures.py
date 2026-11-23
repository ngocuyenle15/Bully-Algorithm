"""Sinh hình minh họa cho báo cáo (matplotlib). Gọi make_all(out_dir)."""
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Circle

plt.rcParams["font.family"] = "Arial"
plt.rcParams["font.size"] = 10

INK = "#1f2933"
BLUE = "#2563eb"
GREEN = "#15803d"
RED = "#b91c1c"
GREY = "#6b7280"
ORANGE = "#c2410c"


def _box(ax, x, y, w, h, text, fc="#eef2ff", ec=BLUE, fs=9, bold=False):
    ax.add_patch(FancyBboxPatch((x - w / 2, y - h / 2), w, h, boxstyle="round,pad=0.02,rounding_size=0.08",
                                fc=fc, ec=ec, lw=1.4))
    ax.text(x, y, text, ha="center", va="center", fontsize=fs, color=INK, weight="bold" if bold else "normal")


def _arrow(ax, x1, y1, x2, y2, color=INK, text=None, style="-|>", ls="-", fs=8, offset=0.12, lw=1.2):
    ax.annotate("", xy=(x2, y2), xytext=(x1, y1),
                arrowprops=dict(arrowstyle=style, color=color, lw=lw, ls=ls, shrinkA=0, shrinkB=0))
    if text:
        ax.text((x1 + x2) / 2, (y1 + y2) / 2 + offset, text, ha="center", va="bottom", fontsize=fs, color=color)


def topology(path):
    fig, ax = plt.subplots(figsize=(9.5, 5.2))
    ax.set_xlim(0, 10); ax.set_ylim(0, 6); ax.axis("off")
    ax.add_patch(FancyBboxPatch((0.2, 2.9), 9.6, 2.85, boxstyle="round,pad=0.02,rounding_size=0.15",
                                fc="#f8fafc", ec=GREY, lw=1, ls="--"))
    ax.text(0.4, 5.5, "LAN 192.168.0.0/24 — UDP multicast 239.255.42.99:8999 (discovery) + TCP :9000 (Bully, heartbeat)",
            fontsize=8.5, color=GREY)
    xs = [1.25, 3.1, 4.95, 6.8, 8.65]
    for i, x in enumerate(xs, start=1):
        coord = i == 5
        _box(ax, x, 4.35, 1.65, 1.5,
             f"Máy Node {i}\n192.168.0.10{i}\nserver-node ID={i}\n:9000  + client-{i}" + ("\nCOORDINATOR" if coord else ""),
             fc="#fef3c7" if coord else "#eef2ff", ec=ORANGE if coord else BLUE, fs=8.2, bold=coord)
    for a, b in zip(xs, xs[1:]):
        _arrow(ax, a + 0.83, 4.35, b - 0.83, 4.35, color=GREY, style="<|-|>")
    ax.text(8.65, 3.4, "Coordinator\n(ID lớn nhất)", ha="center", fontsize=8, color=ORANGE, weight="bold")
    _box(ax, 4.95, 1.0, 3.0, 1.1, "Máy Worker 192.168.0.100\ndocument-worker  gRPC :9100\n(lease, generation, session/token)",
         fc="#dcfce7", ec=GREEN, fs=8.5, bold=True)
    _arrow(ax, 8.65, 3.6, 6.2, 1.55, color=GREEN, text="CLAIM/RENEW/RESERVE/RELEASE", fs=7.5, offset=0.05)
    for x in xs[:4]:
        _arrow(ax, x, 3.6, 4.95 - (4.95 - x) * 0.25, 1.55, color=GREEN, ls=":", lw=0.9)
    ax.text(1.0, 2.2, "Mọi server: STATUS (kiểm tra\nlease, đủ điều kiện ứng cử)", fontsize=7.5, color=GREEN)
    ax.text(7.3, 2.2, "Client: PDF + session/token\n(gRPC trực tiếp tới Worker)", fontsize=7.5, color=GREEN)
    fig.tight_layout(); fig.savefig(path, dpi=200); plt.close(fig)


def _sequence(path, actors, messages, height, title=None, dead=None, width=9.5, notes=()):
    """messages: (from, to, label, color, dashed) ; from==to -> self note."""
    fig, ax = plt.subplots(figsize=(width, height))
    n = len(actors)
    xs = {a: 1 + i * (8 / max(1, n - 1)) for i, a in enumerate(actors)}
    top = len(messages) + 1.5
    ax.set_xlim(0, 10); ax.set_ylim(-0.5, top + 0.8); ax.axis("off")
    dead = dead or {}
    for a, x in xs.items():
        _box(ax, x, top, 1.5, 0.6, a, fc="#eef2ff", ec=BLUE, fs=9, bold=True)
        end = dead.get(a, -0.2)
        ax.plot([x, x], [top - 0.3, top - 0.3 - (top - 0.3 - end) if end > -0.2 else -0.2], color=GREY, lw=1, ls="--")
        if a in dead:
            ax.plot([x], [dead[a]], marker="x", color=RED, ms=14, mew=3)
            ax.text(x + 0.15, dead[a] - 0.1, "crash", color=RED, fontsize=8)
    y = top - 1
    for frm, to, label, color, dashed in messages:
        if frm == to:
            ax.text(xs[frm] + 0.12, y, label, fontsize=8, color=color, va="center",
                    bbox=dict(fc="white", ec=color, lw=0.8, boxstyle="round,pad=0.2"))
        else:
            _arrow(ax, xs[frm], y, xs[to], y, color=color, text=label, ls="--" if dashed else "-", offset=0.06)
        y -= 1
    for x, yy, text in notes:
        ax.text(x, yy, text, fontsize=8, color=GREY)
    if title:
        ax.set_title(title, fontsize=10)
    fig.tight_layout(); fig.savefig(path, dpi=200); plt.close(fig)


def bully_failover(path):
    msgs = [
        ("S1", "S1", "Heartbeat tới S5 lỗi 3 lần → coordinatorId = 0", RED, False),
        ("S1", "S2", "ELECTION", BLUE, False),
        ("S1", "S3", "ELECTION", BLUE, False),
        ("S2", "S1", "ELECTION_OK", GREEN, True),
        ("S3", "S4", "ELECTION", BLUE, False),
        ("S4", "S3", "ELECTION_OK", GREEN, True),
        ("S4", "S5", "ELECTION (gửi song song tới mọi ID lớn hơn)", BLUE, False),
        ("S4", "S4", "Không có ELECTION_OK sau timeout → chờ election-ms", ORANGE, False),
        ("S4", "S4", "CLAIM lease tại Worker → generation+1", GREEN, False),
        ("S4", "S1", "COORDINATOR(id=4, generation)", ORANGE, False),
        ("S4", "S3", "COORDINATOR", ORANGE, False),
    ]
    _sequence(path, ["S1", "S2", "S3", "S4", "S5"], msgs, 6.2, dead={"S5": 11.2})


def mutex_flow(path):
    msgs = [
        ("Client", "Coordinator", "ACCESS_REQUEST(clientId, requestId)", BLUE, False),
        ("Coordinator", "Worker", "STATUS(coordId, generation)  — fencing", GREEN, False),
        ("Coordinator", "Worker", "RESERVE(clientId, requestId)", GREEN, False),
        ("Worker", "Coordinator", "sessionId, accessToken", GREEN, True),
        ("Coordinator", "Client", "ACCESS_GRANTED(sessionId, token)", BLUE, True),
        ("Client", "Worker", "Merge/Split/Watermark(session, token)", INK, False),
        ("Worker", "Client", "PDF kết quả", INK, True),
        ("Client", "Coordinator", "ACCESS_RELEASE(session, token)", BLUE, False),
        ("Coordinator", "Worker", "RELEASE → cấp cho client kế tiếp (FIFO)", GREEN, False),
    ]
    _sequence(path, ["Client", "Coordinator", "Worker"], msgs, 5.6, width=9)


def state_machine(path):
    fig, ax = plt.subplots(figsize=(9.5, 4.6))
    ax.set_xlim(0, 10); ax.set_ylim(0, 5); ax.axis("off")
    S = {
        "DISCOVERING": (1.3, 2.6), "STANDBY": (4.0, 3.9), "ELECTING": (4.0, 1.3),
        "COORDINATOR": (7.8, 2.6), "WORKER_UNREACHABLE": (8.3, 4.5), "ID_CONFLICT": (1.3, 0.5),
    }
    colors = {"COORDINATOR": ("#fef3c7", ORANGE), "ID_CONFLICT": ("#fee2e2", RED), "WORKER_UNREACHABLE": ("#fee2e2", RED)}
    for name, (x, y) in S.items():
        fc, ec = colors.get(name, ("#eef2ff", BLUE))
        _box(ax, x, y, 2.1 if len(name) < 12 else 2.6, 0.62, name, fc=fc, ec=ec, fs=8.5, bold=True)
    t = lambda x, y, s, c=INK: ax.text(x, y, s, fontsize=7.5, color=c, ha="left", va="center")
    _arrow(ax, 2.35, 2.4, 3.1, 1.62); t(1.55, 1.75, "hết warm-up,\nchưa có coordinator")
    _arrow(ax, 3.6, 1.62, 3.6, 3.58); ax.text(3.5, 3.25, "ELECTION_OK +\nCOORDINATOR hợp lệ", fontsize=7.5, ha="right", va="center")
    _arrow(ax, 4.4, 3.58, 4.4, 1.62, color=RED); t(4.5, 2.6, "heartbeat lỗi\n≥ heartbeat-failures", RED)
    _arrow(ax, 5.05, 1.3, 6.7, 2.35, color=GREEN); t(5.9, 1.45, "không có OK +\nCLAIM thành công", GREEN)
    _arrow(ax, 6.7, 2.9, 5.05, 3.85, color=RED); t(6.2, 3.75, "ELECTION_OK từ ID lớn hơn\n→ DROP lease", RED)
    _arrow(ax, 8.0, 2.92, 8.2, 4.18, color=RED, ls=":"); t(8.25, 3.45, "mất Worker", RED)
    _arrow(ax, 1.3, 2.28, 1.3, 0.82, color=RED, ls=":"); t(0.05, 1.55, "trùng ID", RED)
    fig.tight_layout(); fig.savefig(path, dpi=200); plt.close(fig)


def ring(path):
    import math
    fig, ax = plt.subplots(figsize=(4.6, 4.2))
    ax.set_xlim(-1.6, 1.6); ax.set_ylim(-1.5, 1.5); ax.axis("off"); ax.set_aspect("equal")
    ids = [1, 4, 2, 5, 3, 6]
    pts = []
    for k, i in enumerate(ids):
        a = math.pi / 2 - 2 * math.pi * k / len(ids)
        pts.append((math.cos(a), math.sin(a)))
    for k, (x, y) in enumerate(pts):
        nx, ny = pts[(k + 1) % len(pts)]
        _arrow(ax, x * 0.88 + nx * 0.12, y * 0.88 + ny * 0.12, x * 0.12 + nx * 0.88, y * 0.12 + ny * 0.88, color=GREY)
        dead = ids[k] == 6
        ax.add_patch(Circle((x, y), 0.2, fc="#fee2e2" if dead else "#eef2ff", ec=RED if dead else BLUE, lw=1.4))
        ax.text(x, y, str(ids[k]), ha="center", va="center", weight="bold")
    ax.text(0, 0.05, "ELECTION[2,5,3,1,4]\nđi một vòng\n→ COORDINATOR(5)", ha="center", va="center", fontsize=8)
    fig.tight_layout(); fig.savefig(path, dpi=200); plt.close(fig)


def make_all(out):
    os.makedirs(out, exist_ok=True)
    files = {
        "topology": os.path.join(out, "fig_topology.png"),
        "bully": os.path.join(out, "fig_bully_failover.png"),
        "mutex": os.path.join(out, "fig_mutex_flow.png"),
        "state": os.path.join(out, "fig_state_machine.png"),
        "ring": os.path.join(out, "fig_ring.png"),
    }
    topology(files["topology"]); bully_failover(files["bully"]); mutex_flow(files["mutex"])
    state_machine(files["state"]); ring(files["ring"])
    return files


if __name__ == "__main__":
    print(make_all(os.path.join(os.path.dirname(__file__), "build")))
