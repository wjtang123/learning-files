#!/usr/bin/env bash
# ============================================================
# 15-hpa-probe-troubleshoot.sh  生产运维综合演示
# 知识点：HPA自动扩缩容、Liveness/Readiness/Startup Probe、
#         OOM排查、Pending排查、CrashLoopBackOff排查、滚动更新
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require kubectl

NS="ops-demo-$$"
title "第十五章：生产运维 —— HPA / Probe / 故障排查"
kubectl create namespace "$NS" 2>/dev/null || true
register_cleanup "kubectl delete namespace $NS --wait=false 2>/dev/null"

# ──────────────────────────────────────────────────────────
step "实验 1：Readiness & Liveness Probe 对比"
# ──────────────────────────────────────────────────────────
explain "Liveness  失败 → 容器被 kill 并重启（检测死锁/无响应）"
explain "Readiness 失败 → 从 Service Endpoints 摘除（不重启）"
explain "Startup   Probe → 解决慢启动应用被 Liveness 误杀"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: probe-demo
spec:
  replicas: 2
  selector:
    matchLabels:
      app: probe-demo
  template:
    metadata:
      labels:
        app: probe-demo
    spec:
      containers:
      - name: app
        image: nginx:alpine
        ports:
        - containerPort: 80
        resources:
          requests: {memory: "32Mi", cpu: "20m"}
          limits:  {memory: "64Mi", cpu: "100m"}

        startupProbe:              # ① 启动探针：先完成，liveness 才开始
          httpGet:
            path: /
            port: 80
          failureThreshold: 10    # 给最多 10×3=30s 启动时间
          periodSeconds: 3

        readinessProbe:            # ② 就绪探针：失败则摘除 Service 流量
          httpGet:
            path: /
            port: 80
          initialDelaySeconds: 5
          periodSeconds: 5
          failureThreshold: 2
          successThreshold: 1

        livenessProbe:             # ③ 存活探针：失败则 kill+restart
          httpGet:
            path: /
            port: 80
          initialDelaySeconds: 15
          periodSeconds: 10
          failureThreshold: 3
          timeoutSeconds: 5
EOF

kubectl rollout status deployment/probe-demo -n "$NS" --timeout=90s

echo ""
cmd "kubectl get pods -n $NS -l app=probe-demo"
kubectl get pods -n "$NS" -l app=probe-demo

echo ""
explain "观察 Readiness 失败：修改健康检查路径到不存在的路径"
kubectl patch deployment probe-demo -n "$NS" --type='json' \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/path","value":"/nonexistent"}]'

sleep 15
echo ""
echo -e "  ${CYAN}Readiness 失败后 Pod 状态（READY=0/1 但不重启）：${NC}"
cmd "kubectl get pods -n $NS -l app=probe-demo"
kubectl get pods -n "$NS" -l app=probe-demo

echo ""
echo -e "  ${CYAN}Service Endpoints 变化（Pod 被摘除）：${NC}"
cmd "kubectl get endpoints -n $NS 2>/dev/null | grep probe"
kubectl get endpoints -n "$NS" 2>/dev/null | head -5

# 恢复
kubectl patch deployment probe-demo -n "$NS" --type='json' \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/path","value":"/"}]'

pause

# ──────────────────────────────────────────────────────────
step "实验 2：HPA —— 基于 CPU 的自动扩缩容"
# ──────────────────────────────────────────────────────────
explain "HPA 监控 metrics-server 提供的指标，自动调整 Deployment 副本数"
explain "扩容算法：期望副本数 = ceil(当前副本数 × 当前指标值 / 目标指标值)"

# 检查 metrics-server 是否可用
if ! kubectl top nodes &>/dev/null 2>&1; then
  warn "metrics-server 未就绪，尝试安装..."
  kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml 2>/dev/null || true
  sleep 30
fi

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hpa-demo
spec:
  replicas: 1
  selector:
    matchLabels:
      app: hpa-demo
  template:
    metadata:
      labels:
        app: hpa-demo
    spec:
      containers:
      - name: stress
        image: nginx:alpine
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"      # HPA 基于此值计算利用率
          limits:
            memory: "128Mi"
            cpu: "200m"
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: hpa-demo
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: hpa-demo
  minReplicas: 1
  maxReplicas: 5
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 30    # CPU 超过 30% 开始扩容
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0    # 立即扩容
    scaleDown:
      stabilizationWindowSeconds: 30   # 缩容等待 30s（防抖动）
EOF

kubectl rollout status deployment/hpa-demo -n "$NS" --timeout=60s

