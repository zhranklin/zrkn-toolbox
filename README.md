## 构建方法
### 调整脚本
1. 修改代码
2. 执行./build.sh

### 调整基础镜像
如添加软件依赖等

1. 修改Dockerfile.base
2. 修改build.sh中的BASE_VERSION
3. 提交代码
4. 打git tag
5. 执行`./build.sh base` # 根据git tag输出基础镜像
6. 执行`./build.sh` # 以BASE_VERSION作为基础镜像版本, 构建新镜像

### 当前可用镜像(不保证最新)

- hub.c.163.com/qingzhou/amm-toolbox:v0.1.0
- hub.c.163.com/qingzhou/amm-toolbox:v0.1.0-java8
- hub.c.163.com/qingzhou/powerful-base-tomcat:v1.4.1