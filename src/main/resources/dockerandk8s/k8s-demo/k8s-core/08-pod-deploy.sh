#!/usr/bin/env bash
# ============================================================
# 08-pod-deploy.sh  Pod / ReplicaSet / Deployment 演示
# 知识点：Pod创建、RS副本控制、Deployment滚动更新、标签选择器
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require kubectl

NS="demo-$$"
title "第八章：Pod / ReplicaSet / Deployment 实验"

# 创建独立 namespace，实验完整清理
kubectl create namespace "$NS" 2>/dev/null || true
register_cleanup "kubectl delete namespace $NS --wait=false 2>/dev/null"

# ──────────────────────────────────────────────────────────
step "实验 1：裸 Pod —— 死了不会自动重建"
# ──────────────────────────────────────────────────────────
explain "裸 Pod 没有控制器，删除后不会自动重建 → 生产不直接用裸 Pod"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: bare-pod
  labels:
    app: bare
spec:
  containers:
  - name: app
    image: nginx:alpine
    ports:
    - containerPort: 80
    resources:
      requests:
        memory: "32Mi"
        cpu: "50m"
      limits:
        memory: "64Mi"
        cpu: "100m"
EOF

wait_for "裸Pod就绪" 60 3 "kubectl get pod bare-pod -n $NS | grep -q Running"
cmd "kubectl get pod bare-pod -n $NS -o wide"
kubectl get pod bare-pod -n "$NS" -o wide

echo ""
warn "删除裸Pod..."
kubectl delete pod bare-pod -n "$NS" --wait=false

sleep 3
echo -e "  ${RED}裸Pod删除后不会重建：${NC}"
kubectl get pods -n "$NS" 2>/dev/null

explain "裸 Pod vs Deployment：裸 Pod 手动管理，Deployment 自动控制副本数"

pause

# ──────────────────────────────────────────────────────────
step "实验 2：Deployment —— 声明式管理，副本自愈"
# ──────────────────────────────────────────────────────────
explain "Deployment 声明期望状态（replicas=3），Controller 持续确保实际=期望"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-demo
  labels:
    app: nginx-demo
spec:
  replicas: 3                    # 期望3个副本
  selector:
    matchLabels:
      app: nginx-demo            # 通过 label 选择管理的 Pod
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1                # 最多多1个
      maxUnavailable: 0          # 滚动期间始终保持3个可用
  template:
    metadata:
      labels:
        app: nginx-demo
    spec:
      containers:
      - name: nginx
        image: nginx:1.24-alpine
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "32Mi"
            cpu: "50m"
          limits:
            memory: "64Mi"
            cpu: "100m"
        readinessProbe:
          httpGet:
            path: /
            port: 80
          initialDelaySeconds: 5
          periodSeconds: 5
        livenessProbe:
          httpGet:
            path: /
            port: 80
          initialDelaySeconds: 10
          periodSeconds: 10
EOF

echo ""
info "等待 Deployment 就绪..."
kubectl rollout status deployment/nginx-demo -n "$NS" --timeout=120s

echo ""
cmd "kubectl get deployment,replicaset,pod -n $NS -o wide"
kubectl get deployment,replicaset,pod -n "$NS" -o wide

pause

# ──────────────────────────────────────────────────────────
step "实验 3：副本自愈 —— 强制删除一个 Pod"
# ──────────────────────────────────────────────────────────
explain "删除任意一个 Pod，ReplicaSet Controller 立即新建一个补齐到3"

POD_NAME=$(kubectl get pods -n "$NS" -l app=nginx-demo \
  -o jsonpath='{.items[0].metadata.name}')
echo ""
warn "强制删除 Pod: $POD_NAME"
cmd "kubectl delete pod $POD_NAME -n $NS"
kubectl delete pod "$POD_NAME" -n "$NS"

echo ""
echo -e "  ${CYAN}观察 Pod 列表变化（新 Pod 正在创建）：${NC}"
sleep 2
kubectl get pods -n "$NS" -l app=nginx-demo

sleep 5
echo ""
echo -e "  ${GREEN}恢复后：${NC}"
kubectl get pods -n "$NS" -l app=nginx-demo
explain "ReplicaSet 检测到实际副本数=2 < 期望=3，立即创建第3个"

pause

# ──────────────────────────────────────────────────────────
step "实验 4：滚动更新 —— 零停机升级"
# ──────────────────────────────────────────────────────────
explain "修改镜像版本 → Deployment 创建新 ReplicaSet → 逐步切换"

echo ""
echo -e "  ${CYAN}更新前的 ReplicaSet：${NC}"
kubectl get replicaset -n "$NS" -l app=nginx-demo

echo ""
cmd "kubectl set image deployment/nginx-demo nginx=nginx:1.25-alpine -n $NS"
kubectl set image deployment/nginx-demo nginx=nginx:1.25-alpine -n "$NS"