echo ""
cmd "kubectl get hpa hpa-demo -n $NS"
kubectl get hpa hpa-demo -n "$NS" 2>/dev/null || \
  warn "HPA 就绪需要 metrics-server，等待中..."

echo ""
explain "触发 CPU 压力（在容器里死循环）..."
HPA_POD=$(kubectl get pod -n "$NS" -l app=hpa-demo \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)

if [[ -n "$HPA_POD" ]]; then
  cmd "kubectl exec $HPA_POD -n $NS -- sh -c 'yes > /dev/null &'"
  kubectl exec "$HPA_POD" -n "$NS" -- sh -c 'yes > /dev/null &' 2>/dev/null || true

  echo ""
  info "等待 HPA 检测到 CPU 压力并扩容（约30-60秒）..."
  for i in $(seq 1 6); do
    sleep 10
    echo -e "  ${CYAN}${i}0秒后：${NC}"
    kubectl get hpa hpa-demo -n "$NS" 2>/dev/null | \
      grep -v NAME || true
    kubectl get pods -n "$NS" -l app=hpa-demo --no-headers 2>/dev/null | wc -l | \
      xargs -I{} echo "  当前 Pod 数量: {}"
  done

  echo ""
  explain "停止 CPU 压力，观察缩容（有 stabilizationWindow 防抖）"
  kubectl exec "$HPA_POD" -n "$NS" -- sh -c 'kill %1' 2>/dev/null || true
fi

pause

# ──────────────────────────────────────────────────────────
step "实验 3：滚动更新 & 回滚 —— 生产安全发布"
# ──────────────────────────────────────────────────────────
explain "滚动更新：新 RS 逐步扩容，旧 RS 逐步缩容，始终保持可用"
explain "maxSurge=1,maxUnavailable=0 = 最保守策略（始终满副本数）"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rolling-demo
  annotations:
    kubernetes.io/change-cause: "v1: initial release nginx 1.24"
spec:
  replicas: 3
  selector:
    matchLabels:
      app: rolling-demo
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1           # 最多比期望多1个 Pod
      maxUnavailable: 0     # 始终保持3个可用
  template:
    metadata:
      labels:
        app: rolling-demo
    spec:
      containers:
      - name: app
        image: nginx:1.24-alpine
        resources:
          requests: {memory: "32Mi", cpu: "20m"}
          limits: {memory: "64Mi", cpu: "100m"}
        lifecycle:
          preStop:
            exec:
              command: ["/bin/sh", "-c", "sleep 5"]  # 给 Endpoints 摘除留时间
EOF

kubectl rollout status deployment/rolling-demo -n "$NS" --timeout=90s
echo ""
echo -e "  ${CYAN}升级前：${NC}"
kubectl get pods -n "$NS" -l app=rolling-demo -o \
  jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\n"}{end}'

echo ""
warn "开始滚动更新：nginx 1.24 → 1.25..."
kubectl set image deployment/rolling-demo \
  app=nginx:1.25-alpine -n "$NS"
kubectl annotate deployment rolling-demo -n "$NS" \
  kubernetes.io/change-cause="v2: upgrade to nginx 1.25" --overwrite

echo ""
echo -e "  ${CYAN}实时观察更新过程：${NC}"
kubectl rollout status deployment/rolling-demo -n "$NS" --timeout=120s

echo ""
echo -e "  ${CYAN}查看版本历史：${NC}"
cmd "kubectl rollout history deployment/rolling-demo -n $NS"
kubectl rollout history deployment/rolling-demo -n "$NS"

echo ""
warn "模拟发现问题，执行回滚..."
cmd "kubectl rollout undo deployment/rolling-demo -n $NS"
kubectl rollout undo deployment/rolling-demo -n "$NS"
kubectl rollout status deployment/rolling-demo -n "$NS" --timeout=60s

echo ""
echo -e "  ${GREEN}回滚完成，验证版本：${NC}"
kubectl get pods -n "$NS" -l app=rolling-demo -o \
  jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\n"}{end}'
echo ""

pause

# ──────────────────────────────────────────────────────────
step "实验 4：故障排查 —— OOM Kill 场景"
# ──────────────────────────────────────────────────────────
explain "容器超出内存 limit → OOM Kill → 退出码 137（128+9=SIGKILL）"
explain "kubectl describe pod 和 docker inspect 是第一排查工具"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: oom-demo
spec:
  containers:
  - name: oom-app
    image: alpine
    command: ["/bin/sh", "-c"]
    args:
    - |
      echo "开始分配内存，超出 limit 后会被 OOM Kill..."
      # 用 Python 分配内存，比 dd 更可靠地触发 OOM
      python3 -c "
