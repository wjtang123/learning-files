#!/usr/bin/env bash
# ============================================================
# 12-etcd-inspect.sh  etcd 数据结构 + 调度器行为演示
# 知识点：etcd存储结构、Raft、Scheduler过滤打分、亲和性/污点
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require kubectl

NS="arch-demo-$$"
title "第十二章：K8s 架构原理 —— etcd & Scheduler"
kubectl create namespace "$NS" 2>/dev/null || true
register_cleanup "kubectl delete namespace $NS --wait=false 2>/dev/null"

# ──────────────────────────────────────────────────────────
step "实验 1：etcd 数据结构 —— 所有 k8s 对象存在哪里"
# ──────────────────────────────────────────────────────────
explain "etcd 是 k8s 唯一的持久化存储，所有 API 对象都在 /registry/ 路径下"

# 尝试直接访问 etcd
ETCD_POD=$(kubectl get pod -n kube-system -l component=etcd \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)

if [[ -n "$ETCD_POD" ]]; then
  echo ""
  echo -e "  ${CYAN}etcd 中存储的 k8s 资源路径（/registry/ 前缀）：${NC}"
  explain "提示：etcdctl 需要 TLS 证书，以下命令在 master 节点以 root 运行"
  cmd "kubectl exec -n kube-system $ETCD_POD -- etcdctl get / --prefix --keys-only | grep registry | head -30"
  kubectl exec -n kube-system "$ETCD_POD" -- sh -c \
    'ETCDCTL_API=3 etcdctl \
      --endpoints=https://127.0.0.1:2379 \
      --cacert=/etc/kubernetes/pki/etcd/ca.crt \
      --cert=/etc/kubernetes/pki/etcd/server.crt \
      --key=/etc/kubernetes/pki/etcd/server.key \
      get / --prefix --keys-only 2>/dev/null | grep /registry | head -30' 2>/dev/null || \
    warn "etcd 访问需要证书，展示等效路径结构（在 master 节点 sudo 运行可查看）："
fi

echo ""
cat << 'TREE'
  etcd 数据布局（/registry/ 路径结构）：
  ├── /registry/pods/default/nginx-xxx-yyy        # Pod 对象
  ├── /registry/deployments/default/nginx         # Deployment
  ├── /registry/services/specs/default/my-svc     # Service
  ├── /registry/configmaps/default/app-config     # ConfigMap
  ├── /registry/secrets/default/db-secret         # Secret（base64）
  ├── /registry/namespaces/default                # Namespace
  ├── /registry/nodes/node01                      # Node 节点信息
  └── /registry/leases/kube-system/kube-scheduler # 控制面 Leader 锁
TREE

echo ""
explain "Watch 机制：客户端（controller/scheduler/kubelet）监听 etcd 变更事件"
explain "不是轮询！是 etcd 主动推送，这是 k8s 事件驱动的核心"

pause

# ──────────────────────────────────────────────────────────
step "实验 2：Raft 共识 —— 为什么控制面需要奇数节点"
# ──────────────────────────────────────────────────────────
explain "etcd 用 Raft 协议保证强一致性"
explain "quorum = n/2+1，写操作需要多数节点确认才能提交"

cat << 'RAFT'
  Raft 节点容忍故障数：
  ┌─────────────┬──────────┬──────────────┐
  │  etcd 节点数 │  quorum  │  容忍故障数  │
  ├─────────────┼──────────┼──────────────┤
  │      1      │    1     │      0       │  ← 生产不用
  │      2      │    2     │      0       │  ← 生产不用
  │      3      │    2     │      1       │  ← 生产最小规格
  │      5      │    3     │      2       │  ← 生产推荐
  │      7      │    4     │      3       │  ← 超大规模
  └─────────────┴──────────┴──────────────┘

  写操作流程（Raft Leader）：
  1. 客户端写 → Leader
  2. Leader 追加日志条目
  3. 并行发给所有 Follower
  4. 收到 quorum 确认（3节点=2个确认）
  5. Leader commit，通知客户端成功
  6. Follower 异步 apply
RAFT

echo ""
explain "脑裂保护：网络分区时，少数节点分区无法形成 quorum，拒绝写入，保证一致性"

pause

# ──────────────────────────────────────────────────────────
step "实验 3：Scheduler 调度流程 —— 过滤 + 打分"
# ──────────────────────────────────────────────────────────
explain "Scheduler 两阶段：① 过滤（Filtering）② 打分（Scoring）"
explain "过滤：排除不满足条件的节点；打分：在候选节点里选最优"