echo ""
echo -e "  ${CYAN}观察滚动更新过程：${NC}"
kubectl rollout status deployment/nginx-demo -n "$NS" --timeout=120s

echo ""
echo -e "  ${CYAN}更新后 ReplicaSet（旧的缩容到0，新的扩容到3）：${NC}"
kubectl get replicaset -n "$NS" -l app=nginx-demo
explain "旧 RS 副本数=0（保留用于回滚），新 RS 副本数=3"

pause

# ──────────────────────────────────────────────────────────
step "实验 5：查看更新历史 & 回滚"
# ──────────────────────────────────────────────────────────

cmd "kubectl rollout history deployment/nginx-demo -n $NS"
kubectl rollout history deployment/nginx-demo -n "$NS"

echo ""
warn "回滚到上一版本..."
cmd "kubectl rollout undo deployment/nginx-demo -n $NS"
kubectl rollout undo deployment/nginx-demo -n "$NS"
kubectl rollout status deployment/nginx-demo -n "$NS" --timeout=60s

echo ""
echo -e "  ${CYAN}回滚后验证版本：${NC}"
kubectl get pods -n "$NS" -l app=nginx-demo -o jsonpath='{.items[0].spec.containers[0].image}'
echo ""

pause

# ──────────────────────────────────────────────────────────
step "实验 6：Label 选择器 —— 控制器怎么找到 Pod"
# ──────────────────────────────────────────────────────────
explain "Deployment 通过 matchLabels 选择管理的 Pod，Label 是 k8s 的核心机制"

echo ""
cmd "kubectl get pods -n $NS --show-labels"
kubectl get pods -n "$NS" --show-labels

echo ""
echo -e "  ${CYAN}按 label 过滤 Pod：${NC}"
cmd "kubectl get pods -n $NS -l app=nginx-demo"
kubectl get pods -n "$NS" -l app=nginx-demo

echo ""
explain "手动给 Pod 加 label，RS 会误以为它是自己管理的（演示隔离性）"
explain "这也是为什么 k8s 要求 matchLabels 必须唯一的原因"

pause

# ──────────────────────────────────────────────────────────
step "实验 7：Pod 内部 —— 多容器 Sidecar 模式"
# ──────────────────────────────────────────────────────────
explain "同一 Pod 内的容器共享网络（相同 IP）和 Volume"
explain "Sidecar 模式：主容器 + 日志采集/代理容器"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: sidecar-demo
spec:
  volumes:
  - name: shared-logs           # 共享 volume
    emptyDir: {}
  containers:
  - name: main-app              # 主容器
    image: nginx:alpine
    volumeMounts:
    - name: shared-logs
      mountPath: /var/log/nginx
  - name: log-sidecar           # Sidecar：采集日志
    image: alpine
    command: ["/bin/sh", "-c"]
    args:
    - |
      while true; do
        echo "[$(date)] 采集到日志: $(ls /logs/ 2>/dev/null | wc -l) 个文件"
        sleep 5
      done
    volumeMounts:
    - name: shared-logs
      mountPath: /logs
EOF

wait_for "sidecar-demo Pod就绪" 60 3 \
  "kubectl get pod sidecar-demo -n $NS 2>/dev/null | grep -q Running"

echo ""
echo -e "  ${CYAN}Pod 内两个容器共享同一个 IP：${NC}"
cmd "kubectl exec sidecar-demo -n $NS -c main-app -- ip addr"
kubectl exec sidecar-demo -n "$NS" -c main-app -- ip addr 2>/dev/null | head -8
cmd "kubectl exec sidecar-demo -n $NS -c log-sidecar -- ip addr"
kubectl exec sidecar-demo -n "$NS" -c log-sidecar -- ip addr 2>/dev/null | head -8

echo ""
echo -e "  ${CYAN}Sidecar 容器的日志：${NC}"
sleep 6
cmd "kubectl logs sidecar-demo -n $NS -c log-sidecar"
kubectl logs sidecar-demo -n "$NS" -c log-sidecar 2>/dev/null | tail -5

run_cleanup

title "✅ Pod / Deployment 实验完成！"
echo "  核心记忆："
echo "  • 裸 Pod 死了不重建 → 生产用 Deployment"
echo "  • Deployment → ReplicaSet → Pod，三层管理"
echo "  • 滚动更新：新RS扩容 + 旧RS缩容，maxSurge/maxUnavailable 控制节奏"
echo "  • 回滚 = 旧RS重新扩容（旧RS保留，副本数改回3）"
echo "  • Label 选择器是控制器找到 Pod 的唯一机制"
echo ""
echo "  👉 下一步：bash k8s-core/09-service.sh"
