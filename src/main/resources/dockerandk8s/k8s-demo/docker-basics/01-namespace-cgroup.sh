#!/usr/bin/env bash
# ============================================================
# 01-namespace-cgroup.sh  Namespace & Cgroup 可视化演示
# 知识点：pid/net/mnt/uts/ipc namespace + cpu/memory/pids cgroup
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require docker

title "第一章：Namespace & Cgroup 实验"
explain "容器 = Namespace（看不见）+ Cgroup（抢不走）"

# ──────────────────────────────────────────────────────────
step "实验 1：pid namespace —— 容器内 PID=1"
# ──────────────────────────────────────────────────────────
explain "每个容器有独立的 PID 空间，容器内第一个进程 PID=1"
explain "但在宿主机上它是另一个 PID（如 3271）"

pause

# 启动一个 sleep 容器
CNAME="ns-demo-$$"
docker run -d --name "$CNAME" alpine sleep 3600 > /dev/null
register_cleanup "docker rm -f $CNAME"

echo ""
echo -e "  ${CYAN}【容器内视角】${NC}"
cmd "docker exec $CNAME ps aux"
docker exec "$CNAME" ps aux

echo ""
echo -e "  ${CYAN}【宿主机视角】${NC} —— 找到同一个 sleep 进程在宿主机的 PID"
CONTAINER_PID=$(docker inspect --format '{{.State.Pid}}' "$CNAME")
cmd "ps -p $CONTAINER_PID -o pid,ppid,cmd"
ps -p "$CONTAINER_PID" -o pid,ppid,cmd 2>/dev/null || \
  echo "    PID=$CONTAINER_PID (sleep 3600)"

echo ""
explain "结论：容器内 PID=1，宿主机 PID=$CONTAINER_PID，是同一个进程！"

pause

# ──────────────────────────────────────────────────────────
step "实验 2：net namespace —— 容器有独立网络栈"
# ──────────────────────────────────────────────────────────
explain "每个容器获得独立的 eth0、路由表、端口空间"

echo ""
echo -e "  ${CYAN}【容器 A 的网络接口】${NC}"
cmd "docker exec $CNAME ip addr"
docker exec "$CNAME" ip addr

echo ""
echo -e "  ${CYAN}【宿主机的网络接口（对比）】${NC}"
cmd "ip addr show docker0"
ip addr show docker0 2>/dev/null || ip addr | head -20

echo ""
explain "容器 eth0（172.17.x.x）↔ 宿主机 veth pair ↔ docker0 网桥"

pause

# ──────────────────────────────────────────────────────────
step "实验 3：uts namespace —— 独立主机名"
# ──────────────────────────────────────────────────────────
explain "容器可以有与宿主机完全不同的 hostname"

HNAME_CONTAINER=$(docker exec "$CNAME" hostname)
HNAME_HOST=$(hostname)
echo ""
echo -e "  容器 hostname: ${GREEN}$HNAME_CONTAINER${NC}"
echo -e "  宿主机 hostname: ${CYAN}$HNAME_HOST${NC}"

docker run --rm --hostname my-custom-host alpine hostname
explain "用 --hostname 可以给容器设置任意主机名"

pause

# ──────────────────────────────────────────────────────────
step "实验 4：Namespace 文件 —— 内核如何实现隔离"
# ──────────────────────────────────────────────────────────
explain "每个进程的 namespace 都是 /proc/<pid>/ns/ 下的文件"

echo ""
cmd "ls -la /proc/$CONTAINER_PID/ns/"
ls -la /proc/"$CONTAINER_PID"/ns/ 2>/dev/null || \
  docker exec "$CNAME" ls -la /proc/1/ns/ 2>/dev/null || true

echo ""
explain "inode 编号相同 = 同一个 namespace；不同 = 隔离的 namespace"

pause

# ──────────────────────────────────────────────────────────
step "实验 5：Cgroup —— 资源用量限制"
# ──────────────────────────────────────────────────────────
explain "Cgroup 决定容器最多能用多少 CPU 和内存"

# 启动一个限制资源的容器
CLIMIT="cgroup-demo-$$"
docker run -d --name "$CLIMIT" \
  --cpus="0.5" \
  --memory="128m" \
  --pids-limit=20 \
  alpine sleep 3600 > /dev/null
register_cleanup "docker rm -f $CLIMIT"

