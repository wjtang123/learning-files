# Docker & Kubernetes 学习 Demo 套件

## 项目结构

```
k8s-demo/
├── 00-setup.sh                  # 环境安装（Docker + k8s）
├── docker-basics/               # 第一阶段：Docker 基础原理
│   ├── 01-namespace-cgroup.sh   # Namespace & Cgroup 可视化
│   ├── 02-overlayfs.sh          # UnionFS / 镜像分层演示
│   ├── 03-dockerfile/           # Dockerfile 最佳实践对比
│   └── 04-network.sh            # Docker 网络模式演示
├── docker-advanced/             # 第二阶段：Docker 进阶
│   ├── 05-compose/              # Docker Compose 多服务编排
│   ├── 06-multistage/           # 多阶段构建对比
│   └── 07-volume.sh             # Volume 数据持久化演示
├── k8s-core/                    # 第三阶段：K8s 核心概念
│   ├── 08-pod-deploy.sh         # Pod / ReplicaSet / Deployment
│   ├── 09-service.sh            # Service 四种类型演示
│   ├── 10-configmap-secret.sh   # ConfigMap & Secret
│   └── 11-pv-pvc.sh             # PV / PVC / StorageClass
├── k8s-arch/                    # 第四阶段：K8s 架构原理
│   ├── 12-etcd-inspect.sh       # etcd 数据探查
│   ├── 13-scheduler.sh          # 调度器行为演示
│   └── 14-controller.sh         # Controller 控制循环演示
├── k8s-ops/                     # 第五阶段：生产运维
│   ├── 15-hpa.sh                # HPA 自动扩缩容
│   ├── 16-probe.sh              # Liveness & Readiness Probe
│   ├── 17-rolling-update.sh     # 滚动更新 & 回滚
│   └── 18-troubleshoot.sh       # 故障排查实战
└── scripts/
    ├── lib.sh                   # 公共函数库（颜色输出、等待函数）
    └── cleanup.sh               # 清理所有演示资源
```

## 机器要求

| 角色 | 数量 | 配置 | 说明 |
|------|------|------|------|
| master | 1 | 2核4GB | 控制面 |
| worker | 1-2 | 2核2GB | 数据面 |

> 单机演示：所有脚本在 1 台机器上也可运行（Docker 部分完全支持，k8s 用 minikube）

## 快速开始

```bash
# 1. 克隆并进入目录
cd k8s-demo

# 2. 安装环境（约 5-10 分钟）
sudo bash 00-setup.sh

# 3. 按阶段运行演示
bash docker-basics/01-namespace-cgroup.sh
bash docker-basics/02-overlayfs.sh
# ... 依次执行各脚本
```

## 覆盖知识点

- ✅ 容器 vs 虚拟机
- ✅ Namespace 六种类型（pid/net/mnt/uts/ipc/user）
- ✅ Cgroup 资源限制（cpu/memory/pids）
- ✅ UnionFS / OverlayFS 镜像分层 & CoW
- ✅ Dockerfile 好坏写法对比（缓存、多阶段、非root）
- ✅ Docker 四种网络模式（bridge/host/none/overlay）
- ✅ Volume 三种持久化方式
- ✅ Docker Compose 多服务编排
- ✅ Pod / ReplicaSet / Deployment 关系
- ✅ Service 四种类型
- ✅ ConfigMap & Secret 注入方式
- ✅ PV / PVC 动态制备
- ✅ etcd 数据结构探查
- ✅ Scheduler 调度策略（亲和性、污点）
- ✅ Controller 控制循环
- ✅ HPA 自动扩缩容
- ✅ Probe 健康检查
- ✅ 滚动更新 & 回滚
- ✅ 故障排查（OOM / Pending / CrashLoop）