import sys
data = []
for i in range(300):
    data.append(b'x' * 1024 * 1024)
    print(f'已分配 {i+1} MB', flush=True)
" 2>&1 || true
    resources:
      requests:
        memory: "32Mi"
      limits:
        memory: "64Mi"    # 限制 64MB，但程序要申请 256MB
EOF

echo ""
info "等待 OOM Kill 触发（约10秒）..."
sleep 15

echo ""
echo -e "  ${CYAN}查看 Pod 状态（OOMKilled）：${NC}"
cmd "kubectl get pod oom-demo -n $NS"
kubectl get pod oom-demo -n "$NS"

echo ""
echo -e "  ${CYAN}详细信息 —— 确认 OOMKilled：${NC}"
cmd "kubectl describe pod oom-demo -n $NS | grep -A5 'Last State'"
kubectl describe pod oom-demo -n "$NS" | grep -A 8 "Last State:" | head -12

echo ""
echo -e "  ${CYAN}查看退出码（应为137）：${NC}"
cmd "kubectl get pod oom-demo -n $NS -o jsonpath='{.status.containerStatuses[0].lastState.terminated}'"
kubectl get pod oom-demo -n "$NS" \
  -o jsonpath='{.status.containerStatuses[0].lastState.terminated}' \
  2>/dev/null | python3 -m json.tool 2>/dev/null || true

explain "退出码 137 = OOM Kill；137 = 128 + 9（SIGKILL 信号编号）"
explain "排查步骤：describe pod → events → 看 OOMKilled:true → 调高 limit 或修复内存泄漏"

pause

# ──────────────────────────────────────────────────────────
step "实验 5：故障排查 —— CrashLoopBackOff"
# ──────────────────────────────────────────────────────────
explain "容器启动后立即 crash，k8s 反复重启，等待时间指数增长"
explain "排查关键：kubectl logs --previous 看上一次崩溃的日志"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: crash-demo
spec:
  containers:
  - name: crasher
    image: alpine
    command: ["/bin/sh", "-c"]
    args:
    - |
      echo "启动中..."
      echo "错误：无法连接数据库 db.internal:5432" >&2
      echo "错误：CONNECTION_REFUSED" >&2
      exit 1    # 非零退出码 → CrashLoopBackOff
    resources:
      requests: {memory: "16Mi", cpu: "10m"}
      limits: {memory: "32Mi", cpu: "50m"}
EOF

echo ""
info "等待 CrashLoopBackOff 出现（约30秒）..."
sleep 30

echo ""
echo -e "  ${CYAN}Pod 状态（CrashLoopBackOff）：${NC}"
cmd "kubectl get pod crash-demo -n $NS"
kubectl get pod crash-demo -n "$NS"

echo ""
echo -e "  ${CYAN}当前日志（可能为空，容器已退出）：${NC}"
cmd "kubectl logs crash-demo -n $NS"
kubectl logs crash-demo -n "$NS" 2>/dev/null || echo "  （容器已退出，日志为空）"

echo ""
echo -e "  ${CYAN}上一次崩溃的日志（关键！）：${NC}"
cmd "kubectl logs crash-demo -n $NS --previous"
kubectl logs crash-demo -n "$NS" --previous 2>/dev/null || \
  warn "需要至少崩溃一次才有 previous 日志"

echo ""
echo -e "  ${CYAN}退出码分析：${NC}"
kubectl get pod crash-demo -n "$NS" \
  -o jsonpath='{.status.containerStatuses[0]}' 2>/dev/null | \
  python3 -m json.tool 2>/dev/null | \
  grep -E "exitCode|reason|restartCount" || true

explain "CrashLoopBackOff 退出码 1 = 应用自身错误（看 previous 日志）"
explain "退出码 137 = OOM，139 = Segfault，127 = 命令不存在"

pause

# ──────────────────────────────────────────────────────────
step "实验 6：故障排查 —— Pod Pending 完整排查流程"
# ──────────────────────────────────────────────────────────
explain "Pod Pending：调度器找不到合适节点，describe 看 Events 字段"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: pending-demo
spec:
  containers:
  - name: app
    image: nginx:alpine
    resources:
      requests:
        memory: "9999Gi"    # 请求超大内存，必然调度失败
        cpu: "9999"
      limits:
        memory: "9999Gi"
        cpu: "9999"
EOF

sleep 5
echo ""
echo -e "  ${CYAN}Pod 状态（Pending）：${NC}"
cmd "kubectl get pod pending-demo -n $NS"
kubectl get pod pending-demo -n "$NS"