# 查看节点列表
echo ""
cmd "kubectl get nodes -o wide"
kubectl get nodes -o wide 2>/dev/null

echo ""
echo -e "  ${CYAN}查看 Scheduler 日志（调度决策）：${NC}"
SCHED_POD=$(kubectl get pod -n kube-system -l component=kube-scheduler \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
if [[ -n "$SCHED_POD" ]]; then
  kubectl logs "$SCHED_POD" -n kube-system --tail=10 2>/dev/null | \
    grep -i "scheduled\|score\|filter" | head -10 || true
fi

pause

# ──────────────────────────────────────────────────────────
step "实验 4：nodeSelector —— 最简单的调度约束"
# ──────────────────────────────────────────────────────────
explain "nodeSelector 根据节点 label 过滤，不匹配则 Pending"

# 查看节点已有的 label
echo ""
cmd "kubectl get nodes --show-labels"
kubectl get nodes --show-labels 2>/dev/null | head -5

echo ""
explain "演示：要求调度到有 disk=ssd 标签的节点（该标签不存在，所以 Pod 会 Pending）"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: nodeselector-demo
spec:
  nodeSelector:
    disk: ssd              # 要求节点有此标签
  containers:
  - name: app
    image: nginx:alpine
    resources:
      requests: {memory: "16Mi", cpu: "10m"}
      limits: {memory: "32Mi", cpu: "50m"}
EOF

sleep 5
echo ""
echo -e "  ${CYAN}Pod 状态（应该 Pending，因为没有匹配节点）：${NC}"
cmd "kubectl get pod nodeselector-demo -n $NS"
kubectl get pod nodeselector-demo -n "$NS"

echo ""
echo -e "  ${CYAN}Describe 看调度失败原因：${NC}"
cmd "kubectl describe pod nodeselector-demo -n $NS | grep -A5 Events"
kubectl describe pod nodeselector-demo -n "$NS" | \
  grep -A 10 "Events:" | tail -10

explain "0/N nodes are available: N node(s) didn't match nodeSelector"

echo ""
warn "给节点打标签，让 Pod 调度成功..."
NODE_NAME=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
cmd "kubectl label node $NODE_NAME disk=ssd"
kubectl label node "$NODE_NAME" disk=ssd 2>/dev/null || true

wait_for "Pod 调度就绪" 60 3 \
  "kubectl get pod nodeselector-demo -n $NS 2>/dev/null | grep -q Running"

echo ""
cmd "kubectl get pod nodeselector-demo -n $NS -o wide"
kubectl get pod nodeselector-demo -n "$NS" -o wide

# 清理标签
kubectl label node "$NODE_NAME" disk- 2>/dev/null || true

pause

# ──────────────────────────────────────────────────────────
step "实验 5：Pod 亲和性 & 反亲和性"
# ──────────────────────────────────────────────────────────
explain "podAntiAffinity：让相同应用的 Pod 分散到不同节点（高可用）"
explain "required = 硬约束（不满足则Pending），preferred = 软约束（尽量满足）"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ha-web
spec:
  replicas: 2
  selector:
    matchLabels:
      app: ha-web
  template:
    metadata:
      labels:
        app: ha-web
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:  # 软规则
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchLabels:
                  app: ha-web
              topologyKey: kubernetes.io/hostname  # 不同节点
      containers:
      - name: web
        image: nginx:alpine
        resources:
          requests: {memory: "16Mi", cpu: "10m"}
          limits: {memory: "32Mi", cpu: "50m"}
EOF

kubectl rollout status deployment/ha-web -n "$NS" --timeout=60s
echo ""
echo -e "  ${CYAN}两个 Pod 尽量分散到不同节点：${NC}"
cmd "kubectl get pods -n $NS -l app=ha-web -o wide"
kubectl get pods -n "$NS" -l app=ha-web -o wide

pause

# ──────────────────────────────────────────────────────────
step "实验 6：污点（Taint）& 容忍（Toleration）"
# ──────────────────────────────────────────────────────────
explain "污点：节点说'我不接受普通Pod'  容忍：Pod说'我不怕这个污点'"
explain "常见用途：master节点打污点，只允许系统Pod调度"

NODE_NAME=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)

echo ""
echo -e "  ${CYAN}查看节点现有污点：${NC}"
cmd "kubectl describe node $NODE_NAME | grep Taints"
kubectl describe node "$NODE_NAME" 2>/dev/null | grep -A3 "Taints:" | head -5