echo ""
echo -e "  ${CYAN}容器 $CLIMIT 的 Cgroup 配置：${NC}"

CLIMIT_PID=$(docker inspect --format '{{.State.Pid}}' "$CLIMIT")
CGPATH="/sys/fs/cgroup"

# 尝试 cgroup v1 和 v2 两种路径
for path in \
  "$CGPATH/cpu/docker/$(docker inspect --format '{{.Id}}' "$CLIMIT")/cpu.cfs_quota_us" \
  "$CGPATH/cpu.max"; do
  [[ -f "$path" ]] && { echo "  CPU quota: $(cat "$path")"; break; }
done

for path in \
  "$CGPATH/memory/docker/$(docker inspect --format '{{.Id}}' "$CLIMIT")/memory.limit_in_bytes" \
  "$CGPATH/memory.max"; do
  [[ -f "$path" ]] && { echo "  Memory limit: $(cat "$path") bytes (≈128MB)"; break; }
done

echo ""
cmd "docker stats $CLIMIT --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}'"
docker stats "$CLIMIT" --no-stream \
  --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}'

pause

# ──────────────────────────────────────────────────────────
step "实验 6：OOM —— 超出内存限制会发生什么"
# ──────────────────────────────────────────────────────────
explain "容器内存超限 → OOM Killer → 进程被 SIGKILL（退出码 137）"

echo ""
cmd "docker run --rm --memory=64m alpine sh -c 'dd if=/dev/zero bs=1M count=128'"
warn "尝试分配 128MB（超出 64MB 限制）..."

timeout 10 docker run --rm --name "oom-test-$$" \
  --memory=64m alpine \
  sh -c 'dd if=/dev/zero bs=1M count=128' 2>&1 | tail -3 || \
  { EXIT=$?; [[ $EXIT -eq 137 ]] && ok "OOM Kill 触发！退出码=137（128+9=SIGKILL）" || \
    warn "退出码=$EXIT"; }

pause

# ──────────────────────────────────────────────────────────
step "实验 7：Fork Bomb 防护 —— pids cgroup"
# ──────────────────────────────────────────────────────────
explain "pids cgroup 限制容器最多能创建的进程数，防止 fork bomb"

echo ""
CFORK="fork-demo-$$"
docker run -d --name "$CFORK" --pids-limit=5 alpine sleep 3600 > /dev/null
register_cleanup "docker rm -f $CFORK"

cmd "docker exec $CFORK sh -c 'for i in 1 2 3 4 5 6 7; do sleep 10 & done'"
docker exec "$CFORK" sh -c \
  'for i in 1 2 3 4 5 6 7; do sleep 10 & done 2>/dev/null; sleep 1; echo "当前进程数: $(ps | wc -l) 个"' \
  2>&1 || true

echo ""
explain "超出 pids-limit=5 的 fork() 调用返回 EAGAIN（资源不可用）"

pause

# ──────────────────────────────────────────────────────────
step "实验 8：nsenter —— 进入容器的 namespace 调试"
# ──────────────────────────────────────────────────────────
explain "nsenter 让你从宿主机进入容器的 namespace，强大的调试工具"

echo ""
cmd "nsenter -t $CONTAINER_PID -n ip addr"
if command -v nsenter &>/dev/null && [[ -d "/proc/$CONTAINER_PID" ]]; then
  nsenter -t "$CONTAINER_PID" -n ip addr 2>/dev/null || \
    warn "需要 root 权限，请用 sudo 运行"
else
  warn "nsenter 需要 root 权限或 /proc/$CONTAINER_PID 存在"
fi

echo ""
explain "nsenter -t <PID> -n → 进入该进程的 net namespace，看到容器的网卡"
explain "生产调试时，distroless 容器没有 shell，nsenter 是唯一手段"

# ── 清理 ──────────────────────────────────────────────────
run_cleanup

title "✅ Namespace & Cgroup 实验完成！"
echo "  核心记忆："
echo "  • Namespace → 隔离'看得见什么'（pid/net/mnt/uts/ipc/user）"
echo "  • Cgroup    → 限制'用得了多少'（cpu/memory/pids/blkio）"
echo "  • 两者缺一不可，共同构成容器隔离的基础"
echo ""
echo "  👉 下一步：bash docker-basics/02-overlayfs.sh"