echo ""
echo -e "  ${CYAN}describe 看调度失败原因（Events）：${NC}"
cmd "kubectl describe pod pending-demo -n $NS | tail -20"
kubectl describe pod pending-demo -n "$NS" | tail -20

echo ""
explain "常见 Pending 原因（Events 里会明确说明）："
echo "  • Insufficient cpu/memory  → 节点资源不足，扩节点或降低 requests"
echo "  • didn't match nodeSelector → 没有匹配标签的节点"
echo "  • had taint that pod didn't tolerate → 节点污点未容忍"
echo "  • waiting for PVC to be bound → PVC 未绑定（StorageClass 问题）"

# ──────────────────────────────────────────────────────────
step "实验 7：configmap-secret 注入演示"
# ──────────────────────────────────────────────────────────
explain "ConfigMap 环境变量注入 vs Volume 文件挂载两种方式"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  APP_ENV: "production"
  LOG_LEVEL: "info"
  nginx.conf: |
    server {
      listen 80;
      location /health { return 200 "OK\n"; }
    }
---
apiVersion: v1
kind: Secret
metadata:
  name: app-secret
type: Opaque
stringData:
  DB_PASSWORD: "s3cr3t-p@ssw0rd"   # stringData 自动 base64 编码
  API_KEY: "my-api-key-12345"
---
apiVersion: v1
kind: Pod
metadata:
  name: config-demo
spec:
  volumes:
  - name: config-volume
    configMap:
      name: app-config
      items:
      - key: nginx.conf
        path: nginx.conf
  containers:
  - name: app
    image: alpine
    command: ["/bin/sh", "-c", "env | grep -E 'APP|LOG|DB|API'; echo '---'; cat /etc/config/nginx.conf; sleep 300"]
    envFrom:
    - configMapRef:
        name: app-config      # 所有 key 注入为环境变量
    env:
    - name: DB_PASSWORD       # 从 Secret 注入单个值
      valueFrom:
        secretKeyRef:
          name: app-secret
          key: DB_PASSWORD
    volumeMounts:
    - name: config-volume
      mountPath: /etc/config  # 挂载为文件
    resources:
      requests: {memory: "16Mi", cpu: "10m"}
      limits: {memory: "32Mi", cpu: "50m"}
EOF

wait_for "config-demo Pod就绪" 60 3 \
  "kubectl get pod config-demo -n $NS 2>/dev/null | grep -q Running"

echo ""
echo -e "  ${CYAN}容器内的环境变量（来自 ConfigMap + Secret）：${NC}"
cmd "kubectl exec config-demo -n $NS -- env | grep -E 'APP|LOG|DB'"
kubectl exec config-demo -n "$NS" -- env 2>/dev/null | \
  grep -E "APP|LOG|DB|API" | sort

echo ""
echo -e "  ${CYAN}ConfigMap 挂载的文件：${NC}"
cmd "kubectl exec config-demo -n $NS -- cat /etc/config/nginx.conf"
kubectl exec config-demo -n "$NS" -- cat /etc/config/nginx.conf 2>/dev/null

echo ""
explain "Secret 的 base64 是编码不是加密！安全靠 RBAC + etcd 加密 + tmpfs 挂载"
echo ""
echo -e "  ${CYAN}查看 Secret 存储的 base64 值：${NC}"
cmd "kubectl get secret app-secret -n $NS -o jsonpath='{.data.DB_PASSWORD}' | base64 -d"
kubectl get secret app-secret -n "$NS" \
  -o jsonpath='{.data.DB_PASSWORD}' 2>/dev/null | base64 -d && echo ""

run_cleanup

title "✅ 生产运维演示全部完成！"
echo ""
echo "  核心记忆："
echo "  【Probe 三件套】"
echo "  • startupProbe  → 慢启动保护（先它，后其他两个）"
echo "  • readinessProbe → 失败摘流量（不重启）"
echo "  • livenessProbe  → 失败杀容器（重启）"
echo ""
echo "  【HPA】"
echo "  • 期望副本 = ceil(当前数 × 实际利用率 / 目标利用率)"
echo "  • 扩容快（立即），缩容慢（stabilizationWindow防抖）"
echo ""
echo "  【故障排查口诀】"
echo "  • Pending    → kubectl describe pod → 看 Events"
echo "  • OOMKilled  → 退出码 137，describe 看 OOMKilled:true"
echo "  • CrashLoop  → kubectl logs --previous 看上次日志"
echo "  • 慢排查     → kubectl get events --sort-by=.lastTimestamp"
echo ""
echo "  🎉 所有演示脚本执行完毕！"