echo ""
explain "master 节点的污点：node-role.kubernetes.io/control-plane:NoSchedule"
explain "这就是为什么普通 Pod 不会调度到 master 节点"

echo ""
explain "演示：给节点加污点，没有容忍的 Pod 无法调度"

# 只在有多个节点时演示（避免单节点演示崩溃）
NODE_COUNT=$(kubectl get nodes --no-headers | wc -l)
if [[ "$NODE_COUNT" -ge 2 ]]; then
  TAINT_NODE=$(kubectl get nodes -o jsonpath='{.items[1].metadata.name}' 2>/dev/null)
  cmd "kubectl taint node $TAINT_NODE dedicated=gpu:NoSchedule"
  kubectl taint node "$TAINT_NODE" dedicated=gpu:NoSchedule 2>/dev/null || true

  kubectl apply -n "$NS" -f - << 'EOF2'
apiVersion: v1
kind: Pod
metadata:
  name: toleration-demo
spec:
  tolerations:
  - key: "dedicated"
    operator: "Equal"
    value: "gpu"
    effect: "NoSchedule"   # 容忍这个污点
  containers:
  - name: app
    image: nginx:alpine
    resources:
      requests: {memory: "16Mi", cpu: "10m"}
      limits: {memory: "32Mi", cpu: "50m"}
EOF2

  wait_for "toleration-demo Pod就绪" 60 3 \
    "kubectl get pod toleration-demo -n $NS 2>/dev/null | grep -q Running"
  kubectl get pod toleration-demo -n "$NS" -o wide

  # 清理污点
  kubectl taint node "$TAINT_NODE" dedicated=gpu:NoSchedule- 2>/dev/null || true
else
  warn "单节点环境，污点演示跳过（加污点会影响所有 Pod）"
  cat << 'TAINT_EXAMPLE'
  示例配置：
  # 给节点加污点
  kubectl taint node gpu-node dedicated=gpu:NoSchedule

  # Pod 容忍该污点
  tolerations:
  - key: "dedicated"
    operator: "Equal"
    value: "gpu"
    effect: "NoSchedule"
TAINT_EXAMPLE
fi

pause

# ──────────────────────────────────────────────────────────
step "实验 7：Controller Manager —— Reconcile Loop 可视化"
# ──────────────────────────────────────────────────────────
explain "Controller 不断比较 desired state（etcd） vs current state（实际运行）"
explain "发现差异就采取行动：创建/删除/更新资源"

echo ""
echo -e "  ${CYAN}实时观察 Reconcile：手动删除 Pod，观察 Controller 立即补齐${NC}"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: reconcile-demo
spec:
  replicas: 2
  selector:
    matchLabels:
      app: reconcile-demo
  template:
    metadata:
      labels:
        app: reconcile-demo
    spec:
      containers:
      - name: app
        image: nginx:alpine
        resources:
          requests: {memory: "16Mi", cpu: "10m"}
          limits: {memory: "32Mi", cpu: "50m"}
EOF

kubectl rollout status deployment/reconcile-demo -n "$NS" --timeout=60s
echo ""
echo -e "  ${CYAN}当前状态（2个Pod）：${NC}"
kubectl get pods -n "$NS" -l app=reconcile-demo

echo ""
warn "删除所有 Pod，观察 Controller 自动补齐..."
kubectl delete pods -n "$NS" -l app=reconcile-demo

echo ""
echo -e "  ${CYAN}3秒后观察（Pod 正在重建）：${NC}"
sleep 3
kubectl get pods -n "$NS" -l app=reconcile-demo

sleep 10
echo ""
echo -e "  ${GREEN}10秒后（已补齐到2个）：${NC}"
kubectl get pods -n "$NS" -l app=reconcile-demo

explain "Reconcile Loop 是幂等的：无论发生什么（网络闪断、进程重启），"
explain "Controller 重新运行都能从当前状态计算出需要做什么，不重复不遗漏"

run_cleanup

title "✅ K8s 架构原理演示完成！"
echo "  核心记忆："
echo "  • etcd = 唯一状态存储（/registry/...），Watch 推送变更"
echo "  • Raft = 强一致性，quorum=n/2+1，生产用奇数节点（3或5）"
echo "  • Scheduler 两阶段：过滤（排除不合格）→ 打分（选最优）"
echo "  • nodeSelector / affinity / taint = 调度约束三件套"
echo "  • Controller = 持续 Reconcile，desired==actual 是目标"
echo ""
echo "  👉 下一步：bash k8s-ops/15-hpa.sh"
